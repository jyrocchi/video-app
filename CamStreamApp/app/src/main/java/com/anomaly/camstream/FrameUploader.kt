package com.anomaly.camstream

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class FrameUploader(private val baseUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frameChannel = Channel<ByteArray>(capacity = CHANNEL_CAPACITY)
    private val workers = mutableListOf<Job>()
    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var h264Mode = false

    fun start(workerCount: Int = WORKER_COUNT) {
        if (started) return
        started = true
        val n = workerCount.coerceIn(1, 8)
        repeat(n) { workerId ->
            workers.add(scope.launch {
                Log.i(TAG, "Worker $workerId started")
                while (!stopped) {
                    val frame = try {
                        frameChannel.receive()
                    } catch (_: Exception) {
                        return@launch
                    }
                    sendWithRetry(frame)
                }
                Log.i(TAG, "Worker $workerId stopped")
            })
        }
    }

    fun setH264Mode(enabled: Boolean) {
        h264Mode = enabled
    }

    private fun sendWithRetry(data: ByteArray) {
        val path = if (h264Mode) "/upload-h264" else "/upload"
        val contentType = if (h264Mode) "video/h264" else "image/jpeg"
        val urlStr = baseUrl.trimEnd('/') + path
        var attempt = 0
        while (attempt < MAX_ATTEMPTS && !stopped) {
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
                if (code in 200..299) return
                Log.w(TAG, "Worker upload HTTP $code")
                StreamStats.lastError.set("envío HTTP $code ($path)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Worker upload attempt $attempt failed: ${e.message}")
                StreamStats.lastError.set("envío: ${e.message}")
                attempt++
                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(5) } catch (_: Exception) {}
                }
            }
        }
    }

    fun upload(jpeg: ByteArray) {
        if (baseUrl.isBlank() || stopped) return
        val r = frameChannel.trySend(jpeg)
        if (r.isFailure) {
            Log.d(TAG, "Frame dropped (channel full)")
            StreamStats.dropCount.incrementAndGet()
        }
    }

    fun uploadH264(nal: ByteArray) {
        if (baseUrl.isBlank() || stopped) return
        val r = frameChannel.trySend(nal)
        if (r.isFailure) {
            Log.d(TAG, "NAL dropped (channel full)")
            StreamStats.dropCount.incrementAndGet()
        }
    }

    fun shutdown() {
        stopped = true
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
