package com.remotedialer.tablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.remotedialer.shared.Message

/**
 * Foreground service that owns the Bluetooth link to the phone ([PhoneLink]).
 * Incoming call events become a full-screen-intent notification that launches
 * [IncomingCallActivity] over the nav app; the tablet's Accept/Decline are sent
 * back to the phone as [Message.Answer] / [Message.Reject]. Keeps the link alive
 * and reconnecting for as long as the service runs.
 */
class LinkService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var phoneLink: PhoneLink? = null

    // True once we're tearing down, so the link's late disconnect/status callbacks
    // don't overwrite the "Service stopped" state.
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(
            NOTIF_ONGOING,
            ongoingNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        LinkState.serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = DevicePrefs.address(this)
        if (address == null) {
            Log.w(TAG, "No phone selected — stopping")
            LinkState.update("No phone selected")
            stopSelf()
            return START_NOT_STICKY
        }
        if (phoneLink == null) {
            val link = PhoneLink(
                context = this,
                deviceAddress = address,
                // PhoneLink callbacks fire on its own thread — marshal to main.
                onEvent = { message -> mainHandler.post { handleEvent(message) } },
                onConnectionChange = { connected -> mainHandler.post { onConnectionChange(connected) } },
                onStatus = { text -> mainHandler.post { showStatus(text) } },
            )
            phoneLink = link
            LinkState.commandSender = { message -> link.send(message) }
            link.start()
        }
        return START_STICKY
    }

    private fun handleEvent(message: Message) {
        when (message) {
            is Message.Ring -> {
                LinkState.onRinging(message.name, message.number)
                LinkState.update("Incoming: ${message.name} (${message.number})")
                showRingingNotification(message.name, message.number)
                launchCallScreenIfAllowed()
            }
            Message.CallActive -> {
                LinkState.onActive()
                LinkState.update("In call")
                // Keep an ongoing notification so the in-call screen is reachable
                // even if the rider swiped it away; the call screen itself observes
                // LinkState and switches to the timer + End-call button. (The call
                // screen is opened directly — by the Ring full-screen intent for
                // incoming calls, by DialerActivity for outbound — so it's already
                // showing by the time we get here.)
                showInCallNotification()
            }
            Message.CallEnded -> {
                LinkState.onIdle()
                LinkState.update("Idle")
                dismissCall()
                // Explicitly close every live call screen — don't rely on the
                // callListener chain alone (stale/stacked instances stay open).
                IncomingCallActivity.finishAll()
            }
            is Message.Contacts -> {
                LinkState.onContacts(message.entries)
                Log.i(TAG, "Received ${message.entries.size} contacts")
            }
            is Message.Recents -> {
                LinkState.onRecents(message.entries)
                Log.i(TAG, "Received ${message.entries.size} recents")
            }
            // Answer/Reject/Dial/RequestDirectory are tablet→phone only.
            else -> Log.w(TAG, "Ignoring unexpected message: $message")
        }
    }

    private fun onConnectionChange(connected: Boolean) {
        if (stopping) return // ignore the teardown disconnect; we're stopping
        LinkState.setConnected(connected)
        if (connected) {
            showStatus("Connected to phone")
            // Pull a fresh directory (the phone also pushes one on connect).
            LinkState.requestDirectory()
        } else {
            // Link lost — clear any call UI (we can no longer control it).
            LinkState.onIdle()
            dismissCall()
            IncomingCallActivity.finishAll()
        }
    }

    private fun showStatus(text: String) {
        if (stopping) return // don't clobber "Service stopped" during teardown
        LinkState.update(text)
        updateOngoing(text)
    }

    private fun callScreenIntent(): PendingIntent {
        val intent = Intent(this, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * With "display over other apps" (SYSTEM_ALERT_WINDOW) granted, launch the
     * call screen directly so it takes over the nav app immediately. A full-screen
     * intent alone only takes over when the tablet is locked/screen-off; while the
     * nav app is on screen the system downgrades the FSI to a heads-up notification.
     * The overlay permission exempts us from background-activity-launch limits, so
     * we can start the activity outright. Without it we fall back to the FSI
     * notification (still posted just above).
     */
    private fun launchCallScreenIfAllowed() {
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "No overlay permission — relying on the full-screen-intent notification")
            return
        }
        val intent = Intent(this, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Direct call-screen launch failed: ${it.message}") }
    }

    private fun showRingingNotification(name: String, number: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setContentTitle("Incoming call")
            .setContentText("$name  ($number)")
            .setSmallIcon(R.drawable.ic_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(callScreenIntent(), true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, notification)
    }

    private fun showInCallNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setContentTitle("In call")
            .setContentText(LinkState.callerName.ifBlank { LinkState.callerNumber })
            .setSmallIcon(R.drawable.ic_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(callScreenIntent()) // tap to reopen the in-call screen
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, notification)
    }

    private fun dismissCall() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopping = true
        LinkState.serviceRunning = false
        phoneLink?.stop()
        phoneLink = null
        LinkState.commandSender = null
        LinkState.setConnected(false)
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Link status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun ongoingNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("Remote Dialer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setOngoing(true)
            .build()

    private fun updateOngoing(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, ongoingNotification(text))
    }

    private companion object {
        const val TAG = "LinkService"
        const val CHANNEL_ONGOING = "link_status"
        const val CHANNEL_CALL = "incoming_calls"
        const val NOTIF_ONGOING = 1
        const val NOTIF_CALL = 2
    }
}
