package com.anomaly.camstream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int = 2_500_000,
    private val iFrameIntervalSec: Int = 1
) {
    @Volatile private var stopped = false
    @Volatile private var codecError: String? = null

    private val encoder: MediaCodec
    private val colorFormat: Int
    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var started = false
    @Volatile private var drainThread: Thread? = null
    private val yRow = ByteArray(width)
    private val uRow = ByteArray(width / 2)
    private val vRow = ByteArray(width / 2)
    private val uvRow = ByteArray(width)
    private val inputStarveCount = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastInputWaitUs: Long = 0
        private set
    @Volatile var lastYuvCopyUs: Long = 0
        private set
    @Volatile var lastSubmitUs: Long = 0
        private set

    var onEncodedNAL: ((ByteArray, Int, Int) -> Unit)? = null

    init {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        encoder = MediaCodec.createEncoderByType(mime)
        val supported = encoder.codecInfo.getCapabilitiesForType(mime).colorFormats
        colorFormat = when {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            else -> throw IllegalStateException("Codificador AVC sin formato YUV420 compatible")
        }
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        if (started) return
        encoder.start()
        started = true
        drainThread = Thread({
            while (started && !stopped) drainEncoder(false)
        }, "H264OutputDrain").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        Log.i(TAG, "H264 encoder started: ${width}x${height}@${fps}fps ${bitrate/1000}kbps")
    }

    fun encodeFrame(yPlane: ByteBuffer, uPlane: ByteBuffer, vPlane: ByteBuffer,
                    yRowStride: Int, uRowStride: Int, vRowStride: Int,
                     uPixelStride: Int, vPixelStride: Int) {
        if (!started || stopped) return
        try {
            val inputWaitStartNs = System.nanoTime()
            val inputIdx = encoder.dequeueInputBuffer(0)
            lastInputWaitUs = (System.nanoTime() - inputWaitStartNs) / 1_000L
            if (inputIdx < 0) {
                inputStarveCount.incrementAndGet()
                return
            }
            val inputBuf = encoder.getInputBuffer(inputIdx) ?: return

            val ySize = width * height
            val uvWidth = width / 2
            val uvHeight = height / 2
            val totalSize = ySize + 2 * uvWidth * uvHeight

            inputBuf.clear()
            inputBuf.position(0)
            inputBuf.limit(totalSize)

            val copyStartNs = System.nanoTime()
            val ySource = yPlane.duplicate()
            val yBase = ySource.position()
            if (yRowStride == width) {
                ySource.limit(yBase + ySize)
                inputBuf.put(ySource)
            } else {
                for (row in 0 until height) {
                    ySource.position(yBase + row * yRowStride)
                    ySource.get(yRow, 0, width)
                    inputBuf.put(yRow, 0, width)
                }
            }

            // CameraX chroma may have pixelStride=2. Pack U/V according to the
            // actual format supported by this device's hardware encoder.
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                copyPlanarPlane(inputBuf, uPlane, uRowStride, uPixelStride, uRow, uvWidth, uvHeight)
                copyPlanarPlane(inputBuf, vPlane, vRowStride, vPixelStride, vRow, uvWidth, uvHeight)
            } else {
                val uBase = uPlane.position()
                val vBase = vPlane.position()
                for (row in 0 until uvHeight) {
                    val uStart = uBase + row * uRowStride
                    val vStart = vBase + row * vRowStride
                    // Common CameraX layout is NV12: U and V views of the same
                    // interleaved chroma. Copy the contiguous bytes in one go.
                    val interleaved = uPixelStride == 2 && vPixelStride == 2 &&
                        uPlane.get(uStart + 1) == vPlane.get(vStart) &&
                        uPlane.get(uStart + uvWidth - 1) == vPlane.get(vStart + uvWidth - 2)
                    if (interleaved) {
                        val source = uPlane.duplicate()
                        source.position(uStart)
                        source.limit(uStart + width - 1)
                        inputBuf.put(source)
                        inputBuf.put(vPlane.get(vStart + width - 2))
                    } else {
                        for (col in 0 until uvWidth) {
                            uvRow[col * 2] = uPlane.get(uStart + col * uPixelStride)
                            uvRow[col * 2 + 1] = vPlane.get(vStart + col * vPixelStride)
                        }
                        inputBuf.put(uvRow, 0, width)
                    }
                }
            }
            lastYuvCopyUs = (System.nanoTime() - copyStartNs) / 1_000L

            val submitStartNs = System.nanoTime()
            encoder.queueInputBuffer(inputIdx, 0, totalSize, computePts(), 0)
            lastSubmitUs = (System.nanoTime() - submitStartNs) / 1_000L
        } catch (e: Exception) {
            Log.w(TAG, "encodeFrame error: ${e.message ?: e.javaClass.simpleName}", e)
            codecError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun copyPlanarPlane(output: ByteBuffer, plane: ByteBuffer, rowStride: Int,
                                pixelStride: Int, rowBytes: ByteArray, width: Int, height: Int) {
        val source = plane.duplicate()
        val base = source.position()
        if (pixelStride == 1 && rowStride == width) {
            source.limit(base + width * height)
            output.put(source)
        } else {
            for (row in 0 until height) {
                val start = base + row * rowStride
                if (pixelStride == 1) {
                    source.position(start)
                    source.get(rowBytes, 0, width)
                } else {
                    for (col in 0 until width) rowBytes[col] = source.get(start + col * pixelStride)
                }
                output.put(rowBytes, 0, width)
            }
        }
    }

    private var pts = 0L
    private fun computePts(): Long {
        pts += 1_000_000L / fps
        return pts
    }

    private fun drainEncoder(endOfStream: Boolean) {
        while (true) {
            val timeoutUs = if (endOfStream) EOS_TIMEOUT_US else OUTPUT_TIMEOUT_US
            val outIdx = try { encoder.dequeueOutputBuffer(bufferInfo, timeoutUs) }
                       catch (e: Exception) { Log.w(TAG, "dequeueOutput err: ${e.message}"); return }
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val newFormat = encoder.outputFormat
                    Log.i(TAG, "Encoder format changed (csd-0=${newFormat.containsKey("csd-0")} csd-1=${newFormat.containsKey("csd-1")})")
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    val csd1 = newFormat.getByteBuffer("csd-1")
                    Log.i(TAG, "csd-0 remaining=${csd0?.remaining()} csd-1 remaining=${csd1?.remaining()}")
                    if (csd0 != null && csd0.remaining() > 0) {
                        val raw = ByteArray(csd0.remaining())
                        csd0.position(0)
                        csd0.get(raw)
                        val (off, len) = stripLeadingStartCode(raw)
                        emitAnnexB(raw, off, len)
                        Log.i(TAG, "SPS sent: ${len} raw bytes (${4+len} Annex-B) [${raw.take(8).joinToString { "0x%02x".format(it) }}]")
                    }
                    if (csd1 != null && csd1.remaining() > 0) {
                        val raw = ByteArray(csd1.remaining())
                        csd1.position(0)
                        csd1.get(raw)
                        val (off, len) = stripLeadingStartCode(raw)
                        emitAnnexB(raw, off, len)
                        Log.i(TAG, "PPS sent: ${len} raw bytes (${4+len} Annex-B)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "format change handler error: ${e.message}", e)
                }
            } else if (outIdx >= 0) {
                val buf = encoder.getOutputBuffer(outIdx) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    val csd = ByteArray(bufferInfo.size)
                    buf.position(bufferInfo.offset)
                    buf.get(csd, 0, bufferInfo.size)
                    parseAndEmitCsds(csd)
                } else if (bufferInfo.size > 0) {
                    val raw = ByteArray(bufferInfo.size)
                    buf.position(bufferInfo.offset)
                    buf.get(raw, 0, bufferInfo.size)
                    emitEncodedBuffer(raw)
                }
                encoder.releaseOutputBuffer(outIdx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    private fun parseAndEmitCsds(data: ByteArray) {
        try {
            var i = 0
            if (data.isNotEmpty() && data[0].toInt() == 1) {
                i = 1
            }
            if (i >= data.size) return
            val numSps = data[i].toInt() and 0x1F
            i++
            repeat(numSps) {
                if (i + 2 > data.size) return@repeat
                val spsLen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
                if (i + spsLen > data.size) return@repeat
                emitAnnexB(data, i, spsLen)
                Log.i(TAG, "SPS (from csd) sent: $spsLen bytes")
                i += spsLen
            }
            if (i >= data.size) return
            val numPps = data[i].toInt() and 0xFF
            i++
            repeat(numPps) {
                if (i + 2 > data.size) return@repeat
                val ppsLen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
                if (i + ppsLen > data.size) return@repeat
                emitAnnexB(data, i, ppsLen)
                Log.i(TAG, "PPS (from csd) sent: $ppsLen bytes")
                i += ppsLen
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseAndEmitCsds error: ${e.message}", e)
        }
    }

    private fun emitEncodedBuffer(data: ByteArray) {
        // MediaCodec may output Annex-B (possibly several NALs) or AVCC
        // (big-endian length-prefixed NALs). Preserve complete access units.
        if (stripLeadingStartCode(data).first != 0) {
            onEncodedNAL?.invoke(data, 0, data.size)
            return
        }
        var pos = 0
        var validAvcc = data.size >= 5
        while (validAvcc && pos < data.size) {
            if (pos + 4 > data.size) { validAvcc = false; break }
            val len = ((data[pos].toInt() and 0xFF) shl 24) or
                      ((data[pos + 1].toInt() and 0xFF) shl 16) or
                      ((data[pos + 2].toInt() and 0xFF) shl 8) or
                      (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (len <= 0 || len > data.size - pos) { validAvcc = false; break }
            pos += len
        }
        if (validAvcc && pos == data.size) {
            pos = 0
            while (pos < data.size) {
                val len = ((data[pos].toInt() and 0xFF) shl 24) or
                          ((data[pos + 1].toInt() and 0xFF) shl 16) or
                          ((data[pos + 2].toInt() and 0xFF) shl 8) or
                          (data[pos + 3].toInt() and 0xFF)
                emitAnnexB(data, pos + 4, len)
                pos += 4 + len
            }
        } else {
            emitAnnexB(data)
        }
    }

    private fun stripLeadingStartCode(data: ByteArray): Pair<Int, Int> {
        if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1) {
            return Pair(4, data.size - 4)
        }
        if (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1) {
            return Pair(3, data.size - 3)
        }
        return Pair(0, data.size)
    }

    private fun emitAnnexB(nal: ByteArray) {
        emitAnnexB(nal, 0, nal.size)
    }

    private fun emitAnnexB(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val out = ByteArray(4 + length)
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(data, offset, out, 4, length)
        try {
            onEncodedNAL?.invoke(out, 0, out.size)
        } catch (e: Exception) {
            Log.w(TAG, "emitAnnexB callback error: ${e.message}", e)
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        try { drainThread?.join(DRAIN_STOP_TIMEOUT_MS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null
        try {
            if (started) {
                encoder.stop()
            }
            encoder.release()
        } catch (e: Exception) {
            Log.w(TAG, "stop error: ${e.message}")
        }
        Log.i(TAG, "H264 encoder stopped")
    }

    fun getError(): String? = codecError

    fun configurationSummary(): String =
        "codec=${encoder.codecInfo.name} color=$colorFormat bitrate=${bitrate / 1000}kbps"

    fun timingSummary(): String =
        "in=${lastInputWaitUs}us yuv=${lastYuvCopyUs / 1000.0}ms submit=${lastSubmitUs}us " +
            "inputStarve=${inputStarveCount.get()}"

    companion object {
        private const val TAG = "H264Encoder"
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val EOS_TIMEOUT_US = 10_000L
        private const val DRAIN_STOP_TIMEOUT_MS = 100L
    }
}
