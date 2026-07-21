package com.remotedialer.phone

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Answers, ends, or places calls via TelecomManager. [answer]/[reject] need the
 * ANSWER_PHONE_CALLS permission and [dial] needs CALL_PHONE; all work WITHOUT
 * being the default dialer — which is exactly why we chose this lightweight
 * approach. An outbound [dial] runs in the stock dialer (audio to the helmet);
 * the phone then goes off-hook and the tablet's in-call screen takes over.
 */
object CallController {

    private const val TAG = "CallController"

    @SuppressLint("MissingPermission") // permission is requested in MainActivity before use
    fun answer(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java)
        runCatching { tm.acceptRingingCall() }
            .onSuccess { Log.i(TAG, "acceptRingingCall()") }
            .onFailure { Log.e(TAG, "answer failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun reject(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java)
        // endCall() rejects a ringing call or hangs up an active one.
        runCatching { tm.endCall() }
            .onSuccess { Log.i(TAG, "endCall()") }
            .onFailure { Log.e(TAG, "reject failed", it) }
    }

    @SuppressLint("MissingPermission") // CALL_PHONE is requested in MainActivity before use
    fun dial(context: Context, number: String) {
        if (number.isBlank()) return
        val tm = context.getSystemService(TelecomManager::class.java)
        val uri = Uri.fromParts("tel", number, null)
        // placeCall() dials from the background without us launching an activity;
        // Telecom routes it through the phone's default dialer.
        runCatching { tm.placeCall(uri, Bundle()) }
            .onSuccess { Log.i(TAG, "placeCall($number)") }
            .onFailure { Log.e(TAG, "dial failed", it) }
    }
}
