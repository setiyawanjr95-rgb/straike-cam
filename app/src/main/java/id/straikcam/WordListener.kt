package id.straikcam

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Listens offline for the two command words. "straik" is matched by the
 * English word "strike", which sounds the same.
 */
class WordListener(
    private val model: Model,
    private val onWord: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : RecognitionListener {

    private var service: SpeechService? = null

    fun start() {
        val recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR)
        service = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
    }

    fun stop() {
        service?.stop()
        service?.shutdown()
        service = null
    }

    override fun onPartialResult(hypothesis: String?) = handle(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = handle(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = handle(hypothesis, "text")
    override fun onError(exception: Exception?) = onFailure(exception?.message ?: "Pendengar suara error")
    override fun onTimeout() {}

    private fun handle(json: String?, key: String) {
        if (json.isNullOrBlank()) return
        val text = try {
            JSONObject(json).optString(key)
        } catch (e: Exception) {
            return
        }
        // The most recent command word in the utterance wins.
        val word = text.trim().split(" ").lastOrNull { it == WORD_START || it == WORD_STOP } ?: return
        onWord(word)
    }

    companion object {
        const val WORD_START = "strike"
        const val WORD_STOP = "stop"
        private const val SAMPLE_RATE = 16000f
        private const val GRAMMAR = "[\"$WORD_START\", \"$WORD_STOP\", \"[unk]\"]"

        /** Copies the speech model out of the APK once per app version, then loads it. */
        fun loadModel(context: Context): Model {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = PackageInfoCompat.getLongVersionCode(info)
            val dir = File(context.filesDir, "vosk-model")
            val marker = File(dir, ".copied-$version")
            if (!marker.exists()) {
                val assets = context.assets.list("model")
                if (assets.isNullOrEmpty()) {
                    throw IllegalStateException("Model suara tidak ada di dalam APK")
                }
                dir.deleteRecursively()
                copyAsset(context, "model", dir)
                marker.writeText("ok")
            }
            return Model(dir.absolutePath)
        }

        private fun copyAsset(context: Context, path: String, target: File) {
            val children = context.assets.list(path)
            if (children.isNullOrEmpty()) {
                target.parentFile?.mkdirs()
                context.assets.open(path).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                target.mkdirs()
                for (child in children) copyAsset(context, "$path/$child", File(target, child))
            }
        }
    }
}
