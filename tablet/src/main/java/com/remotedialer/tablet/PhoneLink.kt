package com.remotedialer.tablet

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.remotedialer.shared.BtProtocol
import com.remotedialer.shared.Message
import com.remotedialer.shared.ProtocolCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Tablet-side Bluetooth RFCOMM/SPP **client**. Connects to the phone's server
 * ([BtProtocol.UUID]) and holds the link open, reconnecting with exponential
 * backoff whenever it drops (client-side reconnect state machine):
 *
 *  1. open an RFCOMM socket to the saved phone and `connect()`,
 *  2. read `RING`/`ACTIVE`/`ENDED` lines and hand them to [onEvent],
 *  3. send `ANSWER`/`REJECT`/`DIAL` via [send],
 *  4. on any failure, wait (backoff, capped) and retry until [stop].
 *
 * All socket I/O is off the main thread: the connect/read thread above plus a
 * single-thread writer executor so [send] never blocks its caller.
 */
class PhoneLink(
    private val context: Context,
    private val deviceAddress: String,
    private val onEvent: (Message) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) {

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null

    private var connectThread: Thread? = null
    private val writerExecutor = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true
        connectThread = Thread({ connectLoop() }, "PhoneLink-connect").also { it.start() }
    }

    fun stop() {
        running = false
        closeSocket()            // aborts a hung connect()/read()
        connectThread?.interrupt() // wakes the backoff wait()
        writerExecutor.shutdownNow()
    }

    /** Queue a command to the phone. No-op (logged) if not connected. */
    fun send(message: Message) {
        val line = ProtocolCodec.encode(message)
        runCatching {
            writerExecutor.execute {
                val w = writer
                if (w == null) {
                    Log.d(TAG, "send dropped (not connected): $message")
                    return@execute
                }
                try {
                    w.write(line)
                    w.write("\n")
                    w.flush()
                    Log.i(TAG, "→ $message")
                } catch (e: IOException) {
                    Log.w(TAG, "send failed: ${e.message}")
                    closeSocket() // ends the read loop → triggers reconnect
                }
            }
        }.onFailure { Log.d(TAG, "send dropped (link stopping): $message") }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested in MainActivity
    private fun connectLoop() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter")
            return
        }
        var backoffMs = MIN_BACKOFF_MS
        while (running) {
            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth off — will retry")
                onStatus("Bluetooth is off — turn it on")
            } else {
                try {
                    val device = adapter.getRemoteDevice(deviceAddress)
                    val s = device.createRfcommSocketToServiceRecord(BtProtocol.UUID)
                    socket = s // set first so stop()/closeSocket() can abort a hung connect()
                    // NB: don't call adapter.cancelDiscovery() here — it needs the
                    // BLUETOOTH_SCAN permission, which we don't hold. We never start
                    // discovery ourselves, so there's nothing to cancel.
                    Log.i(TAG, "Connecting to $deviceAddress…")
                    onStatus("Connecting to phone…")
                    s.connect()
                    serveSession(s)
                    backoffMs = MIN_BACKOFF_MS // reset after a good session
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT — cannot connect", e)
                    onStatus("Missing Bluetooth permission")
                    return
                } catch (e: IOException) {
                    if (running) {
                        Log.w(TAG, "connect/link failed: ${e.message}")
                        onStatus("Can't reach phone — is its service running? Retrying…")
                    }
                    closeSocket()
                }
            }
            if (!running) break
            sleepBackoff(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        Log.i(TAG, "Connect loop exited")
    }

    private fun serveSession(s: BluetoothSocket) {
        Log.i(TAG, "Connected to phone")
        writer = BufferedWriter(OutputStreamWriter(s.outputStream, StandardCharsets.UTF_8))
        onConnectionChange(true)
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break // null = EOF / phone disconnected
                val message = ProtocolCodec.decode(line)
                if (message == null) {
                    Log.w(TAG, "Ignoring unparseable line: $line")
                    continue
                }
                Log.i(TAG, "← $message")
                onEvent(message)
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Read loop ended: ${e.message}")
        } finally {
            closeSocket()
            onConnectionChange(false)
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        writer = null
        socket = null
    }

    private fun sleepBackoff(ms: Long) {
        runCatching { Thread.sleep(ms) } // interrupted by stop() → returns early
    }

    private companion object {
        const val TAG = "PhoneLink"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
