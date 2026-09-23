package com.oshi.desktop.call.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1-c — the video control lane.
 *
 * Three of these five packet types are UNAUTHENTICATED on both phones, so half of what
 * this file pins is what an unauthenticated packet is NOT allowed to do here.
 */
class VideoControlTest {

    private val key = ByteArray(32) { (it * 11 + 3).toByte() }
    private val salt = byteArrayOf(0x21, 0x43, 0x65, 0x07)

    private class Clock(var now: Long = 10_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    // ============================================================== the cleartext toggles

    /** `[type][timestamp 8 BE]` — nine bytes, no nonce, no tag (`kt:5249-5252`). */
    @Test
    fun `a toggle is nine bytes, type then a big-endian millisecond timestamp`() {
        val bytes = VideoControl.encodeToggle(VideoControl.VIDEO_PAUSED, 0x0102030405060708L)
        assertEquals(9, bytes.size)
        assertEquals(0x0C.toByte(), bytes[0])
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            bytes.copyOfRange(1, 9),
        )
        val back = VideoControl.decodeToggle(bytes)!!
        assertEquals(VideoControl.VIDEO_PAUSED, back.type)
        assertEquals(0x0102030405060708L, back.timestampMs)
    }

    /** Nine bytes exactly. A longer packet with the same first byte is a media frame. */
    @Test
    fun `only a nine-byte packet is a toggle`() {
        assertNull(VideoControl.decodeToggle(ByteArray(10) { if (it == 0) 0x0C else 0 }))
        assertNull(VideoControl.decodeToggle(ByteArray(8)))
        assertNull("0x0E is sealed, not a toggle", VideoControl.decodeToggle(ByteArray(9) { if (it == 0) 0x0E else 0 }))
        assertNull("audio", VideoControl.decodeToggle(ByteArray(9) { if (it == 0) 0x15 else 0 }))
    }

    /** A camera indicator is the ONLY thing an unauthenticated packet may move. */
    @Test
    fun `a pause toggle moves the camera indicator and nothing else`() {
        val s = VideoControlSession(Clock())
        assertTrue(s.onToggle(VideoControl.Toggle(VideoControl.VIDEO_PAUSED, 1)).isEmpty())
        assertTrue(s.remoteCameraPaused)
        assertFalse("no call state may follow from a cleartext packet", s.videoActive)
        assertFalse(s.upgradeRequested)
        assertFalse(s.awaitingUpgrade)
    }

    /**
     * A peer that resumed its camera is sending P-frames against a picture we no longer
     * have, so the IDR has to be ASKED FOR — and the ask has to be visible leaving.
     */
    @Test
    fun `a resume clears the indicator and asks the peer for a keyframe`() {
        val s = VideoControlSession(Clock())
        s.onToggle(VideoControl.Toggle(VideoControl.VIDEO_PAUSED, 1))
        val out = s.onToggle(VideoControl.Toggle(VideoControl.VIDEO_RESUMED, 2))
        assertFalse(s.remoteCameraPaused)
        assertEquals(1, out.size)
        assertEquals(0x0B.toByte(), out[0][0])
    }

    // ============================================================== the keyframe buckets

    /**
     * Media channel: one token per 500 ms, burst 2 — ≤ 2/s (__CALL_VIDEO_SIGNAL_2026_09_23__;
     * was iOS's 350 ms / burst 3 ≈ 170/min). The signal-lane copy is ≤ 1/s on top.
     */
    @Test
    fun `outbound keyframe requests are at most two a second`() {
        val clock = Clock()
        val s = VideoControlSession(clock)
        assertEquals(1, s.requestKeyframe().size)
        assertEquals(1, s.requestKeyframe().size)
        assertEquals("the third in the same millisecond", 0, s.requestKeyframe().size)
        assertEquals(1L, s.outboundKeyframeSwallowed)

        clock.now += 499
        assertEquals(0, s.requestKeyframe().size)
        clock.now += 1
        assertEquals(1, s.requestKeyframe().size)
    }

    /**
     * **The shipped amplification vector, refused.** Both phones act on every inbound
     * `0x0B` with NO rate limit (`kt:3172-3178`), so a spoofed cleartext flood holds the
     * victim's encoder at keyframes and multiplies their uplink for as long as it lasts.
     * The bucket both clients have is on the SENDING side only.
     */
    @Test
    fun `an inbound keyframe flood is dropped by a bucket of its own`() {
        val clock = Clock()
        val s = VideoControlSession(clock)
        repeat(50) { s.onToggle(VideoControl.Toggle(VideoControl.KEYFRAME_REQUEST, 1)) }
        assertEquals("one, then nothing in the same 500 ms", 1L, s.inboundKeyframeRequests)
        assertEquals(49L, s.inboundKeyframeFloodDrops)
    }

    /** __VIDEO_ABR_2026_09_23__ ~2/s: a request every 500 ms is acted on, a faster one is not. */
    @Test
    fun `inbound keyframe requests are admitted at two a second`() {
        val clock = Clock()
        val s = VideoControlSession(clock)
        repeat(10) {
            s.onToggle(VideoControl.Toggle(VideoControl.KEYFRAME_REQUEST, 1))
            clock.now += 250
        }
        assertEquals(5L, s.inboundKeyframeRequests)
    }

    /** An admitted request produces no PACKET: the answer is an IDR from the encoder (CallVideoSession). */
    @Test
    fun `an inbound keyframe request is answered with no packet`() {
        val s = VideoControlSession(Clock())
        assertTrue(s.onToggle(VideoControl.Toggle(VideoControl.KEYFRAME_REQUEST, 1)).isEmpty())
        assertEquals(1L, s.inboundKeyframeRequests)
    }

    // ============================================================== the sealed upgrades

    @Test
    fun `an upgrade packet is a media envelope with one sealed byte`() {
        val p = VideoControl.encodeUpgrade(key, salt, true, 7, VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_REQUEST)
        assertEquals(0x0E.toByte(), p[0])
        val u = VideoControl.decodeUpgrade(key, p)!!
        assertEquals(VideoControl.VIDEO_UPGRADE, u.type)
        assertEquals(VideoControl.VUPG_REQUEST, u.code)
        assertEquals(7L, u.seq)
    }

    @Test
    fun `an upgrade sealed under another key, or with a longer body, is not one`() {
        val p = VideoControl.encodeUpgrade(key, salt, true, 1, VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_ACCEPT)
        assertNull(VideoControl.decodeUpgrade(ByteArray(32), p))
        val twoBytes = CallMediaFrame.encode(key, salt, true, 2, VideoControl.VIDEO_UPGRADE, byteArrayOf(1, 2))
        assertNull("a two-byte body is not a sub-opcode", VideoControl.decodeUpgrade(key, twoBytes))
        val audio = CallMediaFrame.encode(key, salt, true, 3, CallMediaFrame.TYPE_PCM_48K, ByteArray(64))
        assertNull(VideoControl.decodeUpgrade(key, audio))
    }

    /**
     * The counter is the SHARED audio one. A private counter would put a second packet
     * under the same (key, salt, counter) as an audio frame — the nonce reuse the whole
     * scheme exists to prevent (`kt:5413-5415`).
     */
    @Test
    fun `an upgrade nonce is the audio nonce at that counter, because the counter is shared`() {
        val p = VideoControl.encodeUpgrade(key, salt, true, 42, VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_STOP)
        assertArrayEquals(CallMediaFrame.nonce(salt, 42), p.copyOfRange(9, 21))
    }

    /**
     * **Watch-only, never plain accept.** `0x02` claims a camera this platform does not
     * have, and the peer would wait in front of a black rectangle for frames that cannot
     * exist.
     */
    @Test
    fun `an upgrade request is accepted as receive-only`() {
        val s = VideoControlSession(Clock())
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_REQUEST, 1))
        assertTrue(s.upgradeRequested)
        assertEquals(VideoControl.VUPG_ACCEPT_WATCH, s.acceptUpgradeAsWatcher())
        assertTrue(s.videoActive)
        assertFalse(s.upgradeRequested)
        assertNull("nothing pending any more", s.acceptUpgradeAsWatcher())
    }

    @Test
    fun `an upgrade request is never answered on its own`() {
        val s = VideoControlSession(Clock())
        val out = s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_REQUEST, 1))
        assertTrue("a person decides, not the receive loop", out.isEmpty())
        assertTrue(s.upgradeRequested)
        assertFalse(s.videoActive)
    }

    @Test
    fun `a peer that accepts watch-only is remembered as sending nothing`() {
        val s = VideoControlSession(Clock())
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_ACCEPT_WATCH, 1))
        assertTrue(s.videoActive)
        assertTrue("no frames will arrive from them", s.peerWatchOnly)

        val other = VideoControlSession(Clock())
        other.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_ACCEPT, 1))
        assertTrue(other.videoActive)
        assertFalse(other.peerWatchOnly)
    }

    @Test
    fun `stop clears every piece of video state, by either spelling`() {
        for (stop in listOf(
            VideoControl.Upgrade(VideoControl.VIDEO_STOP, VideoControl.VUPG_REQUEST, 2),
            VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_STOP, 2),
        )) {
            val s = VideoControlSession(Clock())
            s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_ACCEPT_WATCH, 1))
            s.onToggle(VideoControl.Toggle(VideoControl.VIDEO_PAUSED, 1))
            assertTrue(s.videoActive)

            s.onUpgrade(stop)
            assertFalse(s.videoActive)
            assertFalse(s.peerWatchOnly)
            assertFalse(s.remoteCameraPaused)
            assertFalse(s.upgradeRequested)
        }
    }

    @Test
    fun `camera on and off ride the sealed lane too`() {
        val s = VideoControlSession(Clock())
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_CAMERA_OFF, 1))
        assertTrue(s.remoteCameraPaused)
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_CAMERA_ON, 2))
        assertFalse(s.remoteCameraPaused)
    }

    /** Answering a code we did not understand would claim a camera. Count it and say nothing. */
    @Test
    fun `an unknown sub-opcode is counted, never guessed at`() {
        val s = VideoControlSession(Clock())
        val out = s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, 0x7F, 1))
        assertTrue(out.isEmpty())
        assertEquals(1L, s.unknownCodes)
        assertFalse(s.videoActive)
    }

    /** A request that arrives while video is already live is not a second call. */
    @Test
    fun `an upgrade request during a live video call is ignored`() {
        val s = VideoControlSession(Clock())
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_ACCEPT, 1))
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_REQUEST, 2))
        assertFalse(s.upgradeRequested)
    }

    @Test
    fun `a decline ends the wait without starting video`() {
        val s = VideoControlSession(Clock())
        s.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_DECLINE, 1))
        assertFalse(s.awaitingUpgrade)
        assertFalse(s.videoActive)

        val other = VideoControlSession(Clock())
        other.onUpgrade(VideoControl.Upgrade(VideoControl.VIDEO_UPGRADE, VideoControl.VUPG_REQUEST, 1))
        assertEquals(VideoControl.VUPG_DECLINE, other.declineUpgrade())
        assertFalse(other.videoActive)
        assertNull(other.declineUpgrade())
    }

    /** The two lanes meet: what the receiver decides, the control session is allowed to say. */
    @Test
    fun `a receiver that wants an IDR gets one packet, not one per datagram`() {
        val clock = Clock()
        val control = VideoControlSession(clock)
        val session = VideoReceiveSession(key, keyframeRequestIntervalFrames = 1)
        val sender = VideoSenderFixture(key)

        var sent = 0
        repeat(10) {
            val r = session.onPacket(sender.pFrame())
            if (r.requestKeyframe) sent += control.requestKeyframe().size
        }
        assertEquals("ten pre-IDR frames, two requests — the bucket, not the pipeline", 2, sent)
        assertEquals(10L, session.droppedBeforeIdr)
    }

    /** A minimal far end: P-frames only, so the receiver never opens the stream. */
    private class VideoSenderFixture(val key: ByteArray) {
        private val salt = VideoMediaFrame.newSalt(true)
        private val seq = MediaSequence()
        private var frameId = 0

        fun pFrame(): ByteArray {
            val slice = byteArrayOf(0, 0, 0, 5, 0x01, 0x11, 0x22, 0x33)
            val packet = VideoFramePacket.encode(false, 0, 0L, null, null, slice)
            val frag = VideoFragment.fragment(frameId, packet)[0]
            frameId = (frameId + 1) and 0xFFFF
            return VideoMediaFrame.encode(key, salt, seq.next(), frag)
        }
    }
}
