package com.anomaly.camstream

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object StreamStats {
    val uploadCount = AtomicLong(0)
    val dropCount = AtomicLong(0)
    val lastError = AtomicReference<String?>(null)
    val desktopConnected = AtomicBoolean(false)
    val resolution = AtomicReference<String?>(null)
    val activeFps = AtomicReference<String?>(null)
    val uploadDelayMs = AtomicLong(-1)
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
        uploadCount.set(0)
        dropCount.set(0)
        lastError.set(null)
        desktopConnected.set(false)
        resolution.set(null)
        activeFps.set(null)
        uploadDelayMs.set(-1)
    }

    private const val MAX_LOG_ENTRIES = 240
}
