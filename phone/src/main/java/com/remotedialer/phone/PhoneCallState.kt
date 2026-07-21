package com.remotedialer.phone

/**
 * Tiny in-process bridge so the background service can push the latest call
 * event to the UI. Everything here is touched on the main thread only
 * (broadcast receivers and the Activity both run there), so no locking needed.
 */
object PhoneCallState {

    @Volatile
    var lastEvent: String = "No calls detected yet"
        private set

    /** Whether [CallMonitorService] is running (set by the service; single process). */
    @Volatile
    var serviceRunning: Boolean = false

    /** Whether the tablet is currently connected over Bluetooth. */
    @Volatile
    var tabletConnected: Boolean = false

    /** The Activity registers this while visible; null when not. */
    var listener: ((String) -> Unit)? = null

    fun update(event: String) {
        lastEvent = event
        listener?.invoke(event)
    }
}
