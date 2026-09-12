package com.oshi.desktop.call.webrtc

import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The WebRTC lane — what can actually be verified without a second machine.
 *
 * These tests only exist when the build ran with `-PwithWebRtc=true`; otherwise the whole
 * package is excluded from both source sets and none of this compiles.
 *
 * ============================================================ WHAT THESE PROVE
 *
 * That the dependency resolves, that its JNI native loads on THIS machine, that a
 * `PeerConnection` is created, that a generated SDP offer is well-formed, that ICE
 * candidates are gathered, and that **two in-process peers complete a full
 * offer/answer/ICE exchange and both reach CONNECTED**. That last one is the strongest
 * test available without a second machine, and it exercises the real DTLS handshake and
 * the real ICE agent rather than a mock of either.
 *
 * ============================================================ WHAT THEY DO NOT PROVE
 *
 * Stated the way PARITY.md row 0.27 states the LoRa radio link, because it is the same
 * kind of gap and the same temptation to overclaim:
 *
 *  - **No audio hardware is opened.** Every peer here uses `HeadlessAudioDeviceModule`.
 *    Capture and render are untested on every platform.
 *  - **No packet leaves the machine.** Two peers on one loopback interface say nothing
 *    about NAT traversal, TURN, or a real network path.
 *  - **Only the host's native has ever loaded.** The suite was developed on macos-aarch64;
 *    windows-x86_64 and linux-x86_64 are published and unrun here, and windows-aarch64 is
 *    not published at all.
 *  - **No OSHI phone can answer any of this.** Neither shipped client speaks WebRTC — see
 *    `WebRtcLane`'s class doc. A green run here is not evidence that a call works.
 *
 * The `assumeTrue` below is deliberate and its consequence is understood: on a machine
 * with no native these SKIP, and the build's test census prints every skip by name so a
 * run that measured nothing cannot pass as a run that measured something.
 */
class WebRtcLaneTest {

    private fun requireNative() {
        assumeTrue(
            "no libwebrtc native for this platform — see build.gradle.kts's WEBRTC block",
            WebRtcLane.isAvailable(),
        )
    }

    @Test
    fun `the native library loads and a peer connection factory is created`() {
        requireNative()
        WebRtcLane().use { lane ->
            lane.Peer().use { peer ->
                assertEquals(RTCPeerConnectionState.NEW, peer.state)
            }
        }
    }

    /**
     * A generated offer must be real SDP, not an empty string.
     *
     * The four assertions are the four things that make an offer answerable: a media
     * section, ICE credentials, a DTLS fingerprint, and at least one codec.
     */
    @Test
    fun `an sdp offer is generated and is well-formed`() {
        requireNative()
        WebRtcLane().use { lane ->
            lane.Peer().use { peer ->
                peer.addAudioTrack()
                val offer = peer.createOffer()
                assertEquals(RTCSdpType.OFFER, offer.sdpType)
                assertTrue("must offer an audio media section", offer.sdp.contains("m=audio"))
                assertTrue("must carry ICE credentials", offer.sdp.contains("a=ice-ufrag:"))
                assertTrue("must carry a DTLS fingerprint", offer.sdp.contains("a=fingerprint:"))
                assertTrue("opus is libwebrtc's default voice codec", offer.sdp.lowercase().contains("opus"))
            }
        }
    }

    /**
     * The full exchange, in one JVM: offer → setLocal → setRemote → answer → ICE, both
     * sides to CONNECTED.
     *
     * Bounded by a wall-clock deadline rather than an unbounded wait, because a native
     * callback that never fires would otherwise hang CI forever — and a hung job reports
     * nothing, which is the failure this repository's test census exists to catch.
     */
    @Test
    fun `two in-process peers complete offer, answer and ICE and both reach connected`() {
        requireNative()
        WebRtcLane().use { lane ->
            val aOut = ConcurrentLinkedQueue<RTCIceCandidate>()
            val bOut = ConcurrentLinkedQueue<RTCIceCandidate>()
            lane.Peer(onCandidate = { aOut.add(it) }).use { a ->
                lane.Peer(onCandidate = { bOut.add(it) }).use { b ->
                    a.addAudioTrack()

                    val offer = a.createOffer()
                    a.setLocal(offer)
                    b.setRemote(offer)
                    val answer = b.createAnswer()
                    b.setLocal(answer)
                    a.setRemote(answer)
                    assertEquals(RTCSdpType.ANSWER, answer.sdpType)

                    var candidates = 0
                    val deadline = System.currentTimeMillis() + CONNECT_DEADLINE_MS
                    var connected = false
                    while (System.currentTimeMillis() < deadline) {
                        while (aOut.isNotEmpty()) { b.addCandidate(aOut.poll()); candidates++ }
                        while (bOut.isNotEmpty()) { a.addCandidate(bOut.poll()); candidates++ }
                        if (a.state == RTCPeerConnectionState.CONNECTED &&
                            b.state == RTCPeerConnectionState.CONNECTED
                        ) {
                            connected = true
                            break
                        }
                        Thread.sleep(50)
                    }

                    assertTrue("ICE gathering produced no candidates at all", candidates > 0)
                    assertTrue(
                        "both peers must reach CONNECTED: a=${a.state} b=${b.state}",
                        connected,
                    )
                }
            }
        }
    }

    private companion object {
        /** Loopback ICE settles in well under a second; 20 s is a generous CI ceiling. */
        const val CONNECT_DEADLINE_MS = 20_000L
    }
}
