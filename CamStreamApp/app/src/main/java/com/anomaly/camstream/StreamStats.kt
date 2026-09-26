package com.anomaly.camstream

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object StreamStats {
    val captureCount = AtomicLong(0)
    val uploadCount = AtomicLong(0)
    val dropCount = AtomicLong(0)
    val lastError = AtomicReference<String?>(null)
    val lastFps = AtomicReference<String?>(null)
    val state = AtomicReference<String?>("Detenido")

    fun reset() {
        captureCount.set(0)
        uploadCount.set(0)
        dropCount.set(0)
        lastError.set(null)
        lastFps.set(null)
    }
}