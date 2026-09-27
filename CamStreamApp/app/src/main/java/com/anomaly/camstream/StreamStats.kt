package com.anomaly.camstream

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object StreamStats {
    val captureCount = AtomicLong(0)
    val uploadCount = AtomicLong(0)
    val dropCount = AtomicLong(0)
    val lastError = AtomicReference<String?>(null)
    val lastFps = AtomicReference<String?>(null)
    val desktopStats = AtomicReference<String?>(null)
    val state = AtomicReference<String?>("Detenido")
    private val logEntries = ArrayDeque<String>()

    @Synchronized
    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logEntries.addLast("[$timestamp] $message")
        while (logEntries.size > MAX_LOG_ENTRIES) logEntries.removeFirst()
    }

    @Synchronized
    fun getLogSnapshot(): List<String> = logEntries.toList()

    @Synchronized
    fun clearLog() = logEntries.clear()

    fun reset() {
        captureCount.set(0)
        uploadCount.set(0)
        dropCount.set(0)
        lastError.set(null)
        lastFps.set(null)
        desktopStats.set(null)
    }

    private const val MAX_LOG_ENTRIES = 240
}
