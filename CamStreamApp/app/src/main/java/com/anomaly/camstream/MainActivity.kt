package com.anomaly.camstream

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anomaly.camstream.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var streaming = false
    private var facingBack = true
    private var rotationStep = 0
    private var mirrorEnabled = false
    @Volatile private var lastConnectionState: Boolean? = null
    @Volatile private var lastPcLogAtMs = 0L

private val qualityOptions = arrayOf("6000 kbps · máx. 8000")
    private val qualityValues = intArrayOf(85)
    private var profiles = listOf(StreamProfile.HD30)
    private var profilesInitialized = false
    private var previewProvider: ProcessCameraProvider? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PC_LOG_INTERVAL_MS = 3000L
        private const val SERVER_PORT = 8080
    }

    private val ipv4Regex = Regex(
        "^(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[01]?\\d?\\d)){3}$"
    )

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun updateFacingAvailability() {
        val provider = previewProvider ?: return
        var available = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        if (available && streaming) {
            val otherFacing = if (facingBack) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            val info = provider.availableCameraInfos.firstOrNull { it.lensFacing == otherFacing }
            val profile = profiles.getOrNull(binding.fpsSpinner.selectedItemPosition)
            available = info != null && profile != null && profile.supportsCamera(
                getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId))
        }
        binding.facingButton.isEnabled = available
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun refreshProfiles(provider: ProcessCameraProvider) {
        val facing = if (facingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val info = provider.availableCameraInfos.firstOrNull { it.lensFacing == facing } ?: return
        val id = androidx.camera.camera2.interop.Camera2CameraInfo.from(info).cameraId
        val characteristics = getSystemService(android.hardware.camera2.CameraManager::class.java)
            .getCameraCharacteristics(id)
        val previous = profiles.getOrNull(binding.fpsSpinner.selectedItemPosition)?.id.takeIf { profilesInitialized }
            ?: getSharedPreferences("camstream_prefs", MODE_PRIVATE).getString("profile", "720p30")
        profiles = StreamProfile.entries.filter { it.supportsCamera(characteristics) && it.encoderName() != null }
        profilesInitialized = true
        binding.fpsSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, profiles.map { it.toString() })
        binding.fpsSpinner.setSelection(profiles.indexOfFirst { it.id == previous }.coerceAtLeast(0))
        binding.fpsSpinner.isEnabled = !streaming && profiles.isNotEmpty()
        binding.startButton.isEnabled = profiles.isNotEmpty()
        if (profiles.isEmpty()) binding.statsText.text = "Esta cámara no admite los perfiles de video disponibles."
    }

    private val permissions = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            setStatus(R.string.status_idle, false)
            bindPreview()
        } else {
            setStatus(R.string.status_camera_required, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

binding.urlInput.setText(loadIp())
        binding.urlLayout.helperText = null
        binding.rotateButton.isEnabled = false
        binding.rotateButton.visibility = android.view.View.GONE
        binding.fpsSpinner.isEnabled = true
        binding.fpsSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, profiles.map { it.toString() })
        binding.fpsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val profile = profiles.getOrNull(position) ?: return
                if (profilesInitialized) getSharedPreferences("camstream_prefs", MODE_PRIVATE)
                    .edit().putString("profile", profile.id).apply()
            }
        }
        binding.fpsSpinner.setSelection(0)
        rotationStep = 0
        mirrorEnabled = loadMirror()
        updateRotateLabel()
        updateMirrorLabel()
        applyPreviewTransform()
        showPreviewIndicator()
        renderStatsSummary()

        binding.startButton.setOnClickListener { onToggle() }
        binding.facingButton.setOnClickListener {
            if (!binding.facingButton.isEnabled) return@setOnClickListener
            facingBack = !facingBack
            binding.facingButton.setText(if (facingBack) R.string.facing_back else R.string.facing_front)
            if (streaming) {
                StreamService.updateTransform(this, rotationStep, mirrorEnabled, facingBack)
            } else {
                bindPreview()
            }
        }
        binding.mirrorButton.setOnClickListener {
            mirrorEnabled = !mirrorEnabled
            saveMirror(mirrorEnabled)
            updateMirrorLabel()
            applyPreviewTransform()
            if (streaming) StreamService.updateTransform(this, rotationStep, mirrorEnabled, facingBack)
        }

        if (allPermissionsGranted()) {
            bindPreview()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun applyPreviewTransform() {
        binding.previewView.rotation = rotationStep.toFloat()
        binding.previewView.scaleX = if (mirrorEnabled) -1f else 1f
    }

    private fun updateRotateLabel() {
        binding.rotateButton.setText(
            if (rotationStep == 0) R.string.rotate_0 else R.string.rotate_90)
    }

    private fun updateMirrorLabel() {
        binding.mirrorButton.setText(
            if (mirrorEnabled) R.string.mirror_on else R.string.mirror_off)
    }

    private fun bindPreview() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (streaming) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (streaming) return@addListener
                previewProvider = provider
                refreshProfiles(provider)
                binding.facingButton.isEnabled = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val preview = CameraPreview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val selector = if (facingBack) CameraSelector.DEFAULT_BACK_CAMERA
                               else CameraSelector.DEFAULT_FRONT_CAMERA
                if (streaming) return@addListener
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
                applyPreviewTransform()
            } catch (e: Exception) {
                Log.e(TAG, "Preview bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showPreviewIndicator() {
        binding.onAirOverlay.visibility = android.view.View.GONE
        binding.previewView.visibility = android.view.View.VISIBLE
        try { binding.onAirDot.clearAnimation() } catch (_: Exception) {}
    }

    private fun showOnAirIndicator() {
        binding.previewView.visibility = android.view.View.INVISIBLE
        binding.onAirOverlay.visibility = android.view.View.VISIBLE
        val pulse = AnimationUtils.loadAnimation(this, R.anim.on_air_pulse)
        pulse.setRepeatCount(android.view.animation.Animation.INFINITE)
        pulse.setRepeatMode(android.view.animation.Animation.REVERSE)
        binding.onAirDot.startAnimation(pulse)
    }

    private fun onToggle() {
        if (streaming) {
            StreamStats.addLog("Deteniendo transmisión")
            StreamService.stop(this)
            streaming = false
            updateFacingAvailability()
            binding.fpsSpinner.isEnabled = true
            stopStatsPolling()
            setStatus(R.string.status_idle, false)
            binding.startButton.setText(R.string.start_stream)
            showPreviewIndicator()
            bindPreview()
        } else {
            if (!hasPermission(Manifest.permission.CAMERA)) {
                permissionLauncher.launch(permissions.toTypedArray())
                return
            }
val ip = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (ip.isEmpty() || !ipv4Regex.matches(ip)) {
                binding.urlLayout.error = getString(R.string.server_url_invalid)
                return
            }
            binding.urlLayout.error = null
            saveIp(ip)
            val url = "http://$ip:$SERVER_PORT"
            val profile = profiles.getOrNull(binding.fpsSpinner.selectedItemPosition) ?: return
            val fps = profile.fps
            StreamStats.clearLog()
            StreamStats.addLog("Iniciando transmisión: $profile, ${profile.bitrate / 1000}kbps → $url")
            lastConnectionState = null
            lastPcLogAtMs = 0L
            streaming = true
            updateFacingAvailability()
            binding.fpsSpinner.isEnabled = false
            showOnAirIndicator()
            StreamService.start(this, url, fps, qualityValues[0], facingBack, rotationStep, mirrorEnabled, profile)
            setStatus(R.string.status_starting, true)
            binding.startButton.setText(R.string.stop_stream)
            startStatusPolling()
        }
    }

    private fun startStatusPolling() {
        lifecycleScope.launch {
            while (streaming) {
                val ip = binding.urlInput.text?.toString()?.trim().orEmpty()
                val url = if (ipv4Regex.matches(ip)) "http://$ip:$SERVER_PORT" else ""
                val ok = ping(url)
                if (streaming) {
                    if (ok) setStatus(R.string.status_streaming, true)
                    else setStatus(R.string.status_error, false)
                }
                kotlinx.coroutines.delay(2000)
            }
        }
        startStatsPolling()
    }

    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            renderStatsSummary()
            if (streaming) statsHandler.postDelayed(this, 500)
        }
    }

    private fun renderStatsSummary() {
        val connected = streaming && StreamStats.desktopConnected.get()
        val status = if (connected) "Transmisión activa" else "Desconectado"
        val resolution = if (streaming) StreamStats.resolution.get() ?: "—" else "—"
        val fps = if (streaming) StreamStats.activeFps.get() ?: "—" else "—"
        val delay = StreamStats.uploadDelayMs.get().takeIf { streaming && it >= 0 }
            ?.let { "$it ms" } ?: "—"
        val error = StreamStats.lastError.get()?.takeIf { streaming }?.let { "\nAviso: $it" }.orEmpty()
        binding.statsText.text = "Estado: $status\nResolución: $resolution   FPS activos: $fps\nDelay envío: $delay$error"
    }

    private fun startStatsPolling() {
        statsHandler.post(statsRunnable)
    }

    private fun stopStatsPolling() {
        statsHandler.removeCallbacks(statsRunnable)
        renderStatsSummary()
    }

    private suspend fun ping(base: String): Boolean = withContext(Dispatchers.IO) {
        if (base.isBlank()) {
            StreamStats.desktopConnected.set(false)
            return@withContext false
        }
        try {
            val url = URL(base.trimEnd('/') + "/status")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 1500
            val code = c.responseCode
            try {
                if (code !in 200..299) {
                    StreamStats.desktopConnected.set(false)
                    return@withContext false
                }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                val h264 = obj.optJSONObject("h264")
                val decoder = h264?.optJSONObject("decoder")
                val pcOutput = h264?.optJSONObject("output")
                if (h264 != null) {
                    val pcLine = "PC active=${h264.optBoolean("active")} " +
                        "RX=${h264.optLong("nals")}NAL/${h264.optLong("bytes")}B " +
                        "chunkGap=${h264.optDouble("avgChunkGapMs")}ms push=${h264.optDouble("avgPushMs")}ms " +
                        "pipeQ=${decoder?.optLong("queueBytes") ?: 0}/" +
                        "${decoder?.optLong("peakQueueBytes") ?: 0}B " +
                        "bp=${h264.optLong("backpressure")} dec=${decoder?.optInt("decodedFps") ?: 0}fps " +
                        "frames=${decoder?.optLong("framesDecoded") ?: 0} " +
                        "out=${pcOutput?.optDouble("callbackMs") ?: 0}ms " +
                        "NDI=${pcOutput?.optDouble("ndiMs") ?: 0}ms " +
                        "cam=${pcOutput?.optDouble("virtualCamMs") ?: 0}ms " +
                        "JPEG=${pcOutput?.optDouble("jpegMs") ?: 0}ms " +
                        "pub=${pcOutput?.optDouble("publishMs") ?: 0}ms " +
                        "render=${pcOutput?.optDouble("rendererAckMs") ?: 0}ms"
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastPcLogAtMs >= PC_LOG_INTERVAL_MS) {
                        StreamStats.addLog(pcLine)
                        lastPcLogAtMs = nowMs
                    }
                }
                // Reachability is distinct from receiving a decoded video frame.
                val connected = obj.optBoolean("connected", false) && obj.optInt("lastFrameAge", 999) < 5
                StreamStats.desktopConnected.set(connected)
                if (lastConnectionState != connected) {
                    StreamStats.addLog(if (connected) "Servidor recibiendo video" else "Servidor sin cuadros recientes")
                    lastConnectionState = connected
                }
                connected
            } finally { c.disconnect() }
        } catch (_: Exception) {
            StreamStats.desktopConnected.set(false)
            false
        }
    }

    private fun setStatus(resId: Int, live: Boolean) {
        binding.statusText.text = getString(resId)
        binding.statusText.setBackgroundColor(
            ContextCompat.getColor(this,
            if (live) R.color.success else R.color.surface_variant))
        binding.statusText.setTextColor(
            ContextCompat.getColor(this,
            if (live) R.color.on_surface else R.color.on_surface_muted))
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun allPermissionsGranted() = permissions.all { hasPermission(it) }

    private fun saveIp(ip: String) {
        getSharedPreferences("camstream_prefs", MODE_PRIVATE).edit()
            .putString("server_ip", ip).apply()
    }

    private fun loadIp(): String {
        val prefs = getSharedPreferences("camstream_prefs", MODE_PRIVATE)
        val stored = prefs.getString("server_ip", null)
        if (!stored.isNullOrBlank() && ipv4Regex.matches(stored)) return stored
        val legacy = prefs.getString("server_url", null)
        if (!legacy.isNullOrBlank()) {
            val extracted = ipv4Regex.find(legacy)?.toString()
            if (!extracted.isNullOrBlank()) return extracted
        }
        return ""
    }

    private fun saveRotation(v: Int) {
        getSharedPreferences("camstream_prefs", MODE_PRIVATE).edit()
            .putInt("rotation_step", v).apply()
    }

    private fun loadRotation(): Int =
        if (getSharedPreferences("camstream_prefs", MODE_PRIVATE)
                .getInt("rotation_step", 0) % 180 == 0) 0 else 90

    private fun saveMirror(v: Boolean) {
        getSharedPreferences("camstream_prefs", MODE_PRIVATE).edit()
            .putBoolean("mirror_enabled", v).apply()
    }

    private fun loadMirror(): Boolean =
        getSharedPreferences("camstream_prefs", MODE_PRIVATE)
            .getBoolean("mirror_enabled", false)

    override fun onResume() {
        super.onResume()
        bindPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (streaming) StreamService.stop(this)
    }
}
