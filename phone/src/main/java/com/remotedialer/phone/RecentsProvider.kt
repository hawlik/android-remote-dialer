package com.remotedialer.phone

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.remotedialer.shared.CallType
import com.remotedialer.shared.RecentCall

/**
 * Reads the phone's **recent calls** for the tablet's dialer (Phase 2). Requires
 * READ_CALL_LOG (already granted). Returns up to [MAX] entries, newest first,
 * collapsing runs of consecutive calls with the same number (like the stock
 * dialer) so the list stays short and glove-scannable.
 */
object RecentsProvider {

    private const val TAG = "RecentsProvider"
    private const val MAX = 30

    fun recent(context: Context): List<RecentCall> {
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
        )
        val sort = "${CallLog.Calls.DATE} DESC"

        val out = ArrayList<RecentCall>(MAX)
        var previousNumber: String? = null

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null, sort
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val numberIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (c.moveToNext() && out.size < MAX) {
                    val number = c.getString(numberIdx)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    if (number == previousNumber) continue // collapse consecutive repeats
                    previousNumber = number
                    val name = c.getString(nameIdx)?.trim().orEmpty()
                    val type = when (c.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallType.MISSED
                        else -> CallType.OTHER
                    }
                    out.add(RecentCall(name, number, type, c.getLong(dateIdx)))
                }
            }
        }.onFailure { Log.e(TAG, "call-log query failed", it) }

        Log.i(TAG, "Read ${out.size} recent calls")
        return out
    }
}
