package com.anomaly.camstream

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test fun mapsAllFourDeviceOrientationsToCameraXRotation() {
        val orientation = CameraOrientation()
        assertEquals(Surface.ROTATION_0, orientation.update(0))
        assertEquals(Surface.ROTATION_270, orientation.update(90))
        assertEquals(Surface.ROTATION_180, orientation.update(180))
        assertEquals(Surface.ROTATION_90, orientation.update(270))
        assertEquals(Surface.ROTATION_0, orientation.update(359))
    }

    @Test fun ignoresUnknownReadingsAndUsesHysteresisAtBoundaries() {
        val orientation = CameraOrientation()
        assertEquals(Surface.ROTATION_0, orientation.update(-1))
        assertEquals(Surface.ROTATION_0, orientation.update(59))
        assertEquals(Surface.ROTATION_270, orientation.update(60))
        assertEquals(Surface.ROTATION_270, orientation.update(31))
        assertEquals(Surface.ROTATION_0, orientation.update(29))
    }
}
