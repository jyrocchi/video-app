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
    private val codecLock = Any()
    private data class CodecOutput(val data: ByteArray? = null, val flags: Int = 0,
                                   val headers: List<ByteArray> = emptyList())
    private val colorFormat: Int
    private val bitrateMode: Int
    private val bufferInfo = MediaCodec.BufferInfo()
    private val framePacker = YuvFramePacker(width, height)
    private val lumaTransformer = YuvFrameTransformer(width, height)
    private val chromaTransformer = YuvFrameTransformer(width / 2, height / 2)
    @Volatile private var started = false
    @Volatile private var drainThread: Thread? = null
    private val transformedY = ByteArray(width * height)
    private val transformedU = ByteArray(width / 2 * (height / 2))
    private val transformedV = ByteArray(width / 2 * (height / 2))
    private val transformedUv = ByteArray(width * height / 2)
    private val inputStarveCount = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastInputWaitUs: Long = 0
        private set
    @Volatile var lastYuvCopyUs: Long = 0
        private set
    @Volatile var lastSubmitUs: Long = 0
        private set

    var onEncodedNAL: ((ByteArray, Int, Int) -> Unit)? = null
    private var spsNal: ByteArray? = null
    private var ppsNal: ByteArray? = null

    init {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val profile = StreamProfile.forFormat(width, height, fps)
        require(bitrate in 1..profile.bitrate) { "Bitrate AVC fuera del perfil" }
        encoder = MediaCodec.createByCodecName(profile.encoderName()
            ?: throw IllegalStateException("Codificador AVC incompatible con $profile"))
        val capabilities = encoder.codecInfo.getCapabilitiesForType(mime)
        val supported = capabilities.colorFormats
        bitrateMode = if (capabilities.encoderCapabilities?.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        } else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
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
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, profile.level)
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (error: Exception) {
            encoder.release()
            throw error
        }
    }

    fun start(): Unit = synchronized(codecLock) {
        if (stopped || started) return@synchronized
        encoder.start()
        started = true
        drainThread = Thread({
            while (started && !stopped) {
                drainEncoder(false)
                // Yield outside codecLock so input submission is not starved
                // by repeated empty output polls.
                if (!stopped) try { Thread.sleep(1) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "H264OutputDrain").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        Log.i(TAG, "H264 encoder started: ${width}x${height}@${fps}fps ${bitrate/1000}kbps")
    }

    fun encodeFrame(yPlane: ByteBuffer, uPlane: ByteBuffer, vPlane: ByteBuffer,
                    yRowStride: Int, uRowStride: Int, vRowStride: Int,
                    uPixelStride: Int, vPixelStride: Int,
                    sourceWidth: Int = width, sourceHeight: Int = height,
                    rotation: Int = 0, mirror: Boolean = false): Unit = synchronized(codecLock) {
        if (!started || stopped) return@synchronized
        try {
            val inputWaitStartNs = System.nanoTime()
            val inputIdx = encoder.dequeueInputBuffer(0)
            lastInputWaitUs = (System.nanoTime() - inputWaitStartNs) / 1_000L
            if (inputIdx < 0) {
                inputStarveCount.incrementAndGet()
                return@synchronized
            }
            val inputBuf = encoder.getInputBuffer(inputIdx) ?: return@synchronized

            val ySize = width * height
            val uvWidth = width / 2
            val uvHeight = height / 2
            val totalSize = ySize + 2 * uvWidth * uvHeight

            inputBuf.clear()
            inputBuf.position(0)
            inputBuf.limit(totalSize)

            val copyStartNs = System.nanoTime()
            val normalizedRotation = ((rotation % 360) + 360) % 360
            if (normalizedRotation == 0 && sourceWidth == width && sourceHeight == height) {
                framePacker.pack(inputBuf, yPlane, uPlane, vPlane,
                    yRowStride, uRowStride, vRowStride, uPixelStride, vPixelStride,
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    mirror)
            } else {
                lumaTransformer.copyPlane(transformedY, yPlane, sourceWidth, sourceHeight,
                    yRowStride, 1, normalizedRotation, mirror)
                inputBuf.put(transformedY, 0, ySize)
                val chromaOutputStart = inputBuf.position()
                val chromaWidth = sourceWidth / 2
                val chromaHeight = sourceHeight / 2
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    chromaTransformer.copyPlane(transformedU, uPlane, chromaWidth, chromaHeight,
                        uRowStride, uPixelStride, normalizedRotation, mirror)
                    inputBuf.put(transformedU, 0, uvWidth * uvHeight)
                    chromaTransformer.copyPlane(transformedV, vPlane, chromaWidth, chromaHeight,
                        vRowStride, vPixelStride, normalizedRotation, mirror)
                    inputBuf.put(transformedV, 0, uvWidth * uvHeight)
                } else {
                    chromaTransformer.copyInterleavedChroma(transformedUv, uPlane, vPlane,
                        chromaWidth, chromaHeight, uRowStride, vRowStride,
                        uPixelStride, vPixelStride, normalizedRotation, mirror)
                    inputBuf.put(transformedUv, 0, 2 * uvWidth * uvHeight)
                }
                check(inputBuf.position() - chromaOutputStart == 2 * uvWidth * uvHeight)
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

    private var pts = 0L
    private fun computePts(): Long {
        pts += 1_000_000L / fps
        return pts
    }

    private fun drainEncoder(endOfStream: Boolean) {
        while (!stopped) {
            // All codec access is serialized with stop/release and input submission.
            // Copy and release output buffers BEFORE a network callback can block.
            val output = try {
                synchronized(codecLock) {
                    if (stopped || !started) return
                    val timeout = if (endOfStream) EOS_TIMEOUT_US else OUTPUT_TIMEOUT_US
                    val index = encoder.dequeueOutputBuffer(bufferInfo, timeout)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = encoder.outputFormat
                            val headers = listOf("csd-0", "csd-1").mapNotNull { key ->
                                format.getByteBuffer(key)?.duplicate()?.let { buffer ->
                                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                                }?.takeIf { it.isNotEmpty() }
                            }
                            CodecOutput(headers = headers)
                        }
                        index >= 0 -> {
                            try {
                                val buffer = encoder.getOutputBuffer(index)
                                val data = if (buffer != null && bufferInfo.size > 0) {
                                    buffer.position(bufferInfo.offset)
                                    ByteArray(bufferInfo.size).also { buffer.get(it) }
                                } else null
                                CodecOutput(data, bufferInfo.flags)
                            } finally { encoder.releaseOutputBuffer(index, false) }
                        }
                        else -> CodecOutput()
                    }
                }
            } catch (e: Exception) {
                if (!stopped) { codecError = e.message; Log.w(TAG, "drain error: ${e.message}") }
                return
            }
            if (stopped) return
            for (header in output.headers) {
                val (offset, length) = stripLeadingStartCode(header)
                emitAnnexB(header, offset, length)
            }
            output.data?.let { data ->
                if ((output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    parseAndEmitCsds(data)
                } else {
                    if ((output.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        spsNal?.let { onEncodedNAL?.invoke(it, 0, it.size) }
                        ppsNal?.let { onEncodedNAL?.invoke(it, 0, it.size) }
                    }
                    emitEncodedBuffer(data)
                }
            }
            if ((output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
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
        when (data[offset].toInt() and 0x1f) {
            7 -> spsNal = out
            8 -> ppsNal = out
        }
        try {
            onEncodedNAL?.invoke(out, 0, out.size)
        } catch (e: Exception) {
            Log.w(TAG, "emitAnnexB callback error: ${e.message}", e)
        }
    }

    fun stop() {
        val wasStarted = synchronized(codecLock) {
            if (stopped) return
            stopped = true
            started.also { started = false }
        }
        try { drainThread?.join(DRAIN_STOP_TIMEOUT_MS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null
        synchronized(codecLock) {
            try { if (wasStarted) encoder.stop() }
            catch (e: Exception) { Log.w(TAG, "stop error: ${e.message}") }
            finally { try { encoder.release() } catch (e: Exception) { Log.w(TAG, "release error: ${e.message}") } }
        }
        Log.i(TAG, "H264 encoder stopped")
    }

    fun getError(): String? = codecError

    fun configurationSummary(): String =
        "codec=${encoder.codecInfo.name} color=$colorFormat bitrate=${bitrate / 1000}kbps " +
            "rateControl=${if (bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) "CBR" else "VBR"}"

    fun timingSummary(): String =
        "in=${lastInputWaitUs}us yuv=${lastYuvCopyUs / 1000.0}ms submit=${lastSubmitUs}us " +
            "inputStarve=${inputStarveCount.get()}"

    companion object {
        private const val TAG = "H264Encoder"
        private const val OUTPUT_TIMEOUT_US = 1_000L
        private const val EOS_TIMEOUT_US = 10_000L
        private const val DRAIN_STOP_TIMEOUT_MS = 100L
    }
}
