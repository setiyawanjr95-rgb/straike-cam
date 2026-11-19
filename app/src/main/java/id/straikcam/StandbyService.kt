package id.straikcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.vosk.Model
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service that keeps listening for "straik" with the screen off.
 * It must be started while the app is on screen (the Siaga button), because
 * Android does not let apps open the camera or microphone from the background.
 */
class StandbyService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val autoStop = Runnable { stopRecording() }

    private var running = false
    private var model: Model? = null
    private var listener: WordListener? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStopAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_SHUTDOWN -> shutdown()
            else -> startStandby()
        }
        return START_NOT_STICKY
    }

    private fun startStandby() {
        if (running) return
        running = true
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Menyiapkan pendengar suara..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StraikCam:standby")
            .apply { acquire(MAX_STANDBY_MS) }
        AppState.set(Mode.LOADING, "Menyiapkan pendengar suara...")

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cameraProvider = future.get() }, ContextCompat.getMainExecutor(this))

        io.execute {
            try {
                val loaded = WordListener.loadModel(this)
                handler.post {
                    if (!running) {
                        loaded.close()
                        return@post
                    }
                    model = loaded
                    listener = WordListener(loaded, ::onWord, ::onListenerError).also { it.start() }
                    showStandby("Siaga: teriak \"straik\" untuk rekam")
                }
            } catch (e: Exception) {
                handler.post { fail("Gagal memuat model suara: ${e.message}") }
            }
        }
    }

    private fun onWord(word: String) {
        when (word) {
            WordListener.WORD_START -> {
                if (recording == null && SystemClock.elapsedRealtime() - lastStopAt > COOLDOWN_MS) {
                    startRecording()
                }
            }
            WordListener.WORD_STOP -> if (recording != null) stopRecording()
        }
    }

    private fun startRecording() {
        val provider = cameraProvider ?: run {
            AppState.set(Mode.STANDBY, "Kamera belum siap, coba lagi")
            return
        }
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                )
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)

            val name = "straik_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/StraikCam")
            }
            val output = MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ).setContentValues(values).build()

            var pending = videoCapture.output.prepareRecording(this, output)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) onRecordingFinished(event)
            }
            vibrate(longArrayOf(0, 700))
            handler.postDelayed(autoStop, AUTO_STOP_MS)
            AppState.set(Mode.RECORDING, "MEREKAM... bilang \"stop\" untuk berhenti")
            updateNotification("Merekam... bilang \"stop\" untuk berhenti", recordingNow = true)
        } catch (e: Exception) {
            recording = null
            provider.unbindAll()
            showStandby("Gagal mulai rekam: ${e.message}")
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(autoStop)
        recording?.stop()
    }

    private fun onRecordingFinished(event: VideoRecordEvent.Finalize) {
        recording = null
        lastStopAt = SystemClock.elapsedRealtime()
        cameraProvider?.unbindAll()
        vibrate(longArrayOf(0, 150, 120, 150))
        if (!running) return
        if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            showStandby("Video gagal disimpan (kode ${event.error}). Siaga lagi.")
        } else {
            showStandby("Video tersimpan di Movies/StraikCam. Siaga lagi.")
        }
    }

    private fun onListenerError(message: String) {
        fail("Pendengar suara error: $message")
    }

    private fun showStandby(message: String) {
        AppState.set(Mode.STANDBY, message)
        updateNotification(message, recordingNow = false)
    }

    private fun fail(message: String) {
        AppState.set(Mode.ERROR, message)
        shutdown(keepStatus = true)
    }

    private fun shutdown(keepStatus: Boolean = false) {
        running = false
        handler.removeCallbacks(autoStop)
        recording?.stop()
        recording = null
        listener?.stop()
        listener = null
        model?.close()
        model = null
        cameraProvider?.unbindAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (!keepStatus) AppState.set(Mode.OFF, "Siaga mati")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        io.shutdown()
        super.onDestroy()
    }

    // --- notification & feedback ---

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Siaga Straik Cam", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, recordingNow: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (recordingNow) "Straik Cam: MEREKAM" else "Straik Cam: siaga")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recordingNow) {
            builder.addAction(0, "Stop rekam", serviceIntent(ACTION_STOP_RECORDING, 1))
        }
        builder.addAction(0, "Matikan siaga", serviceIntent(ACTION_SHUTDOWN, 2))
        return builder.build()
    }

    private fun updateNotification(text: String, recordingNow: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, recordingNow))
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, StandbyService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        const val ACTION_START = "id.straikcam.START"
        const val ACTION_STOP_RECORDING = "id.straikcam.STOP_RECORDING"
        const val ACTION_SHUTDOWN = "id.straikcam.SHUTDOWN"
        private const val CHANNEL_ID = "standby"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_STOP_MS = 5 * 60 * 1000L
        private const val COOLDOWN_MS = 2000L
        private const val MAX_STANDBY_MS = 12 * 60 * 60 * 1000L
    }
}
