package com.remotedialer.shared

/**
 * Serializes [Message]s to and from a single line of text for the RFCOMM socket.
 *
 * Wire format: a verb, optionally followed by tab-separated fields:
 *
 *     RING\t<name>\t<number>
 *     ACTIVE
 *     ENDED
 *     ANSWER
 *     REJECT
 *     REJECTSMS\t<text>
 *     DIAL\t<number>
 *     GETDIR
 *     CONTACTS[\t<name>\t<number>]...
 *     RECENTS[\t<name>\t<number>\t<type>\t<epochMillis>]...
 *
 * CONTACTS/RECENTS carry a whole list in one line — a fixed number of fields per
 * entry (2 and 4 respectively) after the verb — so each snapshot arrives
 * atomically and the tablet just replaces its list (no partial-batch state). An
 * empty list is just the bare verb.
 *
 * String fields are escaped ([escape]) so a contact name containing a tab or
 * newline can never split a field or a frame. [encode] returns the bare line
 * (no trailing newline); the transport appends `\n` and the reader strips it —
 * pairs naturally with `BufferedWriter` + `BufferedReader.readLine()`.
 *
 * [decode] is deliberately lenient: it returns `null` for an unknown verb or a
 * malformed line so the caller can log and skip it rather than crash the link.
 */
object ProtocolCodec {

    private const val SEP = '\t'

    private const val RING = "RING"
    private const val ACTIVE = "ACTIVE"
    private const val ENDED = "ENDED"
    private const val ANSWER = "ANSWER"
    private const val REJECT = "REJECT"
    private const val REJECTSMS = "REJECTSMS"
    private const val DIAL = "DIAL"
    private const val GETDIR = "GETDIR"
    private const val CONTACTS = "CONTACTS"
    private const val RECENTS = "RECENTS"

    fun encode(message: Message): String = when (message) {
        is Message.Ring -> "$RING$SEP${escape(message.name)}$SEP${escape(message.number)}"
        Message.CallActive -> ACTIVE
        Message.CallEnded -> ENDED
        Message.Answer -> ANSWER
        Message.Reject -> REJECT
        is Message.RejectWithSms -> "$REJECTSMS$SEP${escape(message.text)}"
        is Message.Dial -> "$DIAL$SEP${escape(message.number)}"
        Message.RequestDirectory -> GETDIR
        is Message.Contacts -> buildString {
            append(CONTACTS)
            for (c in message.entries) {
                append(SEP).append(escape(c.name)).append(SEP).append(escape(c.number))
            }
        }
        is Message.Recents -> buildString {
            append(RECENTS)
            for (r in message.entries) {
                append(SEP).append(escape(r.name))
                append(SEP).append(escape(r.number))
                append(SEP).append(r.type.name)
                append(SEP).append(r.timestamp.toString())
            }
        }
    }

    /** Parse one line into a [Message], or `null` if unknown/malformed. */
    fun decode(line: String): Message? {
        // Be defensive about stray CR from CRLF transports.
        val clean = line.trimEnd('\r', '\n')
        if (clean.isEmpty()) return null

        val parts = clean.split(SEP)
        return when (parts[0]) {
            RING -> if (parts.size == 3) Message.Ring(unescape(parts[1]), unescape(parts[2])) else null
            ACTIVE -> if (parts.size == 1) Message.CallActive else null
            ENDED -> if (parts.size == 1) Message.CallEnded else null
            ANSWER -> if (parts.size == 1) Message.Answer else null
            REJECT -> if (parts.size == 1) Message.Reject else null
            REJECTSMS -> if (parts.size == 2) Message.RejectWithSms(unescape(parts[1])) else null
            DIAL -> if (parts.size == 2) Message.Dial(unescape(parts[1])) else null
            GETDIR -> if (parts.size == 1) Message.RequestDirectory else null
            CONTACTS -> decodeContacts(parts)
            RECENTS -> decodeRecents(parts)
            else -> null
        }
    }

    /** `CONTACTS` + (name, number) pairs. Field count after the verb must be even. */
    private fun decodeContacts(parts: List<String>): Message? {
        val fields = parts.subList(1, parts.size)
        if (fields.size % 2 != 0) return null
        val entries = ArrayList<Contact>(fields.size / 2)
        var i = 0
        while (i < fields.size) {
            entries.add(Contact(unescape(fields[i]), unescape(fields[i + 1])))
            i += 2
        }
        return Message.Contacts(entries)
    }

    /** `RECENTS` + (name, number, type, epochMillis) tuples. Count must be a multiple of 4. */
    private fun decodeRecents(parts: List<String>): Message? {
        val fields = parts.subList(1, parts.size)
        if (fields.size % 4 != 0) return null
        val entries = ArrayList<RecentCall>(fields.size / 4)
        var i = 0
        while (i < fields.size) {
            val type = runCatching { CallType.valueOf(fields[i + 2]) }.getOrDefault(CallType.OTHER)
            val timestamp = fields[i + 3].toLongOrNull() ?: 0L
            entries.add(RecentCall(unescape(fields[i]), unescape(fields[i + 1]), type, timestamp))
            i += 4
        }
        return Message.Recents(entries)
    }

    // Order matters: escape the backslash first, unescape it last.
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'n' -> out.append('\n')
                    else -> out.append(s[i + 1]) // unknown escape: keep the char
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
