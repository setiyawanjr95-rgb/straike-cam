package id.straikcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Size

/**
 * What the back camera + video encoder of this phone can record.
 * With [wide] the ultra wide lens (0.5x) is used when the phone has one: either as its own
 * camera id, or as a zoom ratio below 1 on the main (logical) camera.
 */
class CameraSupport(context: Context, wide: Boolean = false) {

    val cameraId: String
    private val mainId: String
    val characteristics: CameraCharacteristics
    private val sizes: List<Size>

    /** True when this phone offers an ultra wide lens we can reach. */
    val ultraWideAvailable: Boolean

    /** Zoom ratio to request (0.5 on logical cameras that expose ultra wide through zoom, else 1). */
    val zoomRatio: Float

    /** True when the ultra wide lens is really in use. */
    val usingUltraWide: Boolean

    init {
        val manager = context.getSystemService(CameraManager::class.java)
        val back = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        mainId = back.firstOrNull() ?: throw IllegalStateException("Kamera belakang tidak ditemukan")
        val mainChars = manager.getCameraCharacteristics(mainId)

        fun equivalentFocal(id: String): Float? {
            val c = manager.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            return focal * 43.27f / Math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
        }

        val zoomLow = if (Build.VERSION.SDK_INT >= 30) {
            mainChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        } else 1f
        val zoomWide = zoomLow <= 0.6f

        val mainEq = equivalentFocal(mainId)
        var wideId: String? = null
        if (mainEq != null) {
            for (id in back.drop(1)) {
                val eq = equivalentFocal(id) ?: continue
                val best = wideId?.let { equivalentFocal(it) } ?: Float.MAX_VALUE
                if (eq < mainEq * 0.75f && eq < best) wideId = id
            }
        }

        ultraWideAvailable = zoomWide || wideId != null
        usingUltraWide = wide && ultraWideAvailable
        if (usingUltraWide && zoomWide) {
            cameraId = mainId
            zoomRatio = maxOf(0.5f, zoomLow)
        } else if (usingUltraWide) {
            cameraId = wideId!!
            zoomRatio = 1f
        } else {
            cameraId = mainId
            zoomRatio = 1f
        }
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

    private fun quality(height: Int) = when (height) {
        720 -> CamcorderProfile.QUALITY_720P
        1080 -> CamcorderProfile.QUALITY_1080P
        2160 -> CamcorderProfile.QUALITY_2160P
        else -> null
    }

    private fun profile(height: Int): CamcorderProfile? {
        val quality = quality(height) ?: return null
        for (id in listOf(cameraId, mainId).distinct()) {
            val n = id.toIntOrNull() ?: continue
            runCatching { if (CamcorderProfile.hasProfile(n, quality)) return CamcorderProfile.get(n, quality) }
        }
        return null
    }

    /** Bitrate and frame rate the phone's own camera app records with (vendor media profile). */
    data class StockProfile(val bitrate: Int, val fps: Int, val hevc: Boolean)

    fun stockProfile(height: Int): StockProfile? {
        val quality = quality(height) ?: return null
        if (Build.VERSION.SDK_INT >= 31) {
            for (id in listOf(cameraId, mainId).distinct()) {
                val all = runCatching { CamcorderProfile.getAll(id, quality) }.getOrNull() ?: continue
                val v = all.videoProfiles.filterNotNull()
                val pick = v.firstOrNull { it.codec == MediaRecorder.VideoEncoder.H264 } ?: v.firstOrNull() ?: continue
                return StockProfile(pick.bitrate, pick.frameRate, pick.codec == MediaRecorder.VideoEncoder.HEVC)
            }
        }
        val p = profile(height) ?: return null
        return StockProfile(p.videoBitRate, p.videoFrameRate, p.videoCodec == MediaRecorder.VideoEncoder.HEVC)
    }

    /**
     * Bitrate that matches the built-in camera app: taken from the vendor profile, converted to
     * H.264 when the profile is HEVC, and raised for 60 fps. Falls back to fixed values when the
     * phone has no profile. Clamped to what the encoder accepts and to [max].
     */
    fun videoBitrate(height: Int, fps: Int, max: Int = Int.MAX_VALUE): Int {
        val stock = stockProfile(height)
        var br = if (stock != null && stock.bitrate > 0) {
            var b = stock.bitrate.toLong()
            if (stock.hevc) b = b * 3 / 2
            if (fps > stock.fps && stock.fps > 0) b = b * 3 / 2
            b.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else bitrate(height, fps)
        br = br.coerceAtLeast(bitrate(height, fps) / 2)
        val size = sizeFor(height)
        val range = size?.let { avcCaps()?.videoCapabilities?.bitrateRange }
        if (range != null) br = br.coerceIn(range.lower, range.upper)
        return br.coerceAtMost(max)
    }

    private fun avcCaps(): android.media.MediaCodecInfo.CodecCapabilities? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)

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
        append("Kamera belakang id $cameraId, lensa ${if (usingUltraWide) "ultra wide 0.5x" else "utama 1x"}, ultra wide ${if (ultraWideAvailable) "ada" else "tidak ada"}\n")
        append("AE fps: ").append(aeRanges()?.joinToString(" ") { "[${it.lower}-${it.upper}]" } ?: "-").append('\n')
        for (h in listOf(720, 1080, 2160)) {
            val size = sizeFor(h)
            if (size == null) { append("${CameraSupport.label(h)}: tidak ada\n"); continue }
            val ms = minDurationsNs(size).joinToString("/") { if (it <= 0) "?" else "%.0f".format(it / 1_000_000.0) }
            val stock = stockProfile(h)?.let { "${it.fps}fps ${it.bitrate / 1_000_000}Mbps${if (it.hevc) " HEVC" else ""}" } ?: "-"
            append("${CameraSupport.label(h)}: ${size.width}x${size.height} frame min $ms ms, profil bawaan $stock, encoder 60fps ${if (encoderSupports(size, 60)) "ya" else "tidak"}\n")
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
