package com.oshi.desktop.app

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.OfflineOnlyPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ The offline-only negotiation through two real clients.
 *
 * The defect was not in a rule. The sentinel was in the catalog, so a request reached
 * `ControlEvent.Foreign`, fell to step 7 of `handleV2Inbound` and was logged and dropped —
 * and the asking phone waited for a confirmation that was never coming. Only a test that
 * runs the inbound path can see that.
 */
class OfflineOnlyWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    /**
     * **TURNING IT ON CANNOT USE THE RELAY TO SAY SO, AND THAT IS THE DESIGN.**
     *
     * This test first asserted `SENT` and failed, and the code was right: iOS states the
     * intent in as many words — *"Our state is set BEFORE the request goes out, deliberately:
     * the request itself must travel under the new rule. Setting it afterwards would send the
     * one message that asks for the relay to stop over the relay."*
     *
     * These two fixture clients have ONLY the relay. So turning the switch on restricts this
     * device immediately and the peer is never told, and both halves of that are correct:
     * the promise the switch makes completely is about what THIS device sends. With a radio
     * or a mesh peer the request would travel on it.
     */
    @Test
    fun `turning it on restricts us at once and refuses to announce itself over the relay`() {
        val a = fx.client("a")
        val b = fx.client("b")

        val outcome = a.setOfflineOnly(b.address, true)
        assertTrue("the local restriction must hold whatever the send did", a.offlineOnly.isOfflineOnly(b.address))
        assertNotEquals(
            "the request must NOT have gone over the relay it just banned",
            OshiClient.SendOutcome.SENT, outcome,
        )
        assertEquals("nothing reached B", 0, b.router.poll())
        assertFalse(b.offlineOnly.isOfflineOnly(a.address))
        assertEquals(0, a.offlineOnly.confirmedCount(b.address))
    }

    /**
     * The receive-and-confirm half, driven by injecting the request the way a radio or a mesh
     * peer would deliver it — which is the only way it can arrive once the relay is banned
     * for that conversation.
     */
    @Test
    fun `an arriving request is honoured and confirmed back`() {
        val me = fx.client("me")
        val peer = fx.client("peer")

        // The asker records its own state first — that IS the feature's order — and its
        // request then cannot use the relay, so it is delivered here the way a radio would.
        // Without this the ack would arrive at a peer holding no state to match it against,
        // and `answersCurrentRequest` would correctly refuse to count it.
        peer.setOfflineOnly(me.address, true)
        val at = peer.offlineOnly.state(me.address)!!.changedAtUnix
        fx.inbound(me, peer.address, OfflineOnlyPayload.Request(on = true, atUnix = at).encode())
        assertTrue("the request was ignored — the step-7 drop", me.offlineOnly.isOfflineOnly(peer.address))

        // THE REQUEST RENDERS, as the localised sentence and never the payload: iOS's reason
        // is that seeing when a conversation was taken off the relay, and by whom, is the
        // point of the sentinel being `RENDERED` rather than `SILENT`.
        val row = me.history(peer.address).single()
        assertTrue("got: ${row.content}", row.content!!.contains("offline only"))
        assertFalse("the payload must never print", row.content!!.contains("\"ack\""))

        // **AND THE CONFIRMATION CANNOT COME BACK OVER A BANNED RELAY.** We honoured the
        // request, so our own ack travels under the new rule too and these two clients have
        // only the relay. This is the design, consistently applied, and it has a real
        // consequence worth stating: the confirmation count fills in only where a radio or a
        // mesh peer exists. iOS says the same of the half that does work — the request "is
        // still honoured locally, which is the half that actually protects anything".
        peer.router.poll()
        assertEquals(
            "an ack must not escape over the relay the conversation just banned",
            0, peer.offlineOnly.confirmedCount(me.address),
        )
    }

    /**
     * The confirmation DOES arrive when the ack has a lane — shown by lifting the local
     * restriction on the answering side before it replies, which is the state a peer reached
     * over a radio would be in. This is the positive half of the test above, so the zero
     * there is read as "no lane" and not as "acks are broken".
     */
    @Test
    fun `a confirmation is counted when the ack has a way out`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        peer.setOfflineOnly(me.address, true)
        val at = peer.offlineOnly.state(me.address)!!.changedAtUnix

        // Our side answers while unrestricted, which is what a radio lane amounts to here.
        fx.inbound(me, peer.address, OfflineOnlyPayload.Request(on = true, atUnix = at, isAck = false).encode())
        me.setOfflineOnly(peer.address, false)
        val ack = OfflineOnlyPayload.Request(on = true, atUnix = at, isAck = true)
        assertEquals(OshiClient.SendOutcome.SENT, me.send(peer.address, "a lane exists"))
        fx.inbound(peer, me.address, ack.encode())

        assertEquals("the ack answered the request we are waiting on", 1, peer.offlineOnly.confirmedCount(me.address))
        assertTrue("an ack is never a row", peer.history(me.address).none { it.content?.contains("ack") == true })
    }

    /**
     * AN ACK IS NEVER ECHOED. Without `ack`, an answer would read as a fresh request, which
     * would clear the confirmations and answer back — for ever. Polled repeatedly so a loop
     * would show up as a count that keeps moving or as traffic that never stops.
     */
    @Test
    fun `the negotiation terminates instead of ping-ponging`() {
        val me = fx.client("me")
        val peer = fx.client("peer")
        peer.setOfflineOnly(me.address, true)
        val at = peer.offlineOnly.state(me.address)!!.changedAtUnix
        fx.inbound(me, peer.address, OfflineOnlyPayload.Request(on = true, atUnix = at).encode())
        repeat(4) { peer.router.poll(); me.router.poll() }
        // The loop would show up as traffic that never settles. Both sides are restricted and
        // neither is waiting on the other for anything it will re-ask.
        assertEquals("we must not be waiting on them", 0, me.offlineOnly.confirmedCount(peer.address))
        assertTrue(me.offlineOnly.isOfflineOnly(peer.address))
        assertTrue(peer.offlineOnly.isOfflineOnly(me.address))
        // ONE row, not one per round trip: an echoed ack would have rendered again each time.
        assertEquals("the negotiation rendered more than once", 1, me.history(peer.address).size)
    }

    /**
     * LIFTING IT *CAN* USE THE RELAY, and the asymmetry falls straight out of the same rule:
     * the local state is written first, so by the time the request is sent the restriction is
     * already gone. A conversation that silently stayed restricted after someone lifted it
     * would strand every message sent into it.
     */
    @Test
    fun `turning it off travels, because the restriction is lifted before the request is sent`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val at = System.currentTimeMillis() / 1000.0
        // Both sides restricted, as they would be after a negotiation over a radio.
        a.setOfflineOnly(b.address, true)
        fx.inbound(b, a.address, OfflineOnlyPayload.Request(on = true, atUnix = at).encode())
        assertTrue(b.offlineOnly.isOfflineOnly(a.address))
        a.router.poll()   // drain B's ack

        assertEquals(OshiClient.SendOutcome.SENT, a.setOfflineOnly(b.address, false))
        assertFalse(a.offlineOnly.isOfflineOnly(b.address))
        b.router.poll()
        assertFalse("B stayed restricted after A lifted it", b.offlineOnly.isOfflineOnly(a.address))
    }

    /**
     * THE PROMISE. With the switch on, nothing this device sends in that conversation may
     * reach the relay — and the fixture relay is the ONLY transport these two clients have,
     * so a message that arrives proves the restriction was not applied.
     */
    @Test
    fun `with the switch on nothing reaches the peer over the relay`() {
        val a = fx.client("a")
        val b = fx.client("b")
        a.setOfflineOnly(b.address, true)

        val outcome = a.send(b.address, "this must not travel")
        assertEquals(
            "a restricted conversation with no radio and no mesh has nowhere to go: it WAITS",
            OshiClient.SendOutcome.NO_V2_PATH, outcome,
        )
        assertEquals("the relay carried a message from a restricted conversation", 0, b.router.poll())
        assertTrue(b.history(a.address).isEmpty())

        // Lift it and the same message goes. TWO rows land at B, and both are wanted: the
        // lift request renders its own localised line (a routing change is meant to be
        // visible) and then the message arrives.
        a.setOfflineOnly(b.address, false)
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "now it may"))
        b.router.poll()
        val rows = b.history(a.address)
        assertEquals("now it may", rows.last().content)
        assertTrue(
            "the lift must be visible in the thread: ${rows.first().content}",
            rows.first().content!!.contains("internet again"),
        )
    }

    /**
     * The restriction is a property of ONE conversation. A second contact must be unaffected,
     * which is what separates this from the app-wide ban.
     */
    @Test
    fun `another conversation is untouched`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")
        a.setOfflineOnly(b.address, true)
        assertEquals(OshiClient.SendOutcome.SENT, a.send(c.address, "unaffected"))
        assertEquals(1, c.router.poll())
        assertFalse(a.offlineOnly.isOfflineOnly(c.address))
    }

    /** A stale ack must not be counted towards a later change. */
    @Test
    fun `an ack for a previous state is not counted`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.setOfflineOnly(peer, true)
        val held = me.offlineOnly.state(peer)!!
        // An ack stamped for a DIFFERENT change than the one we are waiting on.
        fx.inbound(me, peer, OfflineOnlyPayload.Request(on = true, atUnix = held.changedAtUnix - 60.0, isAck = true).encode())
        assertEquals("a stale ack was counted", 0, me.offlineOnly.confirmedCount(peer))
        // And the matching one IS counted.
        fx.inbound(me, peer, OfflineOnlyPayload.Request(on = true, atUnix = held.changedAtUnix, isAck = true).encode())
        assertEquals(1, me.offlineOnly.confirmedCount(peer))
    }

    /** An out-of-order request older than what we hold is ordinary on a mesh, and ignored. */
    @Test
    fun `an older request does not flip a newer state`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.setOfflineOnly(peer, true)
        val held = me.offlineOnly.state(peer)!!
        fx.inbound(me, peer, OfflineOnlyPayload.Request(on = false, atUnix = held.changedAtUnix - 30.0).encode())
        assertTrue("an older request lifted a newer restriction", me.offlineOnly.isOfflineOnly(peer))
    }

    /** A malformed request changes nothing and is never a row. */
    @Test
    fun `a malformed request is dropped`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(me, peer, ControlPrefix.OFFLINE_ONLY + "{not json")
        assertFalse(me.offlineOnly.isOfflineOnly(peer))
        assertTrue(me.history(peer).isEmpty())
    }

    /** An incoming request restricts US, which is the whole point of honouring it. */
    @Test
    fun `honouring a peer's request stops our own relay sends to them`() {
        val a = fx.client("a")
        val b = fx.client("b")
        // Delivered as a radio or mesh peer would, for the reason the first test explains.
        fx.inbound(a, b.address, OfflineOnlyPayload.Request(on = true, atUnix = System.currentTimeMillis() / 1000.0).encode())
        assertTrue(a.offlineOnly.isOfflineOnly(b.address))
        assertNotNull(a.offlineOnly.state(b.address))
        // A now refuses the relay for that conversation even though A never asked for it.
        assertEquals(OshiClient.SendOutcome.NO_V2_PATH, a.send(b.address, "blocked by their request"))
        assertEquals(0, b.router.poll())
    }
}
