package com.oshi.desktop.mesh

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing rules, exercised over REAL sockets between real nodes on loopback.
 *
 * Not mocks: a mesh bug is almost always a lifecycle bug — who registers a connection,
 * when a peer key becomes known, what happens on a close — and a mock transport is
 * exactly the part that would hide it. Discovery is switched off so the tests are
 * deterministic; multicast is exercised separately (MdnsLoopbackTest) because a test
 * whose outcome depends on the machine's network is not a test, it is a weather report.
 */
class MeshNodeTest {

    private val nodes = mutableListOf<MeshNode>()

    @After
    fun tearDown() {
        nodes.forEach { it.stop() }
        nodes.clear()
    }

    private fun node(name: String, key: String): MeshNode =
        MeshNode(key, name, log = {}).also { nodes.add(it); it.start(enableDiscovery = false) }

    /** Both directions of the handshake, and the peer lists that result from it. */
    @Test
    fun `two nodes exchange identities and each learns the other`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)

        val bSawA = CountDownLatch(1)
        b.onPeersChanged = { peers -> if (peers.any { it.publicKey == KEY_A }) bSawA.countDown() }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)

        assertTrue("B never learned A's identity", bSawA.await(10, TimeUnit.SECONDS))
        waitUntil("A did not register B") { a.isConnectedTo(KEY_B) }
        waitUntil("B did not register A") { b.isConnectedTo(KEY_A) }

        val peerOfB = b.peers().first { it.publicKey == KEY_A }
        assertEquals("A", peerOfB.displayName)
        assertEquals(MeshProtocol.PLATFORM, peerOfB.platform)

        // Both sides learn a one-hop route to the other, which is what makes the node a
        // relay for its neighbours rather than just an endpoint.
        waitUntil("A has no route to B") { a.routes()[KEY_B]?.hopCount == 1 }
        waitUntil("B has no route to A") { b.routes()[KEY_A]?.hopCount == 1 }
    }

    /**
     * Found by a red Windows CI job, and it was never a Windows bug.
     *
     * `handleIdentityExchange` recorded `conn.socket.port` as the peer's port. On an
     * ACCEPTED socket that is the DIALLING side's ephemeral source port — a number nobody
     * can connect to, and one that looks plausible because it usually sits a couple above
     * their real listen port. Worse, it OVERWROTE the true port mDNS had already resolved,
     * so whichever landed last won. macOS and Linux happened to lose that race in the
     * harmless direction and Windows in the visible one, which is why one CI leg went red
     * over a defect present on all three.
     *
     * The overwrite half is guarded by `MdnsDiscoveryTest` — that is the assertion that
     * caught this. This is the other half: what we record when the peer only ever dialled
     * in and nothing has told us a port at all.
     */
    @Test
    fun `a peer that dialled in is not given a port we could never dial back`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)

        val bSawA = CountDownLatch(1)
        b.onPeersChanged = { peers -> if (peers.any { it.publicKey == KEY_A }) bSawA.countDown() }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        assertTrue("B never learned A's identity", bSawA.await(10, TimeUnit.SECONDS))

        val aAsSeenByB = b.peers().first { it.publicKey == KEY_A }
        assertEquals(
            "B invented a listen port for a peer that only ever dialled in — the value it " +
                "had is A's outbound SOURCE port, which reaches nothing",
            MeshNode.PORT_UNKNOWN, aAsSeenByB.port,
        )
        assertFalse(
            "a port nobody advertised must not be reported as dialable",
            aAsSeenByB.portIsDialable,
        )
        assertEquals(
            "the host is kept — that address demonstrably carried a connection",
            "127.0.0.1", aAsSeenByB.host,
        )
        // And the dialling side is unaffected: A learned B's port by dialling it.
        //
        // The wait is not decoration. `bSawA` only says B processed A's identity; A
        // processes B's REPLY on its own thread, and asserting straight after the latch
        // passed in isolation and lost the race under a full-suite run. A flake that only
        // fires under load is worse than no test.
        waitUntil("A never registered B") { a.peers().any { it.publicKey == KEY_B } }
        assertEquals(b.listenPort, a.peers().first { it.publicKey == KEY_B }.port)
    }

    @Test
    fun `a message reaches the peer it is addressed to`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val received = ConcurrentLinkedQueue<MeshMessage>()
        val got = CountDownLatch(1)
        b.onMessage = { received.add(it); got.countDown() }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("no connection") { a.isConnectedTo(KEY_B) && b.isConnectedTo(KEY_A) }

        assertTrue("send reported failure", a.sendMessage(KEY_B, "hello mesh"))
        assertTrue("message never arrived", got.await(10, TimeUnit.SECONDS))
        val m = received.first()
        assertEquals("hello mesh", m.payload)
        assertEquals(KEY_A, m.senderPublicKey)
        assertEquals(MeshProtocol.TYPE_TEXT, m.type)
        assertEquals(0, m.hopCount)
    }

    /**
     * The privacy rule, as a test. With no path to the recipient the message is DROPPED
     * and the send reports false — it is never sprayed at whoever happens to be connected.
     * The envelope's sender and recipient keys are plaintext, so a broadcast fallback
     * would hand every peer in the room the fact that A is talking to C.
     */
    @Test
    fun `an unroutable message is dropped, not broadcast to whoever is connected`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val leaked = ConcurrentLinkedQueue<MeshMessage>()
        b.onMessage = { leaked.add(it) }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("no connection") { a.isConnectedTo(KEY_B) }

        assertFalse("a send with no path must report failure", a.sendMessage(KEY_C, "for C only"))
        Thread.sleep(500)
        assertTrue("the message leaked to an unrelated peer: ${leaked.map { it.payload }}", leaked.isEmpty())
    }

    /**
     * A—B—C with no A↔C link. B relays because the message is not addressed to it, and
     * the delivered copy shows the hop it took.
     */
    @Test
    fun `a middle node relays to a peer the sender cannot reach directly`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val c = node("C", KEY_C)
        val atC = ConcurrentLinkedQueue<MeshMessage>()
        val got = CountDownLatch(1)
        c.onMessage = { atC.add(it); got.countDown() }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        c.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("chain not formed") { b.isConnectedTo(KEY_A) && b.isConnectedTo(KEY_C) }
        // The 2-hop IDENTITY_ANNOUNCE gossip is what teaches A that C exists behind B.
        waitUntil("A never learned a route to C") { a.routes()[KEY_C]?.hopCount == 2 }

        assertTrue("A could not send to C", a.sendMessage(KEY_C, "through the middle"))
        assertTrue("C never received the relayed message", got.await(10, TimeUnit.SECONDS))
        val m = atC.first()
        assertEquals("through the middle", m.payload)
        assertEquals("the relay must have bumped the hop count", 1, m.hopCount)
        // The originator is already there (both shipped clients originate with
        // `seenBy = [myPublicKey]`), and the relay appends itself — in that order.
        assertEquals("seenBy must read origin-then-relay", listOf(KEY_A, KEY_B), m.seenBy)
        assertEquals("the original sender must survive the relay", KEY_A, m.senderPublicKey)
    }

    @Test
    fun `a node never relays a message that already carries its own key`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val c = node("C", KEY_C)
        val atC = ConcurrentLinkedQueue<MeshMessage>()
        c.onMessage = { atC.add(it) }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        c.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("chain not formed") { b.isConnectedTo(KEY_A) && b.isConnectedTo(KEY_C) }
        waitUntil("no route") { a.routes().containsKey(KEY_C) }

        // Hand-built: a message for C that B has ALREADY relayed once. B must drop it.
        val poisoned = MeshMessage(
            id = "loop-test-1", type = MeshProtocol.TYPE_TEXT,
            senderPublicKey = KEY_A, senderName = "A", recipientPublicKey = KEY_C,
            payload = "should not loop", timestamp = 1787601120588.0,
            hopCount = 1, maxHops = MeshProtocol.MAX_HOPS,
            seenBy = listOf(KEY_B), platform = MeshProtocol.PLATFORM,
        )
        sendRaw(a, KEY_B, poisoned)
        Thread.sleep(1000)
        assertTrue("B relayed a message it had already seen", atC.isEmpty())
    }

    @Test
    fun `a message at its hop ceiling is dropped`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val c = node("C", KEY_C)
        val atC = ConcurrentLinkedQueue<MeshMessage>()
        c.onMessage = { atC.add(it) }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        c.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("chain not formed") { b.isConnectedTo(KEY_A) && b.isConnectedTo(KEY_C) }

        sendRaw(a, KEY_B, MeshMessage(
            id = "hop-ceiling-1", type = MeshProtocol.TYPE_TEXT,
            senderPublicKey = KEY_A, senderName = "A", recipientPublicKey = KEY_C,
            payload = "too far", timestamp = 1787601120588.0,
            hopCount = 50, maxHops = 50, seenBy = emptyList(), platform = MeshProtocol.PLATFORM,
        ))
        Thread.sleep(1000)
        assertTrue("a message at maxHops was still forwarded", atC.isEmpty())
    }

    @Test
    fun `the same message id is delivered once, however many copies arrive`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val delivered = ConcurrentLinkedQueue<MeshMessage>()
        b.onMessage = { delivered.add(it) }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("no connection") { a.isConnectedTo(KEY_B) }

        val dup = MeshMessage(
            id = "duplicate-1", type = MeshProtocol.TYPE_TEXT,
            senderPublicKey = KEY_A, senderName = "A", recipientPublicKey = KEY_B,
            payload = "say it once", timestamp = 1787601120588.0,
            hopCount = 0, maxHops = MeshProtocol.MAX_HOPS, seenBy = emptyList(),
            platform = MeshProtocol.PLATFORM,
        )
        repeat(4) { sendRaw(a, KEY_B, dup) }
        Thread.sleep(1000)
        assertEquals("duplicate suppression failed", 1, delivered.size)
    }

    /**
     * A closed connection has to take its routes with it. Leaving them behind is how a
     * node keeps confidently forwarding into a link that no longer exists — the message
     * is reported sent and is never delivered.
     */
    @Test
    fun `a dropped connection removes the peer and every route through it`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val c = node("C", KEY_C)

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        c.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("chain not formed") { a.isConnectedTo(KEY_B) && c.isConnectedTo(KEY_B) }
        waitUntil("A never learned C") { a.routes().containsKey(KEY_C) }

        b.stop()

        waitUntil("A still thinks B is connected") { !a.isConnectedTo(KEY_B) }
        waitUntil("A kept a route whose next hop is gone") { a.routes().isEmpty() }
        assertTrue("A kept a peer entry for a dead link", a.peers().none { it.publicKey == KEY_B })
        assertFalse("a send into a dead link must fail", a.sendMessage(KEY_C, "nobody there"))
    }

    /**
     * Both shipped clients put the ORIGINATOR in `seenBy` when they create a message
     * (CrossPlatformMesh.swift:282, CrossPlatformMesh.kt:321), and their broadcast relay
     * floods to every link NOT named there. Originating with an empty list means a phone
     * relaying our message floods it straight back at us.
     *
     * Found by an audit against the shipped sources, not by a desktop test — and it could
     * not have been: with both ends running this code, the wrong convention is symmetric
     * and invisible. This test is therefore written against the RULE, not against
     * behaviour observed between two desktops.
     */
    @Test
    fun `an originated message already lists this node in seenBy`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val received = ConcurrentLinkedQueue<MeshMessage>()
        val got = CountDownLatch(1)
        b.onMessage = { received.add(it); got.countDown() }

        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("no connection") { a.isConnectedTo(KEY_B) && b.isConnectedTo(KEY_A) }
        a.sendMessage(KEY_B, "who has seen this")
        assertTrue(got.await(10, TimeUnit.SECONDS))

        assertEquals("the originator must name itself, so a relaying peer does not bounce it back",
            listOf(KEY_A), received.first().seenBy)
    }

    /**
     * A route learned by gossip must not outlive the peer it points at. Both shipped
     * clients sweep every 60 s with a 5-minute cutoff; without it, [sendOrRelay] keeps
     * handing messages to a live intermediate link that leads to a node which is gone,
     * and keeps reporting them sent.
     */
    @Test
    fun `a route nobody has refreshed for five minutes is dropped`() {
        val a = node("A", KEY_A)
        val b = node("B", KEY_B)
        val c = node("C", KEY_C)
        a.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        c.connectToPeer("127.0.0.1", b.listenPort, KEY_B)
        waitUntil("chain not formed") { b.isConnectedTo(KEY_A) && b.isConnectedTo(KEY_C) }
        waitUntil("A never learned a route to C") { a.routes().containsKey(KEY_C) }

        val now = System.currentTimeMillis()
        assertEquals("a fresh route must survive a sweep", 0, a.pruneRoutes(now))
        assertTrue(a.routes().containsKey(KEY_C))

        val dropped = a.pruneRoutes(now + MeshNode.ROUTE_TTL_MS + 1)
        assertTrue("nothing was dropped after the cutoff", dropped > 0)
        assertTrue("the stale route survived", !a.routes().containsKey(KEY_C))

        // A DIRECT peer is unaffected in practice: sendOrRelay finds its socket before it
        // ever consults the routing table.
        assertTrue("a direct link must still be usable after its route expired",
            a.sendMessage(KEY_B, "still reachable"))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Push a hand-built message into a live connection, bypassing `sendMessage` so a test
     * can construct hop counts and `seenBy` lists that a well-behaved node would not.
     */
    private fun sendRaw(from: MeshNode, toKey: String, msg: MeshMessage) {
        val m = MeshNode::class.java.getDeclaredMethod("findConn", String::class.java)
        m.isAccessible = true
        val conn = m.invoke(from, toKey) ?: error("no connection to ${toKey.take(8)}")
        val send = MeshNode::class.java.declaredMethods.first { it.name == "sendBytes" }
        send.isAccessible = true
        send.invoke(from, conn, msg.toWireBytes())
    }

    private fun waitUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("$message (waited ${timeoutMs}ms)")
    }

    companion object {
        // Realistic shapes: standard base64 with padding, exactly what a real address is.
        private const val KEY_A = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
        private const val KEY_B = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
        private const val KEY_C = "Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M="
    }
}
