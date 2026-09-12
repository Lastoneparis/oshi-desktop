package com.oshi.desktop.lora

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LINK, not the codec.
 *
 * `LoRaFrameTest`, `LoRaProtoTest` and `LoRaSecureMessageTest` already check the bytes
 * against the shipped trees. Nothing there can fail if the socket never opens, the buffer
 * loses a straddled frame, or a dropped node is never redialled — which is exactly the set
 * of things a real attachment gets wrong. These tests are about those.
 *
 * Every one of them runs against [FakeMeshtasticNode] over loopback. **None of them says
 * anything about a real radio** (PARITY.md row 0.27).
 */
class LoRaLinkTest {

    private val links = ArrayList<LoRaLink>()
    private val nodes = ArrayList<FakeMeshtasticNode>()

    @After
    fun tearDown() {
        links.forEach { it.stop() }
        nodes.forEach { it.close() }
    }

    private fun node() = FakeMeshtasticNode().also { nodes.add(it) }

    private fun link(
        n: FakeMeshtasticNode,
        onFrame: (ByteArray) -> Unit = {},
        sleeper: (Long) -> Unit = { },
    ) = LoRaLink(n.host, n.port, onFrame, log = {}, sleeper = sleeper).also { links.add(it) }

    /**
     * A node is SILENT until asked for the config stream. A link that skipped the
     * handshake would look connected and receive nothing, for ever.
     */
    @Test
    fun `the link asks for the config stream before anything else`() {
        val n = node()
        val l = link(n)
        l.start()

        assertTrue("no want_config arrived — the node would never speak", n.handshake.await(10, TimeUnit.SECONDS))
        val first = n.received.first()
        assertEquals(
            "the first frame must be ToRadio.want_config_id (field 3, varint)",
            3, LoRaProto.parseFields(first).first().number,
        )
    }

    /** The ordinary direction: a node pushes a FromRadio and the handler sees it whole. */
    @Test
    fun `a frame pushed by the node reaches the handler intact`() {
        val n = node()
        val seen = CopyOnWriteArrayList<ByteArray>()
        val got = CountDownLatch(1)
        val l = link(n, onFrame = { seen.add(it); got.countDown() })
        l.start()
        assertTrue(n.handshake.await(10, TimeUnit.SECONDS))

        val payload = ProtoWriter().varint(1, 42).bytes(2, "hello-lora".toByteArray()).data
        n.push(payload)

        assertTrue("the pushed frame never reached the handler", got.await(10, TimeUnit.SECONDS))
        assertArrayEquals(payload, seen.first())
    }

    /**
     * THE ONE A NAIVE READ LOOP GETS WRONG.
     *
     * TCP is a byte stream, so one `read()` can return half a frame. A loop that decoded
     * only what a single read returned would drop the first half and then mis-parse for
     * ever. The bytes are split mid-body here on purpose.
     */
    @Test
    fun `a frame split across two reads is reassembled, not lost`() {
        val n = node()
        val got = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<ByteArray>()
        val l = link(n, onFrame = { seen.add(it); got.countDown() })
        l.start()
        assertTrue(n.handshake.await(10, TimeUnit.SECONDS))

        val payload = ProtoWriter().bytes(2, ByteArray(200) { (it % 251).toByte() }).data
        val framed = LoRaAttach.StreamFraming.encode(payload)
        n.pushRaw(framed.copyOfRange(0, 7))
        Thread.sleep(150)                                   // force a separate read()
        n.pushRaw(framed.copyOfRange(7, framed.size))

        assertTrue("a straddled frame was dropped", got.await(10, TimeUnit.SECONDS))
        assertArrayEquals(payload, seen.first())
    }

    /** Two frames in one read must both be drained, not one per socket read. */
    @Test
    fun `two frames in a single read are both delivered`() {
        val n = node()
        val got = CountDownLatch(2)
        val l = link(n, onFrame = { got.countDown() })
        l.start()
        assertTrue(n.handshake.await(10, TimeUnit.SECONDS))

        val a = LoRaAttach.StreamFraming.encode(ProtoWriter().varint(1, 1).data)
        val b = LoRaAttach.StreamFraming.encode(ProtoWriter().varint(1, 2).data)
        n.pushRaw(a + b)

        assertTrue("only one of two frames in a single read was drained", got.await(10, TimeUnit.SECONDS))
    }

    /** Garbage before the magic is skipped, and the frame after it still arrives. */
    @Test
    fun `noise before a frame does not swallow the frame`() {
        val n = node()
        val got = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<ByteArray>()
        val l = link(n, onFrame = { seen.add(it); got.countDown() })
        l.start()
        assertTrue(n.handshake.await(10, TimeUnit.SECONDS))

        val payload = ProtoWriter().varint(1, 7).data
        n.pushRaw(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44) + LoRaAttach.StreamFraming.encode(payload))

        assertTrue("the decoder never re-synchronised onto the magic", got.await(10, TimeUnit.SECONDS))
        assertArrayEquals(payload, seen.first())
    }

    /**
     * A radio loses power, comes back, and the link must come back with it — including a
     * FRESH handshake, or the returning node sits silent exactly as it would have on the
     * first connection.
     */
    @Test
    fun `a dropped node is redialled and re-handshaked`() {
        val n = node()
        val l = link(n)
        l.start()
        assertTrue(n.handshake.await(10, TimeUnit.SECONDS))
        assertEquals(1, n.connections.get())

        n.dropConnection()

        assertTrue("the link never re-handshaked after the node dropped", n.handshake.await(15, TimeUnit.SECONDS))
        assertTrue("the node was never redialled", n.connections.get() >= 2)
        assertTrue("the reconnect was not counted", l.reconnects.get() >= 1)
    }

    /** Sending with no live socket is false, never a silent success. */
    @Test
    fun `a send with no link is refused rather than swallowed`() {
        val n = node()
        val l = link(n)
        assertFalse("a send before start() reported success", l.send(ProtoWriter().varint(1, 1).data))
    }

    /**
     * A handler that throws costs its own frame and nothing else — the rule row 0.10
     * applies to one bad envelope in a relay page, applied to one bad frame on a link.
     */
    @Test
    fun `a handler that throws does not take the link down`() {
        val n = node()
        val good = CountDownLatch(1)
        var first = true
        val l = link(n, onFrame = {
            if (first) { first = false; throw IllegalStateException("boom") }
            good.countDown()
        })
        l.start()
        assertTrue(n.handshake.await(10, TimeUnit.SECONDS))

        n.push(ProtoWriter().varint(1, 1).data)
        n.push(ProtoWriter().varint(1, 2).data)

        assertTrue("one throwing frame killed the read loop", good.await(10, TimeUnit.SECONDS))
        assertEquals("the link should not have reconnected", 1, n.connections.get())
    }
}
