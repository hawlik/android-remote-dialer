package com.remotedialer.tablet

import android.os.SystemClock
import com.remotedialer.shared.Contact
import com.remotedialer.shared.Message
import com.remotedialer.shared.RecentCall

/**
 * Tiny in-process bridge between the [LinkService] (which owns the Bluetooth
 * link) and the UI. Mirrors the phone app's PhoneCallState. Everything here is
 * touched on the main thread — the service marshals link callbacks there.
 *
 * It also holds the current call ([CallPhase] + caller + when it went active) so
 * the call screen can render ringing vs. in-call and run a duration timer.
 */
object LinkState {

    enum class CallPhase { NONE, RINGING, DIALING, ACTIVE }

    @Volatile
    var connected: Boolean = false
        private set

    /** Whether [LinkService] is running (set by the service; single process). */
    @Volatile
    var serviceRunning: Boolean = false

    @Volatile
    var lastEvent: String = "Not connected"
        private set

    @Volatile
    var callPhase: CallPhase = CallPhase.NONE
        private set

    @Volatile
    var callerName: String = ""
        private set

    @Volatile
    var callerNumber: String = ""
        private set

    /** `SystemClock.elapsedRealtime()` when the call went active — Chronometer base. */
    @Volatile
    var callBaseElapsed: Long = 0L
        private set

    /** Latest starred-contacts snapshot from the phone (Phase 2). */
    @Volatile
    var contacts: List<Contact> = emptyList()
        private set

    /** Latest recent-calls snapshot from the phone (Phase 2). */
    @Volatile
    var recents: List<RecentCall> = emptyList()
        private set

    /** MainActivity registers this while visible to show status/events. */
    var statusListener: ((String) -> Unit)? = null

    /** Set by the service to the live link's send(); used by the call UI. */
    var commandSender: ((Message) -> Unit)? = null

    /** Set by the call screen so it re-renders as the call phase changes. */
    var callListener: ((CallPhase) -> Unit)? = null

    /** Set by the dialer screen so it rebuilds when a fresh directory arrives. */
    var directoryListener: (() -> Unit)? = null

    fun update(event: String) {
        lastEvent = event
        statusListener?.invoke(event)
    }

    fun setConnected(value: Boolean) {
        connected = value
    }

    /** Send a command (ANSWER/REJECT/DIAL/GETDIR) to the phone, if connected. */
    fun send(message: Message) {
        commandSender?.invoke(message)
    }

    fun requestDirectory() {
        send(Message.RequestDirectory)
    }

    /**
     * Place an outbound call to [number] and enter the [CallPhase.DIALING] state
     * with the target [name]/[number] (there's no [Ring] for a call we start, so
     * the phone won't tell us who we're calling). The dialer opens the call screen
     * immediately; it flips to the running timer when the phone reports
     * [Message.CallActive].
     */
    fun dial(name: String, number: String) {
        callerName = name
        callerNumber = number
        callPhase = CallPhase.DIALING
        send(Message.Dial(number))
        callListener?.invoke(callPhase)
    }

    fun onContacts(list: List<Contact>) {
        contacts = list
        directoryListener?.invoke()
    }

    fun onRecents(list: List<RecentCall>) {
        recents = list
        directoryListener?.invoke()
    }

    fun onRinging(name: String, number: String) {
        callerName = name
        callerNumber = number
        callPhase = CallPhase.RINGING
        callListener?.invoke(callPhase)
    }

    fun onActive() {
        if (callPhase != CallPhase.ACTIVE) {
            callBaseElapsed = SystemClock.elapsedRealtime()
        }
        callPhase = CallPhase.ACTIVE
        callListener?.invoke(callPhase)
    }

    fun onIdle() {
        callPhase = CallPhase.NONE
        callListener?.invoke(callPhase)
    }
}
