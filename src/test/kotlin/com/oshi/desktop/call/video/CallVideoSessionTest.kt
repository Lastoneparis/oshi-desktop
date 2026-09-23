package com.oshi.desktop.call.video

import com.oshi.desktop.call.media.CallMediaFrame
import com.oshi.desktop.call.media.VideoControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control lane of a live call, deviceless: what goes out for each thing that comes in. */
class CallVideoSessionTest {
    private val key = ByteArray(32) { (it + 9).toByte() }
    private val salt = byteArrayOf(9, 8, 7, 6)
    private var seq = 100L
    private val out = ArrayList<ByteArray>()

    private fun session(camera: (() -> CameraCapture)? = null) = CallVideoSession(
        key, salt, isCaller = false, sendDatagram = { out += it; true }, nextAudioSeq = { ++seq },
        cameraFactory = camera, decoderFactory = null,
    )

    private fun peerUpgrade(code: Int, s: Long) =
        VideoControl.encodeUpgrade(key, salt, isCaller = true, sharedAudioSeq = s, type = VideoControl.VIDEO_UPGRADE, code = code)

    private fun sentCodes() = out.mapNotNull { VideoControl.decodeUpgrade(key, it)?.code }

    @Test
    fun `an upgrade request with no camera is answered watch-only, never 0x02`() {
        val v = session(camera = null)
        assertTrue(v.onMedia(peerUpgrade(VideoControl.VUPG_REQUEST, 5)))
        assertTrue(v.upgradeRequested)
        assertTrue(v.acceptUpgrade(shareCamera = true))
        // cameraFactory == null: no camera can open, so the answer must be 0x05.
        assertEquals(listOf(VideoControl.VUPG_ACCEPT_WATCH), sentCodes())
        assertTrue(v.active)
    }

    @Test
    fun `a camera that refuses to open downgrades the answer to watch-only`() {
        val v = session(camera = { throw CameraCapture.CameraUnavailable("permission denied") })
        v.onMedia(peerUpgrade(VideoControl.VUPG_REQUEST, 5))
        v.acceptUpgrade(shareCamera = true)
        assertEquals(listOf(VideoControl.VUPG_ACCEPT_WATCH), sentCodes())
        assertTrue(v.cameraProblem != null)
    }

    @Test
    fun `upgrade controls are sealed on the shared audio counter`() {
        val v = session()
        v.onMedia(peerUpgrade(VideoControl.VUPG_REQUEST, 5))
        v.declineUpgrade()
        val decoded = CallMediaFrame.decode(key, out.single())!!
        assertEquals(101L, decoded.seq)
        assertEquals(VideoControl.VUPG_DECLINE, decoded.pcm[0].toInt())
    }

    @Test
    fun `a replayed upgrade is ignored`() {
        val v = session()
        val pkt = peerUpgrade(VideoControl.VUPG_REQUEST, 5)
        v.onMedia(pkt)
        v.declineUpgrade()
        v.onMedia(pkt)
        assertFalse(v.upgradeRequested)
    }

    @Test
    fun `camera toggles announce both ways the phones listen`() {
        val v = session()
        v.setCameraEnabled(false)
        assertEquals(listOf(VideoControl.VUPG_CAMERA_OFF), sentCodes())
        assertTrue(out.any { it.size == 9 && it[0].toInt() == VideoControl.VIDEO_PAUSED })
        out.clear()
        v.setCameraEnabled(true)
        assertEquals(listOf(VideoControl.VUPG_CAMERA_ON), sentCodes())
        assertTrue(out.any { it.size == 9 && it[0].toInt() == VideoControl.VIDEO_RESUMED })
    }

    @Test
    fun `remote camera flags and keyframe requests are consumed, audio and in-band hang-up are not`() {
        val v = session()
        assertTrue(v.onMedia(VideoControl.encodeToggle(VideoControl.VIDEO_PAUSED, 1)))
        assertTrue(v.remoteCameraOff)
        assertTrue(v.onMedia(VideoControl.encodeToggle(VideoControl.VIDEO_RESUMED, 2)))
        assertFalse(v.remoteCameraOff)
        v.sender.keyframeWanted = false
        assertTrue(v.onMedia(VideoControl.encodeToggle(VideoControl.KEYFRAME_REQUEST, 3)))
        assertTrue(v.sender.keyframeWanted)
        // An audio frame and iOS's sealed in-band callEnd (0x0D, ≥29 bytes) go to audio.
        val audio = CallMediaFrame.encode(key, salt, true, 7, CallMediaFrame.TYPE_PCM_48K, ByteArray(1920))
        assertFalse(v.onMedia(audio))
        val inbandEnd = CallMediaFrame.encode(key, salt, true, 8, 0x0D, "hungUp".toByteArray())
        assertFalse(v.onMedia(inbandEnd))
    }

    @Test
    fun `the peer stopping video stops ours`() {
        val v = session()
        v.onMedia(peerUpgrade(VideoControl.VUPG_REQUEST, 5))
        v.acceptUpgrade(false)
        assertTrue(v.active)
        v.onMedia(VideoControl.encodeUpgrade(key, salt, true, 6, VideoControl.VIDEO_STOP, VideoControl.VUPG_STOP))
        assertFalse(v.active)
    }
}
