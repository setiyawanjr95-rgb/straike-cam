package id.straikcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.OrientationEventListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.vosk.Model
import java.util.concurrent.Executors

/**
 * Foreground service that keeps listening for "straik" with the screen off.
 * It must be started while the app is on screen (the Siaga button), because
 * Android does not let apps open the camera or microphone from the background.
 *
 * With pre-roll on, the camera and encoders run the whole time so the last
 * 30 seconds are always in memory. With it off, the camera only opens on "straik".
 */
class StandbyService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val autoStop = Runnable { stopRecording() }
    private lateinit var feedback: Feedback

    private var running = false
    private var recording = false
    private var preRoll = false
    private var qualityLabel = ""
    private var lastStopAt = 0L
    private var deviceDegrees = 0

    private var model: Model? = null
    private var support: CameraSupport? = null
    private var recorder: ClipRecorder? = null
    private var video: VideoEngine? = null
    private var audio: AudioEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var orientation: OrientationEventListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        feedback = Feedback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            buildNotification("Menyiapkan..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StraikCam:standby")
            .apply { acquire(MAX_STANDBY_MS) }
        AppState.set(Mode.LOADING, "Menyiapkan pendengar suara...")

        io.execute {
            try {
                val loaded = Words.loadModel(this)
                val cam = CameraSupport(this, LensPrefs.ultraWide(this))
                handler.post {
                    if (!running) {
                        loaded.close()
                        return@post
                    }
                    model = loaded
                    support = cam
                    startEngines(loaded, cam)
                }
            } catch (e: Exception) {
                handler.post { fail("Gagal menyiapkan: ${e.message}") }
            }
        }
    }

    private fun startEngines(loaded: Model, cam: CameraSupport) {
        preRoll = Prefs.preRoll(this)
        val (height, fps) = cam.resolve(VideoPrefs.height(this), VideoPrefs.fps(this))

        val rec = ClipRecorder(
            this,
            preRollUs = if (preRoll) Prefs.PRE_ROLL_SECONDS * 1_000_000L else 0L,
            onFinished = ::onClipFinished,
        )
        val vid = VideoEngine(this, cam, height, fps, rec, LensPrefs.stabilize(this)) { msg -> handler.post { fail(msg) } }
        val aud = AudioEngine(loaded, vid::nowUs, rec, ::onWord) { msg -> fail(msg) }
        val realFps = vid.actualFps
        qualityLabel = "${CameraSupport.label(height)} ${realFps}fps" +
            (if (cam.usingUltraWide) " 0.5x" else "") +
            if (realFps < fps) " (kamera tidak mendukung ${fps}fps)" else ""
        recorder = rec
        video = vid
        audio = aud

        aud.encodeAudio = preRoll
        aud.start()
        if (preRoll) vid.start()

        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees != ORIENTATION_UNKNOWN) deviceDegrees = degrees
            }
        }.also { if (it.canDetectOrientation()) it.enable() }

        showStandby(standbyText())
    }

    private fun standbyText(): String {
        val pre = if (preRoll) ", pre-roll ${Prefs.PRE_ROLL_SECONDS} detik" else ""
        return "Siaga ($qualityLabel$pre): teriak \"straik\""
    }

    private fun onWord(word: String) {
        if (!running) return
        when (word) {
            Words.START -> if (!recording && SystemClock.elapsedRealtime() - lastStopAt > COOLDOWN_MS) startRecording()
            Words.STOP -> if (recording) stopRecording()
        }
    }

    private fun startRecording() {
        val rec = recorder ?: return
        val vid = video ?: return
        recording = true
        rec.orientationHint = orientationHint()
        rec.start(vid.nowUs())
        if (!preRoll) {
            audio?.encodeAudio = true
            vid.start()
        }
        feedback.recordingStarted()
        handler.postDelayed(autoStop, AUTO_STOP_MS)
        val text = "MEREKAM $qualityLabel... bilang \"stop\" untuk berhenti"
        AppState.set(Mode.RECORDING, text)
        updateNotification(text, recordingNow = true)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        lastStopAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(autoStop)
        recorder?.stop()
        if (!preRoll) {
            video?.stop()
            audio?.encodeAudio = false
        }
        feedback.recordingStopped()
    }

    private fun onClipFinished(saved: Boolean, message: String) {
        if (!running) return
        if (!saved) feedback.warning()
        showStandby("$message. ${standbyText()}")
    }

    /** Rotation to store in the MP4 so the video plays upright, from how the phone is held. */
    private fun orientationHint(): Int {
        val sensor = support?.sensorOrientation ?: 90
        val device = ((deviceDegrees + 45) / 90 * 90) % 360
        return (sensor + device) % 360
    }

    private fun showStandby(message: String) {
        AppState.set(Mode.STANDBY, message)
        updateNotification(message, recordingNow = false)
    }

    private fun fail(message: String) {
        if (!running) return
        feedback.warning()
        AppState.set(Mode.ERROR, message)
        shutdown(keepStatus = true)
    }

    private fun shutdown(keepStatus: Boolean = false) {
        if (recording) stopRecording()
        running = false
        handler.removeCallbacks(autoStop)
        orientation?.disable()
        orientation = null
        audio?.stop()
        audio = null
        recorder?.release()
        recorder = null
        video?.release()
        video = null
        model?.close()
        model = null
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

    // --- notification ---

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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recordingNow) builder.addAction(0, "Stop rekam", serviceIntent(ACTION_STOP_RECORDING, 1))
        builder.addAction(0, "Matikan siaga", serviceIntent(ACTION_SHUTDOWN, 2))
        return builder.build()
    }

    private fun updateNotification(text: String, recordingNow: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, recordingNow))
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, StandbyService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )

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
