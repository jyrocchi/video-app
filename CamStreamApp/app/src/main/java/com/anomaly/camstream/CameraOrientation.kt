package com.anomaly.camstream

import android.view.Surface
import kotlin.math.abs

/** Maps the orientation sensor's clockwise degrees to CameraX's display rotation. */
class CameraOrientation {
    private var quadrant = 0
    val currentRotation: Int get() = rotationFor(quadrant)

    fun update(degrees: Int): Int {
        if (degrees !in 0..359) return currentRotation
        val candidate = ((degrees + 45) / 90) % 4
        val previous = quadrant
        if (previous == candidate) {
            quadrant = candidate
        } else {
            val distanceFromPrevious = abs((degrees - previous * 90 + 540) % 360 - 180)
            if (distanceFromPrevious >= 60) quadrant = candidate
        }
        return currentRotation
    }

    private fun rotationFor(quadrant: Int): Int = when (quadrant) {
        1 -> Surface.ROTATION_270
        2 -> Surface.ROTATION_180
        3 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}
