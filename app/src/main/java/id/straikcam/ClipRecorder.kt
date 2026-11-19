package id.straikcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One compressed audio or video frame coming out of an encoder. */
class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int, val isVideo: Boolean) {
    val isKeyFrame get() = isVideo && (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
}

/**
 * Keeps the last few seconds of encoded audio and video in memory (the pre-roll),
 * and when a clip starts, writes them to an MP4 followed by the live frames.
 * All methods are thread-safe: encoders call in from their own threads.
 */
class ClipRecorder(
    private val context: Context,
    private val preRollUs: Long,
    private val onFinished: (saved: Boolean, message: String) -> Unit,
) {
    private enum class State { IDLE, PENDING, RECORDING }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val buffer = ArrayDeque<EncodedSample>()

    // All muxer work (open, write, finish) happens on this one thread, so the encoder threads
    // and the main thread never wait on disk I/O. Fields below marked "writer" are only touched here.
    private val writerThread = HandlerThread("clipwriter").apply { start() }
    private val writer = Handler(writerThread.looper)
    private var sessionId = 0

    private var state = State.IDLE
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var triggerUs = 0L
    private var latestUs = 0L

    // writer thread only:
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var clipStartUs = 0L
    private var hasVideo = false
    private var writeFailed = false

    @Volatile
    var orientationHint = 0

    fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        videoFormat = format
        tryStart()
    }

    fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
        audioFormat = format
        tryStart()
    }

    fun onSample(sample: EncodedSample) = synchronized(lock) {
        if (sample.ptsUs > latestUs) latestUs = sample.ptsUs
        if (state == State.RECORDING) {
            writer.post { write(sample) }
        } else {
            buffer.addLast(sample)
            trim()
            if (state == State.PENDING) tryStart()
        }
    }

    /** Start a clip that begins [preRollUs] before [nowUs] (or as early as the buffer allows). */
    fun start(nowUs: Long) = synchronized(lock) {
        if (state != State.IDLE) return@synchronized
        if (preRollUs == 0L) buffer.clear()
        triggerUs = nowUs
        state = State.PENDING
        tryStart()
    }

    fun stop() = synchronized(lock) {
        when (state) {
            State.IDLE -> Unit
            State.PENDING -> {
                state = State.IDLE
                post(false, "Rekaman terlalu pendek, tidak disimpan")
            }
            State.RECORDING -> {
                state = State.IDLE
                writer.post { finish() }
            }
        }
    }

    fun release() = synchronized(lock) {
        if (state == State.RECORDING) writer.post { finish() }
        state = State.IDLE
        buffer.clear()
        writerThread.quitSafely() // already-posted work (the final finish) still runs
    }

    private fun trim() {
        val keepUs = preRollUs + 3_000_000L
        while (buffer.size > 1 && latestUs - buffer.first().ptsUs > keepUs) buffer.removeFirst()
    }

    private fun tryStart() {
        if (state != State.PENDING) return
        val vf = videoFormat ?: return
        // Give the audio encoder up to 2 s to report its format, then go without sound.
        if (audioFormat == null && latestUs - triggerUs < 2_000_000L) return

        val target = triggerUs - preRollUs
        var startIndex = -1
        var firstKey = -1
        buffer.forEachIndexed { i, s ->
            if (s.isKeyFrame) {
                if (firstKey < 0) firstKey = i
                if (s.ptsUs <= target) startIndex = i
            }
        }
        if (startIndex < 0) startIndex = firstKey
        if (startIndex < 0) return // wait for the first key frame

        val af = audioFormat
        val startPts = buffer.elementAt(startIndex).ptsUs
        val snapshot = ArrayList<EncodedSample>(buffer.size)
        var i = 0
        for (s in buffer) {
            if ((i >= startIndex || !s.isVideo) && s.ptsUs >= startPts) snapshot.add(s)
            i++
        }
        buffer.clear()
        state = State.RECORDING
        val id = ++sessionId
        val hint = orientationHint
        writer.post {
            clipStartUs = startPts
            hasVideo = false
            try {
                open(vf, af, hint)
            } catch (e: Exception) {
                closeQuietly()
                synchronized(lock) { if (sessionId == id && state == State.RECORDING) state = State.IDLE }
                post(false, "Gagal membuat file video: ${e.message}")
                return@post
            }
            for (s in snapshot) write(s)
        }
    }

    private fun open(vf: MediaFormat, af: MediaFormat?, orientation: Int) {
        val name = "straik_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/StraikCam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val newUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore menolak file baru")
        uri = newUri
        val fd = resolver.openFileDescriptor(newUri, "rw")
            ?: throw IllegalStateException("Tidak bisa membuka file video")
        pfd = fd
        val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        m.setOrientationHint(orientation)
        videoTrack = m.addTrack(vf)
        audioTrack = if (af != null) m.addTrack(af) else -1
        m.start()
        muxer = m
        lastVideoPts = -1L
        lastAudioPts = -1L
        writeFailed = false
    }

    private fun write(s: EncodedSample) {
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        try {
            if (s.isVideo) {
                if (s.ptsUs <= lastVideoPts) return
                if (s.ptsUs < clipStartUs) return
                lastVideoPts = s.ptsUs
                hasVideo = true
                // Timestamps start at 0 in the file; raw camera clock values are huge.
                info.set(0, s.data.size, s.ptsUs - clipStartUs, s.flags)
                m.writeSampleData(videoTrack, ByteBuffer.wrap(s.data), info)
            } else {
                if (audioTrack < 0 || s.ptsUs <= lastAudioPts || s.ptsUs < clipStartUs) return
                lastAudioPts = s.ptsUs
                info.set(0, s.data.size, s.ptsUs - clipStartUs, s.flags)
                m.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), info)
            }
        } catch (e: Exception) {
            // A bad sample must never crash the app; keep what was written so far.
            writeFailed = true
        }
    }

    private fun finish() {
        if (muxer == null) return // opening failed; the error was already reported
        val seconds = if (lastVideoPts > clipStartUs) (lastVideoPts - clipStartUs) / 1_000_000L else 0L
        var ok = hasVideo
        try {
            muxer?.stop()
        } catch (e: Exception) {
            ok = false
        }
        closeQuietly()
        val savedUri = uri
        uri = null
        if (savedUri != null) {
            val resolver = context.contentResolver
            if (ok) {
                resolver.update(savedUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                try { resolver.delete(savedUri, null, null) } catch (e: Exception) { }
            }
        }
        post(ok, if (ok) "Video $seconds detik tersimpan di Movies/StraikCam" else "Video gagal disimpan")
    }

    private fun closeQuietly() {
        try { muxer?.release() } catch (e: Exception) { }
        muxer = null
        try { pfd?.close() } catch (e: Exception) { }
        pfd = null
    }

    private fun post(saved: Boolean, message: String) {
        main.post { onFinished(saved, message) }
    }
}
