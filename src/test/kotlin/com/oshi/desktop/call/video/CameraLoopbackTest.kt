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
            b.remoteFrame?.let { savePng(it, "build/camera-loopback-remote.png") }
            a.localFrame?.let { savePng(it, "build/camera-loopback-selfview.png") }
            val img = b.remoteFrame!!
            // A real camera picture is not one flat colour.
            val distinct = colours(img)
            // …but neither is a BLACK one, and this test could not tell the two apart.
            // It compared only the far side, so a camera that opens and hands out black
            // frames — a covered lens, or macOS withholding the picture from whatever
            // app launched the JVM while still allowing the device — failed it with
            // "distinct colours 1", blaming the pipeline for the room. Measured on
            // 2026-10-29: the pipeline was intact (camera open, 30 frames encoded by
            // h264_videotoolbox, 30 datagrams, 30 pictures decoded at 640x360) and
            // `ffmpeg -f avfoundation -i 0` from the same shell returned an equally black
            // frame, which is the control that settles it.
            //
            // So the question is asked of the SELF-VIEW first: what did the camera give
            // us BEFORE any of our code touched it? Flat in, nothing to preserve, and the
            // run cannot conclude — that is a skip with the reason named. Colour in and
            // flat out is the regression this test exists to catch, and still fails.
            val localDistinct = a.localFrame?.let { colours(it) } ?: 0
            assumeTrue(
                "the camera opened but handed us a FLAT picture ($localDistinct distinct " +
                    "colours in the self-view), so this run cannot prove the pipeline keeps " +
                    "one. On a Mac that is a covered lens, or Camera permission for whatever " +
                    "app launched the JVM — which a test cannot grant itself. Control: " +
                    "`ffmpeg -f avfoundation -pixel_format nv12 -i 0 -frames:v 1` from the " +
                    "same shell returns black too when this is the cause. A skip is NOT a pass.",
                localDistinct > 20,
            )
            assertTrue(
                "the camera's own picture had $localDistinct distinct colours and only " +
                    "$distinct survived to the far side — the pipeline lost it",
                distinct > 20,
            )
        } finally {
            a.close(); b.close()
        }
    }

    /** Coarse colour count: a real picture has many, a black or flat frame has one. */
    private fun colours(img: VideoImage): Int =
        img.argb.asSequence().take(20_000).map { it and 0xF0F0F0 }.toSet().size

    private fun savePng(img: VideoImage, path: String) {
        val bi = java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width)
        javax.imageio.ImageIO.write(bi, "png", java.io.File(path))
    }
}
