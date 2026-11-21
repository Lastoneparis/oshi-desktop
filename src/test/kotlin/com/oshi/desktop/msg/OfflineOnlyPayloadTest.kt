package com.oshi.desktop.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ The `🔒OFFLINE_ONLY🔒` wire and the routing rule,
 * pinned to payloads written by hand from `OSHI/OfflineOnlyConversation.swift:73-91`.
 *
 * Golden bytes rather than round trips, for the reason [PollPayload]'s test states: a round
 * trip passes with every key renamed as long as both halves are renamed together.
 */
class OfflineOnlyPayloadTest {

    private val iosRequest =
        "🔒OFFLINE_ONLY🔒{\"v\":1,\"on\":true,\"at\":1761220000,\"ack\":false}"

    @Test
    fun `an iPhone's request decodes`() {
        val r = OfflineOnlyPayload.decode(iosRequest)!!
        assertTrue(r.on)
        assertEquals(1761220000.0, r.atUnix, 0.0001)
        assertFalse(r.isAck)
        assertEquals(1, r.version)
    }

    /**
     * AN ABSENT `ack` IS A REQUEST. Every payload written before acks existed looks like
     * this, and iOS states the cost of the other reading: treating it as an ack would mean a
     * request nobody honours, where treating an ack as a request costs one uncounted
     * confirmation.
     */
    @Test
    fun `an absent ack field means request`() {
        val old = "🔒OFFLINE_ONLY🔒{\"v\":1,\"on\":true,\"at\":1761220000}"
        val r = OfflineOnlyPayload.decode(old)!!
        assertFalse("an older build's payload must read as a request", r.isAck)
        assertTrue(r.on)
    }

    /** Turning it OFF travels too — otherwise a lifted restriction would strand messages. */
    @Test
    fun `turning it off is a real payload`() {
        val off = OfflineOnlyPayload.decode(iosRequest.replace("\"on\":true", "\"on\":false"))!!
        assertFalse(off.on)
    }

    /** An ack carries the STATE and the STAMP of the request it answers, and sets `ack`. */
    @Test
    fun `an acknowledgement mirrors the request it answers`() {
        val req = OfflineOnlyPayload.decode(iosRequest)!!
        val ack = req.acknowledgement()
        assertTrue(ack.isAck)
        assertEquals("the stamp must be the REQUEST's, not now", req.atUnix, ack.atUnix, 0.0)
        assertEquals(req.on, ack.on)
        val o = JSONObject(ControlPrefix.strip(ack.encode()))
        assertEquals(setOf("v", "on", "at", "ack"), o.keys().asSequence().toSet())
        assertEquals(true, o.getBoolean("ack"))
    }

    /**
     * A FUTURE VERSION IS REFUSED, which is the opposite of the poll codec and deliberate.
     * A partly-understood poll is still useful; a partly-understood ROUTING RULE could
     * restrict less than the sender asked, and under-restricting is the one failure this
     * payload must never produce silently.
     */
    @Test
    fun `a future version is refused rather than half honoured`() {
        assertNull(OfflineOnlyPayload.decode(iosRequest.replace("\"v\":1", "\"v\":2")))
        // Contrast, on purpose: a future POLL still shows what it can.
        val futurePoll = ControlPrefix.POLL + """{"v":9,"id":"i","q":"x","opts":["a","b"],"at":1.0}"""
        assertTrue("the asymmetry is the point", PollPayload.decodePoll(futurePoll) != null)
    }

    @Test
    fun `the refusals`() {
        assertNull("not this sentinel", OfflineOnlyPayload.decode("hello"))
        assertNull("not JSON", OfflineOnlyPayload.decode(ControlPrefix.OFFLINE_ONLY + "{nope"))
        assertNull("no on field", OfflineOnlyPayload.decode(ControlPrefix.OFFLINE_ONLY + """{"v":1,"at":1}"""))
        assertFalse(OfflineOnlyPayload.isRequest(null))
    }

    // ------------------------------------------------------------------ the rule

    /**
     * EITHER SWITCH SAYING NO MEANS NO. The row that matters most is the last one: a
     * conversation set back to normal must not re-enable a relay the person banned app-wide.
     */
    @Test
    fun `the two switches compose so that a conversation may only restrict`() {
        assertTrue(OfflineOnlyPayload.internetAllowed(globalInternetAllowed = true, conversationIsOfflineOnly = false))
        assertFalse(OfflineOnlyPayload.internetAllowed(globalInternetAllowed = true, conversationIsOfflineOnly = true))
        assertFalse(OfflineOnlyPayload.internetAllowed(globalInternetAllowed = false, conversationIsOfflineOnly = true))
        assertFalse(
            "a conversation switched back to normal must not widen the app-wide ban",
            OfflineOnlyPayload.internetAllowed(globalInternetAllowed = false, conversationIsOfflineOnly = false),
        )
    }

    /** "3 of 2 confirmed" reads as a bug in the app rather than as a departed member. */
    @Test
    fun `the confirmation summary is clamped at both ends`() {
        assertEquals(1 to 3, OfflineOnlyPayload.confirmationSummary(1, 3))
        assertEquals("more confirmations than members", 2 to 2, OfflineOnlyPayload.confirmationSummary(5, 2))
        assertEquals("a negative count", 0 to 2, OfflineOnlyPayload.confirmationSummary(-1, 2))
        assertEquals("a 1:1 has one other participant", 1 to 1, OfflineOnlyPayload.confirmationSummary(1, 1))
        assertEquals(0 to 0, OfflineOnlyPayload.confirmationSummary(3, -4))
    }
}
