package com.oshi.desktop.msg

import org.json.JSONObject

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ `🔒OFFLINE_ONLY🔒` — "this conversation never touches
 * the internet", the third sentinel added to [ControlPrefix] on 2026-10-27 with nothing
 * behind it.
 *
 * The two poll sentinels from that same commit were closed earlier today. This is the third,
 * and it is the one that matters most, because it is a **privacy promise**: a peer asked this
 * client to stop relaying a conversation and this client did not know how to hear the
 * question. The request fell to step 7 of `handleV2Inbound` and was logged and dropped,
 * while the asking phone's UI waited for a confirmation that was never coming.
 *
 * ## What the switch can and cannot promise — iOS's words, kept
 *
 * It promises one thing completely: *nothing THIS device sends in that conversation goes
 * over the relay*. That is local and holds whatever anyone else does.
 *
 * It cannot promise the same of the other side. There is no way to make someone else's phone
 * refuse the internet — an older build does not know the request exists. So the switch is
 * **enforced locally and REQUESTED of the others**, and the UI shows who has confirmed. iOS
 * is explicit that a count below the member count is *"the honest state of the conversation,
 * not a bug to hide"*, because saying "everyone is offline-only" while a silent member still
 * relays is a promise the app cannot keep.
 *
 * ## The ack, and why it exists
 *
 * `ack` is the field that stops an infinite loop: without it, an echoed request carries a
 * fresh timestamp, reads as a NEW request, clears the confirmations and asks everyone again.
 * An ack is never echoed, and it carries the `at` of the request it answers so that a stale
 * one cannot be counted towards a later change.
 *
 * **An ABSENT `ack` means "this is a request"**, which is the safe reading and also what
 * every payload written before acks existed looks like. iOS states the cost of being wrong
 * in that direction and it is the small one: one confirmation that is not counted.
 *
 * ## Timestamps
 *
 * Unix SECONDS as a Double, the same epoch and the same trap as [PollPayload] — `Date`'s own
 * encoding on Apple platforms is seconds since 2001, and Android reads this JSON too.
 */
object OfflineOnlyPayload {

    const val VERSION = 1

    /**
     * One participant asking for a conversation to stop using the relay — or answering.
     *
     * [on] travels in BOTH directions: a conversation that silently stayed restricted after
     * someone lifted it would strand every message sent into it.
     */
    data class Request(
        val on: Boolean,
        val atUnix: Double,
        val isAck: Boolean = false,
        val version: Int = VERSION,
    ) {
        /** The answer to send back for this request: same state, same stamp, `ack` set. */
        fun acknowledgement(): Request = Request(on = on, atUnix = atUnix, isAck = true)

        fun encode(): String = ControlPrefix.OFFLINE_ONLY + JSONObject().apply {
            put("v", version)
            put("on", on)
            put("at", atUnix)
            put("ack", isAck)
        }.toString()
    }

    fun isRequest(text: String?): Boolean =
        text != null && ControlPrefix.match(text) == ControlPrefix.OFFLINE_ONLY

    /**
     * Null when the text is not this sentinel, the body is not JSON, `on` is missing, or the
     * version is from the future.
     *
     * **A FUTURE VERSION IS REFUSED, which is the opposite of what the poll codec does**, and
     * the asymmetry is iOS's (`req.version <= currentVersion`). A poll from a newer build
     * shows the question and the options it understands, because a partly-understood poll is
     * still useful. A ROUTING RULE from a newer build is not: if a later version adds a
     * qualifier this build cannot see, honouring the half it can read could restrict less
     * than the sender asked for, and under-restricting is the one failure this payload must
     * never produce silently.
     */
    fun decode(text: String?): Request? {
        if (!isRequest(text)) return null
        val body = ControlPrefix.strip(text!!)
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!o.has("on")) return null
        val version = o.optInt("v", 1)
        if (version > VERSION) return null
        return Request(
            on = o.optBoolean("on"),
            atUnix = o.optDouble("at", 0.0),
            // Absent means REQUEST. See the class note.
            isAck = o.optBoolean("ack", false),
            version = version,
        )
    }

    /**
     * Whether the relay may carry something, for one conversation.
     *
     * Pure on purpose — iOS's reason is that the send path consults this from several places
     * and *"a rule that needs a store, a network and a group manager to answer is a rule
     * nobody can test"*. This client has exactly two such places, and the second one is easy
     * to miss: the 90-second radio escalation.
     *
     * **The two switches compose the only way they can: either saying no means no.** A
     * per-conversation switch may RESTRICT and never widen, so turning one conversation back
     * to normal cannot quietly re-enable the internet for someone who banned it app-wide.
     */
    fun internetAllowed(globalInternetAllowed: Boolean, conversationIsOfflineOnly: Boolean): Boolean =
        globalInternetAllowed && !conversationIsOfflineOnly

    /**
     * What the window shows under the switch: confirmed out of the OTHER participants.
     *
     * We are never counted — our own compliance is not in question — which is why the total
     * is the other-member count and not the whole group. Both halves are clamped because a
     * roster can shrink between a confirmation arriving and this being drawn, and
     * "3 of 2 confirmed" reads as a bug in the app rather than as a departed member.
     */
    fun confirmationSummary(confirmed: Int, otherMemberCount: Int): Pair<Int, Int> {
        val total = maxOf(0, otherMemberCount)
        return minOf(maxOf(0, confirmed), total) to total
    }
}
