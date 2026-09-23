package com.oshi.desktop.call.video

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * THE REAL CAMERA. Two [CallVideoSession]s wired back to back (A's datagrams are B's
 * input and vice versa): A opens this machine's camera, encodes, seals, fragments; B
 * authenticates, reassembles, decodes and exposes pictures. Everything but the UDP socket.
 *
 * Skipped with the reason printed when the camera will not open — on a Mac that is Camera
 * permission for whatever app launched the JVM (Terminal, an IDE, a CI agent), which a
 * test cannot grant itself. A skip is NOT a pass.
 */
class CameraLoopbackTest {
    @Test
    fun `camera to screen through the whole video pipeline`() {
        assumeTrue(FfmpegVideo.unavailableReason() ?: "", FfmpegVideo.available)
        // A machine with no camera (every CI runner) skips rather than fails: this test is
        // about the real device. The deviceless path is CrossHostCallBench + VideoCodecTest.
        val probe = runCatching { CameraCapture().close() }
        assumeTrue("no usable camera here: ${probe.exceptionOrNull()?.message}", probe.isSuccess)
        val key = ByteArray(32) { (it * 3).toByte() }
        val salt = byteArrayOf(1, 2, 3, 4)
        var b: CallVideoSession? = null
        var seqA = 0L; var seqB = 0L
        val a = CallVideoSession(key, salt, true, { d -> b?.onMedia(d); true }, { ++seqA },
            decoderFactory = null, log = { println("A: $it") })
        b = CallVideoSession(key, salt, false, { d -> a.onMedia(d); true }, { ++seqB },
            cameraFactory = null, log = { println("B: $it") })
        try {
            a.start(videoCall = true)
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline && b.remoteFrameCount < 30 && a.cameraProblem == null) Thread.sleep(50)
            println("CAMERA_LOOPBACK camera=${a.cameraRunning} problem=${a.cameraProblem} encoder=${a.encoderName} " +
                "sent=${a.sender.framesSent} frames/${a.sender.fragmentsSent} datagrams/${a.sender.bytesSent} B " +
                "localPreviews=${a.localFrameCount} remotePictures=${b.remoteFrameCount} " +
                "size=${b.remoteFrame?.width}x${b.remoteFrame?.height}")
            assumeTrue("camera did not open: ${a.cameraProblem}", a.cameraProblem == null && a.sender.framesSent > 0)
            assertTrue("pictures decoded on the far side", b.remoteFrameCount >= 10)
            val img = b.remoteFrame!!
            // A real camera picture is not one flat colour.
            val distinct = img.argb.asSequence().take(20_000).map { it and 0xF0F0F0 }.toSet().size
            assertTrue("distinct colours $distinct", distinct > 20)
            b.remoteFrame?.let { savePng(it, "build/camera-loopback-remote.png") }
            a.localFrame?.let { savePng(it, "build/camera-loopback-selfview.png") }
        } finally {
            a.close(); b.close()
        }
    }

    private fun savePng(img: VideoImage, path: String) {
        val bi = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width)
        javax.imageio.ImageIO.write(bi, "png", java.io.File(path))
    }
}
