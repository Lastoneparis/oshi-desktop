package com.oshi.desktop.media

import com.oshi.desktop.call.video.FfmpegVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An ordinary video attachment keeps the shape it was filmed in.
 *
 * The decoder this exercises is not new — it arrived with round video notes and was wired to
 * exactly one place, the round bubble, while the media viewer two files away still said
 * "Nothing in this window plays video". The ONE thing that had to change to reuse it for an
 * attachment is the picture: a note is centre-cropped to a square because it is drawn in a
 * circle, and doing that to a 16:9 clip throws away a third of the frame.
 *
 * So this file tests the difference and nothing else: [VideoNoteReader.Fit.CONTAIN] against
 * [VideoNoteReader.Fit.SQUARE_CROP] on a source that is genuinely NOT square, decoded through
 * the real FFmpeg natives. A square fixture could not tell the two apart — both would produce
 * the same picture, and the test would pass whichever one the viewer used.
 */
class VideoContainFitTest {

    private fun natives() = assumeTrue(
        "FFmpeg natives not loadable here",
        FfmpegVideo.load().isSuccess && FfmpegVideo.encoderCandidates().isNotEmpty(),
    )

    // ------------------------------------------------------------------ the arithmetic

    @Test
    fun `CONTAIN keeps the aspect ratio and scales the longest edge down`() {
        val r = sizer(targetSide = 720)
        assertEquals("16:9 scales by its WIDTH, the longest edge", 720 to 404, r(1920, 1080))
        assertEquals("9:16 portrait scales by its HEIGHT", 404 to 720, r(1080, 1920))
        assertEquals("a square source stays square", 720 to 720, r(1080, 1080))
    }

    @Test
    fun `CONTAIN never upscales`() {
        val r = sizer(targetSide = 720)
        assertEquals(
            "a 320x240 clip is decoded at its own size — upscaling in the decoder spends " +
                "time to produce a blurrier picture than the compositor would",
            320 to 240,
            r(320, 240),
        )
        assertEquals("exactly at the target is not scaled either", 720 to 480, r(720, 480))
    }

    @Test
    fun `every CONTAIN output edge is even`() {
        val r = sizer(targetSide = 100)
        // An odd edge is legal for BGRA but the SOURCE is chroma-subsampled (every phone
        // writes YUV420P), where half-sized chroma planes and an odd size is where swscale
        // reads a row that is not there.
        for (w in listOf(333, 1001, 777, 101)) for (h in listOf(999, 501, 67, 103)) {
            val (ow, oh) = r(w, h)
            assertEquals("width $ow from ${w}x$h is odd", 0, ow % 2)
            assertEquals("height $oh from ${w}x$h is odd", 0, oh % 2)
            assertTrue("$ow x $oh must stay inside the box", ow <= 100 && oh <= 100)
        }
    }

    @Test
    fun `SQUARE_CROP still answers with the square the round bubble draws`() {
        val r = sizer(targetSide = 240, fit = VideoNoteReader.Fit.SQUARE_CROP)
        assertEquals(240 to 240, r(1920, 1080))
        assertEquals(240 to 240, r(100, 100))
    }

    // ------------------------------------------------- and the same thing, really decoded

    @Test
    fun `a 16 by 9 clip decodes whole under CONTAIN and cropped under SQUARE_CROP`() {
        natives()
        val dir = Files.createTempDirectory("oshi-contain").toFile()
        try {
            val clip = File(dir, "wide.h264")
            TestClips.rawH264(clip, width = 640, height = 360, frames = 12)
            assumeTrue("no usable H.264 encoder on this machine", clip.isFile && clip.length() > 256)

            // CONTAIN: 640 is the longest edge, 320 is the box, so the whole frame at half size.
            VideoNoteReader(clip, targetSide = 320, fit = VideoNoteReader.Fit.CONTAIN).use { r ->
                assertEquals(640, r.width)
                assertEquals(360, r.height)
                val pic = r.firstPicture()
                assertNotNull("CONTAIN decoded no picture at all", pic)
                assertEquals("the width must fill the box", 320, pic!!.width)
                assertEquals("16:9 must survive the scale — 180, not 320", 180, pic.height)
            }

            // SQUARE_CROP: the same file, the same size, a square picture. If these two ever
            // agree on a non-square source, the viewer and the bubble are sharing a bug.
            VideoNoteReader(clip, targetSide = 320, fit = VideoNoteReader.Fit.SQUARE_CROP).use { r ->
                val pic = r.firstPicture()
                assertNotNull(pic)
                assertEquals(320, pic!!.width)
                assertEquals(320, pic.height)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * And the player really plays it: frames arrive, in CONTAIN shape, and the end is
     * announced exactly once.
     *
     * The clip has no audio track, so this needs no sound card and runs on a headless
     * runner — the audio seam is the voice notes' and is covered where they are.
     */
    @Test
    fun `the player drives a non-square clip to its end`() {
        natives()
        val dir = Files.createTempDirectory("oshi-play").toFile()
        try {
            val clip = File(dir, "wide.h264")
            TestClips.rawH264(clip, width = 640, height = 360, frames = 10)
            assumeTrue("no usable H.264 encoder on this machine", clip.length() > 256)

            val frames = java.util.concurrent.atomic.AtomicInteger()
            val sizes = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
            val done = java.util.concurrent.CountDownLatch(1)
            val ends = java.util.concurrent.atomic.AtomicInteger()
            val failure = java.util.concurrent.atomic.AtomicReference<String?>()

            val player = VideoNotePlayer(side = 320, fit = VideoNoteReader.Fit.CONTAIN, maxDurationMs = 60_000)
            val refused = player.play(clip, object : VideoNotePlayer.Listener {
                override fun onFrame(image: com.oshi.desktop.call.video.VideoImage, positionMs: Long) {
                    frames.incrementAndGet()
                    sizes += "${image.width}x${image.height}"
                }
                override fun onEnd(error: String?) {
                    ends.incrementAndGet(); failure.set(error); done.countDown()
                }
            })
            assertEquals("the player refused to start", null, refused)
            assertTrue("playback never ended", done.await(30, java.util.concurrent.TimeUnit.SECONDS))

            assertEquals("onEnd must fire exactly once", 1, ends.get())
            assertEquals("playback reported a failure", null, failure.get())
            assertTrue("only ${frames.get()} frames were drawn", frames.get() >= 8)
            assertEquals("every frame must be CONTAIN-shaped", setOf("320x180"), sizes.toSet())
            assertTrue("the player still thinks it is running", !player.isPlaying)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------- helpers

    /** [VideoNoteReader.outputSize] without opening a file: it needs no stream to answer. */
    private fun sizer(
        targetSide: Int,
        fit: VideoNoteReader.Fit = VideoNoteReader.Fit.CONTAIN,
    ): (Int, Int) -> Pair<Int, Int> = { w, h ->
        VideoNoteReader.outputSizeFor(w, h, targetSide, fit)
    }

}
