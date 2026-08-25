package com.oshi.desktop.mesh

import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two real nodes finding each other over real multicast, with nothing but a service type
 * in common — the whole discovery path end to end: announce, browse, resolve, dial,
 * identity exchange.
 *
 * This test SKIPS rather than fails when the machine cannot do multicast at all (a
 * container with no multicast route, a locked-down VLAN, a build box where UDP 5353 is
 * unavailable). That is the honest outcome: on such a machine the result says nothing
 * about this code. It is checked by actually trying to bind and join, not by guessing
 * from an environment variable — and the skip reason names what failed.
 *
 * What it cannot tell you: whether a PHONE sees this node. Apple's mDNSResponder and
 * Android's NsdManager are different implementations with different tolerances, and only
 * one of the three stacks is exercised here. That check was run by hand, from the other
 * side, and is written up in PLAN_MESH.md.
 */
class MdnsDiscoveryTest {

    private val nodes = mutableListOf<MeshNode>()

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        nodes.clear()
    }

    @Test
    fun `two nodes discover, dial and identify each other over multicast`() {
        assumeTrue("no multicast-capable interface on this machine",
            MdnsService.eligibleInterfaces().isNotEmpty())
        assumeTrue(multicastSkipReason() ?: "", multicastSkipReason() == null)

        val a = MeshNode(key("desktop-A"), "Node A", log = {}).also { nodes.add(it) }
        val b = MeshNode(key("desktop-B"), "Node B", log = {}).also { nodes.add(it) }
        a.start(enableDiscovery = true)
        b.start(enableDiscovery = true)

        // 20 s is generous on purpose: the browse backoff is 1 s, 2 s, 4 s… and a busy
        // network drops multicast. A tighter bound would make this a flake generator.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20)
        while (System.currentTimeMillis() < deadline) {
            if (a.isConnectedTo(b.myPublicKey) && b.isConnectedTo(a.myPublicKey)) break
            Thread.sleep(100)
        }

        assertTrue("A never connected to B — discovered: ${a.peers().map { it.displayName }}",
            a.isConnectedTo(b.myPublicKey))
        assertTrue("B never connected to A — discovered: ${b.peers().map { it.displayName }}",
            b.isConnectedTo(a.myPublicKey))

        // The TXT record is what carries the identity; the instance label carries only
        // eight characters of it and is not an identity.
        val seen = a.peers().first { it.publicKey == b.myPublicKey }
        assertEquals("Node B", seen.displayName)
        assertEquals(MeshProtocol.PLATFORM, seen.platform)
        assertEquals(b.listenPort, seen.port)
    }

    /** null when this machine can host an mDNS responder; otherwise why it cannot. */
    private fun multicastSkipReason(): String? = try {
        val s = MulticastSocket(null as SocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(MdnsService.MDNS_PORT))
        val nif = MdnsService.eligibleInterfaces().first()
        s.joinGroup(InetSocketAddress(MdnsService.group, MdnsService.MDNS_PORT), nif)
        s.leaveGroup(InetSocketAddress(MdnsService.group, MdnsService.MDNS_PORT), nif)
        s.close()
        null
    } catch (e: Exception) {
        "cannot bind/join UDP ${MdnsService.MDNS_PORT}: ${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * A realistically-shaped address — standard base64, padded — that is UNIQUE to this
     * test run.
     *
     * It used to be a plain SHA-256 of a fixed seed, which is deterministic and therefore
     * shared by every simultaneous run on the same network. Two runs then advertise the
     * SAME instance name and the SAME public key, discover each other, and assert that the
     * peer's port equals their own node's — which it is not. Reported by a second agent
     * running this suite from its own worktree while this one ran here; on one machine at
     * a time it looks like a stable test. Multicast is a shared bus, and a test on a shared
     * bus has to name itself.
     */
    private fun key(seed: String): String =
        Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("$seed|${ProcessHandle.current().pid()}|${System.nanoTime()}".toByteArray())
        )
}
