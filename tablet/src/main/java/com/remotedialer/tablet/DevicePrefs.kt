package com.remotedialer.tablet

import android.content.Context
import androidx.core.content.edit

/** Persists which paired device is "the phone" so the service can reconnect to it. */
object DevicePrefs {

    private const val PREFS = "remote_dialer"
    private const val KEY_ADDRESS = "phone_address"
    private const val KEY_NAME = "phone_name"
    private const val KEY_AUTOSTART = "autostart_on_boot"
    private const val KEY_QUICK_REPLIES = "quick_replies"

    fun save(context: Context, address: String, name: String?) {
        prefs(context).edit {
            putString(KEY_ADDRESS, address)
            putString(KEY_NAME, name)
        }
    }

    fun address(context: Context): String? = prefs(context).getString(KEY_ADDRESS, null)

    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    /** Whether to reconnect automatically after a reboot. Default on; honoured by [BootReceiver]. */
    fun autostartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, true)

    fun setAutostartOnBoot(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTOSTART, value) }
    }

    /**
     * The reject-with-SMS quick replies, in display order. Ships with 5 common
     * defaults; every one is editable/deletable in QuickRepliesActivity. Stored
     * newline-separated, so per-message newlines are flattened on save.
     */
    fun quickReplies(context: Context): List<String> {
        val stored = prefs(context).getString(KEY_QUICK_REPLIES, null) ?: return DEFAULT_REPLIES
        return stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setQuickReplies(context: Context, replies: List<String>) {
        val flat = replies
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        prefs(context).edit { putString(KEY_QUICK_REPLIES, flat) }
    }

    private val DEFAULT_REPLIES = listOf(
        "I'm riding. I'll call you back.",
        "Can't talk right now.",
        "On the bike. Talk later.",
        "I'll call you when I stop.",
        "Text me if it's urgent."
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
