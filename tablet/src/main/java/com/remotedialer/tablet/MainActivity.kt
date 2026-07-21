package com.remotedialer.tablet

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedialer.tablet.databinding.ActivityMainBinding

/**
 * Status/setup screen: grant permissions, pick which paired device is the phone,
 * and start/stop the [LinkService]. The full-screen call UI is driven entirely
 * by the service — this screen is just for setup and a live status readout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = arrayOf(
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
        binding.fullScreenButton.setOnClickListener { requestFullScreenIntent() }
        binding.overlayButton.setOnClickListener { requestOverlay() }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.chooseButton.setOnClickListener { choosePhone() }
        binding.quickRepliesButton.setOnClickListener {
            startActivity(Intent(this, QuickRepliesActivity::class.java))
        }
        // Start when stopped; restart (to re-establish the phone link) when running.
        binding.startButton.setOnClickListener {
            when {
                !allGranted() -> permissionLauncher.launch(requiredPermissions)
                DevicePrefs.address(this) == null -> choosePhone()
                else -> {
                    val intent = Intent(this, LinkService::class.java)
                    val wasRunning = LinkState.serviceRunning
                    if (wasRunning) {
                        stopService(intent)
                        LinkState.setConnected(false)
                    }
                    ContextCompat.startForegroundService(this, intent)
                    LinkState.serviceRunning = true
                    LinkState.update(if (wasRunning) "Restarting…" else "Starting…")
                    refreshStatus()
                }
            }
        }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, LinkService::class.java))
            LinkState.serviceRunning = false
            LinkState.setConnected(false)
            LinkState.update("Service stopped")
            refreshStatus()
        }
        // Settings is a leaf screen entered from the dialer — back just returns.
        // (isTaskRoot guards odd entries, e.g. a stale launcher shortcut.)
        binding.backButton.setOnClickListener {
            if (isTaskRoot) startActivity(Intent(this, DialerActivity::class.java))
            finish()
        }

        binding.autostartSwitch.isChecked = DevicePrefs.autostartOnBoot(this)
        binding.autostartSwitch.setOnCheckedChangeListener { _, checked ->
            DevicePrefs.setAutostartOnBoot(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        LinkState.statusListener = { event ->
            runOnUiThread {
                binding.eventText.text = event
                updateStatusDot()
                updateServiceButtons()
            }
        }
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        LinkState.statusListener = null
    }

    @SuppressLint("MissingPermission") // guarded by the BLUETOOTH_CONNECT check below
    private fun choosePhone() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            permissionLauncher.launch(requiredPermissions)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            binding.statusText.text = "Turn Bluetooth on first"
            return
        }
        // Phones first (by Bluetooth device class), then alphabetical — the phone
        // is almost always the row labelled "Phone" at the top.
        val bonded = adapter.bondedDevices.orEmpty().sortedWith(
            compareByDescending<BluetoothDevice> { isPhone(it) }.thenBy { it.name ?: it.address }
        )
        if (bonded.isEmpty()) {
            binding.statusText.text = "No paired devices — pair the phone in Bluetooth settings"
            return
        }

        val dialog = AlertDialog.Builder(this).setTitle("Choose your phone").create()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bonded.forEach { device -> list.addView(deviceRow(device) { pick(device, dialog) }) }
        dialog.setView(ScrollView(this).apply { addView(list) })
        dialog.show()
    }

    private fun pick(device: BluetoothDevice, dialog: AlertDialog) {
        @SuppressLint("MissingPermission") // reached only from choosePhone(), which checks it
        val name = device.name
        DevicePrefs.save(this, device.address, name)
        refreshStatus()
        dialog.dismiss()
    }

    /** A tall, glove-friendly two-line row: "Name — Type" over the MAC address. */
    @SuppressLint("MissingPermission") // called only from choosePhone(), which checks it
    private fun deviceRow(device: BluetoothDevice, onClick: () -> Unit): View {
        val title = TextView(this).apply {
            text = "${device.name ?: "(unnamed)"} — ${deviceType(device)}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = device.address
            textSize = 14f
            alpha = 0.6f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableItemBackground())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            addView(title)
            addView(subtitle)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("MissingPermission") // called only from choosePhone(), which checks it
    private fun isPhone(device: BluetoothDevice): Boolean =
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE

    @SuppressLint("MissingPermission") // called only from choosePhone(), which checks it
    private fun deviceType(device: BluetoothDevice): String =
        when (device.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.PHONE -> "Phone"
            BluetoothClass.Device.Major.COMPUTER -> "Computer"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable"
            BluetoothClass.Device.Major.IMAGING -> "Imaging"
            BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
            BluetoothClass.Device.Major.NETWORKING -> "Networking"
            else -> "Other"
        }

    private fun selectableItemBackground(): Int {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun allGranted(): Boolean = requiredPermissions.all { hasPermission(it) }

    /**
     * Whether the OS will let us launch the incoming-call screen as a full-screen
     * intent (interrupting the nav app) rather than a mere heads-up notification.
     * On Android 14+ a sideloaded app is not granted this by default and the user
     * must allow it in Settings — see [requestFullScreenIntent].
     */
    private fun canUseFullScreenIntent(): Boolean =
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /**
     * Send the user to the per-app "full-screen intents" toggle. If already
     * granted, nothing to do; otherwise open the dedicated settings screen (and
     * fall back to the generic app-details page on OEMs that lack it).
     */
    private fun requestFullScreenIntent() {
        if (canUseFullScreenIntent()) {
            refreshStatus()
            return
        }
        val appUri = Uri.fromParts("package", packageName, null)
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, appUri)
        val opened = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))
            }
        }
    }

    /**
     * Whether we can "display over other apps" (SYSTEM_ALERT_WINDOW). With it, the
     * service launches the incoming-call screen directly so it takes over the nav
     * app immediately; without it a full-screen intent only takes over when the
     * tablet is locked/screen-off and otherwise arrives as a heads-up notification.
     */
    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    /** Send the user to the per-app "display over other apps" toggle. */
    private fun requestOverlay() {
        if (canDrawOverlays()) {
            refreshStatus()
            return
        }
        val appUri = Uri.fromParts("package", packageName, null)
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri)
        val opened = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    /**
     * Whether the tablet has exempted us from battery optimization. When it
     * hasn't, the OS can doze/kill the boot-started [LinkService] over time, so
     * the link silently stops reconnecting until the app is reopened.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /**
     * Ask the OS to stop battery-optimizing this app so the link service survives
     * long idle periods. Pops the direct system dialog; falls back to the
     * battery-optimization settings list if that intent isn't available.
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
        val phone = DevicePrefs.name(this)
            ?.let { "$it (${DevicePrefs.address(this)})" }
            ?: "none selected"
        binding.statusText.text = buildString {
            append(if (allGranted()) "Permissions granted ✓" else "Permissions needed")
            append("\nFull-screen calls: ")
            append(if (canUseFullScreenIntent()) "allowed ✓" else "not allowed — tap the button")
            append("\nShow over nav app: ")
            append(if (canDrawOverlays()) "allowed ✓" else "not allowed — tap the button")
            append("\nBattery: ")
            append(if (isIgnoringBatteryOptimizations()) "unrestricted ✓" else "optimized — may be killed; tap to fix")
            append("\nPhone: ")
            append(phone)
        }
        binding.eventText.text = LinkState.lastEvent
        updateStatusDot()
        updateServiceButtons()
        updateSetupButtons()
    }

    private fun updateSetupButtons() {
        binding.grantButton.visibility = gone(allGranted())
        binding.fullScreenButton.visibility = gone(canUseFullScreenIntent())
        binding.overlayButton.visibility = gone(canDrawOverlays())
        binding.batteryButton.visibility = gone(isIgnoringBatteryOptimizations())
        // Choose phone stays — you may want to switch to a different phone later.
    }

    private fun gone(done: Boolean): Int = if (done) View.GONE else View.VISIBLE

    private fun updateStatusDot() {
        val colorRes = when {
            !LinkState.serviceRunning -> R.color.muted
            LinkState.connected -> R.color.green
            else -> R.color.amber
        }
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun updateServiceButtons() {
        val running = LinkState.serviceRunning
        binding.startButton.setText(if (running) R.string.restart_service else R.string.start_service)
        binding.stopButton.visibility = if (running) View.VISIBLE else View.GONE
    }

}
