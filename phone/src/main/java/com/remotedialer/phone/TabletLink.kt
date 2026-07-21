package com.remotedialer.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
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
 * Phone-side Bluetooth RFCOMM/SPP **server**. The tablet is the client and
 * connects to us. While running, one thread sits in an accept loop:
 *
 *  1. listen on [BtProtocol.UUID] and `accept()` the (paired) tablet,
 *  2. stream call events to it via [send],
 *  3. read `ANSWER`/`REJECT`/`DIAL` lines and hand them to [onCommand],
 *  4. when the link drops, loop back and accept again — server-side reconnect.
 *
 * All socket I/O is off the main thread: the accept/read thread above, plus a
 * single-thread writer executor so [send] never blocks its caller. Callbacks
 * fire on the accept/read thread — the caller marshals them where it needs to.
 *
 * The tablet must already be **paired** with the phone (done once in Android's
 * Bluetooth settings); this class does not initiate pairing or discovery.
 */
class TabletLink(
    private val context: Context,
    private val onCommand: (Message) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
) {

    @Volatile private var running = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null

    private var acceptThread: Thread? = null
    private val writerExecutor = Executors.newSingleThreadExecutor()

    val isConnected: Boolean get() = clientSocket != null

    fun start() {
        if (running) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or off — not starting link")
            return
        }
        running = true
        acceptThread = Thread({ acceptLoop(adapter) }, "TabletLink-accept").also { it.start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() } // unblocks accept()
        closeClient()                          // unblocks readLine()
        acceptThread?.interrupt()
        writerExecutor.shutdownNow()
    }

    /** Queue a message to the tablet. No-op (logged) if no tablet is connected. */
    fun send(message: Message) {
        val line = ProtocolCodec.encode(message)
        runCatching {
            writerExecutor.execute {
                val w = writer
                if (w == null) {
                    Log.d(TAG, "send dropped (no tablet): $message")
                    return@execute
                }
                try {
                    w.write(line)
                    w.write("\n")
                    w.flush()
                    Log.i(TAG, "→ $message")
                } catch (e: IOException) {
                    Log.w(TAG, "send failed: ${e.message}")
                    closeClient() // ends the read loop → triggers re-accept
                }
            }
        }.onFailure { Log.d(TAG, "send dropped (link stopping): $message") }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested in MainActivity
    private fun acceptLoop(adapter: BluetoothAdapter) {
        while (running) {
            val server = try {
                adapter.listenUsingRfcommWithServiceRecord(BtProtocol.SERVICE_NAME, BtProtocol.UUID)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT — cannot listen", e)
                return
            } catch (e: IOException) {
                Log.e(TAG, "listen failed", e)
                return
            }
            serverSocket = server

            val socket = try {
                Log.i(TAG, "Waiting for the tablet to connect…")
                server.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept() ended: ${e.message}")
                null
            } finally {
                // Serve one client at a time; re-listen after it disconnects.
                runCatching { server.close() }
                serverSocket = null
            }

            if (socket != null) serveClient(socket)
        }
        Log.i(TAG, "Accept loop exited")
    }

    @SuppressLint("MissingPermission")
    private fun serveClient(socket: BluetoothSocket) {
        // Defense in depth: the secure RFCOMM socket already requires an encrypted
        // link with a paired device, but commands from this socket can place calls
        // and send SMS — refuse anything that is not a bonded peer outright.
        val device = runCatching { socket.remoteDevice }.getOrNull()
        if (device == null || device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "Rejecting connection from unbonded device: ${device?.address}")
            runCatching { socket.close() }
            return
        }
        Log.i(TAG, "Tablet connected: ${device.address}")
        clientSocket = socket
        writer = BufferedWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8))
        onConnectionChange(true)
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break // null = EOF / tablet disconnected
                val message = ProtocolCodec.decode(line)
                if (message == null) {
                    Log.w(TAG, "Ignoring unparseable line: $line")
                    continue
                }
                Log.i(TAG, "← $message")
                onCommand(message)
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Read loop ended: ${e.message}")
        } finally {
            closeClient()
            onConnectionChange(false)
        }
    }

    private fun closeClient() {
        runCatching { clientSocket?.close() }
        writer = null
        clientSocket = null
    }

    private companion object {
        const val TAG = "TabletLink"
    }
}
