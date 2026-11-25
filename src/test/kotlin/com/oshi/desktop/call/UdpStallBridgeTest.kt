package com.oshi.desktop.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __CALL_FLUIDITY_2026_11_21__ When the `:8089` relay counts as stalled (bridge to the WebSocket). */
class UdpStallBridgeTest {
    @Test fun `never carried media - not a stall (call setup, nothing to bridge yet)`() =
        assertFalse(CallMediaLeg.udpStalled(0L, 60_000L))

    @Test fun `media inside the window - healthy`() =
        assertFalse(CallMediaLeg.udpStalled(10_000L, 10_000L + CallMediaLeg.UDP_STALL_MS - 1))

    @Test fun `carried media then went silent for the window - stalled`() =
        assertTrue(CallMediaLeg.udpStalled(10_000L, 10_000L + CallMediaLeg.UDP_STALL_MS))

    @Test fun `the bridge engages long before usable() gives the relay up`() =
        assertTrue(CallMediaLeg.UDP_STALL_MS * 5 < com.oshi.desktop.call.transport.UdpRelayClient.STALE_MS)
}
