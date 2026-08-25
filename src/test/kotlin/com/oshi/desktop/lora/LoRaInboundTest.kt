package com.oshi.desktop.lora

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive path end to end: portnum routing, reassembly, the recipient filter, and the
 * interop quarantine.
 *
 * Driven by building real `MeshPacket` bytes the way a radio would, so the whole chain runs
 * — a test that called [LoRaInbound.route] with a hand-made `Decision` would prove nothing
 * about the routing.
 */
class LoRaInboundTest {

    private val t0 = 1_756_089_600_000L
    private val myKey = "AAAAbbbbCCCCddddEEEEffffGGGGhhhhIIIIjjjjKKK="
    private val theirKey = "ZZZZyyyyXXXXwwwwVVVVuuuuTTTTssssRRRRqqqqPPP="
    private val theirNode = 0x1234ABCDu

    @After
    fun tearDown() {
        LoRaNodeBindings.adoptRawTextIntoContactThread = false
    }

    /** A `MeshPacket` as the RADIO builds it — `from` is field 1, which OSHI never writes. */
    private fun meshPacket(from: UInt, portnum: Long, payload: ByteArray): ByteArray {
        val data = ProtoWriter().varint(1, portnum).bytes(2, payload).data
        return ProtoWriter()
            .fixed32(1, from)
            .fixed32(2, LoRaProto.BROADCAST_ADDR)
            .bytes(4, data)
            .data
    }

    private fun envelope(recipient: String = myKey) = LoRaSecureMessage(
        id = "11111111-2222-3333-4444-555555555555",
        senderAddress = theirKey,
        recipientAddress = recipient,
        encryptedContent = LoRaEncryptedContent("Y2lwaGVy", "", theirKey, t0),
        unixMillis = t0,
        isRead = false,
        deliveryStatus = LoRaSecureMessage.STATUS_SENT,
        senderPublicKey = theirKey,
        recipientPublicKey = recipient,
    )

    // ============================================================ THE OSHI LANE

    @Test
    fun `a complete OSHI envelope addressed to me is delivered with its node number`() {
        val inbound = LoRaInbound()
        val frames = LoRaFrame.frames(0xCAFEBABEu, envelope().toWireJson())!!

        var last: LoRaInbound.Decision = LoRaInbound.Decision.Ignored
        for (f in frames) last = inbound.route(meshPacket(theirNode, LoRaProto.PORT_OSHI, f), myKey, t0)

        assertTrue("the last frame completes it", last is LoRaInbound.Decision.Envelope)
        val d = last as LoRaInbound.Decision.Envelope
        assertEquals("11111111-2222-3333-4444-555555555555", d.message.id)
        assertEquals("the node number rides along for the LATER bind", theirNode, d.nodeNum)
    }

    /** Partial frames are Ignored, not errors. */
    @Test
    fun `frames before the last are ignored`() {
        val inbound = LoRaInbound()
        val frames = LoRaFrame.frames(1u, envelope().toWireJson())!!
        assertTrue(frames.size > 1)
        for (f in frames.dropLast(1)) {
            assertTrue(inbound.route(meshPacket(theirNode, LoRaProto.PORT_OSHI, f), myKey, t0)
                is LoRaInbound.Decision.Ignored)
        }
    }

    /**
     * OSHI **broadcasts**, so every OSHI radio in range receives every envelope. An
     * envelope for someone else is dropped by the recipient filter.
     */
    @Test
    fun `an envelope addressed to someone else is ignored`() {
        val inbound = LoRaInbound()
        val frames = LoRaFrame.frames(1u, envelope(recipient = theirKey).toWireJson())!!
        var last: LoRaInbound.Decision = LoRaInbound.Decision.Ignored
        for (f in frames) last = inbound.route(meshPacket(theirNode, LoRaProto.PORT_OSHI, f), myKey, t0)
        assertTrue(last is LoRaInbound.Decision.Ignored)
    }

    /**
     * **A client with no identity loaded accepts nothing.**
     *
     * Both phones guard `!myKey.isNullOrEmpty()` before comparing. Without it a desktop with
     * no key would surface every nearby conversation's ciphertext to its own pipeline.
     */
    @Test
    fun `with no identity loaded every envelope is ignored`() {
        val inbound = LoRaInbound()
        val frames = LoRaFrame.frames(1u, envelope().toWireJson())!!
        var last: LoRaInbound.Decision = LoRaInbound.Decision.Ignored
        for (f in frames) last = inbound.route(meshPacket(theirNode, LoRaProto.PORT_OSHI, f), "", t0)
        assertTrue(last is LoRaInbound.Decision.Ignored)
    }

    /** An unknown portnum is ignored rather than guessed at. */
    @Test
    fun `an unknown portnum is ignored`() {
        val inbound = LoRaInbound()
        assertTrue(
            inbound.route(meshPacket(theirNode, 67L, "hello".toByteArray()), myKey, t0)
                is LoRaInbound.Decision.Ignored,
        )
    }

    /** ROUTING_APP is carried, not parsed — it is how the mesh reports a `want_ack` fate. */
    @Test
    fun `a routing reply is carried through`() {
        val inbound = LoRaInbound()
        val d = inbound.route(meshPacket(theirNode, LoRaProto.PORT_ROUTING, byteArrayOf(1, 2)), myKey, t0)
        assertTrue(d is LoRaInbound.Decision.Routing)
        assertEquals(theirNode, (d as LoRaInbound.Decision.Routing).nodeNum)
    }

    // ============================================================ THE INTEROP LANE

    /**
     * A portnum-1 packet is plain text from a stock Meshtastic device: no id, no timestamp,
     * no key, no signature. It goes to its own `lora!<nodehex>` thread and carries
     * `unverified`.
     */
    @Test
    fun `raw meshtastic text lands in its own lora thread and is marked unverified`() {
        val inbound = LoRaInbound()
        val d = inbound.route(
            meshPacket(theirNode, LoRaProto.PORT_TEXT, "hello from a stock node".toByteArray()),
            myKey, t0,
        ) as LoRaInbound.Decision.InteropText

        assertEquals("hello from a stock node", d.text)
        assertEquals("lora!1234abcd", d.conversationKey)
        assertNull("nothing has been proved about this node", d.boundKey)
        assertTrue("always, so a renderer has to acknowledge it", d.unverified)
    }

    /**
     * **The quarantine holds even for a node whose identity HAS been proved.**
     *
     * This is the security property of the whole interop lane. The node number in a raw
     * text packet is an unauthenticated integer anyone in range can claim, so filing it into
     * a contact's E2E conversation would render unverified plaintext identically to a
     * message the ratchet authenticated.
     */
    @Test
    fun `a proved node's raw text STILL goes to the lora thread`() {
        val inbound = LoRaInbound()
        inbound.bindAfterRatchetProof(theirNode, theirKey, t0)

        val d = inbound.route(
            meshPacket(theirNode, LoRaProto.PORT_TEXT, "spoofable".toByteArray()), myKey, t0,
        ) as LoRaInbound.Decision.InteropText

        assertEquals("lora!1234abcd", d.conversationKey)
        assertEquals(
            "boundKey is exposed so an OSHI peer sending raw text is DETECTABLE",
            theirKey, d.boundKey,
        )
    }

    /** With the adopt flag on it does adopt — the behaviour that still needs a UI marker. */
    @Test
    fun `with the adopt flag on a proved node's raw text adopts the contact thread`() {
        val inbound = LoRaInbound()
        inbound.bindAfterRatchetProof(theirNode, theirKey, t0)
        LoRaNodeBindings.adoptRawTextIntoContactThread = true

        val d = inbound.route(
            meshPacket(theirNode, LoRaProto.PORT_TEXT, "x".toByteArray()), myKey, t0,
        ) as LoRaInbound.Decision.InteropText
        assertEquals(theirKey, d.conversationKey)
        assertTrue("and it is STILL unverified", d.unverified)
    }

    /**
     * An impostor node claiming a bound identity makes BOTH ambiguous, and neither routes —
     * including with the adopt flag on.
     */
    @Test
    fun `an impostor claiming a bound identity strands both nodes in lora threads`() {
        val inbound = LoRaInbound()
        inbound.bindAfterRatchetProof(theirNode, theirKey, t0)
        // The attacker has to get past the ratchet to reach bind() at all — this test grants
        // them that, which is the strongest assumption, to show the conflict rule still holds.
        assertEquals(
            LoRaNodeBindings.LearnResult.CONFLICT,
            inbound.bindAfterRatchetProof(0x99u, theirKey, t0 + 1),
        )
        LoRaNodeBindings.adoptRawTextIntoContactThread = true

        val impostor = inbound.route(
            meshPacket(0x99u, LoRaProto.PORT_TEXT, "trust me".toByteArray()), myKey, t0,
        ) as LoRaInbound.Decision.InteropText
        val real = inbound.route(
            meshPacket(theirNode, LoRaProto.PORT_TEXT, "hi".toByteArray()), myKey, t0,
        ) as LoRaInbound.Decision.InteropText

        assertEquals("lora!00000099", impostor.conversationKey)
        assertEquals("the real one loses routing too — we cannot tell them apart",
            "lora!1234abcd", real.conversationKey)
        assertNull(impostor.boundKey)
        assertNull(real.boundKey)
    }

    // ============================================================ THE TEXT BUDGET

    /**
     * The honest statement of the MTU story: not "a LoRa packet is 237 bytes", but
     * **12 frames of 180, which after the ratchet buys about 500 characters**
     * (`MessageSendPolicy.swift:47`, measured at `:22-35`).
     */
    @Test
    fun `the text budget is the measured 500 UTF-8 bytes`() {
        assertEquals(500, LoRaInbound.LORA_SAFE_UTF8_BYTES)
        assertTrue(
            "and it is far below the raw frame budget, because the envelope eats most of it",
            LoRaInbound.LORA_SAFE_UTF8_BYTES < LoRaFrame.MAX_SEND_BYTES / 4,
        )
    }

    /** A message whose JSON exceeds the 12-frame cap is refused at the framing layer. */
    @Test
    fun `an oversized envelope is refused before it reaches the air`() {
        val huge = envelope().copy(
            encryptedContent = LoRaEncryptedContent("A".repeat(3000), "", theirKey, t0),
        )
        assertNull(LoRaFrame.frames(1u, huge.toWireJson()))
        assertFalse("and the frames are never built", huge.toWireJson().size <= LoRaFrame.MAX_SEND_BYTES)
    }
}
