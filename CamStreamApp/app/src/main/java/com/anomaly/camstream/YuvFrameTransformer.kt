package com.anomaly.camstream

import java.nio.ByteBuffer

/** Reuses coordinate maps and copies rotated YUV planes in cache-friendly tiles. */
internal class YuvFrameTransformer(
    private val outputWidth: Int,
    private val outputHeight: Int
) {
    private val columnSourceX = IntArray(outputWidth)
    private val columnSourceY = IntArray(outputWidth)
    private val rowSourceX = IntArray(outputHeight)
    private val rowSourceY = IntArray(outputHeight)
    private val columnOffsets = IntArray(outputWidth)
    private val rowOffsets = IntArray(outputHeight)
    private val otherColumnOffsets = IntArray(outputWidth)
    private val otherRowOffsets = IntArray(outputHeight)
    private var packedPlane = ByteArray(0)
    private var packedOtherPlane = ByteArray(0)

    private var mappedSourceWidth = 0
    private var mappedSourceHeight = 0
    private var mappedRotation = -1
    private var mappedMirror = false

    init {
        require(outputWidth > 0 && outputHeight > 0)
    }

    fun copyPlane(
        output: ByteArray,
        plane: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        rotation: Int,
        mirror: Boolean
    ) {
        require(output.size >= outputWidth * outputHeight)
        require(sourceWidth > 0 && sourceHeight > 0 && rowStride > 0 && pixelStride > 0)
        prepareMap(sourceWidth, sourceHeight, rotation, mirror)
        val packedRowBytes = (sourceWidth - 1) * pixelStride + 1
        require(rowStride >= packedRowBytes)
        packedPlane = ensureCapacity(packedPlane, packedRowBytes * sourceHeight)
        packRows(plane, sourceHeight, rowStride, packedRowBytes, packedPlane)
        fillOffsets(packedRowBytes, pixelStride, columnOffsets, rowOffsets)

        if (mappedRotation == 90 || mappedRotation == 270) {
            for (tileY in 0 until outputHeight step ROTATION_TILE_SIZE) {
                val tileBottom = minOf(tileY + ROTATION_TILE_SIZE, outputHeight)
                for (tileX in 0 until outputWidth step ROTATION_TILE_SIZE) {
                    val tileRight = minOf(tileX + ROTATION_TILE_SIZE, outputWidth)
                    for (col in tileX until tileRight) {
                        val columnOffset = columnOffsets[col]
                        var outputIndex = tileY * outputWidth + col
                        for (row in tileY until tileBottom) {
                            output[outputIndex] = packedPlane[columnOffset + rowOffsets[row]]
                            outputIndex += outputWidth
                        }
                    }
                }
            }
        } else {
            for (row in 0 until outputHeight) {
                val inputRowOffset = rowOffsets[row]
                val outputRow = row * outputWidth
                for (col in 0 until outputWidth) {
                    output[outputRow + col] = packedPlane[inputRowOffset + columnOffsets[col]]
                }
            }
        }
    }

    fun copyInterleavedChroma(
        output: ByteArray,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        rotation: Int,
        mirror: Boolean
    ) {
        require(output.size >= outputWidth * outputHeight * 2)
        require(sourceWidth > 0 && sourceHeight > 0 && uRowStride > 0 && vRowStride > 0 &&
            uPixelStride > 0 && vPixelStride > 0)
        prepareMap(sourceWidth, sourceHeight, rotation, mirror)
        val uPackedRowBytes = (sourceWidth - 1) * uPixelStride + 1
        val vPackedRowBytes = (sourceWidth - 1) * vPixelStride + 1
        require(uRowStride >= uPackedRowBytes && vRowStride >= vPackedRowBytes)
        packedPlane = ensureCapacity(packedPlane, uPackedRowBytes * sourceHeight)
        packedOtherPlane = ensureCapacity(packedOtherPlane, vPackedRowBytes * sourceHeight)
        packRows(uPlane, sourceHeight, uRowStride, uPackedRowBytes, packedPlane)
        packRows(vPlane, sourceHeight, vRowStride, vPackedRowBytes, packedOtherPlane)
        fillOffsets(uPackedRowBytes, uPixelStride, columnOffsets, rowOffsets)
        fillOffsets(vPackedRowBytes, vPixelStride, otherColumnOffsets, otherRowOffsets)

        if (mappedRotation == 90 || mappedRotation == 270) {
            for (tileY in 0 until outputHeight step ROTATION_TILE_SIZE) {
                val tileBottom = minOf(tileY + ROTATION_TILE_SIZE, outputHeight)
                for (tileX in 0 until outputWidth step ROTATION_TILE_SIZE) {
                    val tileRight = minOf(tileX + ROTATION_TILE_SIZE, outputWidth)
                    for (col in tileX until tileRight) {
                        val uColOffset = columnOffsets[col]
                        val vColOffset = otherColumnOffsets[col]
                        var outputIndex = (tileY * outputWidth + col) * 2
                        for (row in tileY until tileBottom) {
                            output[outputIndex] = packedPlane[uColOffset + rowOffsets[row]]
                            output[outputIndex + 1] = packedOtherPlane[vColOffset + otherRowOffsets[row]]
                            outputIndex += outputWidth * 2
                        }
                    }
                }
            }
        } else {
            for (row in 0 until outputHeight) {
                val uRowOffset = rowOffsets[row]
                val vRowOffset = otherRowOffsets[row]
                val outputRow = row * outputWidth * 2
                for (col in 0 until outputWidth) {
                    val outputIndex = outputRow + col * 2
                    output[outputIndex] = packedPlane[uRowOffset + columnOffsets[col]]
                    output[outputIndex + 1] = packedOtherPlane[vRowOffset + otherColumnOffsets[col]]
                }
            }
        }
    }

    private fun prepareMap(sourceWidth: Int, sourceHeight: Int, rotation: Int, mirror: Boolean) {
        val normalizedRotation = ((rotation % 360) + 360) % 360
        val effectiveRotation = when (normalizedRotation) {
            0, 90, 180, 270 -> normalizedRotation
            else -> 0
        }
        if (mappedSourceWidth == sourceWidth && mappedSourceHeight == sourceHeight &&
            mappedRotation == effectiveRotation && mappedMirror == mirror) return

        val rotated = effectiveRotation == 90 || effectiveRotation == 270
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

        for (col in 0 until outputWidth) {
            val outputX = if (mirror) outputWidth - 1 - col else col
            val rotatedX = cropLeft + outputX * cropWidth / outputWidth
            when (effectiveRotation) {
                90 -> {
                    columnSourceX[col] = 0
                    columnSourceY[col] = sourceHeight - 1 - rotatedX
                }
                180 -> {
                    columnSourceX[col] = sourceWidth - 1 - rotatedX
                    columnSourceY[col] = 0
                }
                270 -> {
                    columnSourceX[col] = 0
                    columnSourceY[col] = rotatedX
                }
                else -> {
                    columnSourceX[col] = rotatedX
                    columnSourceY[col] = 0
                }
            }
        }
        for (row in 0 until outputHeight) {
            val rotatedY = cropTop + row * cropHeight / outputHeight
            when (effectiveRotation) {
                90 -> {
                    rowSourceX[row] = rotatedY
                    rowSourceY[row] = 0
                }
                180 -> {
                    rowSourceX[row] = 0
                    rowSourceY[row] = sourceHeight - 1 - rotatedY
                }
                270 -> {
                    rowSourceX[row] = sourceWidth - 1 - rotatedY
                    rowSourceY[row] = 0
                }
                else -> {
                    rowSourceX[row] = 0
                    rowSourceY[row] = rotatedY
                }
            }
        }

        mappedSourceWidth = sourceWidth
        mappedSourceHeight = sourceHeight
        mappedRotation = effectiveRotation
        mappedMirror = mirror
    }

    private fun fillOffsets(
        rowStride: Int,
        pixelStride: Int,
        columns: IntArray,
        rows: IntArray
    ) {
        for (col in 0 until outputWidth) {
            columns[col] = columnSourceY[col] * rowStride + columnSourceX[col] * pixelStride
        }
        for (row in 0 until outputHeight) {
            rows[row] = rowSourceY[row] * rowStride + rowSourceX[row] * pixelStride
        }
    }

    private fun packRows(
        input: ByteBuffer,
        rowCount: Int,
        rowStride: Int,
        rowBytes: Int,
        output: ByteArray
    ) {
        val source = input.duplicate()
        val base = source.position()
        if (rowStride == rowBytes) {
            source.limit(base + rowCount * rowBytes)
            source.get(output, 0, rowCount * rowBytes)
            return
        }
        for (row in 0 until rowCount) {
            source.position(base + row * rowStride)
            source.get(output, row * rowBytes, rowBytes)
        }
    }

    private fun ensureCapacity(current: ByteArray, required: Int): ByteArray =
        if (current.size >= required) current else ByteArray(required)

    private companion object {
        const val ROTATION_TILE_SIZE = 32
    }
}
