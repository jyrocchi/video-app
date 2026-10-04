package com.anomaly.camstream

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on actual Android system images, rather than mocking framework APIs. */
@RunWith(AndroidJUnit4::class)
class AndroidCompatibilityTest {
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("$message; API=${Build.VERSION.SDK_INT}; error=${StreamStats.lastError.get()}", predicate())
    }

    @Test fun cameraTransportMirrorAndForegroundLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        for (permission in permissions) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
            ).use { it.readBytes() }
        }
        val baseUrl = InstrumentationRegistry.getArguments().getString("serverUrl")
            ?: "http://10.0.2.2:18081"
        context.getSharedPreferences("camstream_prefs", Context.MODE_PRIVATE).edit()
            .putString("server_url", baseUrl).putString("profile", "720p30")
            .putBoolean("mirror_enabled", false).commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(3000)
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.rotateButton).visibility)
                val start = activity.findViewById<Button>(R.id.startButton)
                if (start.isEnabled) start.performClick()
                else StreamService.start(activity, baseUrl, 30, 85, true, 0, false)
            }
            try {
                val manager = context.getSystemService(CameraManager::class.java)
                val camera720 = manager.cameraIdList.any { id ->
                    val info = manager.getCameraCharacteristics(id)
                    info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                        StreamProfile.HD30.supportsCamera(info)
                }
                Log.i("JyroCamCompat", "CAMERA_ADVERTISES_720P=$camera720 API=${Build.VERSION.SDK_INT}")
                if (camera720) {
                    await("1280x720 capture did not start") { StreamStats.resolution.get() == "1280×720" }
                    await("H264 transport did not send frames") { StreamStats.uploadCount.get() >= 10 }
                } else {
                    // A low-resolution emulator is not evidence of an Android API bug.
                    // Verify controlled rejection, then independently test real 720p
                    // MediaCodec + transport in the next test (no resolution downgrade).
                    await("Unsupported camera error was not surfaced") { StreamStats.lastError.get()?.startsWith("bind:") == true }
                }
                @Suppress("DEPRECATION")
                val services = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                assertTrue("Camera service is not foreground", services.any {
                    it.service.className == StreamService::class.java.name && it.foreground
                })
                val beforeMirror = StreamStats.uploadCount.get()
                scenario.onActivity { it.findViewById<Button>(R.id.mirrorButton).performClick() }
                if (camera720) {
                    await("Mirror interrupted capture/transport") { StreamStats.uploadCount.get() >= beforeMirror + 10 }
                    assertEquals("1280×720", StreamStats.resolution.get())
                    assertNull(StreamStats.lastError.get())
                }
                scenario.onActivity { it.findViewById<Button>(R.id.mirrorButton).performClick() }
                val beforeFacing = StreamStats.uploadCount.get()
                var frontAvailable = false
                scenario.onActivity {
                    frontAvailable = it.findViewById<Button>(R.id.facingButton).isEnabled
                    if (frontAvailable) it.findViewById<Button>(R.id.facingButton).performClick()
                }
                Log.i("JyroCamCompat", "FRONT_CAMERA_AVAILABLE=$frontAvailable")
                if (camera720) {
                    await("Front camera did not send frames") { StreamStats.uploadCount.get() >= beforeFacing + 10 }
                    assertNull(StreamStats.lastError.get())
                }
            } finally {
                scenario.onActivity { StreamService.stop(it) }
                await("Service did not clear capture on stop") { StreamStats.resolution.get() == null }
            }
        }
    }

    @Test fun hardwareCodec720pMirrorAndNetwork() {
        StreamStats.reset()
        val baseUrl = InstrumentationRegistry.getArguments().getString("serverUrl") ?: "http://10.0.2.2:18081"
        // The isolated receiver fails this path's first /status request. A
        // transient negotiation error must not discard SPS/PPS via legacy HTTP.
        val probeUrl = "$baseUrl/probe-retry-${Build.VERSION.SDK_INT}-${System.nanoTime()}"
        val uploader = FrameUploader(probeUrl).apply { setH264Mode(true); start() }
        val encoder = H264Encoder(1280, 720, 30, 6_000_000)
        val packets = AtomicInteger(0)
        encoder.onEncodedNAL = { bytes, offset, size ->
            packets.incrementAndGet()
            uploader.uploadH264(bytes.copyOfRange(offset, offset + size))
        }
        val y = ByteBuffer.allocateDirect(1280 * 720)
        val u = ByteBuffer.allocateDirect(640 * 360)
        val v = ByteBuffer.allocateDirect(640 * 360)
        for (i in 0 until y.capacity()) y.put(i, (16 + (i % 220)).toByte())
        for (i in 0 until u.capacity()) { u.put(i, 100.toByte()); v.put(i, 150.toByte()) }
        try {
            encoder.start()
            for (frame in 0 until 90) {
                encoder.encodeFrame(y, u, v, 1280, 640, 640, 1, 1,
                    1280, 720, 0, mirror = frame >= 30 && frame < 60)
                Thread.sleep(34)
            }
            await("720p MediaCodec/transport produced no output") { packets.get() > 10 && StreamStats.uploadCount.get() > 10 }
            assertNull("Encoder failed: ${encoder.configurationSummary()}", encoder.getError())
            assertNull(StreamStats.lastError.get())
            Log.i("JyroCamCompat", "CODEC_720P_MIRROR_TRANSPORT=PASS ${encoder.configurationSummary()}")
        } finally { uploader.shutdown(); encoder.stop() }
    }

    @Test fun selectableProfileCodecCompatibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        for (id in manager.cameraIdList) {
            val info = manager.getCameraCharacteristics(id)
            Log.i("JyroCamCompat", "CAMERA_PROFILES id=$id supported=${StreamProfile.entries.filter { it.supportsCamera(info) }}")
        }
        for (profile in StreamProfile.entries) {
            if (profile.encoderName() == null) {
                Log.i("JyroCamCompat", "PROFILE_CODEC=${profile.id}:UNSUPPORTED")
                continue
            }
            val width = profile.width
            val height = profile.height
            val encoder = H264Encoder(width, height, profile.fps, profile.bitrate)
            val packets = AtomicInteger()
            encoder.onEncodedNAL = { _, _, _ -> packets.incrementAndGet() }
            val y = ByteBuffer.allocateDirect(width * height)
            val u = ByteBuffer.allocateDirect(width * height / 4)
            val v = ByteBuffer.allocateDirect(width * height / 4)
            try {
                encoder.start()
                repeat(20) { frame ->
                    encoder.encodeFrame(y, u, v, width, width / 2, width / 2, 1, 1,
                        width, height, 0, frame % 2 == 0)
                    Thread.sleep(1000L / profile.fps)
                }
                await("No codec output for $profile") { packets.get() >= 3 }
                assertNull(encoder.getError())
                Log.i("JyroCamCompat", "PROFILE_CODEC=${profile.id}:PASS ${encoder.configurationSummary()}")
            } finally { encoder.stop() }
        }
    }

    @Test fun codecShutdownWhileOutputSinkBackpressured() {
        val entered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val encoder = H264Encoder(1280, 720, 30, 6_000_000)
        encoder.onEncodedNAL = { bytes, offset, size ->
            val type = if (size > 4) bytes[offset + 4].toInt() and 0x1f else 0
            if (type == 1 || type == 5) {
                entered.countDown()
                releaseSink.await(3, TimeUnit.SECONDS)
            }
        }
        val y = ByteBuffer.allocateDirect(1280 * 720)
        val u = ByteBuffer.allocateDirect(640 * 360)
        val v = ByteBuffer.allocateDirect(640 * 360)
        try {
            encoder.start()
            for (i in 0 until 20) {
                encoder.encodeFrame(y, u, v, 1280, 640, 640, 1, 1)
                if (entered.await(20, TimeUnit.MILLISECONDS)) break
            }
            assertTrue("Codec did not reach a video output callback", entered.await(2, TimeUnit.SECONDS))
            // The sink is blocked longer than the old 100ms join. Returning
            // from it after stop must never touch a released MediaCodec buffer.
            encoder.stop()
            releaseSink.countDown()
            Thread.sleep(500)
            assertNull(encoder.getError())
            Log.i("JyroCamCompat", "CODEC_STOP_BACKPRESSURE=PASS")
        } finally { releaseSink.countDown(); encoder.stop() }
    }
}
