package com.anomaly.camstream

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FrameUploader(private val baseUrl: String) {

    private data class UploadPacket(val data: ByteArray, val enqueuedAtNs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameChannel = Channel<UploadPacket>(capacity = CHANNEL_CAPACITY)
    private val workers = mutableListOf<Job>()
    private val queuedPackets = AtomicInteger(0)
    private val peakQueuedPackets = AtomicInteger(0)
    private val queuedBytes = AtomicLong(0)
    private val peakQueuedBytes = AtomicLong(0)
    private val sending = AtomicBoolean(false)
    private val avgQueueWaitMs = AtomicLong(0)
    private val avgHttpRoundTripMs = AtomicLong(0)
    private val reconnects = AtomicInteger(0)
    private val avgStreamWriteMs = AtomicLong(0)
    private val streamBytesSent = AtomicLong(0)
    @Volatile private var streamConnection: HttpURLConnection? = null
    private var streamOutput: DataOutputStream? = null
    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var h264Mode = false
    @Volatile private var streamingSupported: Boolean? = null

    fun start(workerCount: Int = WORKER_COUNT) {
        if (started) return
        started = true
        val n = workerCount.coerceIn(1, 8)
        repeat(n) { workerId ->
            workers.add(scope.launch {
                Log.i(TAG, "Worker $workerId started")
                while (!stopped) {
                    val packet = try {
                        frameChannel.receive()
                    } catch (_: Exception) {
                        return@launch
                    }
                    queuedPackets.decrementAndGet()
                    queuedBytes.addAndGet(-packet.data.size.toLong())
                    sending.set(true)
                    val queueWaitMs = (SystemClock.elapsedRealtimeNanos() - packet.enqueuedAtNs) / 1_000_000L
                    updateAverage(avgQueueWaitMs, queueWaitMs)
                    try {
                        if (h264Mode && supportsStreaming()) sendH264(packet.data)
                        else sendWithRetry(packet.data)
                    } finally {
                        sending.set(false)
                    }
                }
                Log.i(TAG, "Worker $workerId stopped")
            })
        }
    }

    fun setH264Mode(enabled: Boolean) {
        h264Mode = enabled
    }

    private fun supportsStreaming(): Boolean {
        streamingSupported?.let { return it }
        return try {
            val connection = URL(baseUrl.trimEnd('/') + "/status").openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            val supported = try {
                connection.inputStream.bufferedReader().use { reader ->
                    org.json.JSONObject(reader.readText()).optJSONObject("h264")
                        ?.optBoolean("streamingSupported", false) == true
                }
            } finally { connection.disconnect() }
            streamingSupported = supported
            if (!supported) StreamStats.addLog("PC antigua: usando envío H.264 compatible por HTTP")
            supported
        } catch (_: Exception) {
            // Retry the capability probe when the server becomes reachable.
            false
        }
    }

    private fun sendH264(data: ByteArray) {
        if (stopped) return
        val writeStartedNs = SystemClock.elapsedRealtimeNanos()
        try {
            if (streamOutput == null) {
                val c = (URL(baseUrl.trimEnd('/') + "/stream-h264").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2000
                    readTimeout = 2000
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/x-h264-framed")
                    setChunkedStreamingMode(1024)
                }
                streamConnection = c
                streamOutput = DataOutputStream(c.outputStream)
                StreamStats.addLog("Flujo H.264 persistente conectado")
            }
            // Each Annex-B NAL is length-prefixed; the desktop reassembles HTTP chunks.
            streamOutput!!.writeInt(data.size)
            streamOutput!!.write(data)
            streamOutput!!.flush()
            val writeMs = (SystemClock.elapsedRealtimeNanos() - writeStartedNs) / 1_000_000L
            updateAverage(avgStreamWriteMs, writeMs)
            StreamStats.uploadDelayMs.set(avgQueueWaitMs.get() + avgStreamWriteMs.get())
            streamBytesSent.addAndGet(data.size.toLong() + 4L)
            StreamStats.uploadCount.incrementAndGet()
        } catch (e: Exception) {
            closeStream()
            reconnects.incrementAndGet()
            StreamStats.lastError.set("flujo H264: ${e.message}")
            StreamStats.addLog("Flujo H.264 interrumpido: ${e.message}")
            // A partially written NAL cannot be retried safely. Wait for the next keyframe.
            StreamStats.dropCount.incrementAndGet()
        }
    }

    private fun closeStream() {
        try { streamConnection?.disconnect() } catch (_: Exception) {}
        streamConnection = null
        streamOutput = null
    }

    private fun sendWithRetry(data: ByteArray) {
        val path = if (h264Mode) "/upload-h264" else "/upload"
        val contentType = if (h264Mode) "video/h264" else "image/jpeg"
        val urlStr = baseUrl.trimEnd('/') + path
        var attempt = 0
        while (attempt < MAX_ATTEMPTS && !stopped) {
            val requestStartNs = SystemClock.elapsedRealtimeNanos()
            try {
                val url = URL(urlStr)
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 2000
                c.readTimeout = 2000
                c.doOutput = true
                c.useCaches = false
                c.setRequestProperty("content-type", contentType)
                c.setRequestProperty("content-length", data.size.toString())
                c.setFixedLengthStreamingMode(data.size)
                val out = c.outputStream
                out.write(data)
                out.flush()
                out.close()
                val code = c.responseCode
                try { c.inputStream.close() } catch (_: Exception) {}
                val requestMs = (SystemClock.elapsedRealtimeNanos() - requestStartNs) / 1_000_000L
                updateAverage(avgHttpRoundTripMs, requestMs)
                if (code in 200..299) return
                Log.w(TAG, "Worker upload HTTP $code")
                StreamStats.lastError.set("envío HTTP $code ($path)")
                StreamStats.addLog("Error de envío HTTP $code ($path)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Worker upload attempt $attempt failed: ${e.message}")
                StreamStats.lastError.set("envío: ${e.message}")
                StreamStats.addLog("Error de envío: ${e.message}")
                attempt++
                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(5) } catch (_: Exception) {}
                }
            }
        }
    }

    fun upload(jpeg: ByteArray) {
        if (baseUrl.isBlank() || stopped) return
        enqueue(jpeg)
    }

    fun uploadH264(nal: ByteArray) {
        if (baseUrl.isBlank() || stopped) return
        enqueue(nal)
    }

    private fun enqueue(data: ByteArray) {
        val packet = UploadPacket(data, SystemClock.elapsedRealtimeNanos())
        val depth = queuedPackets.incrementAndGet()
        val bytes = queuedBytes.addAndGet(data.size.toLong())
        peakQueuedPackets.updateAndGet { peak -> maxOf(peak, depth.coerceAtMost(CHANNEL_CAPACITY)) }
        peakQueuedBytes.updateAndGet { peak -> maxOf(peak, bytes) }
        val r = frameChannel.trySend(packet)
        if (r.isFailure) {
            queuedPackets.decrementAndGet()
            queuedBytes.addAndGet(-data.size.toLong())
            Log.d(TAG, "Upload dropped (channel full)")
            StreamStats.dropCount.incrementAndGet()
        }
    }

    fun statsSummary(): String =
        "up q=${queuedPackets.get()}/$CHANNEL_CAPACITY peak=${peakQueuedPackets.get()} " +
            "qB=${queuedBytes.get()}/${peakQueuedBytes.get()} busy=${if (sending.get()) 1 else 0} " +
            "wait=${avgQueueWaitMs.get()}ms " +
            (if (h264Mode && streamingSupported == true)
                "stream sent=${StreamStats.uploadCount.get()} bytes=${streamBytesSent.get()} " +
                    "write=${avgStreamWriteMs.get()}ms reconnect=${reconnects.get()}"
             else "HTTP=${avgHttpRoundTripMs.get()}ms")

    private fun updateAverage(average: AtomicLong, sample: Long) {
        average.updateAndGet { previous -> if (previous == 0L) sample else (previous * 7 + sample) / 8 }
    }

    fun shutdown() {
        stopped = true
        closeStream()
        try { frameChannel.close() } catch (_: Exception) {}
        workers.forEach { try { it.cancel() } catch (_: Exception) {} }
    }

    companion object {
        private const val TAG = "FrameUploader"
        // H.264 is a byte stream: NAL units must arrive in encoding order.
        private const val WORKER_COUNT = 1
        private const val CHANNEL_CAPACITY = 12
        private const val MAX_ATTEMPTS = 2
    }
}
