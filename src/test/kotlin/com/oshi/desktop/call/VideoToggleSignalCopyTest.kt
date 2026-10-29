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
 * __VIDEO_TOGGLE_SIGNAL_2026_10_29__ A desktop's camera on/off announcement also leaves as a
 * sealed call signal, the way both phones send theirs.
 *
 * Row 2.1-c ended *"the phones ALSO send camera toggles over the signalling server
 * (`call_signal`); the desktop neither sends nor reads that copy — media-channel only"*. The
 * reading half had landed on 2026-09-23 and the line was never updated; the sending half was
 * genuinely missing, and this is its test. Typed `videoToggle`, which is neither a critical
 * nor an offer type, so at 37 sealed bytes the server budgets it as `control` exactly like
 * the keyframe copy.
 */
class VideoToggleSignalCopyTest {

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

    private fun toggleSignals(): List<JSONObject> =
        server.postedBodies.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("type") == CallLane.VIDEO_TOGGLE_ENVELOPE_TYPE }

    @Test
    fun theCameraButtonAlsoLeavesAsATypedSignal() {
        val a = Endpoint(); val b = Endpoint()
        a.lane.call(b.address, t0, video = true)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)
        b.lane.pollOnce(t0 + 40)

        val video = a.lane.video()
        assertTrue("the video half must be open on a video call", video != null)

        // Camera OFF — the media channel carries 0x0C, and so must /signal.
        video!!.setCameraEnabled(false)
        waitFor(3_000) { toggleSignals().isNotEmpty() }
        val off = toggleSignals()
        assertEquals("one typed camera toggle on /signal", 1, off.size)
        assertEquals(CallSignalClient.base64Url(b.address), off[0].optString("recipient"))
        // 9-byte packet sealed = 37 B, under the server's 48-byte `control` ceiling.
        val sealed = Base64.getDecoder().decode(off[0].optString("signal"))
        assertTrue("sealed ${sealed.size} B must stay in the control budget", sealed.size <= 48)

        // Camera ON — a second, distinct announcement. A toggle is not idempotent the way a
        // keyframe request is, so unlike the PLI copy this one is NOT rate-limited away:
        // losing the resume would leave the peer showing a camera-off placeholder for good.
        video.setCameraEnabled(true)
        waitFor(3_000) { toggleSignals().size >= 2 }
        assertEquals("the resume must not be swallowed", 2, toggleSignals().size)
    }
}
