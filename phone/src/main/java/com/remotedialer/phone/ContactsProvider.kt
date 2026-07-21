package com.remotedialer.phone

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.remotedialer.shared.Contact

/**
 * Reads the phone's **starred (favourite) contacts** for the tablet's dialer
 * (Phase 2). Requires READ_CONTACTS (already granted for caller-name lookup).
 *
 * A contact can have several numbers; we send **one per contact** — the primary
 * (super-primary) number when marked, else the first — to keep the glove-sized
 * list short. Results are ordered by display name.
 */
object ContactsProvider {

    private const val TAG = "ContactsProvider"
    private const val MAX = 200

    fun starred(context: Context): List<Contact> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val idCol = ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        val nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val numberCol = ContactsContract.CommonDataKinds.Phone.NUMBER
        val superCol = ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY

        val projection = arrayOf(idCol, nameCol, numberCol, superCol)
        val selection = "${ContactsContract.Contacts.STARRED} = 1"
        val sort = "$nameCol COLLATE NOCASE ASC"

        // Keep one number per contact, preferring an explicitly-primary number.
        val byContact = LinkedHashMap<Long, Contact>()
        val haveSuperPrimary = HashSet<Long>()

        runCatching {
            context.contentResolver.query(uri, projection, selection, null, sort)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(idCol)
                val nameIdx = c.getColumnIndexOrThrow(nameCol)
                val numberIdx = c.getColumnIndexOrThrow(numberCol)
                val superIdx = c.getColumnIndexOrThrow(superCol)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx)?.trim().orEmpty()
                    val number = c.getString(numberIdx)?.trim().orEmpty()
                    if (name.isEmpty() || number.isEmpty()) continue
                    val isSuperPrimary = c.getInt(superIdx) == 1
                    val alreadyHave = id in byContact
                    if (!alreadyHave || (isSuperPrimary && id !in haveSuperPrimary)) {
                        byContact[id] = Contact(name, number)
                        if (isSuperPrimary) haveSuperPrimary.add(id)
                    }
                }
            }
        }.onFailure { Log.e(TAG, "starred-contacts query failed", it) }

        val result = byContact.values.take(MAX)
        Log.i(TAG, "Read ${result.size} starred contacts")
        return result
    }
}
