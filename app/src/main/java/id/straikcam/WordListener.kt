package id.straikcam

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import org.vosk.Model
import java.io.File

/**
 * Command words and the offline speech model. "straik" is matched by the
 * English word "strike", which sounds the same.
 */
object Words {
    const val START = "strike"
    const val STOP = "stop"
    const val GRAMMAR = "[\"$START\", \"$STOP\", \"[unk]\"]"

    /** Returns the most recent command word in a Vosk JSON result, or null. */
    fun find(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val text = try {
            JSONObject(json).optString(key)
        } catch (e: Exception) {
            return null
        }
        return text.trim().split(" ").lastOrNull { it == START || it == STOP }
    }

    /** Copies the speech model out of the APK once per app version, then loads it. */
    fun loadModel(context: Context): Model {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = PackageInfoCompat.getLongVersionCode(info)
        val dir = File(context.filesDir, "vosk-model")
        val marker = File(dir, ".copied-$version")
        if (!marker.exists()) {
            val assets = context.assets.list("model")
            if (assets.isNullOrEmpty()) throw IllegalStateException("Model suara tidak ada di dalam APK")
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
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            for (child in children) copyAsset(context, "$path/$child", File(target, child))
        }
    }
}
