package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.ImageComposeScene
import com.oshi.desktop.media.TestClips
import com.oshi.desktop.media.VideoNoteReader
import com.oshi.desktop.media.VideoNoteThumbnails
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.OshiTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * A video message shows its FIRST FRAME in the thread, not a file glyph.
 *
 * Until 2026-10-28 a video bubble was a card — a glyph, a filename and a path — which was the
 * honest thing to draw while nothing in this window could decode one. It can now, so the card
 * would be an understatement rather than a promise, and this is the test that says the frame
 * really reaches the screen.
 *
 * It is a PIXEL test for the same reason `MediaViewerRenderTest` is: the composables are
 * covered by nothing else in this repository, deliberately, and "the bubble drew something"
 * would pass on the play badge alone. The clip's chroma is set hard off neutral so its pixels
 * are a colour this window's palette never produces — only a decoded frame can satisfy it.
 */
class VideoPosterRenderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a video bubble draws the decoded first frame`() {
        assumeTrue("no H.264 encoder on this machine", TestClips.canEncode())
        val clip = tmp.newFile("holiday.h264")
        TestClips.rawH264(clip, width = 640, height = 360, frames = 8, chromaU = 20, chromaV = 20)
        assumeTrue("encoder produced nothing", clip.length() > 256)

        // The poster reads the cache synchronously on first composition and only falls back
        // to a coroutine on a miss. `ImageComposeScene` renders ONE frame and pumps no
        // dispatcher, so a miss would draw the placeholder however correct the decode is.
        VideoNoteThumbnails.load(clip.absolutePath, POSTER_PX, VideoNoteReader.Fit.CONTAIN)

        val img = render("bubble-video-poster.png", clip)
        assertTrue("the bubble is blank", spread(img) > 3.0)
        assertTrue(
            "the decoded frame is not in the bubble: ${greenish(img)} strongly green pixels",
            greenish(img) > 3_000,
        )
    }

    /**
     * And a video this window cannot decode falls back to the card it used to be — silently
     * drawing an empty box would be worse than the card ever was.
     */
    @Test
    fun `an undecodable video falls back to the file card`() {
        val broken = tmp.newFile("broken.mp4").apply { writeBytes(ByteArray(4_096) { 0x7F }) }
        VideoNoteThumbnails.load(broken.absolutePath, POSTER_PX, VideoNoteReader.Fit.CONTAIN)
        val img = render("bubble-video-undecodable.png", broken)
        assertTrue("the fallback card must still draw words", spread(img) > 3.0)
        assertTrue("nothing decoded, so nothing may be painted", greenish(img) == 0)
    }

    // ---------------------------------------------------------------- plumbing

    private companion object {
        /** The density this scene renders at — and therefore the poster's cache key. */
        const val SCALE = 2f

        /**
         * `Metrics.mediaBubble` in PIXELS at [SCALE], which is exactly what the composable
         * asks the cache for. Derived rather than typed: a literal here was wrong on the
         * first run (520 against the real 500), the lookup missed, and the test failed
         * against a perfectly correct poster — a cache key is not a round number to guess.
         */
        val POSTER_PX: Int = (Metrics.mediaBubble.value * SCALE).toInt()
    }

    private fun render(name: String, file: File): BufferedImage {
        val out = File("build/screens").apply { mkdirs() }
        val scale = SCALE
        val row = MessageRow(
            id = "m1",
            fromMe = false,
            who = "Dana",
            body = "",
            attachment = "[video] ${file.absolutePath}",
            stamp = "11:04",
            status = null,
            failed = false,
            edited = false,
            deleted = false,
            reactions = "",
        )
        val scene = ImageComposeScene(width = (520 * scale).toInt(), height = (440 * scale).toInt(), density = Density(scale)) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        MessageBubbleRow(
                            row = row,
                            grouped = false,
                            lastInRun = true,
                            wallpaper = WallpaperId.NONE,
                            viewOnce = false,
                            revealed = false,
                            onReveal = {},
                        )
                    }
                }
            }
        }
        val bytes = try {
            scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("skia refused to encode $name")
        } finally {
            scene.close()
        }
        File(out, name).writeBytes(bytes)
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    /** Pixels no part of this window's palette produces — only a decoded frame can. */
    private fun greenish(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (g > r + 40 && g > b + 40) n++
        }
        return n
    }

    private fun spread(img: BufferedImage): Double {
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 0 until img.height step 3) for (x in 0 until img.width step 3) {
            val p = img.getRGB(x, y)
            val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            sum += lum; sumSq += lum * lum; n++
        }
        val mean = sum / n
        return Math.sqrt(sumSq / n - mean * mean)
    }
}
