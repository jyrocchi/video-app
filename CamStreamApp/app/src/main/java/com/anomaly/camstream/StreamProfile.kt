package com.anomaly.camstream

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

/** Exact native profiles. Unsupported camera/codec combinations are never upscaled. */
enum class StreamProfile(val id: String, val width: Int, val height: Int, val fps: Int,
                         val bitrate: Int, val transportBitrate: Long, val level: Int) {
    HD30("720p30", 1280, 720, 30, 6_000_000, 8_000_000, MediaCodecInfo.CodecProfileLevel.AVCLevel31),
    HD60("720p60", 1280, 720, 60, 10_000_000, 14_000_000, MediaCodecInfo.CodecProfileLevel.AVCLevel32),
    FHD30("1080p30", 1920, 1080, 30, 12_000_000, 16_000_000, MediaCodecInfo.CodecProfileLevel.AVCLevel4);

    override fun toString() = "${width}×${height} · $fps FPS"

    fun supportsCamera(info: CameraCharacteristics): Boolean = try {
        val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = Size(width, height)
        val duration = map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size) ?: 0L
        map?.getOutputSizes(ImageFormat.YUV_420_888)?.contains(size) == true &&
            (duration == 0L || duration <= 1_000_000_000L / fps + 1_000_000L) &&
            info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.any { it.upper == fps && it.lower <= fps } == true
    } catch (_: Exception) { false }

    private val compatibleEncoder: String? by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .sortedBy { if (android.os.Build.VERSION.SDK_INT >= 29) !it.isHardwareAccelerated
            else it.name.startsWith("OMX.google.") || it.name.startsWith("c2.android.") }
        .firstOrNull { codec ->
            try {
                val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video = caps.videoCapabilities
                video != null && video.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                    video.bitrateRange.contains(bitrate) && caps.colorFormats.any {
                        it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    }
            } catch (_: Exception) { false }
        }?.name }

    fun encoderName(): String? = compatibleEncoder

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: HD30
        fun forFormat(width: Int, height: Int, fps: Int) =
            entries.firstOrNull { it.width == width && it.height == height && it.fps == fps }
                ?: throw IllegalArgumentException("Perfil no admitido: ${width}x${height}@$fps")
    }
}
