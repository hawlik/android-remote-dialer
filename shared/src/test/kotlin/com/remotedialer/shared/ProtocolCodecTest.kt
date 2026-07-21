package com.remotedialer.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolCodecTest {

    private fun roundTrip(message: Message) {
        assertEquals(message, ProtocolCodec.decode(ProtocolCodec.encode(message)))
    }

    @Test fun `round-trips every message type`() {
        roundTrip(Message.Ring("Jane Doe", "+15551234567"))
        roundTrip(Message.CallActive)
        roundTrip(Message.CallEnded)
        roundTrip(Message.Answer)
        roundTrip(Message.Reject)
        roundTrip(Message.Dial("+15551234567"))
        roundTrip(Message.RequestDirectory)
        roundTrip(Message.RejectWithSms("I'm riding — I'll call you back."))
        roundTrip(Message.RejectWithSms("multi\nline\ttext with \\escapes"))
    }

    @Test fun `round-trips a contacts snapshot`() {
        roundTrip(
            Message.Contacts(
                listOf(
                    Contact("Jane Doe", "+15551234567"),
                    Contact("Tab\tby\tName", "555\t000"), // separators inside fields
                    Contact("Émilie", "+33123456789"),
                )
            )
        )
    }

    @Test fun `round-trips a recents snapshot`() {
        roundTrip(
            Message.Recents(
                listOf(
                    RecentCall("Jane Doe", "+15551234567", CallType.INCOMING, 1_700_000_000_000L),
                    RecentCall("", "+15559876543", CallType.MISSED, 1_700_000_100_000L),
                    RecentCall("Bob", "555\n123", CallType.OUTGOING, 0L),
                )
            )
        )
    }

    @Test fun `round-trips empty directory snapshots`() {
        roundTrip(Message.Contacts(emptyList()))
        roundTrip(Message.Recents(emptyList()))
    }

    @Test fun `unknown recent-call type decodes to OTHER`() {
        val decoded = ProtocolCodec.decode("RECENTS\tBob\t555\tBOGUS\t42")
        assertEquals(Message.Recents(listOf(RecentCall("Bob", "555", CallType.OTHER, 42L))), decoded)
    }

    @Test fun `escapes fields containing separators and control chars`() {
        val nasty = Message.Ring("Tab\tNewline\nBack\\slash", "\r\n123")
        val encoded = ProtocolCodec.encode(nasty)
        // The field contents must not leak structural chars into the frame:
        // only the 2 separators after RING and after the name may be raw tabs,
        // and there must be no raw newline/carriage-return anywhere.
        assertEquals(2, encoded.count { it == '\t' })
        assertEquals(-1, encoded.indexOf('\n'))
        assertEquals(-1, encoded.indexOf('\r'))
        // ...and it still decodes back to the original.
        assertEquals(nasty, ProtocolCodec.decode(encoded))
    }

    @Test fun `strips a trailing CRLF from the transport`() {
        assertEquals(Message.Answer, ProtocolCodec.decode("ANSWER\r\n"))
        assertEquals(Message.CallEnded, ProtocolCodec.decode("ENDED\n"))
    }

    @Test fun `returns null for unknown or malformed lines`() {
        assertNull(ProtocolCodec.decode(""))
        assertNull(ProtocolCodec.decode("BOGUS"))
        assertNull(ProtocolCodec.decode("RING\tonly-one-field")) // RING needs 2 fields
        assertNull(ProtocolCodec.decode("ANSWER\textra"))        // ANSWER takes none
        assertNull(ProtocolCodec.decode("DIAL"))                 // DIAL needs a number
        assertNull(ProtocolCodec.decode("REJECTSMS"))            // REJECTSMS needs a text
        assertNull(ProtocolCodec.decode("CONTACTS\tOnlyName"))   // CONTACTS needs name+number pairs
        assertNull(ProtocolCodec.decode("RECENTS\tBob\t555\tINCOMING")) // RECENTS needs 4-field tuples
    }
}
