package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.media.CallAudio
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateCodec
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1 — a call that **carries audio**, from `/call` to the speaker queue,
 * through a real call server and a real UDP socket.
 *
 * ============================================================ THE WHOLE PATH, IN ONE TEST
 *
 * [CallSignalingTest] proved the signalling half: two independent clients ring, answer and
 * end through the routes `call_server.js` serves. It ended there, because there was nothing
 * under it — `StartMedia` dropped the session key on the floor.
 *
 * This file continues the same path past that point. A dials, B answers, both lanes open a
 * [CallMediaLeg], both **signal their candidates over the `ice_candidate` packet the
 * protocol already carried and nothing ever sent**, both decode the other's, both punch,
 * a pair is elected, and a 20 ms PCM frame sealed under the offer's session key crosses
 * `127.0.0.1` and reaches the far side's [CallAudioSession].
 *
 * Every hop is real: a real HTTP server, real sealed signalling, a real UDP socket, the
 * real AEAD, the real replay window.
 *
 * ============================================================ WHAT IS STILL NOT PROVEN
 *
 * Stated here because this is the file whose name will be quoted:
 *
 *  - **No microphone and no speaker are opened.** The openers below build legs whose audio
 *    sessions are never `start()`ed; the receiving side's `onFrame` is driven by the
 *    socket, which needs no device. CI has no audio hardware.
 *  - **Both ends are in ONE process on ONE machine.** There is no second computer, no
 *    Windows, no Linux, and loopback has no NAT to traverse.
 *  - **No phone was involved and no real STUN server answered.**
 *  - **No call has been made between two people.** "Audio crosses loopback in a test" is
 *    not "a call works"; the difference is the whole of the remaining work.
 */
class CallMediaLaneTest {

    private val server = FakeCallServer()
    private val t0 = System.currentTimeMillis()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    /** Every leg any endpoint opened, so teardown is guaranteed even on a failed assert. */
    private val opened = ArrayList<CallMediaLeg>()

    @After
    fun tearDown() {
        for (leg in opened) runCatching { leg.close() }
        server.close()
    }

    /**
     * An opener that builds a real leg on `127.0.0.1` with a real [CallAudioSession] that
     * is never started.
     *
     * The audio session is real because the wire under test is
     * `MediaSocket.onSealedMedia -> CallAudioSession.onFrame`; a fake there would assert
     * that the method we wrote is called by the code we wrote. What is NOT real is the
     * device: `start()` is overridden away, because opening the shared microphone on the
     * machine that runs these tests is both antisocial and impossible on CI.
     *
     * Host candidates are forced to loopback for the reason [CallMediaLeg]'s
     * `hostCandidates` parameter documents: the real gatherer skips `127.0.0.1` the way
     * both shipped clients do, so a loopback test would otherwise signal nothing.
     */
    private inner class Opener(val sessions: MutableList<CallAudioSession> = ArrayList()) :
        CallMediaOpener {
        override fun open(spec: CallMediaSpec, log: (String) -> Unit): CallMediaLeg =
            CallMediaLeg(
                spec = spec,
                datagram = DatagramSocket(0, loopback),
                audioFor = { send ->
                    DevicelessSession(spec, send).also { sessions.add(it) }
                },
                stunServer = null,
                log = log,
                hostCandidates = { s ->
                    listOf(
                        IceCandidate(
                            IceCandidateType.HOST, "127.0.0.1", s.localPort,
                            IcePriority.ios(IceCandidateType.HOST, 4),
                        ),
                    )
                },
            ).also { opened.add(it) }
    }

    /** A real session with the device stubbed out. See [Opener]. */
    private class DevicelessSession(spec: CallMediaSpec, send: (ByteArray) -> Unit) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        override fun start() = Unit
        override fun stop() = Unit
    }

    /** An opener that cannot produce a leg at all — a machine with no audio device. */
    private object NoDevice : CallMediaOpener {
        override fun open(spec: CallMediaSpec, log: (String) -> Unit): CallMediaLeg =
            throw javax.sound.sampled.LineUnavailableException("this machine has no audio device")
    }

    private inner class Endpoint(
        val opener: CallMediaOpener? = Opener(),
        val deviceId: String = "dev-" + UUID.randomUUID().toString().take(8),
    ) {
        val identity: DesktopIdentity = DesktopIdentity.generate()
        val address: String get() = identity.userKey
        val events = ArrayList<CallLane.CallEvent>()
        val lane = CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, null, deviceId),
            mediaOpener = opener,
            // 0: this test drives mediaTick() itself. A background probe thread would make
            // every "pings sent" assertion a race against its own clock.
            mediaProbeIntervalMs = 0,
        ).also { l -> l.onEvent = { events += it } }

        fun connected(): CallLane.CallEvent.Connected? =
            events.filterIsInstance<CallLane.CallEvent.Connected>().firstOrNull()

        fun ended(): CallLane.CallEvent.Ended? =
            events.filterIsInstance<CallLane.CallEvent.Ended>().firstOrNull()

        fun problem(): CallLane.CallEvent.TransportProblem? =
            events.filterIsInstance<CallLane.CallEvent.TransportProblem>().firstOrNull()

        fun sessions(): List<CallAudioSession> = (opener as Opener).sessions
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    // ============================================================== the headline

    /**
     * Ring → answer → candidate exchange → hole punch → one 20 ms PCM frame, end to end.
     *
     * The assertions worth reading are the last four: the frame that A's send lambda
     * emitted is the frame B's audio session accepted, B's socket decoded NOTHING itself
     * (one replay window, not two), and the PCM in B's speaker queue is byte-identical to
     * the PCM A sealed.
     */
    @Test
    fun aCallCarriesAudioFromEndToEnd() {
        val a = Endpoint()
        val b = Endpoint()

        // --- signalling, exactly as CallSignalingTest proves it ---------------------
        assertTrue(a.lane.call(b.address, t0) is CallLane.Dialled.Ringing)
        assertEquals(1, b.lane.pollOnce(t0 + 10))
        assertEquals(CallState.RINGING, b.lane.state)

        // B answers: its leg opens and its candidates go on the wire inside this call.
        assertEquals(CallRefusal.NONE, b.lane.answer(t0 + 20))
        assertEquals(CallState.IN_CALL, b.lane.state)
        val bConnected = b.connected()
        assertNotNull("B must announce a connected call", bConnected)
        assertFalse("a call with a media leg must NOT claim noAudio", bConnected!!.noAudio)
        assertEquals("B signalled its candidates on connect", 1, b.lane.candidatesSent)

        // --- the candidate exchange -------------------------------------------------
        // A learns the call is up and opens its own leg. TWO signals come off the queue
        // in this one poll: B's accept, and right behind it B's candidates. The ORDER is
        // the server's queue order and it matters — candidates that arrive before the
        // accept have no leg to go into and are dropped, which
        // `candidatesWithNoLiveLegAreDroppedAndCounted` pins from the other side.
        assertEquals(2, a.lane.pollOnce(t0 + 30))
        assertEquals(CallState.IN_CALL, a.lane.state)
        assertFalse(a.connected()!!.noAudio)
        assertEquals("A signalled its own candidates on connect", 1, a.lane.candidatesSent)
        assertEquals("A learned B's host candidate", 1, a.lane.candidatesReceived)

        assertEquals(1, b.lane.pollOnce(t0 + 40))
        assertEquals("B learned A's host candidate", 1, b.lane.candidatesReceived)
        assertEquals(0, a.lane.candidatesIgnored)
        assertEquals(0, b.lane.candidatesIgnored)

        // --- the hole punch ---------------------------------------------------------
        assertTrue(
            "no candidate pair went live",
            waitFor {
                a.lane.mediaTick(); b.lane.mediaTick()
                Thread.sleep(20)
                a.lane.media?.selected != null && b.lane.media?.selected != null
            },
        )
        val aLeg = a.lane.media!!
        val bLeg = b.lane.media!!
        assertEquals(bLeg.socket.localPort, aLeg.selected!!.port)

        // --- audio ------------------------------------------------------------------
        val pcm = ByteArray(CallAudio.BYTES_PER_FRAME) { ((it * 7 + 11) % 251).toByte() }
        val sealed = CallMediaFrame.encode(
            aLeg.spec.sessionKey, aLeg.spec.nonceSalt, aLeg.spec.isCaller,
            1, CallMediaFrame.TYPE_PCM_48K, pcm,
        )
        assertTrue("A's send lambda reported nothing sent", aLeg.sendSealed(sealed))
        assertTrue("the frame never reached B", waitFor { bLeg.framesAccepted.get() == 1L })
        assertEquals("nothing was refused", 0L, bLeg.framesRefused.get())
        assertEquals("the socket must not decode what the session took", 0L, bLeg.socket.framesPlayed.get())

        val played = b.sessions().single().playback.take(500L)
        assertNotNull("nothing reached B's speaker queue", played)
        assertArrayEquals("the PCM that arrived is the PCM that was sent", pcm, played)

        // The direction convention is the caller's salt on the caller's frame — inverted,
        // both directions would emit identical GCM nonces under one key.
        assertTrue("A is the caller", aLeg.spec.isCaller)
        assertFalse("B is the callee", bLeg.spec.isCaller)
        assertEquals(
            (aLeg.spec.nonceSalt[0].toInt() xor 0xA5).toByte(),
            CallMediaFrame.encode(
                bLeg.spec.sessionKey, bLeg.spec.nonceSalt, bLeg.spec.isCaller,
                1, CallMediaFrame.TYPE_PCM_48K, ByteArray(4),
            )[CallMediaFrame.HEADER_SIZE],
        )
    }

    /** The candidate signal on the wire is the shape the protocol already defined. */
    @Test
    fun theCandidateSignalIsTheShippedShape() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)

        // B posted an offer-response accept and then its candidates. The candidate body is
        // the last thing it sent.
        val ice = server.postedBodies.map { JSONObject(it) }
            .filter { it.optString("type") == CallSignalType.ICE_CANDIDATE }
        assertEquals("exactly one ice_candidate signal per gather", 1, ice.size)
        assertEquals(b.lane.callId, ice[0].getString("callId"))

        // And it decodes, under A's key, to B's actual media port.
        val sealed = Base64.getDecoder().decode(ice[0].getString("signal"))
        val plain = CallSignalCrypto.open(
            a.identity.identity.priv,
            Base64.getDecoder().decode(b.address),
            sealed,
        )
        assertNotNull("the candidate payload must be sealed to A", plain)
        val packet = CallPacket.decode(plain!!)!!
        assertEquals(CallPacket.Type.ICE_CANDIDATE_EXCHANGE, packet.type)
        val decoded = IceCandidateCodec.decode(packet.payload)
        assertEquals(1, decoded.size)
        assertEquals("127.0.0.1", decoded[0].ip)
        assertEquals(b.lane.media!!.socket.localPort, decoded[0].port)
        assertEquals(IceCandidateType.HOST, decoded[0].type)
    }

    // ============================================================== the refusals

    /**
     * ============================================================ REQUIREMENT (d)
     *
     * A call whose media leg will not open is **ended**, not connected.
     *
     * This is the most expensive failure a messenger can have — a connected call with no
     * audio, where both people wait and neither is told anything — so all four halves are
     * asserted: no Connected event at ALL, the state is ENDED, the local reason is
     * announced, and a `callEnd` actually reached the peer.
     */
    @Test
    fun aCallWhoseDeviceWillNotOpenIsEndedAndNotConnected() {
        val a = Endpoint()
        val b = Endpoint(opener = NoDevice)

        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        assertEquals(CallState.RINGING, b.lane.state)

        b.lane.answer(t0 + 20)

        assertNull("a call with no audio device must never announce Connected", b.connected())
        assertEquals(CallState.ENDED, b.lane.state)
        assertEquals(1, b.lane.mediaFailures)
        assertNotNull("the user must be told why", b.problem())
        assertTrue(
            "the reason must name the devices, got '${b.problem()!!.detail}'",
            b.problem()!!.detail.contains("audio devices"),
        )

        // --- and what reaches the peer, which is NOT what you would guess ------------
        //
        // TWO signals arrive in one poll, in this order: B's accept (the machine emits it
        // before StartMedia runs) and then B's callEnd. So A connects — and then
        // DISCARDS the callEnd, because CallTimeouts.END_GRACE_AFTER_CONNECT_MS
        // deliberately ignores any end inside 3 s of connecting. That grace is iOS's and
        // it is right: it is what stops a `callEnd` retransmit fired before the peer saw
        // our accept from killing the call it just established.
        //
        // The consequence is the finding, and it is the reason CallLane.MEDIA_PATH_TIMEOUT_MS
        // exists: without a media watchdog, A would sit in a CONNECTED, SILENT call
        // forever, because CallStateMachine has no watchdog on IN_CALL at all.
        assertEquals(2, a.lane.pollOnce(t0 + 6_000))
        assertEquals("the grace swallows a callEnd this soon after connecting", CallState.IN_CALL, a.lane.state)

        // The watchdog is what finishes it. One tick before the deadline changes nothing;
        // one tick after ends the call. The control is what makes this a test of the
        // TIMEOUT rather than of "mediaTick ends calls".
        a.lane.mediaTick(t0 + 6_000 + CallLane.MEDIA_PATH_TIMEOUT_MS - 1)
        assertEquals("one millisecond early must change nothing", CallState.IN_CALL, a.lane.state)
        a.lane.mediaTick(t0 + 6_000 + CallLane.MEDIA_PATH_TIMEOUT_MS)
        assertEquals(CallState.ENDED, a.lane.state)
        assertEquals(CallEndReason.CONNECTION_LOST, a.ended()!!.reason)
        assertNull("A's leg must be released when the call dies", a.lane.media)
    }

    /**
     * ============================================================ THE MEDIA-PATH WATCHDOG
     *
     * A call that connects, opens its devices, signals candidates and never punches is the
     * silent call this whole row is about. Nothing else in this client would end it —
     * [CallStateMachine] watchdogs RINGING and CONNECTING and has nothing for IN_CALL.
     *
     * Here the two lanes never exchange candidates at all (neither polls), so no pair can
     * ever go live. Both halves are asserted: nothing happens one millisecond early, and
     * the call ends one millisecond later with a reason a person can be shown.
     */
    @Test
    fun aCallThatNeverPunchesIsEndedRatherThanLeftSilent() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)

        assertEquals(CallState.IN_CALL, b.lane.state)
        assertNull("nothing can have been selected — no candidates were ever exchanged", b.lane.media!!.selected)

        b.lane.mediaTick(t0 + 20 + CallLane.MEDIA_PATH_TIMEOUT_MS - 1)
        assertEquals(CallState.IN_CALL, b.lane.state)
        assertEquals(0, b.lane.mediaFailures)

        b.lane.mediaTick(t0 + 20 + CallLane.MEDIA_PATH_TIMEOUT_MS)
        assertEquals(CallState.ENDED, b.lane.state)
        assertEquals(CallEndReason.CONNECTION_LOST, b.ended()!!.reason)
        assertEquals(1, b.lane.mediaFailures)
        assertNull("the devices must be released with the call", b.lane.media)
        assertNotNull("and the user must be told", b.problem())
    }

    /**
     * Candidates from somebody who is not the peer on this call are dropped and COUNTED.
     *
     * The server has no authentication (`SIGNATURE_REQUIRED = false`), so anyone can POST
     * a signal; the seal is what makes a sender real, and the peer check is what keeps a
     * real stranger from repointing live audio at an address of their choosing.
     */
    @Test
    fun candidatesFromAStrangerAreDroppedAndCounted() {
        val a = Endpoint()
        val b = Endpoint()
        val stranger = DesktopIdentity.generate()

        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)
        b.lane.pollOnce(t0 + 40)
        val before = b.lane.media!!.socket.remoteCandidates().size
        val ignoredBefore = b.lane.candidatesIgnored

        // A well-formed, correctly SEALED candidate exchange — from the wrong identity.
        val payload = IceCandidateCodec.encode(
            listOf(IceCandidate(IceCandidateType.HOST, "203.0.113.9", 5555, 0)),
        )
        val packet = CallPacket.encode(CallPacket.Type.ICE_CANDIDATE_EXCHANGE, t0 + 55, payload)
        val sealed = CallSignalCrypto.seal(
            stranger.identity.priv,
            Base64.getDecoder().decode(b.address),
            packet,
        )
        CallSignalClient(server.baseUrl, DesktopV2Signer(stranger)).sendSignal(
            CallSignalEnvelope(
                sender = CallSignalClient.base64Url(stranger.userKey),
                recipient = CallSignalClient.base64Url(b.address),
                signalBase64 = Base64.getEncoder().encodeToString(sealed),
                callId = b.lane.callId!!,
                type = CallSignalType.ICE_CANDIDATE,
            ),
            t0 + 55,
        )

        b.lane.pollOnce(t0 + 60)
        assertEquals("the stranger's candidate must not be added", before, b.lane.media!!.socket.remoteCandidates().size)
        assertEquals(ignoredBefore + 1, b.lane.candidatesIgnored)
        assertTrue(
            "203.0.113.9 must never become a pair",
            b.lane.media!!.socket.remoteCandidates().none { it.ip == "203.0.113.9" },
        )
    }

    /**
     * Candidates from the RIGHT peer but for a DIFFERENT call are dropped and counted.
     *
     * The peer check alone does not cover this: a peer we are legitimately talking to can
     * still name another callId, and accepting it would repoint the live audio of THIS
     * call at an address agreed for a different one.
     */
    @Test
    fun candidatesForAnotherCallAreDroppedAndCounted() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)
        b.lane.pollOnce(t0 + 40)
        val before = b.lane.media!!.socket.remoteCandidates().size
        val ignoredBefore = b.lane.candidatesIgnored

        val payload = IceCandidateCodec.encode(
            listOf(IceCandidate(IceCandidateType.HOST, "198.51.100.7", 4444, 0)),
        )
        val packet = CallPacket.encode(CallPacket.Type.ICE_CANDIDATE_EXCHANGE, t0 + 45, payload)
        val sealed = CallSignalCrypto.seal(
            a.identity.identity.priv, Base64.getDecoder().decode(b.address), packet,
        )
        CallSignalClient(server.baseUrl, DesktopV2Signer(a.identity)).sendSignal(
            CallSignalEnvelope(
                sender = CallSignalClient.base64Url(a.address),
                recipient = CallSignalClient.base64Url(b.address),
                signalBase64 = Base64.getEncoder().encodeToString(sealed),
                // A real UUID, and not this call's.
                callId = "00000000-DEAD-BEEF-0000-000000000001",
                type = CallSignalType.ICE_CANDIDATE,
            ),
            t0 + 45,
        )

        b.lane.pollOnce(t0 + 50)
        assertEquals(before, b.lane.media!!.socket.remoteCandidates().size)
        assertEquals(ignoredBefore + 1, b.lane.candidatesIgnored)
        assertTrue(b.lane.media!!.socket.remoteCandidates().none { it.ip == "198.51.100.7" })
    }

    /** Candidates that arrive with no media leg open are dropped, not buffered. */
    @Test
    fun candidatesWithNoLiveLegAreDroppedAndCounted() {
        val a = Endpoint()
        val b = Endpoint()

        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        // B is RINGING: it has no leg yet, because it has not answered.
        assertNull(b.lane.media)

        val payload = IceCandidateCodec.encode(
            listOf(IceCandidate(IceCandidateType.HOST, "127.0.0.1", 5555, 0)),
        )
        val packet = CallPacket.encode(CallPacket.Type.ICE_CANDIDATE_EXCHANGE, t0 + 15, payload)
        val sealed = CallSignalCrypto.seal(
            a.identity.identity.priv, Base64.getDecoder().decode(b.address), packet,
        )
        CallSignalClient(server.baseUrl, DesktopV2Signer(a.identity)).sendSignal(
            CallSignalEnvelope(
                sender = CallSignalClient.base64Url(a.address),
                recipient = CallSignalClient.base64Url(b.address),
                signalBase64 = Base64.getEncoder().encodeToString(sealed),
                callId = b.lane.callId!!,
                type = CallSignalType.ICE_CANDIDATE,
            ),
            t0 + 15,
        )

        b.lane.pollOnce(t0 + 20)
        assertEquals(1, b.lane.candidatesIgnored)
        assertEquals(0, b.lane.candidatesReceived)
        assertNull(b.lane.media)
    }

    // ============================================================== teardown

    /**
     * Hanging up releases the leg. A microphone that stays lit is a visible bug on Windows
     * and can block the next call on Linux, so this is asserted on the leg itself rather
     * than on a log line.
     */
    @Test
    fun hangingUpReleasesTheMediaLeg() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)

        val aLeg = a.lane.media!!
        val bLeg = b.lane.media!!
        assertFalse(aLeg.isClosed)

        a.lane.hangUp(CallEndReason.HUNG_UP, t0 + 5_000)
        assertTrue("the caller's leg must be released", aLeg.isClosed)
        assertNull(a.lane.media)

        b.lane.pollOnce(t0 + 5_010)
        assertEquals(CallState.ENDED, b.lane.state)
        assertTrue("the callee's leg must be released too", bLeg.isClosed)
        assertNull(b.lane.media)

        // And the released socket is really released — the port is back.
        assertEquals(-1, aLeg.socket.localPort)
        assertEquals(-1, bLeg.socket.localPort)
    }

    /** A call that is declined never opens a leg at all. */
    @Test
    fun aDeclinedCallOpensNoDevice() {
        val a = Endpoint()
        val b = Endpoint()
        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.decline(t0 + 20)
        assertNull(b.lane.media)
        assertEquals(0, b.sessions().size)

        a.lane.pollOnce(t0 + 6_000)
        assertEquals(CallState.ENDED, a.lane.state)
        assertNull(a.lane.media)
    }

    /** closeMedia is reachable from anywhere and costs nothing when there is nothing. */
    @Test
    fun closeMediaIsSafeWithNoCall() {
        val a = Endpoint()
        a.lane.closeMedia()
        a.lane.closeMedia()
        assertNull(a.lane.media)
        assertNull(a.lane.mediaTick())
    }

    // ============================================================== the seam is off

    /**
     * With no opener — the shape `app/OshiClient` still constructs — the lane behaves
     * exactly as it did before this work: it connects, it says so, and it says no audio
     * flows.
     *
     * This is the test that keeps [CallLane.NO_AUDIO_WILL_FLOW] an honest sentence in the
     * shipped build rather than a stale one.
     */
    @Test
    fun aLaneWithNoOpenerStillConnectsWithNoAudio() {
        val a = Endpoint(opener = null)
        val b = Endpoint(opener = null)

        a.lane.call(b.address, t0)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)

        assertTrue("no opener means no audio, and the event must say so", b.connected()!!.noAudio)
        assertNull(b.lane.media)
        assertEquals("and nothing was signalled", 0, b.lane.candidatesSent)
        assertEquals(0, b.lane.mediaFailures)
    }
}
