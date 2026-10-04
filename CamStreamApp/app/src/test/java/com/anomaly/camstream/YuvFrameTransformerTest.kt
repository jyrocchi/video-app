package com.anomaly.camstream

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Locale

class YuvFrameTransformerTest {
    @Test
    fun rotatedLumaMatchesReferenceForEveryOrientationAndMirrorSetting() {
        val source = plane(6, 4, rowStride = 9, pixelStride = 1, first = 3)
        val transformer = YuvFrameTransformer(4, 2)

        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for (mirror in listOf(false, true)) {
                val actual = ByteArray(4 * 2)
                val expected = ByteArray(actual.size)
                transformer.copyPlane(actual, source, 6, 4, 9, 1, rotation, mirror)
                referenceCopyPlane(expected, source, 6, 4, 9, 1, 4, 2, rotation, mirror)
                assertArrayEquals("luma rotation=$rotation mirror=$mirror", expected, actual)
            }
        }
    }

    @Test
    fun rotatedInterleavedChromaMatchesReferenceForEveryOrientationAndMirrorSetting() {
        val u = plane(3, 2, rowStride = 8, pixelStride = 2, first = 31)
        val v = plane(3, 2, rowStride = 9, pixelStride = 3, first = 71)
        val transformer = YuvFrameTransformer(2, 1)

        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for (mirror in listOf(false, true)) {
                val actual = ByteArray(2 * 1 * 2)
                val expected = ByteArray(actual.size)
                transformer.copyInterleavedChroma(actual, u, v, 3, 2,
                    8, 9, 2, 3, rotation, mirror)
                referenceCopyInterleaved(expected, u, v, 3, 2, 8, 9, 2, 3,
                    2, 1, rotation, mirror)
                assertArrayEquals("chroma rotation=$rotation mirror=$mirror", expected, actual)
            }
        }
    }

    @Test
    fun transposedTilesHandlePartialEdges() {
        val source = plane(24, 40, rowStride = 27, pixelStride = 1, first = 7)
        val transformer = YuvFrameTransformer(40, 24)

        for (rotation in intArrayOf(90, 270)) {
            val actual = ByteArray(40 * 24)
            val expected = ByteArray(actual.size)
            transformer.copyPlane(actual, source, 24, 40, 27, 1, rotation, mirror = true)
            referenceCopyPlane(expected, source, 24, 40, 27, 1, 40, 24, rotation, mirror = true)
            assertArrayEquals("rotation=$rotation", expected, actual)
        }
    }

    @Test
    fun rotated720pBenchmarkWhenEnabled() {
        assumeTrue("Set CAMSTREAM_ROTATION_BENCH=1 to run the local throughput benchmark",
            System.getenv("CAMSTREAM_ROTATION_BENCH") == "1")

        val width = 1280
        val height = 720
        val yStride = width + 16
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvStride = uvWidth * 2 + 16
        val y = packedPlane(width, height, yStride)
        val u = packedPlane(uvWidth, uvHeight, uvStride, pixelStride = 2)
        val v = packedPlane(uvWidth, uvHeight, uvStride, pixelStride = 2)
        val optimized = YuvFrameTransformer(width, height)
        val optimizedChroma = YuvFrameTransformer(uvWidth, uvHeight)
        val optimizedY = ByteArray(width * height)
        val referenceY = ByteArray(optimizedY.size)
        val optimizedUv = ByteArray(width * height / 2)
        val referenceUv = ByteArray(optimizedUv.size)

        fun optimizedFrame() {
            optimized.copyPlane(optimizedY, y, width, height, yStride, 1, 90, false)
            optimizedChroma.copyInterleavedChroma(optimizedUv, u, v, uvWidth, uvHeight,
                uvStride, uvStride, 2, 2, 90, false)
        }
        fun referenceFrame() {
            referenceCopyPlane(referenceY, y, width, height, yStride, 1, width, height, 90, false)
            referenceCopyInterleaved(referenceUv, u, v, uvWidth, uvHeight,
                uvStride, uvStride, 2, 2, uvWidth, uvHeight, 90, false)
        }

        repeat(2) {
            optimizedFrame()
            referenceFrame()
        }
        assertArrayEquals(referenceY, optimizedY)
        assertArrayEquals(referenceUv, optimizedUv)

        val iterations = 8
        val referenceNs = measureNs(iterations, ::referenceFrame)
        val optimizedNs = measureNs(iterations, ::optimizedFrame)
        val referenceMs = referenceNs / iterations / 1_000_000.0
        val optimizedMs = optimizedNs / iterations / 1_000_000.0
        val speedup = referenceNs.toDouble() / optimizedNs
        println(String.format(Locale.US,
            "720p rotate=90 transform: reference=%.2fms/frame optimized=%.2fms/frame speedup=%.2fx",
            referenceMs, optimizedMs, speedup))
    }

    private fun measureNs(iterations: Int, action: () -> Unit): Long {
        val start = System.nanoTime()
        repeat(iterations) { action() }
        return System.nanoTime() - start
    }

    private fun plane(width: Int, height: Int, rowStride: Int, pixelStride: Int, first: Int): ByteBuffer {
        val prefix = 5
        val size = prefix + (height - 1) * rowStride + (width - 1) * pixelStride + 1
        val buffer = ByteBuffer.allocateDirect(size)
        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer.put(prefix + row * rowStride + col * pixelStride,
                    ((first + row * width + col) % 251).toByte())
            }
        }
        buffer.position(prefix)
        return buffer
    }

    private fun packedPlane(width: Int, height: Int, rowStride: Int, pixelStride: Int = 1): ByteBuffer {
        val prefix = 7
        val size = prefix + (height - 1) * rowStride + (width - 1) * pixelStride + 1
        val buffer = ByteBuffer.allocateDirect(size)
        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer.put(prefix + row * rowStride + col * pixelStride,
                    ((row * width + col) % 251).toByte())
            }
        }
        buffer.position(prefix)
        return buffer
    }

    private fun referenceCopyPlane(
        output: ByteArray,
        input: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        outputWidth: Int,
        outputHeight: Int,
        rotation: Int,
        mirror: Boolean
    ) {
        val base = input.position()
        val rotated = rotation == 90 || rotation == 270
        val rotatedWidth = if (rotated) sourceHeight else sourceWidth
        val rotatedHeight = if (rotated) sourceWidth else sourceHeight
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
                val outputX = if (mirror) outputWidth - 1 - col else col
                val rotatedX = cropLeft + outputX * cropWidth / outputWidth
                val sourceX: Int
                val sourceY: Int
                when (rotation) {
                    90 -> { sourceX = rotatedY; sourceY = sourceHeight - 1 - rotatedX }
                    180 -> { sourceX = sourceWidth - 1 - rotatedX; sourceY = sourceHeight - 1 - rotatedY }
                    270 -> { sourceX = sourceWidth - 1 - rotatedY; sourceY = rotatedX }
                    else -> { sourceX = rotatedX; sourceY = rotatedY }
                }
                output[row * outputWidth + col] = input.get(base + sourceY * rowStride + sourceX * pixelStride)
            }
        }
    }

    private fun referenceCopyInterleaved(
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
        outputHeight: Int,
        rotation: Int,
        mirror: Boolean
    ) {
        val uBase = uPlane.position()
        val vBase = vPlane.position()
        val rotated = rotation == 90 || rotation == 270
        val rotatedWidth = if (rotated) sourceHeight else sourceWidth
        val rotatedHeight = if (rotated) sourceWidth else sourceHeight
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
                val outputX = if (mirror) outputWidth - 1 - col else col
                val rotatedX = cropLeft + outputX * cropWidth / outputWidth
                val sourceX: Int
                val sourceY: Int
                when (rotation) {
                    90 -> { sourceX = rotatedY; sourceY = sourceHeight - 1 - rotatedX }
                    180 -> { sourceX = sourceWidth - 1 - rotatedX; sourceY = sourceHeight - 1 - rotatedY }
                    270 -> { sourceX = sourceWidth - 1 - rotatedY; sourceY = rotatedX }
                    else -> { sourceX = rotatedX; sourceY = rotatedY }
                }
                val outputIndex = (row * outputWidth + col) * 2
                output[outputIndex] = uPlane.get(uBase + sourceY * uRowStride + sourceX * uPixelStride)
                output[outputIndex + 1] = vPlane.get(vBase + sourceY * vRowStride + sourceX * vPixelStride)
            }
        }
    }
}
