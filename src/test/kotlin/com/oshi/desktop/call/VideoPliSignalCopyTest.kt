package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.media.CallAudioSession
import com.oshi.desktop.call.transport.IceCandidate
import com.oshi.desktop.call.transport.IceCandidateType
import com.oshi.desktop.call.transport.IcePriority
import com.oshi.desktop.call.video.CallVideoSession
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __VIDEO_PLI_SIGNAL_2026_09_23__ / __CALL_VIDEO_SIGNAL_2026_09_23__ A desktop's keyframe
 * request also leaves as a sealed call signal — the only form an iPhone up to b145 reads —
 * typed `keyframeRequest` so the call server budgets it as one, and at most once a second.
 */
class VideoPliSignalCopyTest {

    private val server = FakeCallServer()
    private val t0 = System.currentTimeMillis()
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val opened = ArrayList<CallMediaLeg>()

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
                audioFor = { send ->
                    object : CallAudioSession(spec.sessionKey, spec.nonceSalt, spec.isCaller, send) {
                        override fun start() = Unit
                        override fun stop() = Unit
                    }
                },
                videoFor = { send, nextSeq ->
                    CallVideoSession(
                        spec.sessionKey, spec.nonceSalt, spec.isCaller, send, nextSeq,
                        cameraFactory = null, decoderFactory = null,
                    )
                },
                stunServer = null,
                log = log,
                hostCandidates = { s ->
                    listOf(IceCandidate(IceCandidateType.HOST, "127.0.0.1", s.localPort, IcePriority.ios(IceCandidateType.HOST, 4)))
                },
            ).also { opened.add(it) }
    }

    private inner class Endpoint {
        val identity: DesktopIdentity = DesktopIdentity.generate()
        val address: String get() = identity.userKey
        private val deviceId = "dev-" + UUID.randomUUID().toString().take(8)
        val lane = CallLane(
            myAddress = identity.userKey,
            myPrivateKey = identity.identity.priv,
            transport = CallSignalClient(server.baseUrl, DesktopV2Signer(identity)),
            deviceId = deviceId,
            machine = CallStateMachine(identity.userKey, null, deviceId),
            mediaOpener = Opener(),
            mediaProbeIntervalMs = 0,
        )
    }

    private fun sendControl(from: DesktopIdentity, to: Endpoint, type: CallPacket.Type, nowMs: Long) {
        val packet = CallPacket.encode(type, nowMs, ByteArray(0))
        val sealed = CallSignalCrypto.seal(from.identity.priv, Base64.getDecoder().decode(to.address), packet)
        CallSignalClient(server.baseUrl, DesktopV2Signer(from)).sendSignal(
            CallSignalEnvelope(
                sender = CallSignalClient.base64Url(from.userKey),
                recipient = CallSignalClient.base64Url(to.address),
                signalBase64 = Base64.getEncoder().encodeToString(sealed),
                callId = to.lane.callId!!,
                type = CallSignalType.CALL_REQUEST,
            ),
            nowMs,
        )
    }

    private fun keyframeSignals(): List<JSONObject> =
        server.postedBodies.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == CallLane.KEYFRAME_REQUEST_ENVELOPE_TYPE }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test
    fun aKeyframeRequestAlsoLeavesAsATypedSignalAtMostOnceASecond() {
        val a = Endpoint(); val b = Endpoint()
        a.lane.call(b.address, t0, video = true)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)
        b.lane.pollOnce(t0 + 40)

        // A resumes its camera: B must ask A for a fresh IDR (its picture is stale).
        sendControl(a.identity, b, CallPacket.Type.VIDEO_RESUMED, t0 + 50)
        b.lane.pollOnce(t0 + 60)
        waitFor(3_000) { keyframeSignals().isNotEmpty() }
        val sig = keyframeSignals()
        assertEquals("one typed keyframe request on /signal", 1, sig.size)
        assertEquals(CallSignalClient.base64Url(a.address), sig[0].optString("recipient"))
        // The sealed body is the 9-byte 0x0B packet: 37 B, the size the server classifies as control.
        val sealed = Base64.getDecoder().decode(sig[0].optString("signal"))
        assertTrue("sealed ${sealed.size} B", sealed.size <= 48)

        // A second resume in the same second: the media copy may go, the signal copy may not.
        sendControl(a.identity, b, CallPacket.Type.VIDEO_RESUMED, t0 + 70)
        b.lane.pollOnce(t0 + 80)
        Thread.sleep(300)
        assertEquals(1, keyframeSignals().size)
    }
}
