package com.remotedialer.shared

/**
 * The wire protocol shared by the phone and tablet apps.
 *
 * One [Message] is sent per line over the RFCOMM/SPP socket. Encoding and
 * framing live in [ProtocolCodec] — this file only describes *what* can be sent.
 *
 * Direction is a convention, not enforced by the type system:
 *  - phone → tablet: [Ring], [CallActive], [CallEnded]  (call events)
 *  - phone → tablet: [Contacts], [Recents]              (directory, Phase 2)
 *  - tablet → phone: [Answer], [Reject], [Dial]         (call commands)
 *  - tablet → phone: [RequestDirectory]                 (directory, Phase 2)
 */
sealed interface Message {

    // ---- phone → tablet (call events) ----

    /** An incoming call is ringing. [name] is the resolved contact name (or a
     *  fallback like "Unknown caller"); [number] is the raw caller number. */
    data class Ring(val name: String, val number: String) : Message

    /** The call went off-hook — it is now connected/in progress. */
    data object CallActive : Message

    /** The call ended and the phone returned to idle. */
    data object CallEnded : Message

    // ---- phone → tablet (directory, Phase 2) ----

    /** The phone's starred contacts — a full snapshot that replaces whatever
     *  list the tablet currently holds. */
    data class Contacts(val entries: List<Contact>) : Message

    /** The phone's recent calls, newest first — a full snapshot that replaces
     *  whatever list the tablet currently holds. */
    data class Recents(val entries: List<RecentCall>) : Message

    // ---- tablet → phone (call commands) ----

    /** Accept the ringing call. */
    data object Answer : Message

    /** Reject the ringing call (or end the active one). */
    data object Reject : Message

    /** Reject the ringing call and text [text] back to the caller (the phone
     *  sends the SMS — it has the SIM; the tablet only picks the message). */
    data class RejectWithSms(val text: String) : Message

    /** Phase 2: place an outbound call to [number]. */
    data class Dial(val number: String) : Message

    // ---- tablet → phone (directory, Phase 2) ----

    /** Ask the phone to (re)send its starred contacts + recent calls. */
    data object RequestDirectory : Message
}

/** A starred contact the rider can dial from the tablet. */
data class Contact(val name: String, val number: String)

/** One entry in the phone's recent-calls list. [timestamp] is epoch millis. */
data class RecentCall(
    val name: String,
    val number: String,
    val type: CallType,
    val timestamp: Long,
)

/** Direction/outcome of a [RecentCall], mirrored from the phone's call log. */
enum class CallType { INCOMING, OUTGOING, MISSED, OTHER }
