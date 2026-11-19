package id.straikcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

    fun sizeFor(height: Int): Size? = sizes.firstOrNull { it.height == height && it.width == height * 16 / 9 }

    fun supports(height: Int, fps: Int): Boolean {
        val size = sizeFor(height) ?: return false
        if (!encoderSupports(size, fps)) return false
        if (fps <= 30) return true
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return false
        if (ranges.none { it.upper >= fps }) return false
        // Many phones only list 60 fps for the camera preview classes, not for MediaCodec,
        // even though recording at 60 fps works. Accept if any output class allows it.
        val limitNs = 1_000_000_000L / fps + 500_000L
        val durations = listOf(
            runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrDefault(0L),
            runCatching { map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size) }.getOrDefault(0L),
            runCatching { map.getOutputMinFrameDuration(android.graphics.ImageFormat.YUV_420_888, size) }.getOrDefault(0L),
        )
        // 0 means "unknown": the HAL does not restrict it, so trust the fps range.
        return durations.any { it in 0..limitNs }
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
