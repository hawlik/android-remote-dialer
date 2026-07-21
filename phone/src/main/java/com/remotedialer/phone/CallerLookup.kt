package com.remotedialer.phone

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Resolves a phone number to a contact display name (requires READ_CONTACTS). */
object CallerLookup {

    fun resolveName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
