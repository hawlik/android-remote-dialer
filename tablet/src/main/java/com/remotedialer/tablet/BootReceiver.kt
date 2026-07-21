package com.remotedialer.tablet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts [LinkService] after the tablet reboots so the rider never has to
 * reopen the app — mirrors the phone's boot auto-start. Registered for
 * ACTION_BOOT_COMPLETED.
 *
 * Only starts once the app is actually set up: BLUETOOTH_CONNECT granted **and**
 * a phone already chosen ([DevicePrefs]). A fresh install with nothing configured
 * stays quiet. Both of those survive a reboot, so once set up this fires on every
 * boot and the link's own exponential-backoff reconnect loop takes over.
 *
 * [LinkService] is a `connectedDevice` foreground service — one of the types
 * Android still permits to be started from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED: an app update (e.g. a redeploy from Android Studio)
        // kills the running service — restart it so a reinstall behaves like a reboot.
        // Both actions are on the FGS background-start exemption list.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!DevicePrefs.autostartOnBoot(context)) {
            Log.i(TAG, "$action: auto-start disabled in settings — not starting")
            return
        }
        if (!hasBluetoothConnect(context)) {
            Log.i(TAG, "$action: BLUETOOTH_CONNECT not granted — not auto-starting")
            return
        }
        if (DevicePrefs.address(context) == null) {
            Log.i(TAG, "$action: no phone chosen yet — not auto-starting")
            return
        }
        Log.i(TAG, "$action — starting LinkService")
        ContextCompat.startForegroundService(
            context, Intent(context, LinkService::class.java)
        )
    }

    private fun hasBluetoothConnect(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BootReceiver"
    }
}
