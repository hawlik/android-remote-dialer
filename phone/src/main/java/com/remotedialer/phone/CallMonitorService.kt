package com.remotedialer.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.remotedialer.shared.Message
import java.util.concurrent.Executors

/**
 * Persistent foreground service that listens for phone-call state changes and
 * bridges them to the tablet over Bluetooth ([TabletLink]): call events go out
 * as [Message.Ring] / [Message.CallActive] / [Message.CallEnded], and the
 * tablet's [Message.Answer] / [Message.Reject] commands are routed to
 * [CallController]. Still surfaces the latest event to the phone UI for testing.
 */
class CallMonitorService : Service() {

    private val channelId = "call_monitor"
    private var lastNumber: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tabletLink: TabletLink

    // True once we're tearing down, so the link's late disconnect callback doesn't
    // overwrite the "Service stopped" state with "Waiting for tablet…".
    @Volatile private var stopping = false

    // Content-resolver reads (contacts / call log) run off the main thread.
    private val directoryExecutor = Executors.newSingleThreadExecutor()

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            // EXTRA_INCOMING_NUMBER is deprecated, but the replacement
            // (CallScreeningService) requires holding a role we deliberately
            // avoid — this app must not become the default dialer/screener.
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            handleState(intent.getStringExtra(TelephonyManager.EXTRA_STATE), number)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification("Waiting for tablet…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        ContextCompat.registerReceiver(
            this,
            phoneStateReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // TabletLink callbacks fire on its own thread — marshal to the main
        // thread so command handling and UI/notification updates stay simple.
        tabletLink = TabletLink(
            context = this,
            onCommand = { message -> mainHandler.post { handleCommand(message) } },
            onConnectionChange = { connected -> mainHandler.post { onTabletConnection(connected) } },
        )
        tabletLink.start()

        PhoneCallState.serviceRunning = true
        Log.i(TAG, "Service created — listening for calls")
        PhoneCallState.update("Service running — waiting for calls")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun handleState(state: String?, number: String?) {
        if (!number.isNullOrBlank()) lastNumber = number
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val n = number ?: lastNumber ?: "unknown"
                // Send a blank name when the contact is unknown — each tablet
                // surface picks its own fallback (the pill shows the number).
                val name = CallerLookup.resolveName(this, n).orEmpty()
                val msg = "INCOMING: ${name.ifBlank { "Unknown" }}  ($n)"
                Log.i(TAG, msg)
                PhoneCallState.update(msg)
                tabletLink.send(Message.Ring(name, n))
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val msg = "In call — ${lastNumber ?: "unknown"}"
                Log.i(TAG, msg)
                PhoneCallState.update(msg)
                tabletLink.send(Message.CallActive)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Call ended / idle")
                PhoneCallState.update("Idle — waiting for calls")
                tabletLink.send(Message.CallEnded)
                lastNumber = null
            }
        }
    }

    /** Execute a command sent by the tablet. Runs on the main thread. */
    private fun handleCommand(message: Message) {
        when (message) {
            Message.Answer -> CallController.answer(this)
            Message.Reject -> CallController.reject(this)
            is Message.RejectWithSms -> {
                val number = lastNumber      // capture BEFORE reject — IDLE clears it
                CallController.reject(this)  // stop the ringing first
                if (!number.isNullOrBlank()) SmsSender.send(this, number, message.text)
                else Log.w(TAG, "No caller number (hidden?) — quick reply skipped")
            }
            is Message.Dial -> CallController.dial(this, message.number)
            Message.RequestDirectory -> sendDirectory()
            // Ring / CallActive / CallEnded / Contacts / Recents are phone→tablet only.
            else -> Log.w(TAG, "Ignoring unexpected message from tablet: $message")
        }
    }

    /** Read starred contacts + recent calls off-main and push them to the tablet. */
    private fun sendDirectory() {
        directoryExecutor.execute {
            val contacts = ContactsProvider.starred(this)
            val recents = RecentsProvider.recent(this)
            tabletLink.send(Message.Contacts(contacts))
            tabletLink.send(Message.Recents(recents))
        }
    }

    private fun onTabletConnection(connected: Boolean) {
        if (stopping) return // ignore the teardown disconnect; we're stopping
        PhoneCallState.tabletConnected = connected
        val text = if (connected) "Tablet connected" else "Waiting for tablet…"
        updateNotification(text)
        PhoneCallState.update(text)
        if (connected) sendDirectory()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopping = true
        PhoneCallState.serviceRunning = false
        PhoneCallState.tabletConnected = false
        if (::tabletLink.isInitialized) tabletLink.stop()
        directoryExecutor.shutdownNow()
        runCatching { unregisterReceiver(phoneStateReceiver) }
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Call monitor", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("Remote Dialer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "CallMonitor"
        private const val NOTIF_ID = 1
    }
}
