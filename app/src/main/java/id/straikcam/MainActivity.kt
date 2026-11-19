package id.straikcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import id.straikcam.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var feedback: Feedback
    private var support: CameraSupport? = null
    private var heights: List<Int> = listOf(1080)
    private var fpsOptions: List<Int> = listOf(30)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraOk = result[Manifest.permission.CAMERA] ?: hasPermission(Manifest.permission.CAMERA)
            val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
            if (cameraOk && micOk) {
                setupQualityPickers()
                startStandby()
            } else {
                Toast.makeText(this, "Izin kamera dan mikrofon wajib untuk Siaga", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        feedback = Feedback(this)

        binding.btnStandby.setOnClickListener {
            when (AppState.status.value.mode) {
                Mode.OFF, Mode.ERROR -> requestAndStart()
                else -> sendToService(StandbyService.ACTION_SHUTDOWN)
            }
        }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnUpdate.setOnClickListener { checkUpdate(auto = false) }
        binding.btnCrashClose.setOnClickListener {
            CrashLog.clear(this)
            showCrash()
        }
        showCrash()
        binding.tvVersion.text = "v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        binding.btnTestTone.setOnClickListener {
            feedback.recordingStarted()
            Handler(Looper.getMainLooper()).postDelayed({ feedback.recordingStopped() }, 1200)
        }

        binding.chkPreRoll.isChecked = Prefs.preRoll(this)
        binding.chkSound.isChecked = Prefs.sound(this)
        binding.chkVibrate.isChecked = Prefs.vibrate(this)
        binding.chkPreRoll.setOnCheckedChangeListener { _, on -> Prefs.setPreRoll(this, on) }
        binding.chkSound.setOnCheckedChangeListener { _, on -> Prefs.setSound(this, on) }
        binding.chkVibrate.setOnCheckedChangeListener { _, on -> Prefs.setVibrate(this, on) }

        setupQualityPickers()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppState.status.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val mode = AppState.status.value.mode
        if (mode == Mode.OFF || mode == Mode.ERROR) checkUpdate(auto = true)
    }

    private fun showCrash() {
        val log = CrashLog.read(this)
        binding.crashInfo.visibility = if (log == null) View.GONE else View.VISIBLE
        binding.btnCrashClose.visibility = if (log == null) View.GONE else View.VISIBLE
        if (log != null) binding.crashInfo.text = "Aplikasi sempat crash. Kirim teks ini ke developer:\n\n$log"
    }

    private var updating = false

    /** Looks for a newer release; when found, downloads it and opens the Android installer. */
    private fun checkUpdate(auto: Boolean) {
        if (updating) return
        updating = true
        val info = binding.updateInfo
        if (!auto) { info.visibility = View.VISIBLE; info.text = "Mengecek update..." }
        val ui = Handler(Looper.getMainLooper())
        Thread {
            when (val result = Updater.check(this)) {
                is Updater.Check.Newer -> {
                    val r = result.release
                    ui.post { info.visibility = View.VISIBLE; info.text = "Versi ${r.name} ditemukan, mengunduh..." }
                    try {
                        val apk = Updater.download(this, r) { pct ->
                            ui.post { info.text = "Mengunduh versi ${r.name}: $pct%" }
                        }
                        ui.post {
                            if (!packageManager.canRequestPackageInstalls()) {
                                info.text = "Izinkan Straik Cam memasang aplikasi, lalu tekan Cek update lagi."
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                                )
                            } else {
                                info.text = "Versi ${r.name} siap, ikuti layar pemasangan."
                                try { Updater.install(this, apk) } catch (e: Exception) {
                                    info.text = "Gagal memasang update: ${e.message}"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        ui.post { info.visibility = View.VISIBLE; info.text = "Gagal mengunduh update" }
                    }
                }
                is Updater.Check.UpToDate -> if (!auto) ui.post { info.text = "Sudah versi terbaru" }
                is Updater.Check.Failed -> if (!auto) ui.post { info.text = "Cek update gagal: ${result.reason}" }
            }
            ui.post { updating = false }
        }.start()
    }

    /** Fills the resolution / fps pickers with only what this phone can record. */
    private fun setupQualityPickers() {
        support = try {
            CameraSupport(this)
        } catch (e: Exception) {
            null
        }
        heights = support?.supportedHeights()?.ifEmpty { null } ?: listOf(720, 1080)
        val savedHeight = VideoPrefs.height(this)
        val heightIndex = heights.indexOf(savedHeight).takeIf { it >= 0 } ?: heights.indexOf(1080).coerceAtLeast(0)
        binding.spinResolution.adapter = adapter(heights.map { CameraSupport.label(it) })
        binding.spinResolution.setSelection(heightIndex)
        binding.spinResolution.onItemSelectedListener = onSelect { pos ->
            val h = heights[pos]
            VideoPrefs.setHeight(this, h)
            refreshFps(h)
        }
        refreshFps(heights[heightIndex])
        binding.camInfo.text = support?.report() ?: "Kamera tidak terbaca"
    }

    private fun refreshFps(height: Int) {
        fpsOptions = listOf(30, 60).filter { it == 30 || support?.supports(height, it) == true }
        val fpsLabels = fpsOptions.map {
            if (it == 60 && support?.cameraConfirms(height, 60) != true) "60 fps (uji coba)" else "$it fps"
        }
        val saved = VideoPrefs.fps(this)
        val index = fpsOptions.indexOf(saved).takeIf { it >= 0 } ?: 0
        binding.spinFps.adapter = adapter(fpsLabels)
        binding.spinFps.setSelection(index)
        VideoPrefs.setFps(this, fpsOptions[index])
        binding.spinFps.onItemSelectedListener = onSelect { pos -> VideoPrefs.setFps(this, fpsOptions[pos]) }
    }

    private fun adapter(items: List<String>) =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun onSelect(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private var pulse: android.animation.ObjectAnimator? = null
    private var pulseMode: Mode? = null

    private fun render(status: Status) {
        binding.status.text = status.message
        // label on the round button, pill text, accent color
        val (label, pill, color) = when (status.mode) {
            Mode.OFF -> Triple("SIAGA", "SIAGA MATI", "#FF0E7C6B")
            Mode.ERROR -> Triple("SIAGA", "ERROR", "#FF0E7C6B")
            Mode.LOADING -> Triple("MEMUAT", "MENYIAPKAN", "#FF3A5266")
            Mode.STANDBY -> Triple("MATIKAN\nSIAGA", "SIAGA NYALA", "#FF19C3A5")
            Mode.RECORDING -> Triple("MEREKAM", "REC", "#FFF0443D")
        }
        val accent = Color.parseColor(color)
        binding.btnStandby.text = label
        binding.btnStandby.backgroundTintList = ColorStateList.valueOf(accent)
        binding.ring.backgroundTintList = ColorStateList.valueOf(accent)
        binding.statePill.text = pill
        val pillColor = when (status.mode) {
            Mode.OFF -> Color.parseColor("#FF8BA3B3")
            Mode.ERROR -> Color.parseColor("#FFFF8A80")
            Mode.LOADING -> Color.parseColor("#FFFFB547")
            else -> accent
        }
        binding.statePill.setTextColor(pillColor)
        binding.statePill.backgroundTintList = ColorStateList.valueOf((pillColor and 0x00FFFFFF) or 0x33000000)
        binding.status.setTextColor(Color.parseColor(if (status.mode == Mode.ERROR) "#FFFF8A80" else "#FF8BA3B3"))
        updatePulse(status.mode)

        val editable = status.mode == Mode.OFF || status.mode == Mode.ERROR
        listOf<View>(binding.spinResolution, binding.spinFps, binding.chkPreRoll).forEach {
            it.isEnabled = editable
            it.alpha = if (editable) 1f else 0.4f
        }
        binding.settingsNote.visibility = if (editable) View.GONE else View.VISIBLE
    }

    /** Ring around the main button breathes while standing by, faster while recording. */
    private fun updatePulse(mode: Mode) {
        if (mode == pulseMode) return
        pulseMode = mode
        pulse?.cancel()
        pulse = null
        val ring = binding.ring
        if (mode == Mode.OFF || mode == Mode.ERROR) {
            ring.visibility = View.INVISIBLE
            return
        }
        ring.visibility = View.VISIBLE
        val fast = mode == Mode.RECORDING
        val holder = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.95f, if (fast) 1.18f else 1.1f)
        val holderY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.95f, if (fast) 1.18f else 1.1f)
        val fade = android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
        pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(ring, holder, holderY, fade).apply {
            duration = if (fast) 900L else 1800L
            repeatCount = android.animation.ObjectAnimator.INFINITE
            interpolator = android.view.animation.DecelerateInterpolator()
            start()
        }
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }

    private fun requestAndStart() {
        val missing = requiredPermissions.filterNot(::hasPermission)
        if (missing.isEmpty()) startStandby() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startStandby() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, StandbyService::class.java).setAction(StandbyService.ACTION_START),
        )
    }

    private fun sendToService(action: String) {
        startService(Intent(this, StandbyService::class.java).setAction(action))
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openBatterySettings() {
        val power = getSystemService(PowerManager::class.java)
        val intent = if (!power.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
