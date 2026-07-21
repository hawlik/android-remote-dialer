package com.remotedialer.phone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts [CallMonitorService] after the phone reboots so the rider never has to
 * reopen the app. Registered for ACTION_BOOT_COMPLETED.
 *
 * Only starts if the core runtime permissions are already granted — on a fresh
 * install (nothing granted yet) we stay quiet until the user sets things up in
 * [MainActivity]. Runtime-permission grants survive a reboot, so once the app has
 * been configured this fires on every subsequent boot.
 *
 * Starting a foreground service from BOOT_COMPLETED is an allowed exemption to the
 * background-FGS-start restriction, and the service's `connectedDevice` type is one
 * of the types Android still permits to be started from BOOT_COMPLETED (unlike
 * mediaPlayback / microphone / camera / etc.).
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
        if (!Prefs.autostartOnBoot(context)) {
            Log.i(TAG, "$action: auto-start disabled in settings — not starting")
            return
        }
        if (!hasCorePermissions(context)) {
            Log.i(TAG, "$action: core permissions not granted yet — not auto-starting")
            return
        }
        Log.i(TAG, "$action — starting CallMonitorService")
        ContextCompat.startForegroundService(
            context, Intent(context, CallMonitorService::class.java)
        )
    }

    private fun hasCorePermissions(context: Context): Boolean =
        CORE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val TAG = "BootReceiver"

        /** The permissions the service actually needs to do its job on boot. */
        val CORE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
