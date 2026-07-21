package com.remotedialer.shared

import java.util.UUID

/**
 * Bluetooth RFCOMM/SPP rendezvous constants shared by both apps. The phone
 * listens with this service record (server) and the tablet connects to the same
 * UUID (client). This is a private, app-specific UUID (not the
 * generic SPP `00001101-…` one), so we only match our own counterpart.
 */
object BtProtocol {

    /** SDP service-record name registered by the phone's listening socket. */
    const val SERVICE_NAME = "RemoteDialer"

    /** Both ends must use this exact UUID to connect. */
    val UUID: UUID = java.util.UUID.fromString("f7a3c9d2-4b6e-4c1a-9f83-2d5e7b1c0a94")
}
