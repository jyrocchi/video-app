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
import android.os.SystemClock
import android.util.Log
import android.util.Range as AndroidRange
import android.util.Size
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

class StreamService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CamAnalysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private var uploader: FrameUploader? = null
    private var streamJob: Job? = null
    private var h264Encoder: H264Encoder? = null

    private val mirrorRef = AtomicBoolean(false)
    private val rotationRef = AtomicInteger(0)
    private val targetFpsRef = AtomicInteger(15)
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

    companion object {
        const val ACTION_START = "com.anomaly.camstream.START"
        const val ACTION_STOP = "com.anomaly.camstream.STOP"
        const val ACTION_UPDATE_TRANSFORM = "com.anomaly.camstream.UPDATE_TRANSFORM"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FACING_BACK = "facing_back"
        const val EXTRA_ROTATION = "rotation"
        const val EXTRA_MIRROR = "mirror"

        const val CHANNEL_ID = "camstream_channel"
        const val NOTIF_ID = 1

        fun start(ctx: Context, url: String, fps: Int, quality: Int,
                  facingBack: Boolean, rotation: Int, mirror: Boolean) {
            val i = Intent(ctx, StreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_URL, url)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_QUALITY, quality)
                putExtra(EXTRA_FACING_BACK, facingBack)
                putExtra(EXTRA_ROTATION, rotation)
                putExtra(EXTRA_MIRROR, mirror)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamService::class.java).apply { action = ACTION_STOP })
        }

        fun updateTransform(ctx: Context, rotation: Int, mirror: Boolean) {
            ctx.startService(Intent(ctx, StreamService::class.java).apply {
                action = ACTION_UPDATE_TRANSFORM
                putExtra(EXTRA_ROTATION, rotation)
                putExtra(EXTRA_MIRROR, mirror)
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action")
        try {
            when (action) {
                ACTION_STOP -> {
                    stopStreaming()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_UPDATE_TRANSFORM -> {
                    rotationRef.set(intent.getIntExtra(EXTRA_ROTATION, 0))
                    mirrorRef.set(intent.getBooleanExtra(EXTRA_MIRROR, false))
                    Log.i(TAG, "Transform updated rot=${rotationRef.get()} mirror=${mirrorRef.get()}")
                }
                else -> {
                    val i = intent ?: return START_STICKY
                    val url = i.getStringExtra(EXTRA_SERVER_URL).orEmpty()
                    val fps = i.getIntExtra(EXTRA_FPS, 15).coerceIn(1, 60)
                    val quality = i.getIntExtra(EXTRA_QUALITY, 70).coerceIn(10, 100)
                    val facingBack = i.getBooleanExtra(EXTRA_FACING_BACK, true)
                    val rotation = i.getIntExtra(EXTRA_ROTATION, 0)
                    val mirror = i.getBooleanExtra(EXTRA_MIRROR, false)

                    uploader = FrameUploader(url)
                    uploader?.setH264Mode(true)
                    uploader?.start()
                    targetFpsRef.set(fps)
                    bitrateRef.set(when {
                        quality <= 50 -> 1_200_000
                        quality <= 70 -> 2_500_000
                        else -> 4_000_000
                    })
                    rotationRef.set(rotation)
                    mirrorRef.set(mirror)

                    startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                    serviceActive.set(true)
                    StreamStats.state.set("Iniciando cámara")
                    StreamStats.reset()
                    StreamStats.addLog("Servicio iniciado: ${fps}fps, calidad $quality, bitrate ${bitrateRef.get() / 1000}kbps")
                    captureCount.set(0)
                    cameraFrameCount.set(0)
                    lastCameraTimestampNs.set(0)
                    avgCameraFrameIntervalUs.set(0)
                    nextProcessTimestampNs.set(0)
                    rateLimitSkipCount.set(0)
                    lastReportMs = 0L
                    lastReportCount = 0L
                    lastCameraReportCount = 0L
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
        serviceActive.set(false)
        stopStreaming()
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
                        StreamStats.state.set("Error: ${e.message}")
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
        streamJob?.cancel()
        streamJob = null
        h264Encoder?.stop()
        h264Encoder = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll failed: ${e.message}")
        }
        cameraProvider = null
        uploader?.shutdown()
        uploader = null
        StreamStats.state.set("Detenido")
        StreamStats.addLog("Servicio de cámara detenido")
    }

    private fun bindUseCases(provider: ProcessCameraProvider, fps: Int, facingBack: Boolean) {
        val selector = if (facingBack) CameraSelector.DEFAULT_BACK_CAMERA
                       else CameraSelector.DEFAULT_FRONT_CAMERA

        val width = 1280
        val height = 720

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(width, height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)

        applyFpsRange(analysisBuilder, fps, provider, facingBack)

        val analysis = analysisBuilder.build().also { ia ->
            ia.setAnalyzer(analysisExecutor) { imageProxy ->
                processYuvFrame(imageProxy)
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysis)
            Log.i(TAG, "Camera bound targetFps=${fps} H264")
            StreamStats.state.set("Cámara activa @${fps}fps; iniciando codificador")
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed: ${e.message}", e)
            StreamStats.lastError.set("bind: ${e.message}")
            StreamStats.state.set("Error bind: ${e.message}")
        }
    }

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
                image.close()
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
            if (h264Encoder == null) {
                val encoder = H264Encoder(image.width, image.height, fps, bitrateRef.get())
                encoder.onEncodedNAL = { data, offset, size ->
                    uploader?.uploadH264(if (offset == 0 && size == data.size) data
                        else data.copyOfRange(offset, offset + size))
                    nalCount.incrementAndGet()
                }
                encoder.start()
                h264Encoder = encoder
                StreamStats.state.set("Transmitiendo @${fps}fps ${image.width}x${image.height} H264")
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
                    image.planes[1].pixelStride, image.planes[2].pixelStride
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
            if (timestampNs < nextTimestamp) return false
            val intervalsToAdvance = (timestampNs - nextTimestamp) / intervalNs + 1L
            val updatedNextTimestamp = nextTimestamp + intervalsToAdvance * intervalNs
            if (nextProcessTimestampNs.compareAndSet(nextTimestamp, updatedNextTimestamp)) return true
        }
    }

    private fun updateStats(nowMs: Long) {
        if (lastReportMs == 0L) {
            lastReportMs = nowMs
            lastReportCount = captureCount.get()
            return
        }
        val elapsed = nowMs - lastReportMs
        if (elapsed >= 3000) {
            val delta = captureCount.get() - lastReportCount
            val actualFps = delta.toDouble() * 1000.0 / elapsed
            val cameraDelta = cameraFrameCount.get() - lastCameraReportCount
            val cameraFps = cameraDelta.toDouble() * 1000.0 / elapsed
            val gateSkipped = rateLimitSkipCount.getAndSet(0)
            val msg = "sensor=${"%.1f".format(cameraFps)}fps gap=${avgCameraFrameIntervalUs.get() / 1000.0}ms " +
                "gateSkip=$gateSkipped " +
                "capt ${"%.1f".format(actualFps)} fps | enc=${lastEncodeTimeUs.get() / 1000.0}ms " +
                "${h264Encoder?.timingSummary() ?: ""} | " +
                "${uploader?.statsSummary() ?: "up —"} | NAL=${nalCount.get()} drop=${StreamStats.dropCount.get()}"
            Log.i(TAG, msg)
            StreamStats.lastFps.set(msg)
            StreamStats.addLog(msg)
            StreamStats.captureCount.set(captureCount.get())
            StreamStats.dropCount.set(0)
            lastReportMs = nowMs
            lastReportCount = captureCount.get()
            lastCameraReportCount = cameraFrameCount.get()
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
