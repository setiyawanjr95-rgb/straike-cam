package id.straikcam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and hands it to the Android installer. */
object Updater {
    private const val REPO = "setiyawanjr95-rgb/straike-cam"

    data class Release(val code: Long, val name: String, val apkUrl: String)

    sealed class Check {
        data class Newer(val release: Release) : Check()
        object UpToDate : Check()
        class Failed(val reason: String) : Check()
    }

    fun installedCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Blocking: call from a background thread. */
    fun check(context: Context): Check {
        return try {
            val conn = open("https://api.github.com/repos/$REPO/releases?per_page=5")
            if (conn.responseCode == 404) return Check.Failed("Repo tidak bisa dibaca tanpa login (private?)")
            if (conn.responseCode != 200) return Check.Failed("GitHub membalas ${conn.responseCode}")
            val list = JSONArray(conn.inputStream.bufferedReader().readText())
            var best: Release? = null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                val code = r.getString("tag_name").substringAfterLast('.').toLongOrNull() ?: continue
                val assets = r.getJSONArray("assets")
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.getString("name").endsWith(".apk") && (best == null || code > best.code)) {
                        best = Release(code, r.getString("tag_name").removePrefix("v"), a.getString("browser_download_url"))
                    }
                }
            }
            when {
                best == null -> Check.Failed("Belum ada APK di Releases")
                best.code > installedCode(context) -> Check.Newer(best)
                else -> Check.UpToDate
            }
        } catch (e: Exception) {
            Check.Failed("Tidak ada internet?")
        }
    }

    /** Blocking download into the app cache. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val file = File(context.cacheDir, "update-${release.code}.apk")
        val conn = open(release.apkUrl)
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        return file
    }

    /** Streams the APK into a PackageInstaller session; Android then asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("straikcam.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
}

/** Receives the installer's answer; opens the confirmation screen when Android needs the user's tap. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
        }
    }
}
