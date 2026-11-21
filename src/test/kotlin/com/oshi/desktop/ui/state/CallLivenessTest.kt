package com.oshi.desktop.ui.state

import com.oshi.desktop.call.transport.IceCandidateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLivenessTest {

    @Test
    fun `no packet from the peer for four seconds reads as reconnecting, and clears on the next`() {
        val l = CallLiveness()
        var s = l.observe(0, packets = 50, audioFrames = 50, videoActive = false, decodedFrames = 0)
        assertFalse(s.reconnecting)
        s = l.observe(1_000, 100, 100, false, 0)
        assertFalse(s.reconnecting)
        s = l.observe(4_900, 100, 100, false, 0)
        assertFalse("3.9 s of silence is not yet a drop", s.reconnecting)
        s = l.observe(5_000, 100, 100, false, 0)
        assertTrue(s.reconnecting)
        s = l.observe(6_000, 101, 101, false, 0)
        assertFalse(s.reconnecting)
    }

    @Test
    fun `a muted Android peer sending only its 3 s keepalive never reads as a dropped call`() {
        // Android 1.6.25 sends no audio while muted, only 0x06 every 3 s — counted as a
        // refused audio frame, so `packets` rises while `audioFrames` does not.
        val l = CallLiveness()
        var packets = 200L
        l.observe(0, packets, 200, false, 0)
        for (t in 3_000L..60_000L step 3_000L) {
            packets++
            val s = l.observe(t, packets, 200, false, 0)
            assertFalse("reconnecting at $t ms with keepalives arriving", s.reconnecting)
            assertFalse(s.audioFlowing)
        }
    }

    @Test
    fun `nothing is reconnecting before media first arrives`() {
        val l = CallLiveness()
        assertFalse(l.observe(0, 0, 0, false, 0).reconnecting)
        assertFalse(l.observe(30_000, 0, 0, false, 0).reconnecting)
    }

    @Test
    fun `a picture that stops while audio flows is stalled, with audio_ok`() {
        val l = CallLiveness()
        var pkts = 0L; var audio = 0L; var pics = 0L
        for (t in 0L..2_000L step 500L) { pkts += 40; audio += 25; pics += 15; l.observe(t, pkts, audio, true, pics) }
        var s = l.observe(2_500, pkts + 25, audio + 25, true, pics)
        assertFalse(s.videoStalled)
        audio += 25; pkts += 25
        s = l.observe(5_100, pkts + 25, audio + 25, true, pics)
        assertTrue("3 s without a new picture", s.videoStalled)
        assertTrue("audio still arriving", s.audioFlowing)
        assertFalse(s.reconnecting)
    }

    @Test
    fun `video that never shows a picture is stalled after the grace, and a camera-off peer is not`() {
        val l = CallLiveness()
        l.observe(0, 10, 10, videoActive = true, decodedFrames = 0)
        assertTrue(l.observe(3_000, 60, 60, true, 0).videoStalled)
        val off = CallLiveness()
        off.observe(0, 10, 10, videoActive = false, decodedFrames = 0)
        assertFalse(off.observe(10_000, 900, 900, false, 0).videoStalled)
    }

    @Test
    fun `a dead line is reconnecting, never also stalled`() {
        val l = CallLiveness()
        l.observe(0, 100, 50, true, 30)
        val s = l.observe(6_000, 100, 50, true, 30)
        assertTrue(s.reconnecting)
        assertFalse(s.videoStalled)
    }

    @Test
    fun `the carrier is what sent last, as iOS names it`() {
        assertEquals(CallCarrier.P2P, CallCarrier.of(IceCandidateType.SRFLX, 0, 0))
        assertEquals(CallCarrier.P2P, CallCarrier.of(IceCandidateType.HOST, 0, 0))
        assertEquals(CallCarrier.TURN, CallCarrier.of(IceCandidateType.RELAY, 0, 0))
        assertEquals("audio went to :8089 while a pair was selected", CallCarrier.UDP_RELAY, CallCarrier.of(IceCandidateType.SRFLX, 50, 0))
        assertEquals(CallCarrier.WEBSOCKET, CallCarrier.of(null, 0, 50))
        assertNull(CallCarrier.of(null, 0, 0))
        assertEquals("TURN Relay", CallCarrier.TURN.label)
    }
}
