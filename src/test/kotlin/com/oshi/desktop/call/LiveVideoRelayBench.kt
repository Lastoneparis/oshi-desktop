package com.oshi.desktop.call

import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.transport.UdpRelayClient
import com.oshi.desktop.call.video.CallVideoSession
import com.oshi.desktop.call.video.FfmpegVideo
import com.oshi.desktop.call.video.SyntheticCamera
import org.junit.Assume
import org.junit.Test
import java.net.DatagramSocket
import java.security.SecureRandom
import java.util.UUID

/**
 * __VIDEO_ABR_2026_09_23__ LIVE: desktop ↔ desktop VIDEO through the PRODUCTION `:8089`
 * relay (relay-only, so no direct path can hide it), both directions at once, with the real
 * encoder/decoder of this machine and a synthetic camera (no real camera is opened, no
 * picture of anyone is taken). Reports, per direction, what the sender's rate controller
 * did and what the receiver decoded. `OSHI_LIVE_CALL=1`; `OSHI_LIVE_VIDEO_S` (default 30).
 */
class LiveVideoRelayBench {

    private val rng = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    private class Deviceless(spec: CallMediaSpec, send: (ByteArray) -> Unit) :
        CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
        override fun start() = Unit
        override fun stop() = Unit
    }

    @Test
    fun videoBothWaysThroughTheProductionRelay() {
        Assume.assumeTrue("set OSHI_LIVE_CALL=1", System.getenv("OSHI_LIVE_CALL") == "1")
        Assume.assumeTrue("FFmpeg: ${FfmpegVideo.unavailableReason()}", FfmpegVideo.available)
        val seconds = (System.getenv("OSHI_LIVE_VIDEO_S") ?: "30").toLong()
        val callId = UUID.randomUUID().toString().uppercase()
        val key = bytes(32); val salt = bytes(4)
        val aKey = java.util.Base64.getEncoder().encodeToString(bytes(32))
        val bKey = java.util.Base64.getEncoder().encodeToString(bytes(32))
        val relay = CallRelayConfig(turn = null, udpRelay = UdpRelayClient.DEFAULT_SERVER, relayOnly = true)
        fun leg(isCaller: Boolean, self: String, peer: String): CallMediaLeg {
            val spec = CallMediaSpec(callId, key, salt, isCaller, video = true, selfKey = self, peerKey = peer)
            val name = if (isCaller) "A" else "B"
            return CallMediaLeg(
                spec = spec,
                datagram = DatagramSocket(0),
                audioFor = { send -> Deviceless(spec, send) },
                videoFor = { send, nextSeq ->
                    CallVideoSession(
                        spec.sessionKey, spec.nonceSalt, spec.isCaller, send, nextSeq,
                        cameraFactory = { SyntheticCamera() },
                        log = { println("[live-relay] $name: $it") },
                    )
                },
                stunServer = null,
                log = { println("[live-relay] $name: $it") },
                hostCandidates = { emptyList() },
                relayConfig = relay,
            )
        }
        val a = leg(true, aKey, bKey); val b = leg(false, bKey, aKey)
        try {
            a.start(); b.start()
            val t0 = System.currentTimeMillis()
            while (!(a.udpRelayClient?.usable() == true && b.udpRelayClient?.usable() == true)) {
                check(System.currentTimeMillis() - t0 < 10_000) { "relay registration timed out" }
                Thread.sleep(50)
            }
            val va = a.video!!; val vb = b.video!!
            val every = 5L
            for (s in 1..seconds) {
                Thread.sleep(1000)
                if (s % every == 0L) {
                    for ((n, v, peer) in listOf(Triple("A→B", va, vb), Triple("B→A", vb, va))) {
                        println(
                            "[live-relay] t=${s}s $n sender: ${v.sendBitrate / 1000}k ${v.sendFps}fps rung=${v.rate?.currentRung} " +
                                "peerPLI=${v.rate?.peerKeyframeRequests} frames=${v.sender.framesSent} dgrams=${v.sender.fragmentsSent} " +
                                "KiB=${v.sender.bytesSent / 1024} PLIadmitted=${v.control.inboundKeyframeRequests} | receiver: ${peer.rxStats()}",
                        )
                    }
                }
            }
            val secs = seconds.toDouble()
            println(
                "[live-relay] SUMMARY ${seconds}s relay-only :8089 — A→B: B decoded ${vb.remoteFrameCount} (${"%.1f".format(vb.remoteFrameCount / secs)} fps), " +
                    "A sent ${va.sender.framesSent} frames ${va.sender.bytesSent * 8 / 1000 / seconds} kbit/s; " +
                    "B→A: A decoded ${va.remoteFrameCount} (${"%.1f".format(va.remoteFrameCount / secs)} fps), " +
                    "B sent ${vb.sender.framesSent} frames ${vb.sender.bytesSent * 8 / 1000 / seconds} kbit/s; " +
                    "relaySent A=${a.relaySent.get()} B=${b.relaySent.get()}",
            )
            check(vb.remoteFrameCount > seconds * 10) { "fewer than 10 fps reached B" }
        } finally {
            a.close(); b.close()
        }
    }
}
