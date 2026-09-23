package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.media.InBandCallEnd
import com.oshi.desktop.call.media.VideoControl
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phones' in-band hang-up — a sealed `0x0D` on the media path ([InBandCallEnd]).
 *
 * Before this, a desktop whose signalled `callEnd` was lost stayed "connected" to nobody:
 * the phones' in-band copy failed the audio session's type check and was counted as a
 * refused frame. These tests pin both halves — the desktop honours the phones' copy, and
 * sends its own — and the refusals that keep a forgeable nine-byte toggle from ending a call.
 */
class InBandCallEndTest {

    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    // ============================================================== the codec

    @Test
    fun theWireIsThePhonesShape() {
        val key = bytes(32)
        val salt = bytes(4)
        val pkt = InBandCallEnd.encode(key, salt, isCaller = true, seq = 77, reason = CallEndReason.HUNG_UP)
        // [0x0D][seq 8 BE][salt(4) ‖ counter(8 BE)][ct][tag(16)] — the audio frame shape,
        // plaintext = the reason's snake_case wire string (Android CallEndReasonWire, iOS rawValue).
        assertEquals(0x0D, pkt[0].toInt() and 0xFF)
        assertEquals(77L, (1..8).fold(0L) { acc, i -> (acc shl 8) or (pkt[i].toLong() and 0xFF) })
        assertEquals(CallMediaFrame.HEADER_SIZE + 12 + "hung_up".length + 16, pkt.size)
        val opened = InBandCallEnd.decode(key, pkt)
        assertNotNull(opened)
        assertEquals(CallEndReason.HUNG_UP, opened!!.reason)
        assertEquals(77L, opened.seq)
        // What the audio layer sees inside is exactly the reason string.
        assertEquals("hung_up", String(CallMediaFrame.decode(key, pkt)!!.pcm, Charsets.UTF_8))
    }

    @Test
    fun anUnknownReasonReadsAsHungUpAsOnIos() {
        val key = bytes(32)
        val pkt = CallMediaFrame.encode(key, bytes(4), false, 5, InBandCallEnd.TYPE, "something_new".toByteArray())
        assertEquals(CallEndReason.HUNG_UP, InBandCallEnd.decode(key, pkt)!!.reason)
    }

    @Test
    fun theNineByteCameraToggleIsNeverAHangUp() {
        val toggle = VideoControl.encodeToggle(VideoControl.VIDEO_RESUMED, System.currentTimeMillis())
        assertEquals(9, toggle.size)
        assertEquals(0x0D, toggle[0].toInt() and 0xFF)
        assertFalse(InBandCallEnd.looksLike(toggle))
        assertNull(InBandCallEnd.decode(bytes(32), toggle))
    }

    @Test
    fun forgedTamperedAndOtherCallPacketsDoNotOpen() {
        val key = bytes(32)
        val good = InBandCallEnd.encode(key, bytes(4), true, 9, CallEndReason.HUNG_UP)
        // Forged: right type and size, no key.
        val forged = bytes(good.size).also { it[0] = 0x0D }
        assertNull(InBandCallEnd.decode(key, forged))
        // Tampered tag.
        val tampered = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(InBandCallEnd.decode(key, tampered))
        // Another call: every call has its own session key.
        assertNull(InBandCallEnd.decode(bytes(32), good))
    }

    // ============================================================== through a real lane

    private val server = FakeCallServer()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val opened = ArrayList<CallMediaLeg>()

    /**
     * The lane clock starts 5 s in the past. The in-band hang-up is applied at the WALL
     * clock (it arrives on a socket thread), and the state machine's 3 s post-connect grace
     * is measured from the lane clock; 5 s puts "now" after the grace, which is where a real
     * hang-up lands, and well inside the 20 s media-path watchdog that `mediaTick()` runs on
     * the wall clock. The grace itself is covered below.
     */
    private val t0 = System.currentTimeMillis() - 5_000

    @After
    fun tearDown() {
        for (leg in opened) runCatching { leg.close() }
        server.close()
    }

    private inner class Opener : CallMediaOpener {
        override fun open(spec: CallMediaSpec, log: (String) -> Unit): CallMediaLeg =
            CallMediaLeg(
                spec = spec,
                datagram = DatagramSocket(0, loopback),
                audioFor = { send -> Deviceless(spec, send) },
                stunServer = null,
                log = log,
                hostCandidates = { s ->
                    listOf(IceCandidate(IceCandidateType.HOST, "127.0.0.1", s.localPort, IcePriority.ios(IceCandidateType.HOST, 4)))
                },
            ).also { opened.add(it) }
    }

    private class Deviceless(spec: CallMediaSpec, send: (ByteArray) -> Unit) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        override fun start() = Unit
        override fun stop() = Unit
    }

    private inner class Endpoint {
        val identity: DesktopIdentity = DesktopIdentity.generate()
        val address: String get() = identity.userKey
        val events = ArrayList<CallLane.CallEvent>()
        val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
        val lane = CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, null, deviceId),
            mediaOpener = Opener(),
            mediaProbeIntervalMs = 0,
        ).also { l -> l.onEvent = { synchronized(events) { events += it } } }

        fun ended(): CallLane.CallEvent.Ended? =
            synchronized(events) { events.filterIsInstance<CallLane.CallEvent.Ended>().firstOrNull() }
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    /** Ring, answer, exchange candidates, punch. Returns (caller, callee). */
    private fun connectedPair(): Pair<Endpoint, Endpoint> {
        val a = Endpoint()
        val b = Endpoint()
        assertTrue(a.lane.call(b.address, t0) is CallLane.Dialled.Ringing)
        assertEquals(1, b.lane.pollOnce(t0 + 10))
        assertEquals(CallRefusal.NONE, b.lane.answer(t0 + 20))
        assertEquals(2, a.lane.pollOnce(t0 + 30))
        assertEquals(1, b.lane.pollOnce(t0 + 40))
        assertTrue(
            "no candidate pair went live",
            waitFor {
                a.lane.mediaTick(); b.lane.mediaTick()
                Thread.sleep(20)
                a.lane.media?.selected != null && b.lane.media?.selected != null
            },
        )
        assertEquals(CallState.IN_CALL, a.lane.state)
        assertEquals(CallState.IN_CALL, b.lane.state)
        return a to b
    }

    @Test
    fun aSealedInBandHangUpAloneEndsTheCallWithTheSameHistoryRow() {
        val (a, b) = connectedPair()
        val aLeg = a.lane.media!!
        val seq = aLeg.audio!!.nextSequence()
        assertTrue(aLeg.sendSealed(InBandCallEnd.encode(aLeg.spec.sessionKey, aLeg.spec.nonceSalt, true, seq, CallEndReason.HUNG_UP)))

        // B is never polled: no signalled callEnd exists. The in-band copy alone must end it.
        assertTrue("B stayed connected", waitFor { b.lane.state == CallState.ENDED && b.ended() != null })
        val ended = b.ended()
        assertNotNull(ended)
        assertEquals(CallEndReason.HUNG_UP, ended!!.reason)
        assertTrue("it was a connected call", ended.wasConnected)
        assertEquals(1, b.lane.inBandEndsApplied)
        val row = b.lane.calls().single()
        assertEquals(CallEndReason.HUNG_UP, row.reason)
        assertTrue(row.connected)
        assertEquals(a.lane.callId ?: row.callId, row.callId)
    }

    @Test
    fun theCleartextCameraToggleDoesNotEndTheCall() {
        val (a, b) = connectedPair()
        val aLeg = a.lane.media!!
        val bLeg = b.lane.media!!
        repeat(5) { aLeg.sendSealed(VideoControl.encodeToggle(VideoControl.VIDEO_RESUMED, System.currentTimeMillis())) }
        Thread.sleep(300)
        assertEquals(CallState.IN_CALL, b.lane.state)
        assertEquals(0L, bLeg.inBandEndsAccepted.get())
        assertEquals(0, b.lane.inBandEndsApplied)
    }

    @Test
    fun forgedReflectedAndOtherCallHangUpsDoNotEndTheCall() {
        val (a, b) = connectedPair()
        val aLeg = a.lane.media!!
        val bLeg = b.lane.media!!
        // Forged: no key.
        aLeg.sendSealed(bytes(InBandCallEnd.MIN_SIZE + 7).also { it[0] = 0x0D })
        // Another call's key.
        aLeg.sendSealed(InBandCallEnd.encode(bytes(32), aLeg.spec.nonceSalt, true, 3, CallEndReason.HUNG_UP))
        // Reflected: B's OWN direction under the right key — what an on-path attacker gets by
        // bouncing B's packets back at it.
        aLeg.sendSealed(InBandCallEnd.encode(bLeg.spec.sessionKey, bLeg.spec.nonceSalt, isCaller = false, seq = 999, reason = CallEndReason.HUNG_UP))
        assertTrue(waitFor { bLeg.inBandEndsRefused.get() == 3L })
        Thread.sleep(200)
        assertEquals(CallState.IN_CALL, b.lane.state)
        assertEquals(0, b.lane.inBandEndsApplied)
    }

    @Test
    fun aReplayedHangUpIsActedOnOnce() {
        val (a, b) = connectedPair()
        val aLeg = a.lane.media!!
        val bLeg = b.lane.media!!
        val pkt = InBandCallEnd.encode(aLeg.spec.sessionKey, aLeg.spec.nonceSalt, true, aLeg.audio!!.nextSequence(), CallEndReason.HUNG_UP)
        aLeg.sendSealed(pkt)
        aLeg.sendSealed(pkt)
        assertTrue(waitFor { b.lane.state == CallState.ENDED })
        Thread.sleep(200)
        assertEquals(1L, bLeg.inBandEndsAccepted.get())
        assertEquals(1, b.lane.inBandEndsApplied)
        assertEquals(1, b.lane.calls().size)
    }

    @Test
    fun aLocalHangUpSendsTheBurstAndThePeerEndsWithoutTheSignal() {
        val (a, b) = connectedPair()
        val bLeg = b.lane.media!!
        a.lane.hangUp(nowMs = System.currentTimeMillis())
        assertEquals(CallState.ENDED, a.lane.state)
        assertEquals("the phones send three copies", InBandCallEnd.BURST, a.lane.inBandEndsSent)
        // B is never polled, so the signalled callEnd never reaches it.
        assertTrue("B stayed connected", waitFor { b.lane.state == CallState.ENDED && b.ended() != null })
        assertEquals(CallEndReason.HUNG_UP, b.ended()!!.reason)
        assertEquals(1L, bLeg.inBandEndsAccepted.get())
        // B ended because the PEER said so: it must not echo an in-band hang-up back.
        assertEquals(0, b.lane.inBandEndsSent)
    }

    @Test
    fun theSignalledPathStillEndsTheCallAndSendsInBandToo() {
        val (a, b) = connectedPair()
        a.lane.hangUp(nowMs = System.currentTimeMillis())
        // Whichever copy lands first ends B; the other is refused as WRONG_STATE, and there
        // is exactly one history row.
        b.lane.pollOnce(System.currentTimeMillis())
        assertTrue(waitFor { b.lane.state == CallState.ENDED })
        Thread.sleep(200)
        b.lane.pollOnce(System.currentTimeMillis())
        assertEquals(1, b.lane.calls().size)
    }

    @Test
    fun anInBandHangUpInsideThePostConnectGraceIsIgnoredAsOnIos() {
        val (_, b) = connectedPair()
        // The lane applies it at "now"; the call connected at t0+40. Apply it 1 s after.
        b.lane.onInBandCallEnd(b.lane.callId!!, CallEndReason.HUNG_UP, nowMs = t0 + 1_040)
        assertEquals(CallState.IN_CALL, b.lane.state)
        assertEquals(0, b.lane.inBandEndsApplied)
    }

    @Test
    fun anInBandHangUpForAnotherCallIdIsIgnored() {
        val (_, b) = connectedPair()
        b.lane.onInBandCallEnd("not-this-call", CallEndReason.HUNG_UP)
        assertEquals(CallState.IN_CALL, b.lane.state)
    }
}
