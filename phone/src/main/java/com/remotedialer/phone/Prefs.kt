package com.remotedialer.phone

import android.content.Context
import androidx.core.content.edit

/** Small persisted settings for the phone helper app. */
object Prefs {

    private const val PREFS = "remote_dialer_phone"
    private const val KEY_AUTOSTART = "autostart_on_boot"

    /**
     * Whether to restart the service automatically after a reboot. Default on —
     * that's the always-on behaviour the app is built for — but the user can turn
     * it off from the setup screen. Honoured by [BootReceiver].
     */
    fun autostartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, true)

    fun setAutostartOnBoot(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTOSTART, value) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
