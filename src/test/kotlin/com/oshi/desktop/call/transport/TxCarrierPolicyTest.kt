package com.oshi.desktop.call.transport

import com.oshi.desktop.call.transport.TxCarrierPolicy.Carrier
import com.oshi.desktop.call.transport.TxCarrierPolicy.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phones' one-carrier-per-frame rule (iOS `chooseAudioTxPrimary`, Android
 * `chooseAudioTxPrimary`), checked on the two evidence shapes measured with real phones
 * on 2026-09-23.
 */
class TxCarrierPolicyTest {

    private fun ev(
        p2pHealthy: Boolean = false,
        p2pPlausible: Boolean = true,
        relayUsable: Boolean = true,
        relayDelivering: Boolean = false,
        wsAvailable: Boolean = true,
        wsUsable: Boolean = true,
        wsDelivering: Boolean = false,
        anythingArriving: Boolean = false,
    ) = Evidence(p2pHealthy, p2pPlausible, relayUsable, relayDelivering, wsAvailable, wsUsable, wsDelivering, anythingArriving)

    @Test fun `a healthy pair carries every frame alone`() {
        val p = TxCarrierPolicy()
        assertEquals(Carrier.P2P, p.choose(0, ev(p2pHealthy = true, relayDelivering = true, anythingArriving = true)))
        assertNull("no mirror while nothing changed", p.mirror(10))
    }

    /**
     * SAMSUNG SHAPE: pair selected, pongs fine, the peer's audio arrives only on the relay.
     * The desktop used to keep sending on P2P too; the rule is relay ONLY.
     */
    @Test fun `peer audio arriving on the relay moves our sending to the relay, not relay plus p2p`() {
        val p = TxCarrierPolicy()
        assertEquals(Carrier.P2P, p.choose(0, ev(p2pHealthy = true)))            // grace
        val c = p.choose(6_000, ev(p2pHealthy = false, relayDelivering = true, anythingArriving = true))
        assertEquals(Carrier.UDP_RELAY, c)
        assertEquals("make-before-break mirror for 2 s", Carrier.P2P, p.mirror(6_500))
        assertNull("then P2P stops, so the peer's P2P goes stale too", p.mirror(8_100))
    }

    /** IPHONE SHAPE: selected pair silent in both directions, relay registered but quiet. */
    @Test fun `total blackout rotates through the carriers every two seconds`() {
        val p = TxCarrierPolicy()
        val seen = LinkedHashSet<Carrier>()
        var t = 0L
        repeat(8) {
            seen += p.choose(t, ev(anythingArriving = false))!!
            t += 2_000
        }
        assertEquals(setOf(Carrier.UDP_RELAY, Carrier.WS, Carrier.P2P), seen)
    }

    @Test fun `evidence beats availability`() {
        val p = TxCarrierPolicy()
        assertEquals(Carrier.WS, p.choose(0, ev(wsDelivering = true, anythingArriving = true)))
    }

    @Test fun `media flowing on no relay falls back to availability`() {
        val p = TxCarrierPolicy()
        assertEquals(Carrier.UDP_RELAY, p.choose(0, ev(anythingArriving = true)))
        assertEquals(Carrier.WS, TxCarrierPolicy().choose(0, ev(relayUsable = false, anythingArriving = true)))
    }

    @Test fun `video does not advance the rotation`() {
        val p = TxCarrierPolicy()
        val first = p.choose(0, ev())
        repeat(5) { p.choose(10_000L + it, ev(), advance = false) }
        assertEquals(first, p.current)
    }

    @Test fun `nothing at all gives null`() {
        assertNull(TxCarrierPolicy().choose(0, ev(p2pPlausible = false, relayUsable = false, wsAvailable = false, wsUsable = false)))
    }
}
