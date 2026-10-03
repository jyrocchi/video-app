package com.anomaly.camstream

import java.nio.ByteBuffer

/** Packs fixed-size CameraX YUV420 as I420/NV12, optionally reflecting each row.
 * Bulk reads avoid a JNI ByteBuffer.get for every pixel. Buffers are reused.
 */
class YuvFramePacker(private val width: Int, private val height: Int) {
    private val yRow = ByteArray(width)
    private val mirroredYRow = ByteArray(width)
    private var uRow = ByteArray(width)
    private var vRow = ByteArray(width)
    private val packedChromaRow = ByteArray(width)

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    }

    fun pack(output: ByteBuffer, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
             yRowStride: Int, uRowStride: Int, vRowStride: Int,
             uPixelStride: Int, vPixelStride: Int, planar: Boolean, mirror: Boolean) {
        require(output.remaining() >= width * height * 3 / 2)
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uBytes = (chromaWidth - 1) * uPixelStride + 1
        val vBytes = (chromaWidth - 1) * vPixelStride + 1
        require(uPixelStride > 0 && vPixelStride > 0 && yRowStride >= width &&
            uRowStride >= uBytes && vRowStride >= vBytes)
        if (uRow.size < uBytes) uRow = ByteArray(uBytes)
        if (vRow.size < vBytes) vRow = ByteArray(vBytes)

        val ys = y.duplicate()
        val yBase = ys.position()
        if (!mirror && yRowStride == width) {
            ys.limit(yBase + width * height)
            output.put(ys)
        } else {
            for (row in 0 until height) {
                ys.position(yBase + row * yRowStride)
                ys.get(yRow, 0, width)
                if (mirror) {
                    for (col in 0 until width) mirroredYRow[col] = yRow[width - 1 - col]
                    output.put(mirroredYRow)
                } else output.put(yRow)
            }
        }

        if (planar) {
            copyPlanar(output, u, uRow, uRowStride, uPixelStride, chromaWidth, chromaHeight, mirror)
            copyPlanar(output, v, vRow, vRowStride, vPixelStride, chromaWidth, chromaHeight, mirror)
        } else {
            val us = u.duplicate()
            val vs = v.duplicate()
            val uBase = us.position()
            val vBase = vs.position()
            for (row in 0 until chromaHeight) {
                us.position(uBase + row * uRowStride)
                vs.position(vBase + row * vRowStride)
                // Read only actual pixels; the last camera row may lack padding.
                us.get(uRow, 0, uBytes)
                vs.get(vRow, 0, vBytes)
                for (col in 0 until chromaWidth) {
                    val sourceCol = if (mirror) chromaWidth - 1 - col else col
                    packedChromaRow[col * 2] = uRow[sourceCol * uPixelStride]
                    packedChromaRow[col * 2 + 1] = vRow[sourceCol * vPixelStride]
                }
                output.put(packedChromaRow)
            }
        }
    }

    private fun copyPlanar(output: ByteBuffer, source: ByteBuffer, rowBytes: ByteArray,
                           rowStride: Int, pixelStride: Int, planeWidth: Int,
                           planeHeight: Int, mirror: Boolean) {
        val input = source.duplicate()
        val base = input.position()
        if (!mirror && pixelStride == 1 && rowStride == planeWidth) {
            input.limit(base + planeWidth * planeHeight)
            output.put(input)
            return
        }
        val count = (planeWidth - 1) * pixelStride + 1
        for (row in 0 until planeHeight) {
            input.position(base + row * rowStride)
            input.get(rowBytes, 0, count)
            for (col in 0 until planeWidth) {
                val sourceCol = if (mirror) planeWidth - 1 - col else col
                packedChromaRow[col] = rowBytes[sourceCol * pixelStride]
            }
            output.put(packedChromaRow, 0, planeWidth)
        }
    }
}
