package com.anomaly.camstream

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class YuvFramePackerTest {
    // Direct buffers, distinct U/V strides, prefix positions and a final row
    // without padding reproduce CameraX layouts that broke naive row reads.
    private fun plane(w: Int, h: Int, stride: Int, pixelStride: Int, first: Int): ByteBuffer {
        val prefix = 3
        val bytes = prefix + (h - 1) * stride + (w - 1) * pixelStride + 1
        val buffer = ByteBuffer.allocateDirect(bytes)
        for (i in 0 until bytes) buffer.put(i, 0xE7.toByte())
        for (y in 0 until h) for (x in 0 until w) {
            buffer.put(prefix + y * stride + x * pixelStride, (first + y * w + x).toByte())
        }
        buffer.position(prefix)
        return buffer
    }

    private fun verify(planar: Boolean, mirror: Boolean, pixelStride: Int) {
        val y = plane(4, 4, 7, 1, 1)
        val u = plane(2, 2, 5, pixelStride, 21)
        val v = plane(2, 2, 6, pixelStride, 41)
        val output = ByteBuffer.allocate(5 + 24)
        output.position(5)
        YuvFramePacker(4, 4).pack(output, y, u, v, 7, 5, 6,
            pixelStride, pixelStride, planar, mirror)
        val luma = (0 until 4).flatMap { row ->
            (0 until 4).map { col -> 1 + row * 4 + if (mirror) 3 - col else col }
        }
        val indices = if (mirror) listOf(1, 0, 3, 2) else listOf(0, 1, 2, 3)
        val chroma = if (planar) indices.map { 21 + it } + indices.map { 41 + it }
                     else indices.flatMap { listOf(21 + it, 41 + it) }
        assertArrayEquals((luma + chroma).map { it.toByte() }.toByteArray(),
            output.array().copyOfRange(5, 29))
        assertEquals(29, output.position())
        assertEquals(3, y.position())
        assertEquals(3, u.position())
        assertEquals(3, v.position())
        assertArrayEquals(ByteArray(5), output.array().copyOfRange(0, 5))
    }

    @Test fun planarPadded() = verify(planar = true, mirror = false, pixelStride = 1)
    @Test fun planarMirrored() = verify(planar = true, mirror = true, pixelStride = 2)
    @Test fun semiplanarPadded() = verify(planar = false, mirror = false, pixelStride = 2)
    @Test fun semiplanarMirrored() = verify(planar = false, mirror = true, pixelStride = 2)

    @Test fun tightlyPackedPlanes() {
        val result = ByteBuffer.allocate(24)
        YuvFramePacker(4, 4).pack(result,
            ByteBuffer.wrap(ByteArray(16) { (it + 1).toByte() }),
            ByteBuffer.wrap(byteArrayOf(21, 22, 23, 24)),
            ByteBuffer.wrap(byteArrayOf(41, 42, 43, 44)),
            4, 2, 2, 1, 1, true, false)
        assertArrayEquals(ByteArray(16) { (it + 1).toByte() } +
            byteArrayOf(21, 22, 23, 24, 41, 42, 43, 44), result.array())
    }
}
