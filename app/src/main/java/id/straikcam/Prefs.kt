package id.straikcam

import android.content.Context

/** User settings shown as checkboxes on the main screen. */
object Prefs {
    private const val FILE = "settings"
    const val PRE_ROLL_SECONDS = 30

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun preRoll(context: Context) = sp(context).getBoolean("pre_roll", true)
    fun setPreRoll(context: Context, on: Boolean) = sp(context).edit().putBoolean("pre_roll", on).apply()

    fun sound(context: Context) = sp(context).getBoolean("sound", true)
    fun setSound(context: Context, on: Boolean) = sp(context).edit().putBoolean("sound", on).apply()

    fun vibrate(context: Context) = sp(context).getBoolean("vibrate", true)
    fun setVibrate(context: Context, on: Boolean) = sp(context).edit().putBoolean("vibrate", on).apply()
}

/** Video quality the user picked; the engine falls back if the phone can't do it. */
object VideoPrefs {
    private fun sp(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 720, 1080 or 2160 (4K). */
    fun height(context: Context) = sp(context).getInt("height", 1080)
    fun setHeight(context: Context, h: Int) = sp(context).edit().putInt("height", h).apply()

    /** 30 or 60. */
    fun fps(context: Context) = sp(context).getInt("fps", 30)
    fun setFps(context: Context, f: Int) = sp(context).edit().putInt("fps", f).apply()
}

/** Camera choices: which lens, and whether to stabilize. */
object LensPrefs {
    private fun sp(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** True = ultra wide (0.5x), false = main lens (1x). */
    fun ultraWide(context: Context) = sp(context).getBoolean("ultra_wide", false)
    fun setUltraWide(context: Context, on: Boolean) = sp(context).edit().putBoolean("ultra_wide", on).apply()

    fun stabilize(context: Context) = sp(context).getBoolean("stabilize", true)
    fun setStabilize(context: Context, on: Boolean) = sp(context).edit().putBoolean("stabilize", on).apply()
}
