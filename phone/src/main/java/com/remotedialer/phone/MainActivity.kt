package com.remotedialer.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedialer.phone.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantButton.setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }
        // Start when stopped; restart (to re-establish the tablet link) when running.
        binding.startButton.setOnClickListener {
            if (!allGranted()) {
                permissionLauncher.launch(requiredPermissions)
                return@setOnClickListener
            }
            val intent = Intent(this, CallMonitorService::class.java)
            val wasRunning = PhoneCallState.serviceRunning
            if (wasRunning) {
                stopService(intent)
                PhoneCallState.tabletConnected = false
            }
            ContextCompat.startForegroundService(this, intent)
            PhoneCallState.serviceRunning = true
            PhoneCallState.update(if (wasRunning) "Restarting…" else "Starting…")
            refreshStatus()
        }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, CallMonitorService::class.java))
            PhoneCallState.serviceRunning = false
            PhoneCallState.tabletConnected = false
            PhoneCallState.update("Service stopped")
            refreshStatus()
        }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.autostartSwitch.isChecked = Prefs.autostartOnBoot(this)
        binding.autostartSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutostartOnBoot(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        PhoneCallState.listener = { event ->
            runOnUiThread {
                binding.callText.text = event
                updateStatusDot()
                updateServiceButtons()
            }
        }
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        PhoneCallState.listener = null
    }

    private fun allGranted(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Whether the phone has exempted us from battery optimization. Without it the
     * OS can doze/kill the always-on [CallMonitorService] over long idle periods —
     * even on a stock Pixel — so an incoming call would never reach the tablet.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /**
     * Ask the OS to stop battery-optimizing this app. Pops the direct system
     * dialog; falls back to the battery-optimization settings list if unavailable.
     * ([SuppressLint] "BatteryLife": Play restricts this, fine for our sideload.)
     */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            refreshStatus()
            return
        }
        val appUri = Uri.fromParts("package", packageName, null)
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri)
        val opened = runCatching { startActivity(direct); true }.getOrDefault(false)
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun refreshStatus() {
        binding.statusText.text = buildString {
            append(if (allGranted()) "All permissions granted ✓" else "Permissions needed — tap “Grant permissions”")
            append("\nBattery: ")
            append(if (isIgnoringBatteryOptimizations()) "unrestricted ✓" else "optimized — may be killed; tap to fix")
        }
        binding.callText.text = PhoneCallState.lastEvent
        updateStatusDot()
        updateServiceButtons()
        updateSetupButtons()
    }

    private fun updateSetupButtons() {
        binding.grantButton.visibility = if (allGranted()) View.GONE else View.VISIBLE
        binding.batteryButton.visibility =
            if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    private fun updateStatusDot() {
        val colorRes = when {
            !PhoneCallState.serviceRunning -> R.color.muted
            PhoneCallState.tabletConnected -> R.color.green
            else -> R.color.amber
        }
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun updateServiceButtons() {
        val running = PhoneCallState.serviceRunning
        binding.startButton.setText(if (running) R.string.restart_service else R.string.start_service)
        binding.stopButton.visibility = if (running) View.VISIBLE else View.GONE
    }
}
