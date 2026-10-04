package com.anomaly.camstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import android.util.Range as AndroidRange
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

class StreamService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CamAnalysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private var uploader: FrameUploader? = null
    private var streamJob: Job? = null
    private var h264Encoder: H264Encoder? = null
    private var encoderSourceWidth = 0
    private var encoderSourceHeight = 0
    private var encoderOutputWidth = 0
    private var encoderOutputHeight = 0
    private var profile = StreamProfile.HD30
    @Volatile private var autoRotate = false
    @Volatile private var activeAnalysis: ImageAnalysis? = null
    private val cameraOrientation = CameraOrientation()
    private var lastOrientationTarget = Surface.ROTATION_0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (autoRotate && orientation in 0..359) {
                    val targetRotation = cameraOrientation.update(orientation)
                    if (targetRotation != lastOrientationTarget) {
                        lastOrientationTarget = targetRotation
                        activeAnalysis?.targetRotation = targetRotation
                        Log.i(TAG, "Auto rotation target=$targetRotation")
                    }
                }
            }
        }
    }

    private val mirrorRef = AtomicBoolean(false)
    private val rotationRef = AtomicInteger(0)
    private val facingBackRef = AtomicBoolean(true)
    private val targetFpsRef = AtomicInteger(DEFAULT_FPS)
    private val bitrateRef = AtomicInteger(2_500_000)
    private val serviceActive = AtomicBoolean(false)

    private val captureCount = AtomicLong(0)
    private val nalCount = AtomicLong(0)
    private val nextProcessTimestampNs = AtomicLong(0)
    private val lastEncodeTimeUs = AtomicLong(0)
    private val cameraFrameCount = AtomicLong(0)
    private val lastCameraTimestampNs = AtomicLong(0)
    private val avgCameraFrameIntervalUs = AtomicLong(0)
    private val rateLimitSkipCount = AtomicLong(0)
    private var lastReportMs = 0L
    private var lastReportCount = 0L
    private var lastCameraReportCount = 0L
    private val encodedBytes = AtomicLong(0)
    private var lastEncodedBytes = 0L
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val lockHandler = Handler(Looper.getMainLooper())
    private val renewCpuLock = object : Runnable {
        override fun run() {
            if (!serviceActive.get()) return
            cpuLock?.acquire(10 * 60 * 1000L)
            lockHandler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    companion object {
        const val ACTION_START = "com.anomaly.camstream.START"
        const val ACTION_STOP = "com.anomaly.camstream.STOP"
        const val ACTION_UPDATE_TRANSFORM = "com.anomaly.camstream.UPDATE_TRANSFORM"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_FPS = "fps"
        const val EXTRA_PROFILE = "profile"
        const val DEFAULT_FPS = 30
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FACING_BACK = "facing_back"
        const val EXTRA_ROTATION = "rotation"
        const val EXTRA_MIRROR = "mirror"
        const val EXTRA_AUTO_ROTATE = "auto_rotate"

        const val CHANNEL_ID = "camstream_channel"
        const val NOTIF_ID = 1

        fun start(ctx: Context, url: String, fps: Int, quality: Int,
                  facingBack: Boolean, rotation: Int, mirror: Boolean,
                  profile: StreamProfile = if (fps == 60) StreamProfile.HD60 else StreamProfile.HD30,
                  autoRotate: Boolean = false) {
            val i = Intent(ctx, StreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_URL, url)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_PROFILE, profile.id)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_FACING_BACK, facingBack)
                putExtra(EXTRA_ROTATION, rotation)
                putExtra(EXTRA_MIRROR, mirror)
                putExtra(EXTRA_AUTO_ROTATE, autoRotate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamService::class.java).apply { action = ACTION_STOP })
        }

        fun updateTransform(ctx: Context, rotation: Int, mirror: Boolean, facingBack: Boolean,
                            autoRotate: Boolean = false) {
            ctx.startService(Intent(ctx, StreamService::class.java).apply {
                action = ACTION_UPDATE_TRANSFORM
                putExtra(EXTRA_ROTATION, rotation)
                putExtra(EXTRA_MIRROR, mirror)
                putExtra(EXTRA_AUTO_ROTATE, autoRotate)
                putExtra(EXTRA_FACING_BACK, facingBack)
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        createChannel()
    }

    private fun setAutoRotate(enabled: Boolean) {
        if (enabled && !orientationListener.canDetectOrientation()) {
            autoRotate = false
            activeAnalysis?.targetRotation = Surface.ROTATION_0
            StreamStats.lastError.set("Este dispositivo no tiene sensor de orientación disponible")
            StreamStats.addLog("Rotación automática no disponible: falta sensor de orientación")
            return
        }
        autoRotate = enabled
        if (enabled) {
            orientationListener.enable()
            lastOrientationTarget = cameraOrientation.currentRotation
            activeAnalysis?.targetRotation = lastOrientationTarget
        } else {
            orientationListener.disable()
            lastOrientationTarget = Surface.ROTATION_0
            activeAnalysis?.targetRotation = Surface.ROTATION_0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")
        try {
            when (action) {
                ACTION_STOP -> {
                    setAutoRotate(false)
                    stopStreaming()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_UPDATE_TRANSFORM -> {
                    rotationRef.set(intent.getIntExtra(EXTRA_ROTATION, 0))
                    setAutoRotate(intent.getBooleanExtra(EXTRA_AUTO_ROTATE, false))
                    mirrorRef.set(intent.getBooleanExtra(EXTRA_MIRROR, false))
                    var facingBack = intent.getBooleanExtra(EXTRA_FACING_BACK, facingBackRef.get())
                    val requestedCamera = if (facingBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if (cameraProvider?.hasCamera(requestedCamera) == false ||
                        cameraProvider?.let { !cameraSupportsProfile(it, facingBack) } == true) {
                        Log.w(TAG, "Requested camera is unavailable; keeping active camera")
                        StreamStats.addLog("La otra cámara no admite $profile; se mantiene la cámara actual")
                        facingBack = facingBackRef.get()
                    }
                    if (facingBackRef.getAndSet(facingBack) != facingBack) {
                        cameraProvider?.let { provider ->
                            provider.unbindAll()
                            bindUseCases(provider, targetFpsRef.get(), facingBack)
                        }
                    }
                    Log.i(TAG, "Transform updated rot=${rotationRef.get()} autoRotate=$autoRotate mirror=${mirrorRef.get()}")
                }
                else -> {
                    val i = intent ?: return START_STICKY
                    val url = i.getStringExtra(EXTRA_SERVER_URL).orEmpty()
                    profile = StreamProfile.fromId(i.getStringExtra(EXTRA_PROFILE))
                    val fps = profile.fps
                    val facingBack = i.getBooleanExtra(EXTRA_FACING_BACK, true)
                    val mirror = i.getBooleanExtra(EXTRA_MIRROR, false)
                    val requestedAutoRotate = i.getBooleanExtra(EXTRA_AUTO_ROTATE, false)

                    uploader = FrameUploader(url, profile)
                    uploader?.setH264Mode(true)
                    uploader?.start()
                    targetFpsRef.set(fps)
                    bitrateRef.set(profile.bitrate)
                    rotationRef.set(i.getIntExtra(EXTRA_ROTATION, 0))
                    mirrorRef.set(mirror)
                    facingBackRef.set(facingBack)

                    val notification = buildNotification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                    } else {
                        startForeground(NOTIF_ID, notification)
                    }
                    serviceActive.set(true)
                    acquireStreamingLocks(url)
                    StreamStats.reset()
                    setAutoRotate(requestedAutoRotate)
                    StreamStats.addLog("Servicio iniciado: $profile, bitrate ${profile.bitrate / 1000}kbps máx. ${profile.transportBitrate / 1000}kbps")
                    captureCount.set(0)
                    cameraFrameCount.set(0)
                    lastCameraTimestampNs.set(0)
                    avgCameraFrameIntervalUs.set(0)
                    nextProcessTimestampNs.set(0)
                    rateLimitSkipCount.set(0)
                    lastReportMs = 0L
                    lastReportCount = 0L
                    lastCameraReportCount = 0L
                    encodedBytes.set(0)
                    lastEncodedBytes = 0L
                    startStreaming(fps, facingBack)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error", e)
            StreamStats.lastError.set("start: ${e.message}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        orientationListener.disable()
        serviceActive.set(false)
        stopStreaming()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startStreaming(fps: Int, facingBack: Boolean) {
        if (streamJob?.isActive == true) return
        streamJob = lifecycleScope.launch {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(this@StreamService)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        cameraProvider = provider
                        bindUseCases(provider, fps, facingBack)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to obtain camera provider: ${e.message}", e)
                        StreamStats.lastError.set("provider: ${e.message}")
                        StreamStats.addLog("No se pudo abrir CameraX: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(this@StreamService))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera: ${e.message}", e)
                StreamStats.lastError.set("start: ${e.message}")
                StreamStats.addLog("No se pudo iniciar la cámara: ${e.message}")
            }
        }
    }

    private fun stopStreaming() {
        serviceActive.set(false)
        releaseStreamingLocks()
        streamJob?.cancel()
        streamJob = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll failed: ${e.message}")
        }
        cameraProvider = null
        uploader?.shutdown()
        uploader = null
        // Serialize codec teardown after the last analyzer task. Shutdown the
        // sender first so a backpressured drain thread can finish safely.
        analysisExecutor.execute {
            h264Encoder?.stop()
            h264Encoder = null
        }
        StreamStats.desktopConnected.set(false)
        StreamStats.resolution.set(null)
        StreamStats.activeFps.set(null)
        StreamStats.uploadDelayMs.set(-1)
        StreamStats.addLog("Servicio de cámara detenido")
    }

    private fun acquireStreamingLocks(url: String) {
        releaseStreamingLocks()
        try {
            val power = getSystemService(PowerManager::class.java)
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stream").apply {
                setReferenceCounted(false)
            }
            renewCpuLock.run()
            val host = Uri.parse(url).host
            if (host != null && host !in setOf("127.0.0.1", "localhost", "::1")) {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                if (wifi?.isWifiEnabled == true) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wifi.createWifiLock(mode, "$packageName:video-wifi").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    Log.i(TAG, "Wi-Fi streaming lock mode=$mode acquired")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Streaming power lock unavailable: ${e.message}")
        }
    }

    private fun releaseStreamingLocks() {
        lockHandler.removeCallbacks(renewCpuLock)
        wifiLock?.let { if (it.isHeld) it.release() }
        cpuLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        cpuLock = null
    }

    private fun bindUseCases(provider: ProcessCameraProvider, fps: Int, facingBack: Boolean) {
        if (!cameraSupportsProfile(provider, facingBack) || profile.encoderName() == null) {
            StreamStats.lastError.set("bind: cámara/codificador no compatible con $profile")
            return
        }
        val selector = if (facingBack) CameraSelector.DEFAULT_BACK_CAMERA
                       else CameraSelector.DEFAULT_FRONT_CAMERA

        val width = profile.width
        val height = profile.height

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(width, height),
                    ResolutionStrategy.FALLBACK_RULE_NONE
                )
            )
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(if (autoRotate) cameraOrientation.currentRotation else Surface.ROTATION_0)
            .setResolutionSelector(resolutionSelector)

        applyFpsRange(analysisBuilder, fps, provider, facingBack)

        val analysis = analysisBuilder.build().also { ia ->
            activeAnalysis = ia
            if (autoRotate) ia.targetRotation = cameraOrientation.currentRotation
            ia.setAnalyzer(analysisExecutor) { imageProxy ->
                processYuvFrame(imageProxy)
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysis)
            Log.i(TAG, "Camera bound targetFps=${fps} H264")
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed: ${e.message}", e)
            StreamStats.lastError.set("bind: ${e.message}")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun cameraSupportsProfile(provider: ProcessCameraProvider, facingBack: Boolean): Boolean {
        val facing = if (facingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        val info = provider.availableCameraInfos.firstOrNull { it.lensFacing == facing } ?: return false
        return profile.supportsCamera(getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId))
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyFpsRange(
        builder: ImageAnalysis.Builder,
        fps: Int,
        provider: ProcessCameraProvider,
        facingBack: Boolean
    ) {
        try {
            val lensFacing = if (facingBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            val cameraInfo = provider.availableCameraInfos.firstOrNull { it.lensFacing == lensFacing }
            val ranges = cameraInfo?.let { info ->
                val cameraId = Camera2CameraInfo.from(info).cameraId
                getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.toList()
            }.orEmpty()
            val fpsRange = ranges
                .filter { fps in it.lower..it.upper }
                .minByOrNull { it.upper - it.lower }
                ?: ranges.minByOrNull { range ->
                    val distance = when {
                        fps < range.lower -> range.lower - fps
                        fps > range.upper -> fps - range.upper
                        else -> 0
                    }
                    distance * 1000 + range.upper - range.lower
                }
                ?: AndroidRange(fps, fps)
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    fpsRange
                )
            Log.i(TAG, "Camera2 FPS target=$fps range=$fpsRange")
        } catch (e: Exception) {
            Log.w(TAG, "Camera2Interop failed, skipping FPS control: ${e.message}")
        }
    }

    private fun processYuvFrame(image: ImageProxy) {
        if (!serviceActive.get()) {
            image.close()
            return
        }
        try {
            val cameraTimestampNs = image.imageInfo.timestamp
            val previousCameraTimestampNs = lastCameraTimestampNs.getAndSet(cameraTimestampNs)
            cameraFrameCount.incrementAndGet()
            if (previousCameraTimestampNs > 0 && cameraTimestampNs > previousCameraTimestampNs) {
                val intervalUs = (cameraTimestampNs - previousCameraTimestampNs) / 1_000L
                avgCameraFrameIntervalUs.updateAndGet { previous ->
                    if (previous == 0L) intervalUs else (previous * 7 + intervalUs) / 8
                }
            }
            val now = SystemClock.elapsedRealtime()
            val fps = targetFpsRef.get().coerceAtLeast(1)
            if (!shouldProcessCameraFrame(cameraTimestampNs, fps)) {
                rateLimitSkipCount.incrementAndGet()
                return
            }

            captureCount.incrementAndGet()
            updateStats(now)

            val yPlane = image.planes[0].buffer
            val uPlane = image.planes[1].buffer
            val vPlane = image.planes[2].buffer
            val yRowStride = image.planes[0].rowStride
            val uRowStride = image.planes[1].rowStride
            val vRowStride = image.planes[2].rowStride
            val rotation = if (autoRotate) image.imageInfo.rotationDegrees else rotationRef.get()
            val outputWidth = profile.width
            val outputHeight = profile.height
            if (h264Encoder != null && (encoderSourceWidth != image.width ||
                    encoderSourceHeight != image.height || encoderOutputWidth != outputWidth ||
                    encoderOutputHeight != outputHeight)) {
                h264Encoder?.stop()
                h264Encoder = null
            }
            if (h264Encoder == null) {
                val encoder = H264Encoder(outputWidth, outputHeight, fps, bitrateRef.get())
                encoder.onEncodedNAL = { data, offset, size ->
                    encodedBytes.addAndGet(size.toLong())
                    uploader?.uploadH264(if (offset == 0 && size == data.size) data
                        else data.copyOfRange(offset, offset + size))
                    nalCount.incrementAndGet()
                }
                encoder.start()
                h264Encoder = encoder
                encoderSourceWidth = image.width
                encoderSourceHeight = image.height
                encoderOutputWidth = outputWidth
                encoderOutputHeight = outputHeight
                StreamStats.resolution.set("${outputWidth}×${outputHeight}")
                Log.i(TAG, "Capture=${image.width}x${image.height} output=${outputWidth}x${outputHeight} " +
                    "${encoder.configurationSummary()}")
                StreamStats.addLog(
                    "CameraX ${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees}° " +
                        "strideY/U/V=${yRowStride}/${uRowStride}/${vRowStride} " +
                        "pixelUV=${image.planes[1].pixelStride}/${image.planes[2].pixelStride}; " +
                        encoder.configurationSummary()
                )
            }

            val encodeStartNs = SystemClock.elapsedRealtimeNanos()
            try {
                h264Encoder?.encodeFrame(
                    yPlane, uPlane, vPlane,
                    yRowStride, uRowStride, vRowStride,
                    image.planes[1].pixelStride, image.planes[2].pixelStride,
                    image.width, image.height, rotation, mirrorRef.get()
                )
            } finally {
                lastEncodeTimeUs.set((SystemClock.elapsedRealtimeNanos() - encodeStartNs) / 1_000L)
            }
            h264Encoder?.getError()?.let { StreamStats.lastError.set("codificador: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "Frame error: ${e.message}")
            StreamStats.lastError.set("frame: ${e.message}")
            StreamStats.addLog("Error procesando cuadro: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun shouldProcessCameraFrame(timestampNs: Long, fps: Int): Boolean {
        val intervalNs = 1_000_000_000L / fps
        while (true) {
            val nextTimestamp = nextProcessTimestampNs.get()
            if (nextTimestamp == 0L) {
                if (nextProcessTimestampNs.compareAndSet(0L, timestampNs + intervalNs)) return true
                continue
            }
            // Allow small sensor jitter at the requested frame interval.
            if (timestampNs + intervalNs / 10 < nextTimestamp) return false
            // Anchor to the accepted sensor timestamp, not an accumulating
            // ideal clock: 33.327ms vs 33.333ms must not cause periodic losses.
            val updatedNextTimestamp = timestampNs + intervalNs
            if (nextProcessTimestampNs.compareAndSet(nextTimestamp, updatedNextTimestamp)) return true
        }
    }

    private fun updateStats(nowMs: Long) {
        if (lastReportMs == 0L) {
            lastReportMs = nowMs
            lastReportCount = captureCount.get()
            lastCameraReportCount = cameraFrameCount.get()
            lastEncodedBytes = encodedBytes.get()
            return
        }
        val elapsed = nowMs - lastReportMs
        if (elapsed >= 3000) {
            val delta = captureCount.get() - lastReportCount
            val actualFps = delta.toDouble() * 1000.0 / elapsed
            StreamStats.activeFps.set(String.format(Locale.getDefault(), "%.1f", actualFps))
            val cameraDelta = cameraFrameCount.get() - lastCameraReportCount
            val cameraFps = cameraDelta.toDouble() * 1000.0 / elapsed
            val gateSkipped = rateLimitSkipCount.getAndSet(0)
            val bytes = encodedBytes.get()
            val kbps = (bytes - lastEncodedBytes) * 8.0 / elapsed
            val msg = "sensor=${"%.1f".format(cameraFps)}fps gap=${avgCameraFrameIntervalUs.get() / 1000.0}ms " +
                "gateSkip=$gateSkipped " +
                "bitrate=${"%.0f".format(kbps)}kbps " +
                "capt ${"%.1f".format(actualFps)} fps | enc=${lastEncodeTimeUs.get() / 1000.0}ms " +
                "${h264Encoder?.timingSummary() ?: ""} | " +
                "${uploader?.statsSummary() ?: "up —"} | NAL=${nalCount.get()} drop=${StreamStats.dropCount.get()}"
            Log.i(TAG, msg)
            StreamStats.addLog(msg)
            StreamStats.dropCount.set(0)
            lastReportMs = nowMs
            lastReportCount = captureCount.get()
            lastCameraReportCount = cameraFrameCount.get()
            lastEncodedBytes = bytes
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
                ch.description = getString(R.string.notification_channel_desc)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, StreamService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_notification, getString(R.string.action_stop), stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private val TAG = "StreamService"
}
