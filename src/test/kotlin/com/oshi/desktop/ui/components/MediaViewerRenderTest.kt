package com.oshi.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.ui.OshiTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.oshi.desktop.media.VideoNoteThumbnails
import com.oshi.desktop.media.VideoNoteReader
import com.oshi.desktop.media.TestClips
import org.junit.rules.TemporaryFolder

/**
 * Does the viewer actually PUT THE PICTURE ON THE SCREEN?
 *
 * ============================================================ WHY THIS IS A TEST AND `ScreenRenderer` IS NOT
 *
 * `ScreenRenderer` is deliberately a task and not a test: a golden image of a young UI fails
 * on every intentional change until somebody deletes the assertion. Nothing here is a golden
 * image. The assertions are about INK, and specifically about one fact no other test in this
 * repository can reach:
 *
 *   **a colour that exists only inside the decoded file comes out the other end.**
 *
 * The fixture image is one flat, absurd magenta that appears nowhere in `OshiTheme`. If the
 * `Image` composable is dropped, if the bitmap is never handed to it, if the panel measures
 * to zero height, if the decode silently returns something empty — the magenta count goes to
 * zero and this goes red. That is the difference between "the loader returned a bitmap"
 * (which `MediaViewerTest` proves) and "the user can see the photograph".
 *
 * It rasterises through `ImageComposeScene`, so it needs no display and runs on the headless
 * Windows and Linux legs where nobody has ever looked at this window.
 *
 * **It does not prove the viewer is right.** Alignment, spacing, wrapping, contrast, whether
 * the close button is where a hand expects it: none of that is here, and the PNGs this test
 * leaves in `build/screens/` are for a person to look at, which remains the only real check.
 */
class MediaViewerRenderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        init {
            // No window to composite into on a test runner; the GPU path wants a surface it
            // will not get. `ScreenRenderer`'s Gradle task sets the same property.
            System.setProperty("skiko.renderApi", "SOFTWARE")
        }

        /** Nowhere in `OshiTheme`. That is the whole point — see the class note. */
        private const val FIXTURE_ARGB = 0xFFE0218A.toInt()
        private const val W = 900
        private const val H = 760
    }

    @Test
    fun `an image the loader accepted is really drawn, in its own colour`() {
        val f = tmp.newFile("parrot.png").also { writePng(it, 400, 300, FIXTURE_ARGB) }
        val png = render(
            "viewer-01-image.png",
            MediaViewerTarget(MediaType.IMAGE, f.absolutePath, "parrot.png", viewOnce = false),
        )
        val magenta = count(png, FIXTURE_ARGB)
        assertTrue(
            "the decoded picture must reach the screen — magenta pixels found: $magenta. Zero " +
                "means the bitmap never got drawn, however well it decoded.",
            magenta > 20_000,
        )
        assertTrue("and the panel around it must have ink too", spread(png) > 3.0)
    }

    @Test
    fun `a truncated image is drawn AND the incompleteness is on the screen with it`() {
        val whole = tmp.newFile("whole.png").also { writePng(it, 400, 300, FIXTURE_ARGB) }.readBytes()
        val half = tmp.newFile("half.png").also { it.writeBytes(whole.copyOfRange(0, whole.size / 2)) }
        // The load is what the overlay will draw; assert here that this fixture really is the
        // "shown with a caveat" case, so the render below is the one being looked at.
        val load = MediaViewerLoader.load(half.absolutePath) as MediaLoad.Ready
        assertTrue(load.caveats.contains(MediaViewerLimits.TRUNCATED_CAVEAT))

        val png = render(
            "viewer-02-truncated.png",
            MediaViewerTarget(MediaType.IMAGE, half.absolutePath, "half.png", viewOnce = false),
        )
        assertTrue("the part that did decode is still shown", count(png, FIXTURE_ARGB) > 2_000)
        assertTrue(spread(png) > 3.0)
    }

    @Test
    fun `a refusal draws words, not a blank box and not the picture`() {
        val f = tmp.newFile("holiday.png")
        f.writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(64))
        val png = render(
            "viewer-03-refused.png",
            // Declared IMAGE on purpose: this is the mislabelled-file case, where the row
            // says photo and the bytes say video. The refusal must come from the BYTES.
            MediaViewerTarget(MediaType.IMAGE, f.absolutePath, "holiday.png", viewOnce = false),
        )
        assertTrue("a refusal must never be an empty panel", spread(png) > 3.0)
        assertTrue("and must not paint a picture it refused", count(png, FIXTURE_ARGB) == 0)
    }

    /**
     * This test used to be called *"a video draws the no-player panel and never touches the
     * file"*, and it rendered a path that did not exist in order to prove that the branch
     * decoded nothing. The viewer plays video now, so both halves of that had to go: the
     * panel decodes a still, and the still has to be ON THE SCREEN.
     *
     * The clip's chroma is set hard away from neutral, so its pixels are a colour the
     * window's own greys and accents never produce. "The panel is not blank" would pass on
     * the placeholder glyph alone; "there are 5 000 strongly green pixels" can only pass if
     * a decoded frame was really drawn.
     */
    @Test
    fun `a video draws its first frame in the viewer`() {
        assumeTrue("no H.264 encoder on this machine", TestClips.canEncode())
        val clip = tmp.newFile("clip.h264")
        // U low + V low reads as a strong green after YUV→RGB.
        TestClips.rawH264(clip, width = 640, height = 360, frames = 8, chromaU = 20, chromaV = 20)
        assumeTrue("encoder produced nothing", clip.length() > 256)

        // The panel reads the cache synchronously on first composition and only falls back to
        // a coroutine when it misses. `ImageComposeScene` renders ONE frame and does not pump
        // a dispatcher, so a miss here would draw the placeholder no matter how correct the
        // player is — warming it is what makes this a test of drawing rather than of timing.
        VideoNoteThumbnails.load(clip.absolutePath, 720, VideoNoteReader.Fit.CONTAIN)

        val png = render(
            "viewer-04-video.png",
            MediaViewerTarget(MediaType.VIDEO, clip.absolutePath, "clip.h264", viewOnce = false),
        )
        assertTrue(spread(png) > 3.0)
        assertTrue(
            "the decoded frame is not on the screen: ${greenish(png)} strongly green pixels",
            greenish(png) > 5_000,
        )
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

    @Test
    fun `an unopened voice note draws controls and never touches the file`() {
        // Rendering the overlay must not probe or decode an attachment before the person asks
        // to play it. The deliberately absent file turns an accidental still-image load into
        // a visible refusal, while the audio branch still renders its play control.
        val png = render(
            "viewer-07-audio.png",
            MediaViewerTarget(MediaType.AUDIO, "/does/not/exist/note.m4a", "note.m4a", viewOnce = false),
        )
        assertTrue(spread(png) > 3.0)
    }

    @Test
    fun `a revealed view-once row still draws`() {
        val f = tmp.newFile("secret.png").also { writePng(it, 300, 200, FIXTURE_ARGB) }
        val png = render(
            "viewer-05-viewonce.png",
            MediaViewerTarget(MediaType.IMAGE, f.absolutePath, "secret.png", viewOnce = true),
        )
        assertTrue("a revealed row is a row the user asked for", count(png, FIXTURE_ARGB) > 10_000)
    }

    /**
     * The host, composed the way the ThreadPane snippet in this agent's report composes it.
     *
     * It proves the wiring shape measures and draws with NO viewer open — which is the state
     * a thread is in for all but a few seconds — and that providing the opener does not
     * disturb what is underneath. What it cannot do is click: `ImageComposeScene` has no
     * pointer here, so "clicking a bubble opens the overlay" is verified by a person and by
     * nothing else. Said plainly rather than implied by a green tick.
     */
    @Test
    fun `the host draws its content and opens nothing on its own`() {
        val out = File("build/screens").apply { mkdirs() }
        val scale = 2f
        val scene = ImageComposeScene(width = 600, height = 300, density = Density(scale)) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                    WithMediaViewer { SnippetShape() }
                }
            }
        }
        val png = try {
            scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
        File(out, "viewer-06-host.png").writeBytes(png)
        val img = ImageIO.read(ByteArrayInputStream(png))
        assertTrue("the content under the host must still be drawn", spread(img) > 3.0)
        // No scrim: an overlay that opened itself would darken the whole frame.
        val mean = (0 until img.width step 7).sumOf { x -> luminance(img.getRGB(x, 6)) } /
            ((img.width + 6) / 7)
        assertTrue("nothing may be open until something asks: mean=$mean", mean > 200)
    }

    // ---------------------------------------------------------------- plumbing

    /** Rasterises the overlay at window size and leaves the PNG for a person to look at. */
    private fun render(name: String, target: MediaViewerTarget): BufferedImage {
        val out = File("build/screens").apply { mkdirs() }
        val scale = 2f
        val scene = ImageComposeScene(
            width = (W * scale).toInt(),
            height = (H * scale).toInt(),
            density = Density(scale),
        ) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        MediaViewerOverlay(target) {}
                    }
                }
            }
        }
        val bytes = try {
            val data = scene.render().encodeToData(EncodedImageFormat.PNG)
                ?: error("skia refused to encode $name")
            data.bytes
        } finally {
            scene.close()
        }
        File(out, name).writeBytes(bytes)
        return ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun count(img: BufferedImage, argb: Int, tolerance: Int = 14): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (Math.abs(((p shr 16) and 0xFF) - r) <= tolerance &&
                Math.abs(((p shr 8) and 0xFF) - g) <= tolerance &&
                Math.abs((p and 0xFF) - b) <= tolerance
            ) n++
        }
        return n
    }

    /**
     * Luminance standard deviation. A flat frame is the failure a headless render is here to
     * catch, and PARITY's own history says why the MEAN is not enough on its own — a gate on
     * spread alone once passed 13 black frames. These frames are never black (the scrim is
     * 62% over a white surface), so spread is the discriminator that matters here.
     */
    private fun spread(img: BufferedImage): Double {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 0 until img.height step 3) for (x in 0 until img.width step 3) {
            val p = img.getRGB(x, y)
            val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            sum += lum; sumSq += lum * lum; n++
        }
        val mean = sum / n
        return kotlin.math.sqrt(sumSq / n - mean * mean)
    }

    /**
     * The EXACT shape the bubble's call site takes, kept here so it is compiled.
     *
     * `MessageBubble.kt` is another agent's file; this agent's report hands over a two-line
     * change to it rather than making it. A snippet in a report that has never been through
     * a compiler is a guess, so the same three lines live here, in a composable that is
     * actually composed by the test above: `LocalMediaOpener.current`, the target built
     * through `of` (null for a sealed view-once, which disables the click), and the call.
     */
    @Composable
    private fun SnippetShape() {
        val media = MediaPresentation.of("[image] /m/cat.jpg") { 4096L }!!
        val opener = LocalMediaOpener.current
        val target = androidx.compose.runtime.remember(media) { MediaViewerTarget.of(media) }
        androidx.compose.material3.Text(
            "a thread would be here",
            color = OshiTheme.textPrimary,
            modifier = Modifier.clickable(enabled = target != null) { target?.let(opener::open) },
        )
    }

    private fun luminance(p: Int): Double =
        0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)

    private fun writePng(target: File, w: Int, h: Int, argb: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb)
        ImageIO.write(img, "png", target)
    }
}
