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
    private val fpsOptions = arrayOf("30")
    private val fpsValues = intArrayOf(30)

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

        binding.urlInput.setText(loadUrl())
        rotationStep = 0
        mirrorEnabled = loadMirror()
        updateRotateLabel()
        updateMirrorLabel()
        applyPreviewTransform()
        showPreviewIndicator()

        binding.qualitySpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, qualityOptions)
        binding.rotateButton.isEnabled = false
        binding.rotateButton.visibility = android.view.View.GONE
        binding.qualitySpinner.isEnabled = false
        binding.fpsSpinner.isEnabled = false
        binding.fpsSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        binding.qualitySpinner.setSelection(qualityValues.indexOf(85))
        binding.fpsSpinner.setSelection(0)
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
            val url = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                binding.urlLayout.error = "Ingresa la URL del servidor"
                return
            }
            binding.urlLayout.error = null
            saveUrl(url)
            val quality = qualityValues[binding.qualitySpinner.selectedItemPosition]
            val fps = fpsValues[binding.fpsSpinner.selectedItemPosition]
            StreamStats.clearLog()
            StreamStats.addLog("Iniciando transmisión: 1280×720 ${fps}fps, 6000kbps (máx. 8000)")
            lastConnectionState = null
            lastPcLogAtMs = 0L
            streaming = true
            showOnAirIndicator()
            StreamService.start(this, url, fps, quality, facingBack, rotationStep, mirrorEnabled)
            setStatus(R.string.status_starting, true)
            binding.startButton.setText(R.string.stop_stream)
            startStatusPolling()
        }
    }

    private fun startStatusPolling() {
        lifecycleScope.launch {
            while (streaming) {
                val url = binding.urlInput.text?.toString()?.trim().orEmpty()
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

    private fun saveUrl(url: String) {
        getSharedPreferences("camstream_prefs", MODE_PRIVATE).edit()
            .putString("server_url", url).apply()
    }

    private fun loadUrl(): String {
        val prefs = getSharedPreferences("camstream_prefs", MODE_PRIVATE)
        return prefs.getString("server_url", "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
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

    companion object {
        private const val TAG = "MainActivity"
        private const val PC_LOG_INTERVAL_MS = 3000L
    }
}
