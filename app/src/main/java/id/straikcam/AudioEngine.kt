package id.straikcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The only microphone user in the app. Every chunk of sound goes to the word
 * recognizer, and (while video is being encoded) to the AAC encoder for the
 * video's soundtrack. Sharing one microphone avoids the two fighting over it.
 */
class AudioEngine(
    private val model: Model,
    private val clockUs: () -> Long,
    private val sink: ClipRecorder,
    private val onWord: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var encodeAudio = false

    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        running = true
        thread = Thread({ loop() }, "audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4),
            )
        } catch (e: Exception) {
            main.post { onError("Mikrofon tidak bisa dibuka: ${e.message}") }
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            main.post { onError("Mikrofon tidak bisa dibuka") }
            return
        }
        val recognizer = Recognizer(model, 16000f, Words.GRAMMAR)
        val chunk = ShortArray(CHUNK)
        val down = ShortArray(CHUNK / 3)
        var encoder: MediaCodec? = null
        var baseUs = 0L
        var queued = 0L

        record.startRecording()
        try {
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) {
                    main.post { onError("Mikrofon terputus (kode $n)") }
                    break
                }
                if (n == 0) continue
                val endUs = clockUs()

                // 48 kHz -> 16 kHz for the recognizer.
                val m = n / 3
                for (i in 0 until m) {
                    down[i] = ((chunk[3 * i] + chunk[3 * i + 1] + chunk[3 * i + 2]) / 3).toShort()
                }
                val word = if (recognizer.acceptWaveForm(down, m)) {
                    Words.find(recognizer.result, "text")
                } else {
                    Words.find(recognizer.partialResult, "partial")
                }
                if (word != null) main.post { onWord(word) }

                if (encodeAudio) {
                    val enc = encoder ?: createEncoder().also {
                        encoder = it
                        baseUs = endUs - n * 1_000_000L / RATE
                        queued = 0L
                    }
                    queued = feed(enc, chunk, n, baseUs, queued)
                    drain(enc)
                } else {
                    encoder?.let { release(it) }
                    encoder = null
                }
            }
        } catch (e: Exception) {
            main.post { onError("Mikrofon error: ${e.message}") }
        } finally {
            try { record.stop() } catch (e: Exception) { }
            record.release()
            recognizer.close()
            encoder?.let { release(it) }
        }
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Queues PCM into the encoder; returns the new count of samples queued. */
    private fun feed(enc: MediaCodec, pcm: ShortArray, n: Int, baseUs: Long, queuedIn: Long): Long {
        var queued = queuedIn
        var offset = 0
        while (offset < n) {
            val index = enc.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(enc)
                continue
            }
            val buf = enc.getInputBuffer(index) ?: continue
            buf.clear()
            val count = minOf(n - offset, buf.remaining() / 2)
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm, offset, count)
            buf.put(bytes)
            val ptsUs = baseUs + queued * 1_000_000L / RATE
            enc.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
            offset += count
            queued += count
        }
        return queued
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = enc.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.setAudioFormat(enc.outputFormat)
                index >= 0 -> {
                    val buf = enc.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        sink.onSample(EncodedSample(data, info.presentationTimeUs, info.flags, isVideo = false))
                    }
                    enc.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    private fun release(enc: MediaCodec) {
        try { enc.stop() } catch (e: Exception) { }
        try { enc.release() } catch (e: Exception) { }
    }

    companion object {
        private const val RATE = 48000
        private const val CHUNK = 4800 // 100 ms
    }
}
