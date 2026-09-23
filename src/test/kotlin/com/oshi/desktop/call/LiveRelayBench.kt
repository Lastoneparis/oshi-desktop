package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.media.VideoMediaFrame
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.TurnCredentials
import com.oshi.desktop.call.transport.UdpRelayClient
import com.oshi.desktop.call.transport.WsRelayClient
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.ui.state.CallScreenModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.DatagramSocket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PARITY.md row 2.1-t, LIVE, against production: the coturn at 45.67.216.197:3478 with
 * ephemeral credentials from `/voip/turn-creds`, and the `:8089` UDP relay.
 *
 * Gate: `OSHI_LIVE_CALL=1`. Both ends are this one machine — but in RELAY-ONLY mode no
 * packet takes a direct path: every datagram leaves for the server and comes back, which
 * is exactly the path two peers behind symmetric NATs would use.
 */
class LiveRelayBench {

    private fun live() = Assume.assumeTrue("set OSHI_LIVE_CALL=1", System.getenv("OSHI_LIVE_CALL") == "1")

    private val rng = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    private class Deviceless(spec: CallMediaSpec, send: (ByteArray) -> Unit) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        override fun start() = Unit
        override fun stop() = Unit
    }

    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (!cond()) {
            check(System.currentTimeMillis() - t0 < timeoutMs) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
        return System.currentTimeMillis() - t0
    }

    private fun pairOfLegs(relay: CallRelayConfig): Pair<CallMediaLeg, CallMediaLeg> {
        val callId = UUID.randomUUID().toString().uppercase()
        val key = bytes(32); val salt = bytes(4)
        val aKey = java.util.Base64.getEncoder().encodeToString(bytes(32))
        val bKey = java.util.Base64.getEncoder().encodeToString(bytes(32))
        fun leg(isCaller: Boolean, self: String, peer: String): CallMediaLeg {
            val spec = CallMediaSpec(callId, key, salt, isCaller, selfKey = self, peerKey = peer)
            return CallMediaLeg(
                spec = spec,
                datagram = DatagramSocket(0),
                audioFor = { send -> Deviceless(spec, send) },
                stunServer = null,
                log = { println("[relay] ${if (isCaller) "A" else "B"}: $it") },
                hostCandidates = { emptyList() },
                relayConfig = relay,
            )
        }
        val a = leg(true, aKey, bKey); val b = leg(false, bKey, aKey)
        a.onLocalCandidates = { b.addRemoteCandidates(it) }
        b.onLocalCandidates = { a.addRemoteCandidates(it) }
        return a to b
    }

    /**
     * Two legs, relay-only, no :8089: the ONLY path is TURN relay ↔ TURN relay. A sealed
     * audio frame and a sealed 0xF1 video envelope must each cross it byte-identical and
     * the audio frame must authenticate on the far side.
     */
    @Test
    fun sealedAudioAndVideoCrossTheProductionTurnRelay() {
        live()
        val (a, b) = pairOfLegs(CallRelayConfig(TurnCredentials(), udpRelay = null, relayOnly = true, turnTransport = "udp"))
        val got = CopyOnWriteArrayList<ByteArray>()
        b.tap = { got += it.copyOf() }
        try {
            a.start(); b.start()
            val allocMs = waitFor("both TURN allocations", 10_000) { a.turnAllocation != null && b.turnAllocation != null }
            println("[relay] allocations after $allocMs ms: A=${a.turnAllocation} B=${b.turnAllocation}")
            val pathMs = waitFor("a RELAY pair selected on both sides", 15_000) {
                a.tick(); b.tick()
                a.selected?.type == IceCandidateType.RELAY && b.selected?.type == IceCandidateType.RELAY
            }
            println("[relay] relay pair selected after $pathMs ms: A→${a.selected} B→${b.selected}")

            val spec = a.spec
            val audio = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, true, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            val video = VideoMediaFrame.encode(spec.sessionKey, VideoMediaFrame.newSalt(true), 1, bytes(1100))
            val t0 = System.currentTimeMillis()
            assertTrue(a.sendMedia(audio))
            assertTrue(a.sendMedia(video))
            waitFor("both frames on B", 5_000) { got.size >= 2 }
            println("[relay] audio ${audio.size} B + video ${video.size} B arrived in ${System.currentTimeMillis() - t0} ms through coturn")
            assertArrayEquals(audio, got.first { it.contentEquals(audio) })
            assertArrayEquals(video, got.first { it.contentEquals(video) })
            assertEquals("the audio frame authenticated under the session key", 1L, b.framesAccepted.get())

            // Reverse direction: B → A.
            val back = CopyOnWriteArrayList<ByteArray>()
            a.tap = { back += it.copyOf() }
            val audioB = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, false, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            assertTrue(b.sendMedia(audioB))
            waitFor("B→A frame", 5_000) { back.any { it.contentEquals(audioB) } }
            assertEquals(1L, a.framesAccepted.get())
            assertEquals("nothing touched the :8089 relay", 0L, a.relaySent.get() + b.relaySent.get())
        } finally {
            a.close(); b.close()
        }
    }

    /**
     * No TURN, no direct pairs: the :8089 relay is the only carrier. The ladder must fall
     * through to it with no pair selected.
     */
    @Test
    fun sealedAudioAndVideoCrossTheProductionUdpRelay() {
        live()
        val (a, b) = pairOfLegs(CallRelayConfig(turn = null, udpRelay = UdpRelayClient.DEFAULT_SERVER, relayOnly = true))
        val got = CopyOnWriteArrayList<ByteArray>()
        b.tap = { got += it.copyOf() }
        try {
            a.start(); b.start()
            waitFor("both registered", 5_000) { a.udpRelayClient!!.usable() && b.udpRelayClient!!.usable() }
            val spec = a.spec
            val audio = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, true, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            val video = VideoMediaFrame.encode(spec.sessionKey, VideoMediaFrame.newSalt(true), 1, bytes(1100))
            assertTrue(a.sendMedia(audio)); assertTrue(a.sendMedia(video))
            waitFor("both frames on B", 5_000) { got.size >= 2 }
            assertNotNull(got.firstOrNull { it.contentEquals(audio) })
            assertNotNull(got.firstOrNull { it.contentEquals(video) })
            assertEquals(1L, b.framesAccepted.get())
            assertTrue("B's watchdog sees the relay as a live path", b.relayCarrying())
            println("[relay] :8089 carried audio+video; A relaySent=${a.relaySent.get()} B relayReceived=${b.relayReceived.get()}")
        } finally {
            a.close(); b.close()
        }
    }

    /**
     * A whole call through the production call server with RELAY-ONLY media and no :8089:
     * ring → answer → TURN allocate → relay candidates signalled → relay pair → real
     * microphone audio and real camera video both ways over coturn.
     */
    @Test
    fun twoDesktopsCallThroughTurnOnly() {
        live()
        val server = V2Http.defaultBaseUrl()
        val relay = CallRelayConfig(TurnCredentials(), udpRelay = null, relayOnly = true, turnTransport = "udp")
        fun lane(id: DesktopIdentity, device: String) = CallLane(
            myAddress = id.userKey,
            myPrivateKey = id.identity.priv,
            transport = CallSignalClient(server, DesktopV2Signer(id)),
            deviceId = device,
            machine = CallStateMachine(id.userKey, null, device),
            mediaOpener = CallMedia.real(relay = relay),
            log = { println("[live-turn] $device: $it") },
        )
        val aId = DesktopIdentity.generate(); val bId = DesktopIdentity.generate()
        val a = lane(aId, "desk-A"); val b = lane(bId, "desk-B")
        val aModel = CallScreenModel(a, labelFor = { "B" })
        val bModel = CallScreenModel(b, labelFor = { "A" })
        a.onEvent = { aModel.onEvent(it) }
        b.onEvent = { bModel.onEvent(it) }
        val pollers = Executors.newScheduledThreadPool(2)
        for (l in listOf(a, b)) pollers.scheduleWithFixedDelay({
            runCatching { l.pollOnce(); l.tick() }.onFailure { println("[live-turn] poll failed: $it") }
        }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        try {
            aModel.call(bId.userKey, video = true)
            waitFor("B to ring", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.INCOMING }
            bModel.answer()
            waitFor("both connected", 15_000) {
                aModel.screen?.phase == CallScreenModel.Phase.CONNECTED && bModel.screen?.phase == CallScreenModel.Phase.CONNECTED
            }
            val pathMs = waitFor("a relay pair on both sides", 25_000) {
                a.media?.selected?.type == IceCandidateType.RELAY && b.media?.selected?.type == IceCandidateType.RELAY
            }
            println("[live-turn] relay pair selected on both sides $pathMs ms after connect: A→${a.media?.selected} B→${b.media?.selected}")
            b.video()?.setCameraEnabled(false)
            val da0 = a.mediaDiagnostics(); val db0 = b.mediaDiagnostics()
            val vb = b.video()
            val v0 = vb?.remoteFrameCount ?: 0
            Thread.sleep(10_000)
            val da = a.mediaDiagnostics(); val db = b.mediaDiagnostics()
            println(
                "[live-turn] over 10 s via coturn: A sent ${da.framesSent - da0.framesSent} accepted ${da.framesAccepted - da0.framesAccepted}; " +
                    "B sent ${db.framesSent - db0.framesSent} accepted ${db.framesAccepted - db0.framesAccepted}; " +
                    "B decoded ${(vb?.remoteFrameCount ?: 0) - v0} video pictures; A camera problem=${a.video()?.cameraProblem}",
            )
            // Delivery, not capture rate: both ends share ONE microphone on this Mac, and the
            // second capture session is routinely starved (measured: 66 frames in 10 s on one
            // side, 502 on the other). What the relay owes is that what was SENT arrives.
            val aSent = da.framesSent - da0.framesSent; val bSent = db.framesSent - db0.framesSent
            assertTrue("A sent audio", aSent > 0); assertTrue("B sent audio", bSent > 0)
            assertTrue("audio crossed coturn A→B", (db.framesAccepted - db0.framesAccepted) >= aSent * 9 / 10)
            assertTrue("audio crossed coturn B→A", (da.framesAccepted - da0.framesAccepted) >= bSent * 9 / 10)
            if (a.video()?.cameraProblem == null) assertTrue("video crossed coturn", (vb?.remoteFrameCount ?: 0) - v0 > 100)
            assertEquals(0L, (a.media?.relaySent?.get() ?: 0) + (b.media?.relaySent?.get() ?: 0))
            aModel.hangUp()
            waitFor("B ended", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.ENDED }
        } finally {
            pollers.shutdownNow()
            a.closeMedia(); b.closeMedia()
        }
    }

    /**
     * TURN over TLS only (`oshi-messenger.com:5349`, certificate + hostname validated), no
     * :8089, no WebSocket: allocate on both legs and move sealed audio + video both ways.
     */
    @Test
    fun sealedAudioAndVideoCrossTurnOverTls() {
        live()
        val (a, b) = pairOfLegs(CallRelayConfig(TurnCredentials(), udpRelay = null, relayOnly = true, turnTransport = "tls"))
        val atB = CopyOnWriteArrayList<ByteArray>(); val atA = CopyOnWriteArrayList<ByteArray>()
        b.tap = { atB += it.copyOf() }; a.tap = { atA += it.copyOf() }
        try {
            a.start(); b.start()
            val allocMs = waitFor("both TLS allocations", 15_000) { a.turnAllocation != null && b.turnAllocation != null }
            assertEquals("tls", a.turnTransport); assertEquals("tls", b.turnTransport)
            println("[relay-tls] allocations over TLS after $allocMs ms: A=${a.turnAllocation} B=${b.turnAllocation}")
            val pathMs = waitFor("relay pair both sides", 15_000) {
                a.tick(); b.tick()
                a.selected?.type == IceCandidateType.RELAY && b.selected?.type == IceCandidateType.RELAY
            }
            val spec = a.spec
            val audioA = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, true, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            val videoA = VideoMediaFrame.encode(spec.sessionKey, VideoMediaFrame.newSalt(true), 1, bytes(1101))
            val audioB = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, false, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            val videoB = VideoMediaFrame.encode(spec.sessionKey, VideoMediaFrame.newSalt(false), 1, bytes(1103))
            val t0 = System.currentTimeMillis()
            assertTrue(a.sendMedia(audioA)); assertTrue(a.sendMedia(videoA))
            assertTrue(b.sendMedia(audioB)); assertTrue(b.sendMedia(videoB))
            waitFor("all four frames", 5_000) {
                atB.any { it.contentEquals(audioA) } && atB.any { it.contentEquals(videoA) } &&
                    atA.any { it.contentEquals(audioB) } && atA.any { it.contentEquals(videoB) }
            }
            println("[relay-tls] relay pair after $pathMs ms; audio+video both ways byte-identical in ${System.currentTimeMillis() - t0} ms over TURN-TLS (odd sizes exercise the 4-byte ChannelData padding)")
            assertEquals(1L, a.framesAccepted.get()); assertEquals(1L, b.framesAccepted.get())
        } finally {
            a.close(); b.close()
        }
    }

    /** WebSocket relay only: no TURN, no :8089, no direct pair. */
    @Test
    fun sealedAudioAndVideoCrossTheWebSocketRelay() {
        live()
        val url = WsRelayClient.urlFor(V2Http.defaultBaseUrl())
        val (a, b) = pairOfLegs(CallRelayConfig(turn = null, udpRelay = null, relayOnly = true, webSocketUrl = url))
        val atB = CopyOnWriteArrayList<ByteArray>(); val atA = CopyOnWriteArrayList<ByteArray>()
        b.tap = { atB += it.copyOf() }; a.tap = { atA += it.copyOf() }
        try {
            a.start(); b.start()
            val regMs = waitFor("both registered on the WS relay", 10_000) { a.wsRelayClient!!.usable() && b.wsRelayClient!!.usable() }
            val spec = a.spec
            val audioA = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, true, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            val videoA = VideoMediaFrame.encode(spec.sessionKey, VideoMediaFrame.newSalt(true), 1, bytes(1100))
            val audioB = CallMediaFrame.encode(spec.sessionKey, spec.nonceSalt, false, 1, CallMediaFrame.TYPE_PCM_48K, bytes(1920))
            val t0 = System.currentTimeMillis()
            assertTrue(a.sendMedia(audioA)); assertTrue(a.sendMedia(videoA)); assertTrue(b.sendMedia(audioB))
            waitFor("frames both ways", 5_000) {
                atB.any { it.contentEquals(audioA) } && atB.any { it.contentEquals(videoA) } && atA.any { it.contentEquals(audioB) }
            }
            println("[relay-ws] registered after $regMs ms; audio+video A→B and audio B→A byte-identical in ${System.currentTimeMillis() - t0} ms over $url")
            assertEquals(1L, b.framesAccepted.get()); assertEquals(1L, a.framesAccepted.get())
            assertTrue(b.relayCarrying())
        } finally {
            a.close(); b.close()
        }
    }

    /**
     * A whole call through the production call server with EVERY UDP carrier off: no direct
     * pairs, no TURN, no :8089 — the signed WebSocket relay carries real-microphone audio and
     * real-camera video both ways.
     */
    @Test
    fun twoDesktopsCallThroughTheWebSocketRelayOnly() {
        live()
        val server = V2Http.defaultBaseUrl()
        val relay = CallRelayConfig(turn = null, udpRelay = null, relayOnly = true, webSocketUrl = WsRelayClient.urlFor(server))
        fun lane(id: DesktopIdentity, device: String) = CallLane(
            myAddress = id.userKey,
            myPrivateKey = id.identity.priv,
            transport = CallSignalClient(server, DesktopV2Signer(id)),
            deviceId = device,
            machine = CallStateMachine(id.userKey, null, device),
            mediaOpener = CallMedia.real(relay = relay),
            log = { println("[live-ws] $device: $it") },
        )
        val aId = DesktopIdentity.generate(); val bId = DesktopIdentity.generate()
        val a = lane(aId, "desk-A"); val b = lane(bId, "desk-B")
        val aModel = CallScreenModel(a, labelFor = { "B" })
        val bModel = CallScreenModel(b, labelFor = { "A" })
        a.onEvent = { aModel.onEvent(it) }
        b.onEvent = { bModel.onEvent(it) }
        val pollers = Executors.newScheduledThreadPool(2)
        for (l in listOf(a, b)) pollers.scheduleWithFixedDelay({
            runCatching { l.pollOnce(); l.tick() }.onFailure { println("[live-ws] poll failed: $it") }
        }, 0, CallLane.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        try {
            aModel.call(bId.userKey, video = true)
            waitFor("B to ring", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.INCOMING }
            bModel.answer()
            waitFor("both connected", 15_000) {
                aModel.screen?.phase == CallScreenModel.Phase.CONNECTED && bModel.screen?.phase == CallScreenModel.Phase.CONNECTED
            }
            val regMs = waitFor("both on the WS relay", 15_000) {
                a.media?.wsRelayClient?.usable() == true && b.media?.wsRelayClient?.usable() == true
            }
            println("[live-ws] both registered on the WebSocket relay $regMs ms after connect (signed upgrade)")
            val va = a.video(); val vb = b.video()
            // Camera open is asynchronous and slow on a loaded machine (measured > 10 s); wait
            // for both — they share this Mac's one camera — before the measured window.
            val camMs = runCatching {
                waitFor("both cameras", 45_000) {
                    (va?.cameraRunning == true || va?.cameraProblem != null) && (vb?.cameraRunning == true || vb?.cameraProblem != null)
                }
            }.getOrElse { -1L }
            println("[live-ws] cameras after $camMs ms: A=${va?.cameraRunning}/${va?.cameraProblem} B=${vb?.cameraRunning}/${vb?.cameraProblem}")
            val da0 = a.mediaDiagnostics(); val db0 = b.mediaDiagnostics()
            val pa0 = va?.remoteFrameCount ?: 0; val pb0 = vb?.remoteFrameCount ?: 0
            Thread.sleep(10_000)
            val da = a.mediaDiagnostics(); val db = b.mediaDiagnostics()
            val aSent = da.framesSent - da0.framesSent; val bSent = db.framesSent - db0.framesSent
            val aWs = a.media?.wsSent?.get() ?: 0; val bWs = b.media?.wsSent?.get() ?: 0
            println(
                "[live-ws] over 10 s, WebSocket only: A accepted ${da.framesAccepted - da0.framesAccepted} audio frames, " +
                    "B accepted ${db.framesAccepted - db0.framesAccepted}; packets handed to WS A=$aWs B=$bWs; " +
                    "video pictures decoded: A←B ${(va?.remoteFrameCount ?: 0) - pa0}, B←A ${(vb?.remoteFrameCount ?: 0) - pb0}; " +
                    "cameras A=${va?.cameraRunning}/${va?.cameraProblem} B=${vb?.cameraRunning}/${vb?.cameraProblem}; " +
                    "P2P selected A=${a.media?.selected} B=${b.media?.selected}",
            )
            assertEquals("no direct or TURN pair in WS-only mode", null, a.media?.selected)
            assertTrue("audio crossed the WS relay to B", db.framesAccepted - db0.framesAccepted > 50)
            assertTrue("audio crossed the WS relay to A", da.framesAccepted - da0.framesAccepted > 50)
            if (va?.cameraRunning == true) assertTrue("video crossed the WS relay to B", (vb?.remoteFrameCount ?: 0) - pb0 > 50)
            if (vb?.cameraRunning == true) assertTrue("video crossed the WS relay to A", (va?.remoteFrameCount ?: 0) - pa0 > 50)
            assertTrue("at least one camera ran", va?.cameraRunning == true || vb?.cameraRunning == true)
            aModel.hangUp()
            waitFor("B ended", 15_000) { bModel.screen?.phase == CallScreenModel.Phase.ENDED }
        } finally {
            pollers.shutdownNow()
            a.closeMedia(); b.closeMedia()
        }
    }
}
