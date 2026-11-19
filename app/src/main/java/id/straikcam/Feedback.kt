package id.straikcam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Beeps and vibration so the angler knows recording started or stopped without looking. */
class Feedback(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        const val BEEP_HZ = 2400
    }

    // GoPro style: one beep to start, three quick beeps to stop.
    fun recordingStarted() {
        if (Prefs.sound(context)) play(listOf(BEEP_HZ to 380))
        if (Prefs.vibrate(context)) vibrate(longArrayOf(0, 500))
    }

    fun recordingStopped() {
        if (Prefs.sound(context)) {
            play(listOf(BEEP_HZ to 130, 0 to 90, BEEP_HZ to 130, 0 to 90, BEEP_HZ to 130))
        }
        if (Prefs.vibrate(context)) vibrate(longArrayOf(0, 130, 90, 130, 90, 130))
    }

    fun warning() {
        if (Prefs.sound(context)) play(listOf(440 to 200, 0 to 80, 440 to 200, 0 to 80, 440 to 200))
        if (Prefs.vibrate(context)) vibrate(longArrayOf(0, 200, 100, 200, 100, 200))
    }

    private fun play(steps: List<Pair<Int, Int>>) {
        try {
            val rate = 44100
            val total = steps.sumOf { it.second } * rate / 1000
            val pcm = ShortArray(total)
            var pos = 0
            for ((freq, ms) in steps) {
                val n = ms * rate / 1000
                val fade = min(n / 4, rate / 200)
                for (i in 0 until n) {
                    if (freq == 0 || pos + i >= total) continue
                    val env = when {
                        i < fade -> i.toDouble() / fade
                        i > n - fade -> (n - i).toDouble() / fade
                        else -> 1.0
                    }
                    // Fundamental plus odd harmonics: the thin, squarish tone of a camera piezo beeper.
                    val x = 2 * PI * freq * i / rate
                    val wave = (sin(x) + sin(3 * x) / 3 + sin(5 * x) / 5) / 1.2
                    pcm[pos + i] = (wave * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
                }
                pos += n
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            handler.postDelayed({ track.release() }, steps.sumOf { it.second } + 300L)
        } catch (e: Exception) {
            // A missing beep must never break recording.
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
