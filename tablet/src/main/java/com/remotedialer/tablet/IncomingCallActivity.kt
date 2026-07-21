package com.remotedialer.tablet

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedialer.shared.Message
import com.remotedialer.tablet.databinding.ActivityIncomingCallBinding

/**
 * Full-screen call screen, launched over the nav app by [LinkService]'s
 * full-screen-intent notification. It renders the current [LinkState.CallPhase]:
 *
 *  - RINGING: caller + big Accept / Decline (Accept → [Message.Answer]).
 *  - ACTIVE:  caller + a running duration timer + a big End-call button
 *    (End → [Message.Reject], which the phone maps to `endCall()`).
 *
 * It observes [LinkState.callListener] so it updates live as the call moves from
 * ringing → active → ended (finishing itself when the call ends), no matter
 * whether the call was answered here or directly on the phone.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding

    // Our own listener instance, so onDestroy can tell whether the registered
    // listener is still ours (a newer instance may have replaced it).
    private val callListener: (LinkState.CallPhase) -> Unit =
        { phase -> runOnUiThread { render(phase) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live.add(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.acceptButton.setOnClickListener {
            LinkState.send(Message.Answer)
            renderAnswering() // immediate feedback until CallActive flips us to the timer
        }
        binding.declineButton.setOnClickListener {
            LinkState.send(Message.Reject)
            finish()
        }
        binding.endButton.setOnClickListener {
            LinkState.send(Message.Reject) // Reject == endCall() on the phone
            finish()
        }
        binding.smsButton.setOnClickListener { showReplies() }

        LinkState.callListener = callListener
        render(LinkState.callPhase)
    }

    private fun render(phase: LinkState.CallPhase) {
        binding.callerName.text = LinkState.callerName.ifBlank { "Unknown caller" }
        binding.callerNumber.text = LinkState.callerNumber
        binding.callerAvatar.text = initials(LinkState.callerName, LinkState.callerNumber)
        // Any phase change dismisses the quick-reply picker and restores the header.
        binding.messageListScroll.visibility = View.GONE
        binding.callerAvatar.visibility = View.VISIBLE
        binding.callerNumber.visibility = View.VISIBLE
        when (phase) {
            LinkState.CallPhase.RINGING -> {
                binding.statusLabel.setText(R.string.incoming_call)
                binding.statusLabel.visibility = View.VISIBLE
                binding.callTimer.stop()
                binding.callTimer.visibility = View.GONE
                binding.ringingButtons.visibility = View.VISIBLE
                binding.smsButton.visibility = View.VISIBLE
                binding.endButton.visibility = View.GONE
            }
            LinkState.CallPhase.DIALING -> {
                // Outbound: "Calling…" + End (cancel) until the phone connects.
                binding.statusLabel.setText(R.string.calling)
                binding.statusLabel.visibility = View.VISIBLE
                binding.callTimer.stop()
                binding.callTimer.visibility = View.GONE
                binding.ringingButtons.visibility = View.GONE
                binding.smsButton.visibility = View.GONE
                binding.endButton.visibility = View.VISIBLE
            }
            LinkState.CallPhase.ACTIVE -> {
                binding.statusLabel.visibility = View.GONE
                binding.ringingButtons.visibility = View.GONE
                binding.smsButton.visibility = View.GONE
                binding.endButton.visibility = View.VISIBLE
                binding.callTimer.visibility = View.VISIBLE
                binding.callTimer.base = LinkState.callBaseElapsed
                binding.callTimer.start()
            }
            LinkState.CallPhase.NONE -> finish()
        }
    }

    /** Between tapping Accept and the phone reporting the call active. */
    private fun renderAnswering() {
        binding.statusLabel.setText(R.string.answering)
        binding.statusLabel.visibility = View.VISIBLE
        binding.ringingButtons.visibility = View.GONE
        binding.smsButton.visibility = View.GONE
        binding.endButton.visibility = View.VISIBLE
        binding.callTimer.visibility = View.GONE
    }

    /** Swap the ringing controls for the quick-reply list (+ Back to restore). */
    private fun showReplies() {
        binding.ringingButtons.visibility = View.GONE
        binding.smsButton.visibility = View.GONE
        binding.callerAvatar.visibility = View.GONE
        binding.callerNumber.visibility = View.GONE

        val list = binding.messageList
        list.removeAllViews()
        DevicePrefs.quickReplies(this).forEach { text ->
            list.addView(replyRow(text, muted = false) {
                LinkState.send(Message.RejectWithSms(text))
                finish()
            })
        }
        list.addView(replyRow(getString(R.string.back), muted = true) {
            render(LinkState.callPhase)
        })
        binding.messageListScroll.visibility = View.VISIBLE
    }

    private fun replyRow(text: String, muted: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(typeface, if (muted) Typeface.NORMAL else Typeface.BOLD)
            setTextColor(
                ContextCompat.getColor(
                    this@IncomingCallActivity,
                    if (muted) R.color.muted else R.color.text
                )
            )
            minHeight = dp(64)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(this@IncomingCallActivity, R.drawable.bg_row)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Monogram for the avatar: initials from the name, else "#" for a bare number. */
    private fun initials(name: String, number: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            number.isNotBlank() -> "#"
            else -> "?"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        live.remove(this)
        binding.callTimer.stop()
        // Only clear the listener if it's still OURS — a newer instance may have
        // registered its own, and nulling that would leave it deaf (stuck screen).
        if (LinkState.callListener === callListener) LinkState.callListener = null
    }

    companion object {
        // All live call-screen instances. Belt-and-braces: the overlay direct
        // launch and the full-screen intent can race into two stacked instances;
        // the service closes every one of them when the call ends, instead of
        // relying solely on the single callListener chain.
        private val live = java.util.Collections.synchronizedList(mutableListOf<IncomingCallActivity>())

        /** Called by LinkService when the call ends or the link drops. */
        fun finishAll() {
            synchronized(live) { live.toList() }.forEach { it.finish() }
        }
    }
}
