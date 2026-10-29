package com.oshi.desktop.call.transport

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `TurnClient` does with a datagram from the relay — PARITY.md row 2.1-t.
 *
 * `handle` is `internal` and its own comment says why: "Internal so a test can feed bytes
 * in by hand." Nothing fed it any. The whole file was one of 23 that the test tree never
 * names, and it is the one carrying protocol decisions rather than pixels: this is the
 * code that decides whether bytes arriving from a TURN server become CALL AUDIO.
 *
 * Three of the four below are refusals, which is the half that cannot be observed by
 * making a call work.
 */
class TurnClientHandleTest {

    private val clients = ArrayList<TurnClient>()
    @After fun tearDown() { clients.forEach { runCatching { it.close() } } }

    /** A link that goes nowhere: `handle` is driven directly. */
    private class DeadLink : TurnLink {
        override val padsChannelData = false
        val sent = CopyOnWriteArrayList<ByteArray>()
        override fun start(onMessage: (ByteArray, Int) -> Unit) = Unit
        override fun send(bytes: ByteArray): Boolean { sent += bytes; return true }
        override val describe = "dead"
        override fun close() = Unit
    }

    private fun client(): Pair<TurnClient, MutableList<Triple<ByteArray, String, Int>>> {
        val got = CopyOnWriteArrayList<Triple<ByteArray, String, Int>>()
        val c = TurnClient(DeadLink(), "u", "p").also { clients += it }
        c.onData = { d, ip, port -> got += Triple(d, ip, port) }
        return c to got
    }

    @Test
    fun `channel data for a channel we never bound is dropped`() {
        val (c, got) = client()
        val frame = TurnMessage.channelData(0x4001, byteArrayOf(1, 2, 3, 4))
        c.handle(frame, frame.size)
        assertTrue("an unbound channel must not reach the call: ${got.size}", got.isEmpty())
    }

    @Test
    fun `a data indication is delivered with the PEER's address, not the server's`() {
        val (c, got) = client()
        val tx = TurnMessage.newTransactionId()
        val payload = byteArrayOf(9, 8, 7)
        val msg = TurnMessage.build(
            TurnMessage.DATA_INDICATION, tx,
            listOf(
                TurnMessage.Attr(TurnMessage.ATTR_XOR_PEER_ADDRESS, TurnMessage.encodeXorAddress("203.0.113.9", 40404, tx)),
                TurnMessage.Attr(TurnMessage.ATTR_DATA, payload),
            ),
        )
        c.handle(msg, msg.size)
        assertEquals(1, got.size)
        assertEquals("203.0.113.9", got[0].second)
        assertEquals(40404, got[0].third)
        assertEquals(payload.toList(), got[0].first.toList())
    }

    @Test
    fun `a data indication with no peer address is dropped rather than guessed at`() {
        val (c, got) = client()
        val tx = TurnMessage.newTransactionId()
        val msg = TurnMessage.build(
            TurnMessage.DATA_INDICATION, tx,
            listOf(TurnMessage.Attr(TurnMessage.ATTR_DATA, byteArrayOf(1))),
        )
        c.handle(msg, msg.size)
        assertTrue("media with no sender must not be attributed to anyone", got.isEmpty())
    }

    /**
     * The FINGERPRINT check is the one that cannot be seen from a working call: a forged or
     * corrupted message that still parses must not become audio.
     */
    @Test
    fun `a message whose fingerprint does not match is dropped`() {
        val (c, got) = client()
        val tx = TurnMessage.newTransactionId()
        val msg = TurnMessage.build(
            TurnMessage.DATA_INDICATION, tx,
            listOf(
                TurnMessage.Attr(TurnMessage.ATTR_XOR_PEER_ADDRESS, TurnMessage.encodeXorAddress("203.0.113.9", 1, tx)),
                TurnMessage.Attr(TurnMessage.ATTR_DATA, byteArrayOf(1, 2)),
                TurnMessage.Attr(TurnMessage.ATTR_FINGERPRINT, byteArrayOf(0, 0, 0, 0)),
            ),
        )
        c.handle(msg, msg.size)
        assertTrue("a bad fingerprint must not reach the call", got.isEmpty())
    }

    @Test
    fun `a truncated or non-STUN datagram is ignored without throwing`() {
        val (c, got) = client()
        c.handle(byteArrayOf(0x7F, 0x7F), 2)
        c.handle(ByteArray(0), 0)
        assertTrue(got.isEmpty())
    }
}
