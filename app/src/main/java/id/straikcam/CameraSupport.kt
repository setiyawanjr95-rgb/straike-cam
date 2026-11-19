package id.straikcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

/** What the back camera + video encoder of this phone can record. */
class CameraSupport(context: Context) {

    val cameraId: String
    val characteristics: CameraCharacteristics
    private val sizes: List<Size>

    init {
        val manager = context.getSystemService(CameraManager::class.java)
        cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalStateException("Kamera belakang tidak ditemukan")
        characteristics = manager.getCameraCharacteristics(cameraId)
        sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaCodec::class.java)?.toList() ?: emptyList()
    }

    val sensorOrientation: Int
        get() = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    /** True when camera frame timestamps use elapsedRealtime instead of uptime. */
    val realtimeTimestamps: Boolean
        get() = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    fun sizeFor(height: Int): Size? {
        sizes.firstOrNull { it.height == height && it.width == height * 16 / 9 }?.let { return it }
        // Some phones hide sizes from the stream map but still list them as camcorder profiles.
        val profile = profile(height) ?: return null
        return Size(profile.videoFrameWidth, profile.videoFrameHeight)
    }

    private fun profile(height: Int): CamcorderProfile? {
        val id = cameraId.toIntOrNull() ?: return null
        val quality = when (height) {
            720 -> CamcorderProfile.QUALITY_720P
            1080 -> CamcorderProfile.QUALITY_1080P
            2160 -> CamcorderProfile.QUALITY_2160P
            else -> return null
        }
        return runCatching { if (CamcorderProfile.hasProfile(id, quality)) CamcorderProfile.get(id, quality) else null }.getOrNull()
    }

    /** Encoder can do this size and rate. For 60 fps the camera may still refuse; see [cameraConfirms]. */
    fun supports(height: Int, fps: Int): Boolean {
        val size = sizeFor(height) ?: return false
        return encoderSupports(size, fps)
    }

    private fun aeRanges() = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

    /** True when the camera itself says it can deliver [fps] at this height. */
    fun cameraConfirms(height: Int, fps: Int): Boolean {
        if (fps <= 30) return true
        val size = sizeFor(height) ?: return false
        if ((profile(height)?.videoFrameRate ?: 0) >= fps) return true
        if (aeRanges()?.any { it.upper >= fps } != true) return false
        val limitNs = 1_000_000_000L / fps + 500_000L
        return minDurationsNs(size).any { it in 0..limitNs }
    }

    /** What the camera will really run at: the asked fps if it has a range for it, else 30. */
    fun effectiveFps(fps: Int): Int =
        if (fps <= 30 || aeRanges()?.any { it.upper >= fps } == true) fps else 30

    private fun minDurationsNs(size: Size): List<Long> {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return listOf(
            runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrDefault(0L),
            runCatching { map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size) }.getOrDefault(0L),
            runCatching { map.getOutputMinFrameDuration(android.graphics.ImageFormat.YUV_420_888, size) }.getOrDefault(0L),
        )
    }

    /** Heights (720/1080/2160) this phone can record at 30 fps. */
    fun supportedHeights(): List<Int> = listOf(720, 1080, 2160).filter { supports(it, 30) }

    /** Picks the closest supported setting at or below what the user asked for. */
    fun resolve(height: Int, fps: Int): Pair<Int, Int> {
        val heights = supportedHeights().ifEmpty { listOf(720) }
        val h = heights.lastOrNull { it <= height } ?: heights.first()
        val f = if (fps >= 60 && supports(h, 60)) 60 else 30
        return h to f
    }

    /** Plain-text report of what the camera says, shown in the app so odd phones can be diagnosed. */
    fun report(): String = buildString {
        append("Kamera belakang id $cameraId\n")
        append("AE fps: ").append(aeRanges()?.joinToString(" ") { "[${it.lower}-${it.upper}]" } ?: "-").append('\n')
        for (h in listOf(720, 1080, 2160)) {
            val size = sizeFor(h)
            if (size == null) { append("${CameraSupport.label(h)}: tidak ada\n"); continue }
            val ms = minDurationsNs(size).joinToString("/") { if (it <= 0) "?" else "%.0f".format(it / 1_000_000.0) }
            append("${CameraSupport.label(h)}: ${size.width}x${size.height} frame min $ms ms, profil ${profile(h)?.videoFrameRate ?: "-"}fps, encoder 60fps ${if (encoderSupports(size, 60)) "ya" else "tidak"}\n")
        }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val hs = map?.highSpeedVideoFpsRanges?.joinToString(" ") { "[${it.lower}-${it.upper}]" }
        append("High-speed: ").append(if (hs.isNullOrEmpty()) "tidak ada" else hs)
    }

    private fun encoderSupports(size: Size, fps: Int): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
        }
    }

    companion object {
        fun bitrate(height: Int, fps: Int): Int {
            val base = when (height) {
                2160 -> 35_000_000
                1080 -> 10_000_000
                else -> 5_000_000
            }
            return if (fps >= 60) base * 3 / 2 else base
        }

        fun label(height: Int) = if (height == 2160) "4K" else "${height}p"
    }
}
