package com.anomaly.camstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Opt-in device benchmark for the CPU transform that runs when CameraX reports rotated frames. */
@RunWith(AndroidJUnit4::class)
class YuvFrameTransformerPerformanceTest {
    @Test
    fun rotated720pTransformBenchmark() {
        assumeTrue("Pass rotationBenchmark=true to run this hardware-specific benchmark",
            InstrumentationRegistry.getArguments().getString("rotationBenchmark") == "true")

        val width = 1280
        val height = 720
        val yStride = width + 16
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvStride = uvWidth * 2 + 16
        val y = directPlane(width, height, yStride, 1)
        val u = directPlane(uvWidth, uvHeight, uvStride, 2)
        val v = directPlane(uvWidth, uvHeight, uvStride, 2)
        val lumaTransformer = YuvFrameTransformer(width, height)
        val chromaTransformer = YuvFrameTransformer(uvWidth, uvHeight)
        val optimizedY = ByteArray(width * height)
        val referenceY = ByteArray(optimizedY.size)
        val optimizedUv = ByteArray(width * height / 2)
        val referenceUv = ByteArray(optimizedUv.size)

        fun optimizedFrame() {
            lumaTransformer.copyPlane(optimizedY, y, width, height, yStride, 1, 90, false)
            chromaTransformer.copyInterleavedChroma(optimizedUv, u, v, uvWidth, uvHeight,
                uvStride, uvStride, 2, 2, 90, false)
        }
        fun referenceFrame() {
            copyReference(referenceY, y, width, height, yStride, 1, width, height)
            copyReferenceInterleaved(referenceUv, u, v, uvWidth, uvHeight,
                uvStride, uvStride, 2, 2, uvWidth, uvHeight)
        }

        repeat(4) {
            optimizedFrame()
            referenceFrame()
        }
        assertArrayEquals(referenceY, optimizedY)
        assertArrayEquals(referenceUv, optimizedUv)

        val iterations = 20
        val optimizedSamples = LongArray(iterations)
        val referenceSamples = LongArray(iterations)
        repeat(iterations) { index ->
            if (index % 2 == 0) {
                optimizedSamples[index] = measureNs(::optimizedFrame)
                referenceSamples[index] = measureNs(::referenceFrame)
            } else {
                referenceSamples[index] = measureNs(::referenceFrame)
                optimizedSamples[index] = measureNs(::optimizedFrame)
            }
        }
        val optimizedP50 = optimizedSamples.sorted()[iterations / 2]
        val optimizedP95 = optimizedSamples.sorted()[(iterations * 19 / 20).coerceAtMost(iterations - 1)]
        val referenceP50 = referenceSamples.sorted()[iterations / 2]
        val speedup = referenceP50.toDouble() / optimizedP50
        Log.i(TAG, "720p rotate=90 transform referenceP50=${referenceP50 / 1_000_000.0}ms " +
            "optimizedP50=${optimizedP50 / 1_000_000.0}ms optimizedP95=${optimizedP95 / 1_000_000.0}ms " +
            "speedup=${speedup}x frameBudget60fps=16.67ms")
        assertTrue("Rotated YUV copy p95=${optimizedP95 / 1_000_000.0}ms exceeds the 720p60 frame budget",
            optimizedP95 < 16_666_667L)
    }

    private fun measureNs(action: () -> Unit): Long {
        val start = System.nanoTime()
        action()
        return System.nanoTime() - start
    }

    private fun directPlane(width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteBuffer {
        val prefix = 7
        val capacity = prefix + (height - 1) * rowStride + (width - 1) * pixelStride + 1
        val buffer = ByteBuffer.allocateDirect(capacity)
        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer.put(prefix + row * rowStride + col * pixelStride,
                    ((row * width + col) % 251).toByte())
            }
        }
        buffer.position(prefix)
        return buffer
    }

    private fun copyReference(
        output: ByteArray,
        input: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        outputWidth: Int,
        outputHeight: Int
    ) {
        val base = input.position()
        val rotatedWidth = sourceHeight
        val rotatedHeight = sourceWidth
        val cropWidth: Int
        val cropHeight: Int
        if (rotatedWidth.toLong() * outputHeight > rotatedHeight.toLong() * outputWidth) {
            cropWidth = rotatedHeight * outputWidth / outputHeight
            cropHeight = rotatedHeight
        } else {
            cropWidth = rotatedWidth
            cropHeight = rotatedWidth * outputHeight / outputWidth
        }
        val cropLeft = (rotatedWidth - cropWidth) / 2
        val cropTop = (rotatedHeight - cropHeight) / 2
        for (row in 0 until outputHeight) {
            val rotatedY = cropTop + row * cropHeight / outputHeight
            for (col in 0 until outputWidth) {
                val rotatedX = cropLeft + col * cropWidth / outputWidth
                val sourceX = rotatedY
                val sourceY = sourceHeight - 1 - rotatedX
                output[row * outputWidth + col] = input.get(base + sourceY * rowStride + sourceX * pixelStride)
            }
        }
    }

    private fun copyReferenceInterleaved(
        output: ByteArray,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        outputWidth: Int,
        outputHeight: Int
    ) {
        val uBase = uPlane.position()
        val vBase = vPlane.position()
        val rotatedWidth = sourceHeight
        val rotatedHeight = sourceWidth
        val cropWidth: Int
        val cropHeight: Int
        if (rotatedWidth.toLong() * outputHeight > rotatedHeight.toLong() * outputWidth) {
            cropWidth = rotatedHeight * outputWidth / outputHeight
            cropHeight = rotatedHeight
        } else {
            cropWidth = rotatedWidth
            cropHeight = rotatedWidth * outputHeight / outputWidth
        }
        val cropLeft = (rotatedWidth - cropWidth) / 2
        val cropTop = (rotatedHeight - cropHeight) / 2
        for (row in 0 until outputHeight) {
            val rotatedY = cropTop + row * cropHeight / outputHeight
            for (col in 0 until outputWidth) {
                val rotatedX = cropLeft + col * cropWidth / outputWidth
                val sourceX = rotatedY
                val sourceY = sourceHeight - 1 - rotatedX
                val outputIndex = (row * outputWidth + col) * 2
                output[outputIndex] = uPlane.get(uBase + sourceY * uRowStride + sourceX * uPixelStride)
                output[outputIndex + 1] = vPlane.get(vBase + sourceY * vRowStride + sourceX * vPixelStride)
            }
        }
    }

    private companion object {
        const val TAG = "JyroCamRotation"
    }
}
