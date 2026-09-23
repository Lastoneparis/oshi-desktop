package com.oshi.desktop.call.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1, video — the frame packet, the fragment envelope and the `0xF1`
 * envelope's nonce scheme.
 *
 * The wire shapes are pinned as BYTES, and where a fixture is built to check the decoder
 * it is built INDEPENDENTLY — `ByteBuffer` in the same order Android sets, never by
 * calling the encoder under test. A codec checked against its own output agrees with
 * itself and proves nothing.
 */
class VideoWireTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
    private val pps = byteArrayOf(0x68, 0x0D.toByte(), 0x2E)

    /**
     * A frame packet built by hand, the way Android builds one —
     * `ByteBuffer.order(LITTLE_ENDIAN)`, `putLong`, `putShort` (`kt:1284-1312`). Used
     * wherever a fixture has to differ from what our own encoder would produce.
     */
    private fun rawPacket(
        headerSps: ByteArray?,
        headerPps: ByteArray?,
        frameData: ByteArray,
        flags: Byte = 0x01,
        timestampMs: Long = 0L,
    ): ByteArray {
        val sl = headerSps?.size ?: 0
        val pl = headerPps?.size ?: 0
        val buf = ByteBuffer.allocate(1 + 8 + 2 + sl + 2 + pl + frameData.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(flags)
        buf.putLong(timestampMs)
        buf.putShort(sl.toShort()); headerSps?.let { buf.put(it) }
        buf.putShort(pl.toShort()); headerPps?.let { buf.put(it) }
        buf.put(frameData)
        return buf.array()
    }

    /** One AVCC NAL: `[len(4 BE)][type byte][body]`. */
    private fun nal(type: Int, body: ByteArray = ByteArray(3) { 0x5A }): ByteArray {
        val len = 1 + body.size
        val out = ByteArray(4 + len)
        out[0] = ((len ushr 24) and 0xFF).toByte(); out[1] = ((len ushr 16) and 0xFF).toByte()
        out[2] = ((len ushr 8) and 0xFF).toByte(); out[3] = (len and 0xFF).toByte()
        out[4] = (type and 0x1F).toByte()
        body.copyInto(out, 5)
        return out
    }

    // ====================================================== the frame packet, byte for byte

    /**
     * `[flags(1)][timestamp(8 LE)][spsLen(2 LE)][sps][ppsLen(2 LE)][pps][frameData]`.
     *
     * Pinned as bytes rather than round-tripped, because the endianness is the whole
     * question: this packet is little-endian while everything around it (`frame_id`, the
     * envelope sequence, the relay envelope) is big-endian.
     */
    @Test
    fun `frame packet header is little-endian, field by field`() {
        val frame = nal(5)
        val bytes = VideoFramePacket.encode(
            isKeyFrame = true, rotationCode = 0, timestampMs = 0x0102030405060708L,
            sps = sps, pps = pps, avccFrame = frame,
        )

        assertEquals("keyframe bit", 0x01.toByte(), bytes[0])
        // timestamp: least significant byte FIRST
        assertArrayEquals(
            byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01),
            bytes.copyOfRange(1, 9),
        )
        assertEquals("spsLen low byte", 4.toByte(), bytes[9])
        assertEquals("spsLen high byte", 0.toByte(), bytes[10])
        assertArrayEquals(sps, bytes.copyOfRange(11, 15))
        assertEquals("ppsLen low byte", 3.toByte(), bytes[15])
        assertEquals("ppsLen high byte", 0.toByte(), bytes[16])
        assertArrayEquals(pps, bytes.copyOfRange(17, 20))
    }

    /**
     * A 256-byte parameter set is the one that catches a big-endian length: it is
     * `00 01` one way and `01 00` the other.
     */
    @Test
    fun `a 256-byte parameter set proves the length is not big-endian`() {
        val big = ByteArray(256) { 0x67 }
        val bytes = VideoFramePacket.encode(true, 0, 0L, big, pps, nal(5))
        assertEquals(0x00.toByte(), bytes[9])
        assertEquals(0x01.toByte(), bytes[10])
        assertEquals(256, VideoFramePacket.decode(bytes)!!.sps!!.size)
    }

    /** bit 0 = keyframe, bits 1-2 = rotation. `swift:2327`, `kt:1303`. */
    @Test
    fun `flags byte carries the keyframe bit and the rotation code`() {
        for (rot in 0..3) {
            val k = VideoFramePacket.encode(true, rot, 0, null, null, nal(5))
            val p = VideoFramePacket.encode(false, rot, 0, null, null, nal(1))
            assertEquals(((rot shl 1) or 1).toByte(), k[0])
            assertEquals((rot shl 1).toByte(), p[0])
            assertTrue(VideoFramePacket.decode(k)!!.isKeyFrame)
            assertFalse(VideoFramePacket.decode(p)!!.isKeyFrame)
            assertEquals(rot, VideoFramePacket.decode(k)!!.rotationCode)
        }
    }

    /** A rotation code that does not fit two bits would silently corrupt the flags byte. */
    @Test
    fun `a rotation code outside 0-3 is refused, not truncated`() {
        val e = runCatching { VideoFramePacket.encode(true, 4, 0, null, null, nal(5)) }.exceptionOrNull()
        assertTrue("expected an IllegalArgumentException, got $e", e is IllegalArgumentException)
    }

    /**
     * The decoder reads a packet this test builds the way ANDROID builds it —
     * `ByteBuffer.order(LITTLE_ENDIAN)`, `putLong`, `putShort` (`kt:1284-1312`) — so
     * agreement is with the shipped emitter rather than with our own encoder.
     */
    @Test
    fun `a packet built the way Android builds it decodes here`() {
        val frame = nal(5)
        val enriched = VideoFramePacket.enrich(sps, pps, frame)
        val buf = ByteBuffer.allocate(1 + 8 + 2 + sps.size + 2 + pps.size + enriched.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x01.toByte())
        buf.putLong(1_756_000_000_000L)
        buf.putShort(sps.size.toShort()); buf.put(sps)
        buf.putShort(pps.size.toShort()); buf.put(pps)
        buf.put(enriched)

        val parsed = VideoFramePacket.decode(buf.array())!!
        assertTrue(parsed.isKeyFrame)
        assertEquals(1_756_000_000_000L, parsed.timestampMs)
        assertArrayEquals(sps, parsed.sps)
        assertArrayEquals(pps, parsed.pps)
        assertArrayEquals(enriched, parsed.frameData)
    }

    /** The parameter sets ride twice: the 2-byte LE header fields AND AVCC inside the frame. */
    @Test
    fun `the parameter sets are carried twice, in two different length encodings`() {
        val bytes = VideoFramePacket.encode(true, 0, 0, sps, pps, nal(5))
        val parsed = VideoFramePacket.decode(bytes)!!
        assertArrayEquals("header copy", sps, parsed.sps)
        val (embSps, embPps) = VideoFramePacket.embeddedParameterSets(parsed.frameData)
        assertArrayEquals("embedded copy", sps, embSps)
        assertArrayEquals(pps, embPps)
        // 4-byte BIG-endian length in front of the embedded copy, not the header's 2-byte LE
        assertArrayEquals(byteArrayOf(0, 0, 0, 4), parsed.frameData.copyOfRange(0, 4))
    }

    /** Embedded beats header beats cached — iOS's own order (`swift:2665-2670`). */
    @Test
    fun `embedded parameter sets outrank the header copy and the cache`() {
        val embeddedSps = byteArrayOf(0x67, 0x64, 0x00, 0x28)
        val headerSps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val cachedSps = byteArrayOf(0x67, 0x00)

        // Built by hand, because [VideoFramePacket.encode] is CONSISTENT — it writes the
        // same parameter sets into both places, so it cannot produce the packet a peer
        // whose cache and encoder disagree emits, which is the packet the priority order
        // exists for.
        val disagreeing = rawPacket(headerSps, pps, VideoFramePacket.enrich(embeddedSps, pps, nal(5)))
        val (best, _) = VideoFramePacket.bestParameterSets(VideoFramePacket.decode(disagreeing)!!, cachedSps, pps)
        assertArrayEquals(embeddedSps, best)

        // With nothing embedded, the header wins; with neither, the cache does.
        val noEmbed = rawPacket(headerSps, pps, nal(5))
        assertArrayEquals(headerSps, VideoFramePacket.bestParameterSets(VideoFramePacket.decode(noEmbed)!!, cachedSps, pps).first)
        val bare = rawPacket(null, null, nal(1))
        assertArrayEquals(cachedSps, VideoFramePacket.bestParameterSets(VideoFramePacket.decode(bare)!!, cachedSps, pps).first)
    }

    /**
     * **Stricter than iOS.** iOS reads a `spsLen` that runs past the buffer, leaves the
     * SPS nil and DOES NOT ADVANCE the offset (`swift:2637-2641`), so it goes on to read
     * the SPS bytes as the PPS length and produces a plausible frame out of a corrupt
     * packet. Here the packet fails.
     */
    @Test
    fun `a parameter-set length that runs past the end fails the packet`() {
        val bytes = VideoFramePacket.encode(true, 0, 0, sps, pps, nal(5))
        // spsLen = 0x7FFF, far past the end
        bytes[9] = 0xFF.toByte(); bytes[10] = 0x7F
        assertNull(VideoFramePacket.decode(bytes))

        // The same lie, in the shape where tolerating it is NOT caught downstream. The
        // first fixture is killed by the PPS guard whichever way the SPS one behaves —
        // skipping the SPS bytes makes 0x67,0x42 the next length and that overruns too.
        // Here the SPS begins 00 00, so a tolerated overrun reads ppsLen = 0 and parses
        // cleanly into a plausible frame built out of a corrupt packet. Only the SPS
        // guard can refuse this one.
        val quiet = rawPacket(byteArrayOf(0x00, 0x00, 0x67, 0x42), pps, nal(5))
        quiet[9] = 0xFF.toByte(); quiet[10] = 0x7F
        assertNull("a lie the next field cannot catch", VideoFramePacket.decode(quiet))
    }

    /** 13 bytes is a header with no frame behind it. iOS: `guard count > 13`. */
    @Test
    fun `a header with no frame data is not a frame`() {
        assertNull(VideoFramePacket.decode(ByteArray(13)))
        assertNull(VideoFramePacket.decode(ByteArray(0)))
        assertNull(VideoFramePacket.decode(ByteArray(12)))
    }

    // ====================================================== AVCC

    /** Types 1..5 are pictures; 6/7/8 are not, and are skipped rather than reported. */
    @Test
    fun `the first NAL type is the first SLICE, not the first NAL`() {
        val stream = nal(7) + nal(8) + nal(6) + nal(5) + nal(1)
        assertEquals(5, VideoFramePacket.firstNalType(stream))
        assertTrue(VideoFramePacket.isIdr(stream))
        assertEquals(1, VideoFramePacket.firstNalType(nal(7) + nal(1)))
        assertFalse(VideoFramePacket.isIdr(nal(7) + nal(1)))
        assertEquals("no slice at all", -1, VideoFramePacket.firstNalType(nal(7) + nal(8)))
    }

    /** SEI (6) survives the strip. iOS calls that out deliberately (`swift:2676-2677`). */
    @Test
    fun `stripping parameter sets keeps SEI`() {
        val stripped = VideoFramePacket.stripParameterSets(nal(7) + nal(6) + nal(8) + nal(5))
        val types = ArrayList<Int>()
        var o = 0
        while (o + 4 <= stripped.size) {
            var len = 0
            for (i in 0 until 4) len = (len shl 8) or (stripped[o + i].toInt() and 0xFF)
            types.add(stripped[o + 4].toInt() and 0x1F)
            o += 4 + len
        }
        assertEquals(listOf(6, 5), types)
    }

    /** Every 4-byte length becomes `00 00 00 01`, and the payload is untouched. */
    @Test
    fun `Annex-B conversion replaces the length prefix with a start code`() {
        val body = byteArrayOf(0x11, 0x22, 0x33)
        val annexB = VideoFramePacket.toAnnexB(nal(5, body))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x05, 0x11, 0x22, 0x33), annexB)
    }

    /**
     * A frame that lost its last fragment ends mid-NAL. The walk has to stop, not throw:
     * this is the DOMINANT loss shape on UDP, not an exotic one.
     */
    @Test
    fun `a truncated tail ends the NAL walk instead of throwing`() {
        val whole = nal(5) + nal(1)
        val torn = whole.copyOfRange(0, whole.size - 2)
        assertEquals(5, VideoFramePacket.firstNalType(torn))
        val annexB = VideoFramePacket.toAnnexB(torn)
        assertEquals("only the intact NAL survives", nal(5).size, annexB.size)
    }

    @Test
    fun `an overflowing AVCC length is rejected before it can index the frame`() {
        // 0x7fffffff made `o + 4 + len` wrap negative in the old guard. There is no
        // payload after this header, so every consumer must return its empty answer.
        val hostile = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        assertEquals(-1, VideoFramePacket.firstNalType(hostile))
        assertTrue(VideoFramePacket.embeddedParameterSets(hostile).first == null)
        assertTrue(VideoFramePacket.toAnnexB(hostile).isEmpty())
        assertTrue(VideoFramePacket.stripParameterSets(hostile).isEmpty())
    }

    // ====================================================== fragments

    /** `[0x01][frame_id(2 BE)][idx][total][payload]` — `swift:2404-2418`, `kt:1336-1344`. */
    @Test
    fun `the fragment header is pinned, and frame_id is BIG-endian`() {
        val frags = VideoFragment.fragment(0x0102, ByteArray(10) { 0x7E })
        assertEquals(1, frags.size)
        val f = frags[0]
        assertEquals(0x01.toByte(), f[0])
        assertEquals("frame_id high", 0x01.toByte(), f[1])
        assertEquals("frame_id low", 0x02.toByte(), f[2])
        assertEquals(0.toByte(), f[3])
        assertEquals("a frame that fits is total=1, not unfragmented", 1.toByte(), f[4])
        assertEquals(15, f.size)
    }

    @Test
    fun `a frame one byte over the payload budget becomes two fragments`() {
        val packet = ByteArray(VideoFragment.MAX_PAYLOAD + 1) { (it % 251).toByte() }
        val frags = VideoFragment.fragment(7, packet)
        assertEquals(2, frags.size)
        assertEquals(VideoFragment.HEADER_SIZE + VideoFragment.MAX_PAYLOAD, frags[0].size)
        assertEquals(VideoFragment.HEADER_SIZE + 1, frags[1].size)
        assertEquals(2.toByte(), frags[0][4])
        assertEquals(1.toByte(), frags[1][3])
    }

    /**
     * **The 255-fragment cliff, refused.** Android clamps the count and loops to it, so
     * every byte past 280,500 is dropped, the receiver sees a complete frame and hands a
     * truncated access unit to the decoder (`kt:1332-1345`). iOS refuses the frame
     * instead (`swift:2385-2398`) and so does this.
     */
    @Test
    fun `a frame too big for a one-byte fragment count is refused, never truncated`() {
        val ok = ByteArray(VideoFragment.MAX_FRAME_BYTES) { 1 }
        assertEquals(255, VideoFragment.fragment(1, ok).size)

        val tooBig = ByteArray(VideoFragment.MAX_FRAME_BYTES + 1) { 1 }
        val e = runCatching { VideoFragment.fragment(1, tooBig) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        assertTrue("the message has to name the ceiling: ${e!!.message}", e.message!!.contains("255"))
    }

    @Test
    fun `a header that contradicts itself is not a fragment`() {
        assertNull("bad magic", VideoFragment.parse(byteArrayOf(0x02, 0, 1, 0, 1, 0x55)))
        assertNull("total 0", VideoFragment.parse(byteArrayOf(0x01, 0, 1, 0, 0, 0x55)))
        assertNull("idx past total", VideoFragment.parse(byteArrayOf(0x01, 0, 1, 3, 2, 0x55)))
        assertNull("no payload", VideoFragment.parse(byteArrayOf(0x01, 0, 1, 0, 1)))
    }

    // ====================================================== reassembly

    @Test
    fun `fragments arriving out of order still reassemble in order`() {
        val packet = ByteArray(2500) { (it % 251).toByte() }
        val frags = VideoFragment.fragment(9, packet)
        assertEquals(3, frags.size)
        val r = VideoReassembler()
        assertEquals(VideoReassembler.Reason.BUFFERED, r.offer(frags[2]).reason)
        assertEquals(VideoReassembler.Reason.BUFFERED, r.offer(frags[0]).reason)
        val out = r.offer(frags[1])
        assertEquals(VideoReassembler.Reason.COMPLETE, out.reason)
        assertArrayEquals(packet, out.frame)
        assertEquals(0, r.pending())
    }

    @Test
    fun `a duplicated fragment does not count twice`() {
        val frags = VideoFragment.fragment(1, ByteArray(2 * VideoFragment.MAX_PAYLOAD) { 3 })
        val r = VideoReassembler()
        r.offer(frags[0]); r.offer(frags[0]); r.offer(frags[0])
        assertEquals(VideoReassembler.Reason.COMPLETE, r.offer(frags[1]).reason)
    }

    /** Circular distance, so a `frame_id` wrap is not read as 65535 frames of lateness. */
    @Test
    fun `lateness is measured on the circle, not on the line`() {
        val r = VideoReassembler()
        r.offer(VideoFragment.fragment(65535, ByteArray(4))[0])
        // id 2 is NEWER than 65535 across the wrap
        assertEquals(VideoReassembler.Reason.COMPLETE, r.offer(VideoFragment.fragment(2, ByteArray(4))[0]).reason)
        // id 65534 is older
        assertEquals(VideoReassembler.Reason.LATE, r.offer(VideoFragment.fragment(65534, ByteArray(4))[0]).reason)
    }

    /**
     * **A divergence, on purpose.** Both phones test `dist > 32768`, and `dist == 0` is
     * the id they just finished — so a repeated single-fragment frame is decoded twice
     * there and refused here.
     */
    @Test
    fun `a repeat of the frame just completed is refused`() {
        val r = VideoReassembler()
        val f = VideoFragment.fragment(4, ByteArray(8) { 9 })[0]
        assertEquals(VideoReassembler.Reason.COMPLETE, r.offer(f).reason)
        assertEquals(VideoReassembler.Reason.LATE, r.offer(f).reason)
        assertEquals(1L, r.completedFrames)
    }

    @Test
    fun `two different totals for one frame_id drop the whole entry`() {
        val r = VideoReassembler()
        r.offer(byteArrayOf(0x01, 0, 5, 0, 3, 0x11))
        val out = r.offer(byteArrayOf(0x01, 0, 5, 1, 4, 0x22))
        assertEquals(VideoReassembler.Reason.TOTAL_MISMATCH, out.reason)
        assertEquals(0, r.pending())
    }

    /**
     * An in-flight frame that a newer one overtakes is unrecoverable, so it goes — and the
     * peer has to be asked for an IDR, because our reference frame just died.
     */
    @Test
    fun `a newer frame abandons an older in-flight one and asks the peer for a keyframe`() {
        val old = VideoFragment.fragment(10, ByteArray(2200) { 1 })
        val new = VideoFragment.fragment(11, ByteArray(2200) { 2 })
        val r = VideoReassembler()
        assertFalse(r.offer(old[0]).requestKeyframe)
        val out = r.offer(new[0])
        assertTrue("the abandoned frame must produce a keyframe request", out.requestKeyframe)
        assertEquals(1, r.pending())
    }

    /**
     * A frame lost WHOLE leaves nothing in flight to abandon — only the gap in frame ids
     * shows it. That gap must ask for an IDR too (iOS `noteFrameCompleted`), or the decoder
     * runs on a broken reference until the peer's next periodic keyframe.
     */
    @Test
    fun `a frame lost whole is seen from the gap and asks the peer for a keyframe`() {
        val r = VideoReassembler()
        assertFalse(r.offer(VideoFragment.fragment(20, ByteArray(300) { 1 })[0]).requestKeyframe)
        // frame 21 (one fragment) never arrives
        val out = r.offer(VideoFragment.fragment(22, ByteArray(300) { 3 })[0])
        assertEquals(VideoReassembler.Reason.COMPLETE, out.reason)
        assertTrue("a gap in frame ids is a lost reference", out.requestKeyframe)
        assertEquals(1L, r.lostFrames)
        assertFalse("the next in-order frame is healthy",
            r.offer(VideoFragment.fragment(23, ByteArray(300) { 4 })[0]).requestKeyframe)
    }

    /** Every datagram a full fragment makes must fit 1200 B even after the `:8089` relay framing. */
    @Test
    fun `a full fragment fits 1200 B through the relay, upstream and down`() {
        val env = VideoMediaFrame.HEADER_SIZE + 12 + 16 + VideoFragment.HEADER_SIZE + VideoFragment.MAX_PAYLOAD
        val relayUp = 1 + 1 + 44 + 1 + 44 + 1 + 36   // [t][len][recip][len][sender][len][callId]
        val relayDown = 1 + 1 + 44 + 1 + 36            // [t][len][sender][len][callId]
        assertTrue("relay upstream ${env + relayUp} B", env + relayUp <= 1200)
        assertTrue("relay downstream ${env + relayDown} B (server UDP_SAFE_DATAGRAM)", env + relayDown <= 1200)
        assertTrue("IPv6 packet ${env + relayUp + 48} B within the 1280 B minimum MTU", env + relayUp + 48 <= 1280)
    }

    @Test
    fun `decreasing unfinished frame ids cannot grow reassembly without bound`() {
        val r = VideoReassembler()
        // Every id is older than its predecessor on the circular sequence, so the normal
        // newer-frame eviction deliberately cannot apply. Before the cap this grew to all
        // 65,536 ids, each retaining an array sized by the peer-controlled total field.
        for (id in 40 downTo 9) {
            assertEquals(VideoReassembler.Reason.BUFFERED,
                r.offer(byteArrayOf(0x01, 0, id.toByte(), 0, 2, 0x55)).reason)
        }
        assertEquals(32, r.pending())
        val capped = r.offer(byteArrayOf(0x01, 0, 8, 0, 2, 0x55))
        assertEquals(VideoReassembler.Reason.BUFFERED, capped.reason)
        assertTrue("eviction must ask the peer for a recoverable keyframe", capped.requestKeyframe)
        assertEquals("the 33rd unfinished frame replaces the oldest, never grows the map", 32, r.pending())
    }

    /** Fragment loss never fires a completion callback, so it is counted from the gaps. */
    @Test
    fun `frames that never complete are counted from the frame_id gaps`() {
        val r = VideoReassembler()
        r.offer(VideoFragment.fragment(1, ByteArray(4))[0])
        r.offer(VideoFragment.fragment(5, ByteArray(4))[0])
        assertEquals(2L, r.completedFrames)
        assertEquals("2, 3 and 4 never completed", 3L, r.lostFrames)
        assertEquals(40.0, r.completionRate(), 0.001)
    }

    // ====================================================== the 0xF1 envelope

    private val key = ByteArray(32) { (it * 5 + 1).toByte() }

    @Test
    fun `the envelope round-trips and its type byte is 0xF1`() {
        val salt = VideoMediaFrame.newSalt(isCaller = true)
        val frag = VideoFragment.fragment(3, ByteArray(50) { 0x2A })[0]
        val env = VideoMediaFrame.encode(key, salt, 1, frag)
        assertEquals(0xF1.toByte(), env[0])
        val opened = VideoMediaFrame.decode(key, env)!!
        assertArrayEquals(frag, opened.fragment)
        assertEquals(1L, opened.counter)
        assertTrue(opened.hasVideoDomainBit)
    }

    /**
     * **iOS writes this field big-endian and Android writes it little-endian**
     * (`swift:11139` vs `kt:4923-4945`). We take iOS's side, and this pins which one.
     */
    @Test
    fun `the envelope sequence is big-endian, which is iOS's side of a live disagreement`() {
        val env = VideoMediaFrame.encode(key, VideoMediaFrame.newSalt(true), 1, ByteArray(20))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1), env.copyOfRange(1, 9))
        // Read the same bytes the way Android writes them and the number is not 1.
        var le = 0L
        for (i in 0 until 8) le = le or ((env[1 + i].toLong() and 0xFF) shl (i * 8))
        assertNotEquals(1L, le)
    }

    /** `salt(4) ‖ (counter | 1<<63)(8 BE)` — `swift:345-357`. */
    @Test
    fun `the nonce is the salt then the counter with the video domain bit set`() {
        val salt = byteArrayOf(0x80.toByte(), 0x11, 0x22, 0x33)
        val n = VideoMediaFrame.nonce(salt, 2)
        assertArrayEquals(salt, n.copyOfRange(0, 4))
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 2),
            n.copyOfRange(4, 12),
        )
    }

    /**
     * **The shipped video nonce-reuse bug, pinned.** Without the direction bit both ends
     * start at counter 0 under ONE key and emit bit-identical nonces — the XOR of the two
     * plaintexts leaks and the GHASH subkey falls out of the pair (`swift:276-292`).
     */
    @Test
    fun `caller and callee can never collide at the same counter`() {
        repeat(64) {
            val caller = VideoMediaFrame.newSalt(isCaller = true)
            val callee = VideoMediaFrame.newSalt(isCaller = false)
            assertEquals("caller direction bit", 0x80, caller[0].toInt() and 0x80)
            assertEquals("callee direction bit", 0x00, callee[0].toInt() and 0x80)
            for (counter in 1L..3L) {
                assertFalse(
                    "identical nonce across directions at counter $counter",
                    VideoMediaFrame.nonce(caller, counter).contentEquals(VideoMediaFrame.nonce(callee, counter)),
                )
            }
        }
    }

    /**
     * Audio and video share the session key, so their nonce spaces must not touch. The
     * domain bit makes that true even when the two salts are the same four bytes.
     */
    @Test
    fun `the domain bit keeps video nonces off the audio path even under one salt`() {
        val salt = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        for (counter in 1L..1000L) {
            assertFalse(
                "video nonce collided with the audio nonce at counter $counter",
                VideoMediaFrame.nonce(salt, counter).contentEquals(CallMediaFrame.nonce(salt, counter)),
            )
        }
    }

    /** A counter of 0 is the all-zero counter field the salt exists to avoid reusing. */
    @Test
    fun `a zero counter is refused`() {
        val e = runCatching { VideoMediaFrame.nonce(ByteArray(4), 0) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `a tampered envelope opens as nothing`() {
        val env = VideoMediaFrame.encode(key, VideoMediaFrame.newSalt(true), 1, ByteArray(40) { 7 })
        val flipped = env.copyOf()
        flipped[flipped.size - 3] = (flipped[flipped.size - 3].toInt() xor 0x01).toByte()
        assertNull(VideoMediaFrame.decode(key, flipped))
        assertNull("wrong key", VideoMediaFrame.decode(ByteArray(32), env))
        val wrongType = env.copyOf(); wrongType[0] = 0x15
        assertNull("audio type byte", VideoMediaFrame.decode(key, wrongType))
        assertNull("too short", VideoMediaFrame.decode(key, ByteArray(36)))
    }

    /**
     * A peer old enough to emit `[counter LE(8)][0×4]` still decrypts: the receiver takes
     * the nonce off the wire and never derives it. The domain bit is REPORTED, not
     * required — requiring it would drop that peer's entire stream.
     */
    @Test
    fun `a peer using the pre-fix nonce layout still decrypts`() {
        val legacyNonce = ByteArray(12)
        legacyNonce[0] = 1 // counter 1, little-endian, then four zero bytes
        val body = com.oshi.messenger.network.v2.OSHICryptoV2.aesGcmSeal(key, legacyNonce, ByteArray(30) { 4 }, ByteArray(0))
        val env = ByteArray(9 + 12 + body.size)
        env[0] = 0xF1.toByte()
        legacyNonce.copyInto(env, 9)
        body.copyInto(env, 21)

        val opened = VideoMediaFrame.decode(key, env)!!
        assertEquals(30, opened.fragment.size)
        assertFalse("the old layout has no domain bit", opened.hasVideoDomainBit)
    }

    // ====================================================== replay

    @Test
    fun `a nonce is accepted once and never again`() {
        val w = NonceReplayWindow()
        val n = ByteArray(12) { it.toByte() }
        assertTrue(w.accept(n))
        assertFalse(w.accept(n.copyOf()))
        assertEquals(1L, w.drops)
    }

    /**
     * The window is bounded, and rolling it is what lets an old-build peer that restarts
     * its counter mid-call recover. Costs at most [NonceReplayWindow.MAX] fragments.
     */
    @Test
    fun `the window forgets its oldest nonce once it is full`() {
        val w = NonceReplayWindow(max = 4)
        val nonces = (0 until 5).map { i -> ByteArray(12) { i.toByte() } }
        nonces.forEach { assertTrue(w.accept(it)) }
        assertTrue("the oldest has been forgotten", w.accept(nonces[0].copyOf()))
        assertFalse("the newest is still remembered", w.accept(nonces[4].copyOf()))
    }
}
