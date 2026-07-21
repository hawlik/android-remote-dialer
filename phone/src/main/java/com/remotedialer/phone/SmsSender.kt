package com.remotedialer.phone

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/**
 * Sends the reject-with-SMS quick reply chosen on the tablet (requires SEND_SMS).
 * Fire-and-forget: failures are logged, not surfaced — the call is already
 * rejected by then and the rider can't act on an error anyway. Since Android
 * KitKat the system writes SmsManager sends from non-default SMS apps into the
 * SMS provider, so the reply shows up in the stock Messages thread.
 */
object SmsSender {

    private const val TAG = "SmsSender"

    // Quick replies are one or two sentences; the cap bounds what a misbehaving
    // peer on the link could make us send as a multipart burst.
    private const val MAX_LENGTH = 480

    fun send(context: Context, number: String, text: String) {
        if (number.isBlank() || text.isBlank()) return
        val body = text.take(MAX_LENGTH)
        runCatching {
            val sm = context.getSystemService(SmsManager::class.java)
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
        }.onSuccess { Log.i(TAG, "Quick reply sent to $number") }
            .onFailure { Log.e(TAG, "Quick reply to $number failed", it) }
    }
}
