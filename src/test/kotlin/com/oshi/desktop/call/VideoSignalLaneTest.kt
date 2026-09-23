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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __VIDEO_PLI_SIGNAL_2026_09_23__ iOS sends `0x0B` (keyframe request) and `0x0C`/`0x0D`
 * (camera paused/resumed) ONLY as sealed call signals, never on the media socket. These
 * pin that the lane hands them to the video half — and only from the peer of this call.
 */
class VideoSignalLaneTest {

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
                    // No camera, no decoder: the control lane is what is under test.
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
                type = CallSignalType.CALL_REQUEST, // iOS/Android: the receiver reads the real type from byte 0
            ),
            nowMs,
        )
    }

    private fun connect(): Pair<Endpoint, Endpoint> {
        val a = Endpoint(); val b = Endpoint()
        a.lane.call(b.address, t0, video = true)
        b.lane.pollOnce(t0 + 10)
        b.lane.answer(t0 + 20)
        a.lane.pollOnce(t0 + 30)
        b.lane.pollOnce(t0 + 40)
        return a to b
    }

    @Test
    fun theSignalChannelKeyframeRequestAndCameraTogglesReachTheVideoHalf() {
        val (a, b) = connect()
        val video = b.lane.video()!!
        video.sender.keyframeWanted = false

        sendControl(a.identity, b, CallPacket.Type.VIDEO_PAUSED, t0 + 50)
        b.lane.pollOnce(t0 + 60)
        assertTrue("0x0C over /signal marks the peer's camera off", video.remoteCameraOff)

        sendControl(a.identity, b, CallPacket.Type.REQUEST_KEYFRAME, t0 + 70)
        b.lane.pollOnce(t0 + 80)
        assertEquals(1L, video.control.inboundKeyframeRequests)
        assertTrue("an iPhone PLI must force our next frame to be an IDR", video.sender.keyframeWanted)

        sendControl(a.identity, b, CallPacket.Type.VIDEO_RESUMED, t0 + 90)
        b.lane.pollOnce(t0 + 100)
        assertFalse(video.remoteCameraOff)
    }

    @Test
    fun aStrangersSignalChannelToggleIsIgnored() {
        val (_, b) = connect()
        val video = b.lane.video()!!
        sendControl(DesktopIdentity.generate(), b, CallPacket.Type.VIDEO_PAUSED, t0 + 50)
        b.lane.pollOnce(t0 + 60)
        assertFalse("only the peer of this call may flip the camera indicator", video.remoteCameraOff)
        assertEquals(0L, video.control.inboundKeyframeRequests)
    }
}
