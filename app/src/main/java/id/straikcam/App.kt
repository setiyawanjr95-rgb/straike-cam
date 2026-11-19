package id.straikcam

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the stack trace of any crash so the main screen can show it on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                CrashLog.file(this).writeText("$time (thread ${thread.name})\n${error.stackTraceToString().take(4000)}")
            } catch (e: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashLog {
    fun file(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
    fun read(app: android.content.Context): String? = file(app).takeIf { it.exists() }?.readText()
    fun clear(app: android.content.Context) { file(app).delete() }
}
