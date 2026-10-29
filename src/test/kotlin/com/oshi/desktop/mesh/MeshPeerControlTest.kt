package com.oshi.desktop.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __PEERS_ARE_A_SCREEN_2026_10_28__ The two controls PARITY.md row 1.3 named as absent:
 * disconnecting a peer by hand, and a scan toggle.
 *
 * Driven against two real nodes on loopback, because both are about SOCKET and SERVICE
 * state and neither has a pure form worth asserting.
 */
class MeshPeerControlTest {

    private fun node(name: String) = MeshNode(
        myPublicKey = "key-$name-${java.util.UUID.randomUUID()}",
        myDisplayName = name,
        log = {},
    )

    /** Disconnecting someone we are not connected to is false, not an exception. */
    @Test
    fun `disconnecting an unknown peer is false`() {
        val n = node("solo")
        try {
            n.start(enableDiscovery = false)
            assertFalse(n.disconnectPeer("nobody"))
            assertFalse("an empty key must not match anything", n.disconnectPeer(""))
        } finally {
            n.stop()
        }
    }

    /**
     * The scan toggle is BOTH halves of mDNS. Off means this machine stops announcing
     * itself as well as looking — a switch that silenced only one would leave someone
     * believing they were invisible while still broadcasting their name and public key.
     */
    @Test
    fun `the scan toggle turns discovery off and on, and reports what took`() {
        val n = node("scan")
        try {
            n.start(enableDiscovery = true)
            assertTrue("started with discovery, so it should be on", n.isDiscovering)

            assertFalse("off must report off", n.setDiscoveryEnabled(false))
            assertFalse(n.isDiscovering)

            assertTrue("on must report on", n.setDiscoveryEnabled(true))
            assertTrue(n.isDiscovering)
        } finally {
            n.stop()
        }
    }

    /** Idempotent in both directions: a second call must not start a second service. */
    @Test
    fun `toggling twice in the same direction is harmless`() {
        val n = node("twice")
        try {
            n.start(enableDiscovery = false)
            assertFalse(n.isDiscovering)
            assertTrue(n.setDiscoveryEnabled(true))
            assertTrue("a second on is a no-op, not a second service", n.setDiscoveryEnabled(true))
            assertFalse(n.setDiscoveryEnabled(false))
            assertFalse("a second off is a no-op", n.setDiscoveryEnabled(false))
        } finally {
            n.stop()
        }
    }

    /** A stopped node cannot be made to scan: there is no socket for mDNS to advertise. */
    @Test
    fun `a stopped node refuses to start discovering`() {
        val n = node("stopped")
        assertFalse(n.setDiscoveryEnabled(true))
        assertFalse(n.isDiscovering)
    }
}
