package id.straikcam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Back camera -> H.264 encoder. Encoded frames go to the [ClipRecorder], which
 * decides whether they are kept in the pre-roll buffer or written to a file.
 */
class VideoEngine(
    context: Context,
    private val support: CameraSupport,
    private val height: Int,
    private val fps: Int,
    private val sink: ClipRecorder,
    private val stabilize: Boolean,
    private val onError: (String) -> Unit,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("video").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var running = false

    /** The frame rate the camera will really deliver (may be lower than asked). */
    val actualFps: Int = support.effectiveFps(fps)

    /**
     * Every timestamp in the app (audio, trigger, video) lives on the elapsedRealtime clock.
     * Phones disagree on which clock the camera uses (and some encoders re-base to zero),
     * so the first video frame decides how to convert.
     */
    fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000

    private var clockMode = -1 // 0 realtime, 1 uptime, 2 relative to first frame
    private var relativeShiftUs = 0L

    private fun toRealtimeUs(raw: Long): Long {
        val realUs = SystemClock.elapsedRealtimeNanos() / 1000
        val upUs = System.nanoTime() / 1000
        if (clockMode < 0) {
            val dReal = Math.abs(raw - realUs)
            val dUp = Math.abs(raw - upUs)
            clockMode = when {
                minOf(dReal, dUp) > 10_000_000L -> 2
                dUp < dReal -> 1
                else -> 0
            }
            if (clockMode == 2) relativeShiftUs = realUs - raw
        }
        return when (clockMode) {
            1 -> raw + (realUs - upUs)
            2 -> raw + relativeShiftUs
            else -> raw
        }
    }

    fun start() = handler.post {
        if (running) return@post
        try {
            open()
        } catch (e: Exception) {
            close()
            onError("Kamera gagal dibuka: ${e.message}")
        }
    }

    fun stop() = handler.post { close() }

    fun release() {
        handler.post { close() }
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        clockMode = -1
        val size = support.sizeFor(height) ?: throw IllegalStateException("Resolusi ${height}p tidak didukung")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, CameraSupport.bitrate(height, actualFps))
            setInteger(MediaFormat.KEY_FRAME_RATE, actualFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        sink.onSample(EncodedSample(data, toRealtimeUs(info.presentationTimeUs), info.flags, isVideo = true))
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    // Codec is being stopped.
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError("Encoder video error: ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                try { sink.setVideoFormat(format) } catch (e: Exception) { onError("Gagal menyiapkan video: ${e.message}") }
            }
        }, handler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val s = c.createInputSurface()
        c.start()
        codec = c
        surface = s
        running = true

        cameraManager.openCamera(support.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!running) {
                    device.close()
                    return
                }
                camera = device
                try {
                    createSession(device, s)
                } catch (e: Exception) {
                    onError("Kamera gagal mulai: ${e.message}")
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
                if (running) onError("Kamera dipakai aplikasi lain")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
                if (running) onError("Kamera error (kode $error)")
            }
        }, handler)
    }

    private fun createSession(device: CameraDevice, target: Surface) {
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(target)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) {
                        s.close()
                        return
                    }
                    session = s
                    val chars = support.characteristics
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(target)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(chars))
                            if (stabilize) {
                                val eis = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                                val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                                // Meniru kamera bawaan: OIS (kalau ada) dan EIS dinyalakan bersamaan.
                                // Android 13+ memakai mode PREVIEW_STABILIZATION (yang dipakai kamera bawaan) kalau tersedia.
                                if (ois?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                                }
                                val previewStab = android.os.Build.VERSION.SDK_INT >= 33 &&
                                    eis?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true
                                if (previewStab) {
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
                                } else if (eis?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                                }
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 30 && support.zoomRatio != 1f) {
                                set(CaptureRequest.CONTROL_ZOOM_RATIO, support.zoomRatio)
                            }
                        }
                        s.setRepeatingRequest(request.build(), null, handler)
                    } catch (e: Exception) {
                        onError("Kamera gagal mulai: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("Kamera menolak pengaturan video ini")
                }
            },
        )
        device.createCaptureSession(config)
    }

    private fun pickFpsRange(chars: CameraCharacteristics): Range<Int> {
        val f = actualFps
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(f, f)
        return ranges.firstOrNull { it.lower == f && it.upper == f }
            ?: ranges.filter { it.upper == f }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= f }.minByOrNull { it.upper }
            ?: ranges.maxByOrNull { it.upper } ?: Range(f, f)
    }

    private fun close() {
        running = false
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { camera?.close() } catch (e: Exception) { }
        camera = null
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        codec = null
        surface?.release()
        surface = null
    }
}
