package com.oshi.desktop.call.media

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1, video — the whole inbound path, driven the way a call drives it.
 *
 * The sender here is the codec under test used in the emitting direction, which is
 * exactly what [VideoWireTest] refuses to accept as proof of the WIRE FORMAT — that file
 * pins the bytes against fixtures built by hand. What this file tests is the PIPELINE:
 * that a stream of datagrams becomes the right frames, that loss and replay and a missing
 * IDR do what they should, and that nothing decodable is invented.
 */
class VideoReceiveTest {

    private val key = ByteArray(32) { (it * 3 + 2).toByte() }
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
    private val pps = byteArrayOf(0x68, 0x0D.toByte(), 0x2E)

    private fun nal(type: Int, body: ByteArray): ByteArray {
        val len = 1 + body.size
        val out = ByteArray(4 + len)
        out[0] = ((len ushr 24) and 0xFF).toByte(); out[1] = ((len ushr 16) and 0xFF).toByte()
        out[2] = ((len ushr 8) and 0xFF).toByte(); out[3] = (len and 0xFF).toByte()
        out[4] = (type and 0x1F).toByte()
        body.copyInto(out, 5)
        return out
    }

    /** The far end: one frame → its fragments → one sealed `0xF1` envelope each. */
    private class Sender(val key: ByteArray, isCaller: Boolean = true) {
        val salt = VideoMediaFrame.newSalt(isCaller)
        private val seq = MediaSequence()
        var frameId = 0

        fun send(
            slice: ByteArray,
            keyframe: Boolean,
            sps: ByteArray?,
            pps: ByteArray?,
            advanceFrameId: Boolean = true,
        ): List<ByteArray> {
            val packet = VideoFramePacket.encode(keyframe, 0, 1_756_000_000_000L, sps, pps, slice)
            val frags = VideoFragment.fragment(frameId, packet)
            if (advanceFrameId) frameId = (frameId + 1) and 0xFFFF
            return frags.map { VideoMediaFrame.encode(key, salt, seq.next(), it) }
        }
    }

    private fun feed(session: VideoReceiveSession, packets: List<ByteArray>): VideoReceiveSession.Result {
        var last = VideoReceiveSession.Result(false)
        for (p in packets) last = session.onPacket(p)
        return last
    }

    // ============================================================== the happy path

    @Test
    fun `an IDR arrives as an Annex-B access unit with its parameter sets stripped`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)
        val slice = nal(5, ByteArray(20) { 0x33 })

        val out = feed(session, s.send(slice, keyframe = true, sps = sps, pps = pps))

        assertTrue("the IDR should have been delivered", out.delivered)
        assertTrue(out.isKeyFrame)
        assertEquals(1L, session.delivered)
        // Exactly the slice, start-coded — the two parameter-set copies are gone from the body.
        val expected = byteArrayOf(0, 0, 0, 1) + slice.copyOfRange(4, slice.size)
        assertArrayEquals(expected, out.annexB)
    }

    @Test
    fun `a frame split across three datagrams is delivered once, whole`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)
        val big = nal(5, ByteArray(2600) { (it % 251).toByte() })

        val packets = s.send(big, true, sps, pps)
        assertEquals("three fragments", 3, packets.size)
        assertFalse("nothing before the last fragment", session.onPacket(packets[0]).delivered)
        assertFalse(session.onPacket(packets[1]).delivered)
        assertTrue(session.onPacket(packets[2]).delivered)
        assertEquals(1L, session.delivered)
    }

    /** A later P-frame carries no parameter sets; the ones from the IDR have to persist. */
    @Test
    fun `parameter sets from the IDR are remembered for the P-frames after it`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)
        feed(session, s.send(nal(5, ByteArray(8) { 1 }), true, sps, pps))
        val p = feed(session, s.send(nal(1, ByteArray(8) { 2 }), false, null, null))
        assertTrue("a P-frame after an IDR is decodable and must be delivered", p.delivered)
        assertFalse(p.isKeyFrame)
        assertEquals(2L, session.delivered)
    }

    // ============================================================== what it refuses

    /**
     * A P-frame before the first IDR references a picture the receiver never had. iOS
     * gates on the same thing (`swift:270`, `hasDecodedIDR`); the difference is that iOS
     * has a decoder to protect and this has a file.
     */
    @Test
    fun `a P-frame before the first keyframe is dropped and the peer is asked for an IDR`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)
        val out = feed(session, s.send(nal(1, ByteArray(8) { 5 }), false, null, null))
        assertFalse(out.delivered)
        assertTrue("the peer has to be told", out.requestKeyframe)
        assertEquals(1L, session.droppedBeforeIdr)
        assertEquals(0L, session.delivered)
    }

    /**
     * The parameter sets are learned from EVERY packet that carries them, including ones
     * that are then dropped — which is what makes the cache load-bearing rather than
     * decorative. Both phones put SPS/PPS in every packet (`kt:1250-1252`, iOS "ALWAYS
     * include cached SPS/PPS with every packet"), so the stream can hand them over before
     * it hands over anything decodable.
     */
    @Test
    fun `parameter sets learned before the IDR open a stream whose IDR carries none`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)

        val early = feed(session, s.send(nal(1, ByteArray(8) { 1 }), keyframe = false, sps = sps, pps = pps))
        assertFalse("a P-frame before the IDR is still not decodable", early.delivered)

        val idr = feed(session, s.send(nal(5, ByteArray(8) { 2 }), keyframe = true, sps = null, pps = null))
        assertTrue("the IDR is decodable with the parameter sets already learned", idr.delivered)
        assertEquals(1L, session.delivered)
    }

    /** A keyframe with no parameter sets is not decodable either — keep waiting. */
    @Test
    fun `a keyframe without parameter sets does not open the stream`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)
        val out = feed(session, s.send(nal(5, ByteArray(8) { 6 }), true, null, null))
        assertFalse(out.delivered)
        assertEquals(1L, session.droppedBeforeIdr)
    }

    /**
     * Without a throttle a stream that never carries an IDR asks for one on every single
     * datagram — a request storm aimed at a peer that is already struggling.
     */
    @Test
    fun `keyframe requests are throttled, not sent per packet`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key, keyframeRequestIntervalFrames = 3)
        val asked = (0 until 7).count {
            feed(session, s.send(nal(1, ByteArray(4) { 9 }), false, null, null)).requestKeyframe
        }
        assertEquals("one request every three packets, not one per packet", 3, asked)
        assertEquals(7L, session.droppedBeforeIdr)
    }

    @Test
    fun `a replayed datagram is refused after its tag verifies`() {
        val s = Sender(key)
        val session = VideoReceiveSession(key)
        val packets = s.send(nal(5, ByteArray(8) { 1 }), true, sps, pps)
        assertTrue(session.onPacket(packets[0]).delivered)
        assertFalse("the same datagram again", session.onPacket(packets[0]).delivered)
        assertEquals(1L, session.replayed)
        assertEquals(1L, session.delivered)
    }

    @Test
    fun `a datagram sealed under another key is rejected, and nothing is parsed`() {
        val stranger = Sender(ByteArray(32) { 0x5B })
        val session = VideoReceiveSession(key)
        val out = session.onPacket(stranger.send(nal(5, ByteArray(8)), true, sps, pps)[0])
        assertFalse(out.delivered)
        assertNull(out.annexB)
        assertEquals(1L, session.rejected)
        assertEquals("a rejected datagram must not open the stream", 0L, session.delivered)
    }

    @Test
    fun `an audio frame offered to the video path is not video`() {
        val session = VideoReceiveSession(key)
        val audio = CallMediaFrame.encode(key, byteArrayOf(1, 2, 3, 4), true, 1, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
        assertFalse(session.onPacket(audio).delivered)
        assertEquals(1L, session.rejected)
    }

    /** Loss is invisible to a completion callback, so the gap counter is the only signal. */
    @Test
    fun `a frame that loses a fragment is counted as lost, not waited for for ever`() {
        val s = Sender(key)
        var now = 1_000L
        val session = VideoReceiveSession(key, clock = { now })
        feed(session, s.send(nal(5, ByteArray(8) { 1 }), true, sps, pps))

        val torn = s.send(nal(1, ByteArray(2600) { 2 }), false, null, null)
        session.onPacket(torn[0])            // fragment 1 of 3 — the rest never arrive
        feed(session, s.send(nal(1, ByteArray(8) { 3 }), false, null, null))
        // __VIDEO_REORDER_2026_09_23__ the next frame is held for the torn one (it might be
        // reordering) — at most 100 ms, then the torn frame is given up.
        assertEquals(1L, session.delivered)
        now += 100
        val released = session.poll()
        assertTrue(released.delivered)
        assertTrue("the next P-frame references the lost one", released.requestKeyframe)

        assertEquals(2L, session.delivered)
        assertEquals("the torn frame", 1L, session.lostFrames)
        assertEquals(66.66, session.completionRate(), 0.01)
    }

    // ============================================================== loss meter (__VIDEO_ABR_2026_09_23__)

    private fun datagrams(n: Int): List<ByteArray> {
        val s = Sender(key)
        val out = ArrayList<ByteArray>()
        out += s.send(nal(5, ByteArray(8) { 1 }), true, sps, pps)
        while (out.size < n) out += s.send(nal(1, ByteArray(20) { 2 }), false, null, null)
        return out.take(n)
    }

    @Test
    fun `datagram loss is measured from the nonce counter`() {
        val session = VideoReceiveSession(key)
        datagrams(400).forEachIndexed { i, d -> if (i % 10 != 5) session.onPacket(d) }
        val loss = session.takeLossSample()!!
        assertEquals(10.0, loss, 1.0)
    }

    /** Reordering is not loss: a datagram overtaken by a few others still counts as received. */
    @Test
    fun `reordered datagrams are not counted as lost`() {
        val session = VideoReceiveSession(key)
        val d = datagrams(400).toMutableList()
        var i = 3
        while (i + 6 < d.size) { val x = d.removeAt(i); d.add(i + 6, x); i += 20 } // ~5 % held back 6 places
        d.forEach { session.onPacket(it) }
        assertEquals(0.0, session.takeLossSample()!!, 0.001)
    }

    @Test
    fun `too few datagrams say nothing`() {
        val session = VideoReceiveSession(key)
        datagrams(30).forEach { session.onPacket(it) }
        assertNull(session.takeLossSample())
    }

    // ============================================================== the sink

    /**
     * The one thing this client can do with video: write it out in a shape another
     * program can play. Verified by reading the file back, not by trusting the writer.
     */
    @Test
    fun `the file sink writes a playable Annex-B stream, parameter sets first`() {
        val f = File.createTempFile("oshi-video", ".h264")
        f.deleteOnExit()
        val s = Sender(key)
        VideoFileSink(f).use { sink ->
            val session = VideoReceiveSession(key, sink)
            feed(session, s.send(nal(5, ByteArray(16) { 0x41 }), true, sps, pps))
            feed(session, s.send(nal(1, ByteArray(16) { 0x42 }), false, null, null))
        }

        val bytes = f.readBytes()
        val startCode = byteArrayOf(0, 0, 0, 1)
        assertArrayEquals("SPS first, start-coded", startCode + sps, bytes.copyOfRange(0, 8))
        assertArrayEquals("then PPS", startCode + pps, bytes.copyOfRange(8, 15))
        // Four start codes in all: SPS, PPS, the IDR slice, the P slice.
        var found = 0
        for (i in 0..bytes.size - 4) if (bytes.copyOfRange(i, i + 4).contentEquals(startCode)) found++
        assertEquals(4, found)
    }

    /** Nothing is written before the stream can be decoded. */
    @Test
    fun `the sink stays empty until an IDR with parameter sets arrives`() {
        val f = File.createTempFile("oshi-video-empty", ".h264")
        f.deleteOnExit()
        val s = Sender(key)
        VideoFileSink(f).use { sink ->
            val session = VideoReceiveSession(key, sink)
            feed(session, s.send(nal(1, ByteArray(8) { 7 }), false, null, null))
            feed(session, s.send(nal(1, ByteArray(8) { 8 }), false, null, null))
        }
        assertEquals(0L, f.length())
    }

    /** The rotation hint is the receiver's to apply; it has to survive the pipeline. */
    @Test
    fun `the rotation hint reaches the far side of the pipeline`() {
        val session = VideoReceiveSession(key)
        val salt = VideoMediaFrame.newSalt(true)
        val packet = VideoFramePacket.encode(true, 2, 0L, sps, pps, nal(5, ByteArray(8) { 1 }))
        val env = VideoMediaFrame.encode(key, salt, 1, VideoFragment.fragment(0, packet)[0])
        assertTrue(session.onPacket(env).delivered)
        assertEquals(2, session.lastRotationCode)
    }
}
