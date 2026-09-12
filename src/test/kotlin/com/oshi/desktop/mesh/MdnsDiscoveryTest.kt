package com.oshi.desktop.mesh

import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.StandardSocketOptions
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        val reason = multicastSkipReason()
        if (reason != null && expectMulticast()) {
            fail("OSHI_EXPECT_MULTICAST is set, but $reason")
        }
        assumeTrue(reason ?: "", reason == null)

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

    /**
     * null when a multicast datagram actually ARRIVES on this machine; otherwise why not.
     *
     * **This probe used to bind and join and call that a pass, and it was wrong.** On the
     * first CI run this project ever had (2026-08-25, `macos-latest`), bind and join both
     * succeeded, the probe returned null, the test ran — and the two nodes never saw each
     * other. The whole suite went red on that one test. Binding a socket and joining a
     * group are local operations that a kernel will grant on a host with no multicast
     * routing at all; the only question that predicts this test is whether a packet sent
     * to the group comes BACK, and that is now the question being asked.
     *
     * It probes on an EPHEMERAL port rather than 5353: macOS runs `mDNSResponder` on 5353
     * and a probe that fought it for the port would measure the fight, not the network.
     * Loopback delivery is exactly the capability the test needs, since both nodes live in
     * this one JVM on this one host.
     *
     * Set `OSHI_EXPECT_MULTICAST=1` on a machine that is supposed to have it — the skip
     * then becomes a failure, so a runner cannot report this row green having measured
     * nothing. Same escalation `OSHI_EXPECT_SECRET_STORE` gives row 0.5.
     */
    private fun multicastSkipReason(): String? {
        val nif = MdnsService.eligibleInterfaces().firstOrNull()
            ?: return "no multicast-capable interface"
        var s: MulticastSocket? = null
        return try {
            s = MulticastSocket(0)
            val port = s.localPort
            val groupAddr = InetSocketAddress(MdnsService.group, port)
            s.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
            s.setOption(StandardSocketOptions.IP_MULTICAST_IF, nif)
            s.joinGroup(groupAddr, nif)
            s.soTimeout = 2000

            val probe = "oshi-multicast-probe-${System.nanoTime()}".toByteArray()
            s.send(DatagramPacket(probe, probe.size, MdnsService.group, port))

            val buf = ByteArray(256)
            val heard = DatagramPacket(buf, buf.size)
            s.receive(heard)          // throws SocketTimeoutException when nothing arrives
            s.leaveGroup(groupAddr, nif)
            if (buf.copyOfRange(0, heard.length).contentEquals(probe)) null
            else "multicast delivered a packet that was not the probe"
        } catch (e: Exception) {
            "multicast does not deliver on ${nif.name}: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try { s?.close() } catch (_: Exception) { }
        }
    }

    /**
     * Turn the skip into a failure where multicast is expected. A machine that is supposed
     * to route multicast and does not is a broken machine, and this test is the only thing
     * that would have noticed.
     */
    private fun expectMulticast(): Boolean =
        System.getenv("OSHI_EXPECT_MULTICAST")?.lowercase() in setOf("1", "true", "yes")

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
