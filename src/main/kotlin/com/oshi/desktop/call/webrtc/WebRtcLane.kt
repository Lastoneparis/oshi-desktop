package com.oshi.desktop.call.webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The WebRTC lane — **opt-in, compiled out by default, and it talks to no OSHI phone.**
 *
 * ============================================================ READ THIS BEFORE USING IT
 *
 * This file exists only when the build ran with `-PwithWebRtc=true`; otherwise
 * `build.gradle.kts` excludes the package from both source sets and the dependency is
 * never resolved. Nothing in `com.oshi.desktop.app` imports it. That is the same status
 * PARITY.md row 0.26 gives the bot codec — implemented, exercised, and deliberately not
 * wired, with the decision recorded as open rather than taken quietly.
 *
 * **The reason it is not the shipping media path:** neither shipped OSHI client speaks
 * WebRTC. iOS links no WebRTC framework at all (`OSHI/StunClient.swift:12` — "No
 * libwebrtc. Pure Network.framework."), Android removed the dependency and documented it
 * (`app/build.gradle.kts:221-235`), there is no SDP anywhere in either tree, and media is
 * AES-256-GCM raw PCM rather than SRTP. `CALL_V2_PLAN.md:26-31` then points explicitly
 * away from Opus. So a desktop that called over WebRTC could reach another desktop
 * running this same lane and nothing else. The path that actually rings a phone is
 * [com.oshi.desktop.call.media.CallMediaFrame].
 *
 * What this lane WOULD buy, if the product ever wants desktop↔desktop or decides to move
 * the phones: a mature congestion controller, an adaptive jitter buffer, packet-loss
 * concealment and — the one the JDK cannot approximate at all — an acoustic echo
 * canceller. See [com.oshi.desktop.call.media.CallAudio]'s note on what
 * `javax.sound.sampled` does not give you.
 *
 * ============================================================ WHAT IS VERIFIED
 *
 * Measured on macOS aarch64 / JDK 17 on 2026-08-25, and reproduced by
 * `WebRtcLaneTest`: the native library loads, a [PeerConnectionFactory] is created in
 * about a second, an SDP offer is generated and is well-formed (`m=audio`, `a=ice-ufrag:`,
 * `a=fingerprint:`, opus among the codecs), and **two in-process peers complete
 * offer → answer → ICE and both reach [RTCPeerConnectionState.CONNECTED]** with five host
 * candidates each.
 *
 * ============================================================ WHAT IS NOT, AND CANNOT BE
 *
 * Stated the way PARITY.md row 0.27 states the LoRa radio link, because it is the same
 * kind of gap:
 *
 *  - **No audio hardware has ever been opened by this lane.** Every test uses
 *    [HeadlessAudioDeviceModule]. Capture and render are unexercised.
 *  - **No call has crossed a machine boundary.** Two peers in one JVM share a loopback
 *    interface; they prove the API, the DTLS handshake and the ICE loop, and they prove
 *    nothing about NAT traversal, TURN, or a real network.
 *  - **Only the macos-aarch64 native has ever been loaded.** The windows-x86_64 and
 *    linux-x86_64 natives are published and have never run here. **windows-aarch64 is not
 *    published at all** — Windows on ARM cannot use this lane.
 *  - No STUN or TURN server is configured by default ([iceServers] is empty). The OSHI
 *    TURN deployment is a coturn with `use-auth-secret` whose ephemeral credentials come
 *    from `GET /voip/turn-creds`; wiring that here is not done, because a lane that
 *    cannot call a phone does not need to traverse a NAT to reach one.
 */
class WebRtcLane(
    /** Empty by default. See the WHAT IS NOT section. */
    private val iceServers: List<RTCIceServer> = emptyList(),
    /**
     * Headless by default so a test can run with no sound card.
     *
     * This default is also the honest one for CI: swapping in a real device module is the
     * single change that would make these tests depend on hardware, and it is left to a
     * caller that has some.
     */
    private val audioModule: AudioDeviceModuleBase = HeadlessAudioDeviceModule(),
) : AutoCloseable {

    private val factory: PeerConnectionFactory = PeerConnectionFactory(audioModule)

    /** One peer connection plus the plumbing to drive it synchronously from a test. */
    inner class Peer(
        /** Called for every locally gathered candidate. Hand these to the far side. */
        private val onCandidate: (RTCIceCandidate) -> Unit = {},
        /** Called on every connection-state change. */
        private val onState: (RTCPeerConnectionState) -> Unit = {},
    ) : AutoCloseable {

        private val observer = object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = onCandidate(candidate)
            override fun onConnectionChange(state: RTCPeerConnectionState) = onState(state)
        }

        val connection: RTCPeerConnection = factory.createPeerConnection(
            RTCConfiguration().apply { iceServers = ArrayList(this@WebRtcLane.iceServers) },
            observer,
        )

        /** Add a local microphone track. Silent under a headless device module. */
        fun addAudioTrack(id: String = "oshi-audio", stream: String = "oshi-stream") {
            val source = factory.createAudioSource(AudioOptions())
            connection.addTrack(factory.createAudioTrack(id, source), listOf(stream))
        }

        fun createOffer(): RTCSessionDescription = await { future ->
            connection.createOffer(RTCOfferOptions(), sdpObserver(future))
        }

        fun createAnswer(): RTCSessionDescription = await { future ->
            connection.createAnswer(RTCAnswerOptions(), sdpObserver(future))
        }

        fun setLocal(sdp: RTCSessionDescription) = awaitVoid { f ->
            connection.setLocalDescription(sdp, voidObserver(f))
        }

        fun setRemote(sdp: RTCSessionDescription) = awaitVoid { f ->
            connection.setRemoteDescription(sdp, voidObserver(f))
        }

        fun addCandidate(c: RTCIceCandidate) = connection.addIceCandidate(c)

        val state: RTCPeerConnectionState get() = connection.getConnectionState()

        override fun close() {
            runCatching { connection.close() }
        }
    }

    override fun close() {
        runCatching { factory.dispose() }
    }

    // ------------------------------------------------------------------ callback → value
    //
    // libwebrtc's API is entirely observer-based and every callback lands on a native
    // thread. Bridging to a value here rather than at each call site is what keeps the
    // lane testable: an assertion cannot be written against a callback that may or may
    // not have fired yet, and a test that sleeps instead is a flake.

    private fun sdpObserver(f: CompletableFuture<RTCSessionDescription>) =
        object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) { f.complete(description) }
            override fun onFailure(error: String) { f.completeExceptionally(RuntimeException(error)) }
        }

    private fun voidObserver(f: CompletableFuture<Unit>) = object : SetSessionDescriptionObserver {
        override fun onSuccess() { f.complete(Unit) }
        override fun onFailure(error: String) { f.completeExceptionally(RuntimeException(error)) }
    }

    private fun await(block: (CompletableFuture<RTCSessionDescription>) -> Unit): RTCSessionDescription {
        val f = CompletableFuture<RTCSessionDescription>()
        block(f)
        return f.get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitVoid(block: (CompletableFuture<Unit>) -> Unit) {
        val f = CompletableFuture<Unit>()
        block(f)
        f.get(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    companion object {
        /**
         * Bounded, not infinite. A native callback that never fires would otherwise hang a
         * test run forever — and a hung CI job reports nothing, which is the failure mode
         * this repository's test-census guard exists to prevent.
         */
        const val OP_TIMEOUT_SECONDS = 10L

        /**
         * Is the native library loadable on this machine?
         *
         * Answers the question `build.gradle.kts` can only guess at from `os.arch`: the
         * classifier may be published and the jar still absent from this classpath, or the
         * platform may have no published native at all (windows-aarch64). Catches
         * [Throwable] because a missing native surfaces as [UnsatisfiedLinkError], an
         * [Error] rather than an [Exception].
         */
        fun isAvailable(): Boolean = runCatching {
            PeerConnectionFactory(HeadlessAudioDeviceModule()).dispose()
        }.isSuccess
    }
}
