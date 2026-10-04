package com.anomaly.camstream

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileStreamingTest {
    @Test fun streamSelectedProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val profile = StreamProfile.fromId(args.getString("profile"))
        val manager = context.getSystemService(CameraManager::class.java)
        for (id in manager.cameraIdList) {
            val info = manager.getCameraCharacteristics(id)
            Log.i("JyroCamProfiles", "CAMERA=$id facing=${info.get(CameraCharacteristics.LENS_FACING)} " +
                "ranges=${info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()} " +
                "profiles=${StreamProfile.entries.filter { it.supportsCamera(info) }}")
        }
        val supported = manager.cameraIdList.any {
            val info = manager.getCameraCharacteristics(it)
            info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                profile.supportsCamera(info)
        } && profile.encoderName() != null
        assumeTrue("Unsupported native $profile on ${Build.MODEL}", supported)
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        for (permission in permissions) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("pm grant ${context.packageName} $permission")).use { it.readBytes() }
        }
        context.getSharedPreferences("camstream_prefs", Context.MODE_PRIVATE).edit()
            .putString("server_url", args.getString("serverUrl") ?: "http://127.0.0.1:8080")
            .putString("profile", profile.id).putBoolean("mirror_enabled", args.getString("mirror") == "true").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(3000)
            scenario.onActivity { activity ->
                val spinner = activity.findViewById<Spinner>(R.id.fpsSpinner)
                val index = (0 until spinner.count).firstOrNull { spinner.getItemAtPosition(it).toString() == profile.toString() }
                assertNotNull("Profile missing from UI", index)
                spinner.setSelection(index!!)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<Button>(R.id.startButton).performClick() }
            try {
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (StreamStats.uploadCount.get() < 30 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertEquals("${profile.width}×${profile.height}", StreamStats.resolution.get())
                assertTrue("No video uploaded: ${StreamStats.lastError.get()}", StreamStats.uploadCount.get() >= 30)
                Log.i("JyroCamProfiles", "PROFILE_READY=${profile.id}")
                Thread.sleep((args.getString("seconds")?.toLongOrNull() ?: 75) * 1000)
                assertNull(StreamStats.lastError.get())
                Log.i("JyroCamProfiles", "PROFILE_PASS=${profile.id} fps=${StreamStats.activeFps.get()}")
            } finally {
                scenario.onActivity { it.findViewById<Button>(R.id.startButton).performClick() }
                Thread.sleep(1000)
            }
        }
    }
}
