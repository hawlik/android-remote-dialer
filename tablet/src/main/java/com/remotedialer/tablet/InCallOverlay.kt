package com.remotedialer.tablet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.TextView
import com.remotedialer.shared.Message

/**
 * Compact floating widget shown over the nav app while a call is active:
 * caller name, running timer, and a big End button, pinned top centre (the
 * right side usually shows the next navigation instruction). Owned by
 * [LinkService]; all calls happen on the main thread.
 *
 * Requires "display over other apps" (the tablet app already requests it for
 * the incoming-call takeover); [show] is a no-op without it, and the caller
 * falls back to the full-screen in-call view.
 */
class InCallOverlay(private val context: Context) {

    private var view: View? = null

    // A window overlay has no parent ViewGroup; the WindowManager.LayoutParams
    // below supply the sizing, so a null inflate root is correct here.
    @SuppressLint("InflateParams")
    fun show() {
        if (view != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission — keeping the full-screen in-call view")
            return
        }

        val themed = ContextThemeWrapper(context, R.style.Theme_RemoteDialerTablet)
        val pill = LayoutInflater.from(themed).inflate(R.layout.overlay_in_call, null)

        pill.findViewById<TextView>(R.id.overlayCaller).text =
            LinkState.callerName.ifBlank { LinkState.callerNumber.ifBlank { "In call" } }
        pill.findViewById<Chronometer>(R.id.overlayTimer).apply {
            base = LinkState.callBaseElapsed
            start()
        }
        pill.findViewById<View>(R.id.overlayEndButton).setOnClickListener {
            // The phone maps Reject to endCall(); the resulting CallEnded hides us.
            LinkState.send(Message.Reject)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Nav keeps input focus everywhere outside the pill; the screen stays
            // on for the whole call in widget mode, matching the call screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (32 * context.resources.displayMetrics.density).toInt()
        }

        runCatching { windowManager().addView(pill, params) }
            .onSuccess { view = pill }
            .onFailure { Log.e(TAG, "Failed to show in-call overlay", it) }
    }

    fun hide() {
        val pill = view ?: return
        view = null
        pill.findViewById<Chronometer>(R.id.overlayTimer)?.stop()
        runCatching { windowManager().removeView(pill) }
            .onFailure { Log.w(TAG, "Failed to remove in-call overlay: ${it.message}") }
    }

    private fun windowManager(): WindowManager =
        context.getSystemService(WindowManager::class.java)

    private companion object {
        const val TAG = "InCallOverlay"
    }
}
