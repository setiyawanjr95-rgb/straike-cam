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
    }

    private fun refreshFps(height: Int) {
        fpsOptions = listOf(30, 60).filter { it == 30 || support?.supports(height, it) == true }
        val saved = VideoPrefs.fps(this)
        val index = fpsOptions.indexOf(saved).takeIf { it >= 0 } ?: 0
        binding.spinFps.adapter = adapter(fpsOptions.map { "$it fps" })
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

    private fun render(status: Status) {
        binding.status.text = status.message
        val (label, color) = when (status.mode) {
            Mode.OFF, Mode.ERROR -> "SIAGA" to "#FF2E7D32"
            Mode.LOADING -> "MEMUAT..." to "#FF546E7A"
            Mode.STANDBY -> "MATIKAN\nSIAGA" to "#FF37474F"
            Mode.RECORDING -> "MEREKAM" to "#FFC62828"
        }
        binding.btnStandby.text = label
        binding.btnStandby.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        binding.status.setTextColor(Color.parseColor(if (status.mode == Mode.ERROR) "#FFFF8A80" else "#FFB0BEC5"))

        val editable = status.mode == Mode.OFF || status.mode == Mode.ERROR
        listOf<View>(binding.spinResolution, binding.spinFps, binding.chkPreRoll).forEach {
            it.isEnabled = editable
            it.alpha = if (editable) 1f else 0.4f
        }
        binding.settingsNote.visibility = if (editable) View.GONE else View.VISIBLE
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
