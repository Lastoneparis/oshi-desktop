package com.oshi.desktop.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `"OM"` frame, against the LITERAL fixtures that ship in the Android tree.
 *
 * `OSHI-Android/app/src/test/java/com/oshi/messenger/service/lora/LoRaEnvelopeTest.kt`
 * pins the layout with a hand-written byte array, and that array is reproduced here rather
 * than any encoder's output. It is the strongest kind of vector this project has: a
 * literal, written by a different implementation, checked against iOS line by line.
 */
class LoRaFrameTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ============================================================ THE LITERAL LAYOUT

    /**
     * `LoRaEnvelopeTest.frame matches the literal iOS layout` (`:26-31`).
     *
     * `"OM"` ‖ msgId LITTLE-endian ‖ seq ‖ total ‖ chunk — `MeshtasticManager.swift:521-525`.
     */
    @Test
    fun `shipped vector - frame matches the literal iOS layout`() {
        val fixture = bytes(0x4F, 0x4D, 0xEF, 0xBE, 0xAD, 0xDE, 0x01, 0x03, 0x41, 0x42)
        assertArrayEquals(
            fixture,
            LoRaFrame.frame(0xDEADBEEFu, seq = 1, total = 3, chunk = bytes(0x41, 0x42)),
        )
    }

    /** `LoRaEnvelopeTest.parses the literal iOS fixture back` (`:33-41`). */
    @Test
    fun `shipped vector - parses the literal iOS fixture back`() {
        val h = LoRaFrame.parse(bytes(0x4F, 0x4D, 0xEF, 0xBE, 0xAD, 0xDE, 0x01, 0x03, 0x41, 0x42))!!
        assertEquals(0xDEADBEEFu, h.msgId)
        assertEquals(1, h.seq)
        assertEquals(3, h.total)
        assertArrayEquals(bytes(0x41, 0x42), h.chunk)
    }

    /**
     * `LoRaEnvelopeTest.msgId is little-endian, not big-endian` (`:43-48`), whose own
     * comment calls it *"the single most likely silent break between two platforms"*.
     *
     * It is silent because a big-endian reader does not crash — it computes a different
     * message id, so every frame of one message lands in a different reassembly bucket and
     * nothing ever completes, with no error anywhere.
     */
    @Test
    fun `shipped vector - msgId is little-endian and not big-endian`() {
        val f = LoRaFrame.frame(0x01020304u, 0, 1, bytes(0xFF))
        assertArrayEquals(bytes(0x04, 0x03, 0x02, 0x01), f.copyOfRange(2, 6))
    }

    /** `LoRaEnvelopeTest.header is exactly eight bytes` (`:50-54`). */
    @Test
    fun `shipped vector - the header is exactly eight bytes`() {
        assertEquals(8, LoRaFrame.HEADER_BYTES)
        assertEquals(9, LoRaFrame.frame(1u, 0, 1, bytes(0x00)).size)
    }

    /** A msgId with the top bit set survives the UInt round trip unsigned. */
    @Test
    fun `a msgId above Int MAX_VALUE round-trips`() {
        val big = 0xFFFFFFFFu
        val h = LoRaFrame.parse(LoRaFrame.frame(big, 0, 1, bytes(0x01)))!!
        assertEquals(big, h.msgId)
    }

    // ============================================================ THE MTU STORY

    /** `LoRaEnvelopeTest.chunk boundary sits at exactly 180 bytes` (`:58-64`). */
    @Test
    fun `shipped vector - the chunk boundary sits at exactly 180 bytes`() {
        assertEquals(1, LoRaFrame.chunk(ByteArray(180)).size)
        assertEquals(2, LoRaFrame.chunk(ByteArray(181)).size)
        assertEquals(180, LoRaFrame.chunk(ByteArray(181))[0].size)
        assertEquals(1, LoRaFrame.chunk(ByteArray(181))[1].size)
    }

    /**
     * `LoRaEnvelopeTest.2160 bytes is twelve chunks and is accepted` (`:66-77`).
     *
     * 12 × 180 — the exact ceiling `MessageSendPolicy` budgets against, and each frame is
     * 188 bytes on the wire, inside Meshtastic's ~237 with margin.
     */
    @Test
    fun `shipped vector - 2160 bytes is twelve frames of 188 and is accepted`() {
        val frames = LoRaFrame.frames(1u, ByteArray(2160))
        assertNotNull(frames)
        assertEquals(12, frames!!.size)
        frames.forEachIndexed { i, f ->
            assertEquals(188, f.size)
            assertEquals(i, f[6].toInt())
            assertEquals(12, f[7].toInt())
        }
        assertEquals(2160, LoRaFrame.MAX_SEND_BYTES)
    }

    /**
     * `LoRaEnvelopeTest.2161 bytes is thirteen chunks and is REFUSED` (`:79-85`).
     *
     * **Refused, not truncated.** Truncating would put a prefix of a JSON object on the air
     * that the far side reassembles "successfully" and then fails to parse — the same lost
     * message with a worse diagnosis. iOS surfaces it as `.tooLarge(bytes:)`.
     */
    @Test
    fun `shipped vector - 2161 bytes is thirteen chunks and is REFUSED`() {
        assertEquals(13, LoRaFrame.chunk(ByteArray(2161)).size)
        assertNull(LoRaFrame.frames(1u, ByteArray(2161)))
    }

    /** `LoRaEnvelopeTest.single-byte payload still produces one frame` (`:87-90`). */
    @Test
    fun `shipped vector - a single-byte payload still produces one frame`() {
        assertEquals(1, LoRaFrame.frames(1u, ByteArray(1))!!.size)
    }

    /**
     * An EMPTY payload produces no frames and is refused.
     *
     * Swift's `stride(from: 0, to: 0, by: 180)` yields nothing, so a single empty frame is
     * never emitted — and could not be read if it were, since every receiver's first guard
     * is `payload.count > 8`.
     */
    @Test
    fun `an empty payload is refused rather than framed as one empty chunk`() {
        assertTrue(LoRaFrame.chunk(ByteArray(0)).isEmpty())
        assertNull(LoRaFrame.frames(1u, ByteArray(0)))
    }

    // ============================================================ RECEIVER VALIDATION

    /**
     * `LoRaEnvelopeTest.rejects a frame with no chunk bytes` (`:94-98`).
     *
     * `payload.count > 8`, not `>=` (`MeshtasticManager.swift:1063`). A header alone carries
     * nothing, and accepting it would insert a zero-length part that satisfies the
     * completeness count while contributing no bytes — the message reassembles short and
     * then fails JSON parsing with no indication why.
     */
    @Test
    fun `shipped vector - a frame with no chunk bytes is rejected`() {
        assertNull(LoRaFrame.parse(bytes(0x4F, 0x4D, 0x01, 0, 0, 0, 0x00, 0x01)))
    }

    /** `LoRaEnvelopeTest.rejects wrong magic` (`:100-104`). */
    @Test
    fun `shipped vector - wrong magic is rejected in either byte`() {
        assertNull(LoRaFrame.parse(bytes(0x4F, 0x4E, 0x01, 0, 0, 0, 0x00, 0x01, 0xAA)))
        assertNull(LoRaFrame.parse(bytes(0x00, 0x4D, 0x01, 0, 0, 0, 0x00, 0x01, 0xAA)))
    }

    /** `LoRaEnvelopeTest.rejects total zero, seq out of range and total over forty` (`:106-113`). */
    @Test
    fun `shipped vector - total zero seq out of range and total over forty are rejected`() {
        assertNull("total 0", LoRaFrame.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 0x00, 0xAA)))
        assertNull("seq == total", LoRaFrame.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x02, 0x02, 0xAA)))
        assertNull("total 41", LoRaFrame.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 41, 0xAA)))
        assertNotNull("total 40 is fine", LoRaFrame.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 40, 0xAA)))
    }

    /**
     * `LoRaEnvelopeTest.receiver accepts more chunks than the sender will ever produce`
     * (`:115-120`).
     *
     * Sender caps at 12, receiver at 40 — deliberately asymmetric, because the receiver has
     * to cope with whatever a peer or a future build put on the air.
     */
    @Test
    fun `shipped vector - the receiver cap exceeds the sender cap`() {
        assertTrue(LoRaFrame.MAX_TOTAL > LoRaFrame.MAX_CHUNKS)
        assertEquals(40, LoRaFrame.MAX_TOTAL)
        assertEquals(12, LoRaFrame.MAX_CHUNKS)
    }

    // ============================================================ REASSEMBLY

    private fun framesFor(msgId: UInt, payload: ByteArray) = LoRaFrame.frames(msgId, payload)!!

    @Test
    fun `reassembles a multi-frame payload in order`() {
        val payload = ByteArray(400) { (it % 251).toByte() }
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertNull(r.accept(f[0], 0))
        assertNull(r.accept(f[1], 0))
        assertArrayEquals(payload, r.accept(f[2], 0))
        assertEquals("the entry is removed on completion", 0, r.pendingCount)
    }

    /** Out of order is the normal case on a radio, not an exception. */
    @Test
    fun `reassembles out of order`() {
        val payload = ByteArray(400) { (it % 251).toByte() }
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertNull(r.accept(f[2], 0))
        assertNull(r.accept(f[0], 0))
        assertArrayEquals(payload, r.accept(f[1], 0))
    }

    /**
     * A duplicate `seq` OVERWRITES and does not advance the count
     * (`MeshtasticManager.swift:1071`).
     *
     * LoRa retransmits, so duplicates are ordinary. Counting them would complete a message
     * that is still missing a part, and the concatenation would then hit the hole.
     */
    @Test
    fun `a duplicate seq does not advance the count`() {
        val payload = ByteArray(400) { 9 }
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertNull(r.accept(f[0], 0))
        assertNull("a repeat of frame 0 must not count as frame 1", r.accept(f[0], 0))
        assertNull(r.accept(f[1], 0))
        assertArrayEquals(payload, r.accept(f[2], 0))
    }

    /**
     * `total` is taken from the FIRST frame seen and a later frame cannot resize the entry
     * (`swift:1070`).
     *
     * That is what stops a corrupt or hostile `total` holding a buffer open: the first
     * frame fixes the size, and everything after it is filed into that shape or not at all.
     */
    @Test
    fun `total comes from the first frame and a later frame cannot shrink it`() {
        val r = LoRaReassembler()
        // Frame 0 of 3 opens the entry at total = 3.
        assertNull(r.accept(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 0x03, 0xAA), 0))
        // A later frame for the SAME id claiming total = 1 must NOT complete it: the entry
        // keeps the first frame's size. If `total` were re-read per frame, this single
        // byte would be delivered as a whole message and the other two would be lost.
        assertNull(
            "a shrinking total must not complete the message",
            r.accept(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 0x01, 0xBB), 0),
        )
        assertEquals(1, r.pendingCount)
        // The real remaining frames still finish it, and the overwritten part is the LAST
        // one written for that seq.
        assertNull(r.accept(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x01, 0x03, 0xCC), 0))
        assertArrayEquals(
            bytes(0xBB, 0xCC, 0xDD),
            r.accept(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x02, 0x03, 0xDD), 0),
        )
    }

    /** Two messages in flight do not interfere: the map is keyed by msgId. */
    @Test
    fun `two interleaved messages reassemble independently`() {
        val a = ByteArray(300) { 1 }
        val b = ByteArray(300) { 2 }
        val r = LoRaReassembler()
        val fa = framesFor(1u, a)
        val fb = framesFor(2u, b)
        assertNull(r.accept(fa[0], 0))
        assertNull(r.accept(fb[0], 0))
        assertEquals(2, r.pendingCount)
        assertArrayEquals(b, r.accept(fb[1], 0))
        assertArrayEquals(a, r.accept(fa[1], 0))
        assertEquals(0, r.pendingCount)
    }

    /**
     * A partial older than the TTL is dropped (`swift:1073-1074`).
     *
     * This asserts the SWEEP, not its position. The shipped comment says the sweep runs
     * after the insert so a fresh entry survives its own arrival — that ordering was
     * mutated and every test still passed, because a fresh entry's age is zero and the
     * eviction test is `>= ttlMs`. See [LoRaReassembler]'s class doc: kept for parity,
     * recorded as not load-bearing.
     */
    @Test
    fun `a stale partial is dropped`() {
        val payload = ByteArray(400) { 3 }
        val r = LoRaReassembler(ttlMs = 1_000)
        val f = framesFor(7u, payload)

        assertNull(r.accept(f[0], 0))
        assertEquals(1, r.pendingCount)

        // A frame for a DIFFERENT message, long after: the sweep drops the stale partial
        // and the new entry — inserted first — is still there.
        assertNull(r.accept(framesFor(8u, ByteArray(200))[0], 5_000))
        assertEquals("the stale one is gone, the fresh one is not", 1, r.pendingCount)

        // And the old message can no longer complete.
        assertNull(r.accept(f[1], 5_000))
        assertNull(r.accept(f[2], 5_000))
    }

    /** A frame the parser rejects contributes nothing and does not open an entry. */
    @Test
    fun `a rejected frame does not open a reassembly entry`() {
        val r = LoRaReassembler()
        assertNull(r.accept(bytes(0x00, 0x00, 1, 0, 0, 0, 0x00, 0x01, 0xAA), 0))
        assertEquals(0, r.pendingCount)
    }
}
