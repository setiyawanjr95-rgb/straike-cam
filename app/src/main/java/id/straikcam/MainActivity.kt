package id.straikcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
                startStandby()
            } else {
                Toast.makeText(this, "Izin kamera dan mikrofon wajib untuk Siaga", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStandby.setOnClickListener {
            when (AppState.status.value.mode) {
                Mode.OFF, Mode.ERROR -> requestAndStart()
                else -> sendToService(StandbyService.ACTION_SHUTDOWN)
            }
        }
        binding.btnBattery.setOnClickListener { openBatterySettings() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppState.status.collect { render(it) }
            }
        }
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
        binding.status.setTextColor(
            Color.parseColor(if (status.mode == Mode.ERROR) "#FFFF8A80" else "#FFB0BEC5"),
        )
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
            // Already exempt: open the app's settings page for brand-specific options (Autostart, etc.)
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
