package com.remotedialer.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedialer.shared.CallType
import com.remotedialer.shared.Contact
import com.remotedialer.shared.RecentCall
import com.remotedialer.tablet.databinding.ActivityDialerBinding

/**
 * Phase 2 dialer: shows the phone's starred contacts (Favourites) and recent
 * calls, both pushed over the link into [LinkState]. Tapping a row places an
 * outbound call via [LinkState.dial] — the phone dials in the stock dialer and
 * the in-call screen (timer + End-call) appears once it connects.
 *
 * Rows are built programmatically (glove-sized, cockpit-styled) so the list can
 * be a simple two-section vertical stack of monogram + name + data.
 */
class DialerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.refreshButton.setOnClickListener { LinkState.requestDirectory() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        LinkState.directoryListener = { runOnUiThread { rebuild() } }
        LinkState.statusListener = { runOnUiThread { rebuild() } } // reflect connect/disconnect live
        ensureServiceRunning() // opening the app means "I want the link up"
        LinkState.requestDirectory() // fresh snapshot each time the screen opens
        rebuild()
    }

    /**
     * The dialer is the home screen — if the app is fully set up but the service
     * isn't running (first open after a reboot with autostart off, an app update,
     * or a crash), start it here rather than making the user visit Settings.
     */
    private fun ensureServiceRunning() {
        if (LinkState.serviceRunning || !isConfigured()) return
        ContextCompat.startForegroundService(this, Intent(this, LinkService::class.java))
        LinkState.serviceRunning = true
    }

    private fun isConfigured(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED && DevicePrefs.address(this) != null

    override fun onPause() {
        super.onPause()
        LinkState.directoryListener = null
        LinkState.statusListener = null
    }

    private fun rebuild() {
        val connected = LinkState.connected
        val dotColor = when {
            !LinkState.serviceRunning -> R.color.muted
            connected -> R.color.green
            else -> R.color.amber
        }
        binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, dotColor)
        binding.statusText.text = when {
            connected -> getString(R.string.dialer_connected_to, DevicePrefs.name(this) ?: "phone")
            !isConfigured() -> getString(R.string.dialer_setup_hint)
            else -> getString(R.string.dialer_disconnected)
        }

        val container = binding.listContainer
        container.removeAllViews()

        val contacts = LinkState.contacts
        container.addView(sectionHeader(getString(R.string.favorites), contacts.size))
        if (contacts.isEmpty()) {
            container.addView(emptyRow(getString(R.string.no_favorites)))
        } else {
            contacts.forEach { container.addView(contactRow(it)) }
        }

        val recents = LinkState.recents
        container.addView(sectionHeader(getString(R.string.recents), recents.size))
        if (recents.isEmpty()) {
            container.addView(emptyRow(getString(R.string.no_recents)))
        } else {
            recents.forEach { container.addView(recentRow(it)) }
        }
    }

    private fun contactRow(contact: Contact): View =
        row(initials(contact.name, contact.number), contact.name, contact.number, muted()) {
            placeCall(contact.name, contact.number)
        }

    private fun recentRow(recent: RecentCall): View {
        val title = recent.name.ifBlank { recent.number }
        val whenText = DateUtils.getRelativeTimeSpanString(
            recent.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val subtitle = buildString {
            append(typeLabel(recent.type)).append("  ·  ").append(whenText)
            if (recent.name.isNotBlank()) append("  ·  ").append(recent.number)
        }
        val subtitleColor = if (recent.type == CallType.MISSED) color(R.color.red) else muted()
        return row(initials(recent.name, recent.number), title, subtitle, subtitleColor) {
            placeCall(recent.name, recent.number)
        }
    }

    private fun placeCall(name: String, number: String) {
        if (!LinkState.connected) {
            rebuild()
            return
        }
        LinkState.dial(name, number)
        // Launching from the foreground, so no full-screen intent needed. The
        // dialer stays in the stack — ending the call returns here.
        startActivity(Intent(this, IncomingCallActivity::class.java))
    }

    private fun typeLabel(type: CallType): String = when (type) {
        CallType.INCOMING -> "↙ " + getString(R.string.call_incoming)
        CallType.OUTGOING -> "↗ " + getString(R.string.call_outgoing)
        CallType.MISSED -> "✕ " + getString(R.string.call_missed)
        CallType.OTHER -> "• " + getString(R.string.call_other)
    }

    // ---- cockpit-styled view builders ----

    private fun row(monogram: String, title: String, subtitle: String, subtitleColor: Int, onClick: () -> Unit): View {
        val avatar = TextView(this).apply {
            text = monogram
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(color(R.color.amber))
            textSize = 18f
            background = ContextCompat.getDrawable(this@DialerActivity, R.drawable.bg_avatar)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text))
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(subtitleColor)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
            addView(titleView)
            addView(subtitleView)
        }
        // The ONLY tap target: a big green call button. The row itself is inert so
        // a glove brushing the list while scrolling can't start a call.
        val callButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_call)
            imageTintList = ContextCompat.getColorStateList(this@DialerActivity, R.color.green)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundResource(borderlessRipple())
            contentDescription = getString(R.string.call_someone, title)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(this@DialerActivity, R.drawable.bg_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            addView(avatar)
            addView(textColumn)
            addView(callButton)
        }
    }

    private fun borderlessRipple(): Int {
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        return out.resourceId
    }

    private fun sectionHeader(text: String, count: Int): View = TextView(this).apply {
        this.text = if (count > 0) "$text  ·  $count" else text
        textSize = 13f
        isAllCaps = true
        letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(muted())
        setPadding(dp(6), dp(22), dp(6), dp(8))
    }

    private fun emptyRow(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(muted())
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun initials(name: String, number: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            number.isNotBlank() -> "#"
            else -> "?"
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun muted(): Int = color(R.color.muted)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
