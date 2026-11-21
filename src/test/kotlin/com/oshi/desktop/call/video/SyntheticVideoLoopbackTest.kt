package com.oshi.desktop.call.video

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Does a PICTURE survive the video pipeline — not just a frame count?
 *
 * Nothing asserted that until now. `CrossHostCallBench` and `LiveVideoRelayBench` push
 * [SyntheticCamera]'s moving bars through the real encoder, fragmenter, seal and decoder
 * and then check `remoteFrameCount`, which a pipeline emitting uniform grey would satisfy
 * just as well. [CameraLoopbackTest] does look at the pixels, but it needs the webcam, and
 * on a machine whose camera hands out black frames — a covered lens, or macOS withholding
 * the picture from whatever launched the JVM — it can only skip. So the one claim row
 * 2.1-v actually rests on, that what goes in comes out looking like something, was the one
 * claim no test made on a machine without a working camera.
 *
 * This is that test, and it needs no camera and no permission: colour bars in, encode,
 * seal, fragment, authenticate, reassemble, decode, and the far side's picture must still
 * have many distinct colours. What it does NOT exercise is [CameraCapture] — the capture
 * device itself — which stays [CameraLoopbackTest]'s job.
 */
class SyntheticVideoLoopbackTest {

    /** Coarse colour count: a flat frame has one, colour bars a handful, a camera thousands. */
    private fun colours(img: VideoImage): Int =
        img.argb.asSequence().take(20_000).map { it and 0xF0F0F0 }.toSet().size

    @Test
    fun `colour bars survive encode, seal, fragment and decode`() {
        assumeTrue(FfmpegVideo.unavailableReason() ?: "", FfmpegVideo.available)
        val key = ByteArray(32) { (it * 7).toByte() }
        val salt = byteArrayOf(9, 8, 7, 6)
        var b: CallVideoSession? = null
        var seqA = 0L
        var seqB = 0L
        val a = CallVideoSession(
            key, salt, true, { d -> b?.onMedia(d); true }, { ++seqA },
            cameraFactory = { SyntheticCamera() }, decoderFactory = null, log = { println("A: $it") },
        )
        b = CallVideoSession(
            key, salt, false, { d -> a.onMedia(d); true }, { ++seqB },
            cameraFactory = null, log = { println("B: $it") },
        )
        try {
            a.start(videoCall = true)
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && b.remoteFrameCount < 20 && a.cameraProblem == null) {
                Thread.sleep(50)
            }
            println(
                "SYNTHETIC_LOOPBACK problem=${a.cameraProblem} encoder=${a.encoderName} " +
                    "sent=${a.sender.framesSent} frames/${a.sender.fragmentsSent} datagrams/${a.sender.bytesSent} B " +
                    "decoded=${b.remoteFrameCount} size=${b.remoteFrame?.width}x${b.remoteFrame?.height}",
            )
            assertTrue("the synthetic source must not fail: ${a.cameraProblem}", a.cameraProblem == null)
            assertTrue("pictures decoded on the far side, got ${b.remoteFrameCount}", b.remoteFrameCount >= 10)
            // Compare what came OUT against what went IN, rather than against a number.
            // The first version of this asserted `> 20` distinct colours, borrowed from
            // CameraLoopbackTest, and failed at 10 — which is simply what colour BARS
            // are: a handful of saturated colours, not a camera's thousands. A threshold
            // picked for one source says nothing about another, so the source is the
            // reference here and the test holds for any future one.
            val sent = a.localFrame
            val got = b.remoteFrame!!
            val sentColours = sent?.let { colours(it) } ?: 0
            val gotColours = colours(got)
            println("SYNTHETIC_LOOPBACK colours sent=$sentColours got=$gotColours")
            assertTrue("the source itself was flat ($sentColours colours) — nothing to prove",
                sentColours > 4)
            assertTrue("a picture went in with $sentColours colours and $gotColours came out — " +
                "the pipeline flattened it", gotColours >= sentColours / 2)
            assertTrue("the far side's picture is flat ($gotColours colours)", gotColours > 4)
        } finally {
            a.close()
            b.close()
        }
    }
}
