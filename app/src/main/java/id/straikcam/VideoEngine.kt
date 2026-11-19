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

    /** Current time on the same clock the camera stamps its frames with, in microseconds. */
    fun nowUs(): Long =
        if (support.realtimeTimestamps) SystemClock.elapsedRealtimeNanos() / 1000 else System.nanoTime() / 1000

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
        val size = support.sizeFor(height) ?: throw IllegalStateException("Resolusi ${height}p tidak didukung")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, CameraSupport.bitrate(height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
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
                        sink.onSample(EncodedSample(data, info.presentationTimeUs, info.flags, isVideo = true))
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
                sink.setVideoFormat(format)
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
                createSession(device, s)
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
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(target)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(chars))
                        val stab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        if (stab?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        }
                    }
                    try {
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
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper } ?: Range(fps, fps)
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
