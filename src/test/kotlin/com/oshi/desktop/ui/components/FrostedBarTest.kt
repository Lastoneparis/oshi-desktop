package com.oshi.desktop.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.oshi.desktop.ui.DisplaySettings
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.WallpaperBlurScreens
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * __WALLPAPER_BLUR_2026_11_21__ The frosted chat bars, measured on the real `ThreadPane`.
 *
 *  * the wallpaper and the messages run UNDER the header and the composer (edge to edge);
 *  * the blur is LIVE: scrolling changes what shows through the header, i.e. the backdrop is
 *    read at draw time and is not a stale snapshot;
 *  * with the blur off the bar is the near-solid fallback;
 *  * the governor's verdict is pure and ignores idle gaps.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FrostedBarTest {

    private val w = 900
    private val h = 760
    private val scale = 2f

    @After fun restore() { BackdropBlur.set(true); OshiTheme.appearance = WallpaperBlurScreens.appearance("light") }

    private class Shot(val pixels: IntArray, val width: Int, val height: Int) {
        fun argb(x: Int, y: Int) = pixels[y * width + x]
    }

    private fun shot(img: Image): Shot {
        val bmp = Bitmap.makeFromImage(img)
        val out = IntArray(bmp.width * bmp.height)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) out[y * bmp.width + x] = bmp.getColor(x, y)
        return Shot(out, bmp.width, bmp.height)
    }

    /** Header band minus its text/icons: the strip between the title block and the buttons. */
    private fun headerBand(s: Shot): List<Int> {
        val y0 = 4; val y1 = (56 * scale).toInt()
        val x0 = (420 * scale).toInt(); val x1 = (720 * scale).toInt()
        return (y0 until y1 step 3).flatMap { y -> (x0 until x1 step 5).map { x -> s.argb(x, y) } }
    }

    private fun renderPair(theme: String): Pair<Shot, Shot> {
        OshiTheme.appearance = WallpaperBlurScreens.appearance(theme)
        DisplaySettings.preview("en")
        val scene = WallpaperBlurScreens.scene(w, h, scale) {
            WallpaperBlurScreens.pane(WallpaperBlurScreens.direct(40), WallpaperId.VIOLET)
        }
        try {
            for (f in 1..10) scene.render(f * 50_000_000L)
            val before = shot(scene.render(11 * 50_000_000L))
            val mid = Offset(w * scale / 2, h * scale / 2)
            // Several larger notches: a wheel notch is worth fewer pixels on Windows than on
            // macOS/Linux, and three single notches left the list where it was on the Windows CI.
            repeat(6) { i ->
                scene.sendPointerEvent(PointerEventType.Scroll, mid, scrollDelta = Offset(0f, -4f))
                scene.render((12 + i) * 10_000_000L + 600_000_000L)
            }
            for (f in 15..20) scene.render(f * 50_000_000L)
            val after = shot(scene.render(21 * 50_000_000L))
            return before to after
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the header is see-through and follows the scroll`() {
        BackdropBlur.set(true)
        val (before, after) = renderPair("light")
        val a = headerBand(before); val b = headerBand(after)
        val changed = a.indices.count { a[it] != b[it] }
        // The outgoing bubbles (right half) pass under this band as the list scrolls.
        assertTrue("header band did not change after scrolling ($changed px of ${a.size})", changed > a.size / 20)
    }

    @Test
    fun `the quarter-resolution blur lands where the full-resolution one does`() {
        BackdropBlur.set(true)
        val saved = BackdropBlur.DOWNSCALE
        try {
            BackdropBlur.DOWNSCALE = 1f
            val (full, _) = renderPair("dark")   // resting frame: wheel steps differ per OS
            BackdropBlur.DOWNSCALE = 4f
            val (quarter, _) = renderPair("dark")
            // The whole header row, text included: a misplaced backdrop (offset or scaled)
            // moves the blurred bubbles and shows up as large local differences.
            var sum = 0L; var worst = 0; var n = 0
            for (y in 2 until (56 * scale).toInt()) for (x in 0 until quarter.width step 2) {
                val a = full.argb(x, y); val b = quarter.argb(x, y)
                val d = listOf(16, 8, 0).maxOf { sh -> kotlin.math.abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) }
                sum += d; worst = maxOf(worst, d); n++
            }
            val mean = sum.toDouble() / n
            // Measured: 0.21 / 2. A backdrop scaled up 15 % too far (a mutation) gives 1.35 / 12.
            assertTrue("mean channel difference $mean (worst $worst)", mean < 0.6 && worst < 8)
        } finally {
            BackdropBlur.DOWNSCALE = saved
        }
    }

    @Test
    fun `without blur the header is the near-solid fallback`() {
        BackdropBlur.set(false)
        val (before, after) = renderPair("light")
        val a = headerBand(before); val b = headerBand(after)
        // At 94 % opacity the content under it can move a channel by at most ~6 % of 255.
        val maxDelta = a.indices.maxOf { i ->
            listOf(16, 8, 0).maxOf { sh -> kotlin.math.abs(((a[i] shr sh) and 0xFF) - ((b[i] shr sh) and 0xFF)) }
        }
        assertTrue("fallback bar let $maxDelta of 255 through", maxDelta <= 18)
    }

    @Test
    fun `the governor ignores idle gaps and trips only on sustained slow frames`() {
        val ms = 1_000_000L
        assertFalse(BackdropBlur.tooSlow(List(120) { 8 * ms }))
        assertFalse("idle gaps are not slow frames", BackdropBlur.tooSlow(List(120) { 500 * ms }))
        assertFalse("a few hitches are not a verdict", BackdropBlur.tooSlow(List(100) { 8 * ms } + List(20) { 50 * ms }))
        assertTrue(BackdropBlur.tooSlow(List(60) { 8 * ms } + List(60) { 40 * ms }))
        assertFalse("too few active frames to judge", BackdropBlur.tooSlow(List(30) { 40 * ms }))
    }

    @Test
    fun `disable records why and set(true) clears it`() {
        BackdropBlur.set(true)
        BackdropBlur.disable("test")
        assertFalse(BackdropBlur.enabled)
        assertEquals("test", BackdropBlur.disabledBecause)
        BackdropBlur.set(true)
        assertTrue(BackdropBlur.enabled)
        assertEquals(null, BackdropBlur.disabledBecause)
    }

    /**
     * A rough frame-cost comparison, gated (`OSHI_BENCH=1`): scroll a 600-message thread with
     * the bars frosted and with them solid. `ImageComposeScene` rasterises on the CPU, so the
     * blur costs MORE here than on the GPU window; the delta is an upper bound.
     */
    @Test
    fun benchScroll() {
        assumeTrue(System.getenv("OSHI_BENCH") == "1")
        OshiTheme.appearance = WallpaperBlurScreens.appearance("light")
        DisplaySettings.preview("en")
        fun run(blur: Boolean): List<Double> {
            BackdropBlur.set(blur)
            val scene = WallpaperBlurScreens.scene(1100, 800, 2f) {
                WallpaperBlurScreens.pane(WallpaperBlurScreens.group(600), WallpaperId.VIOLET)
            }
            try {
                var t = 0L
                repeat(20) { scene.render(t); t += 16_000_000L }
                val mid = Offset(1100f, 800f)
                val out = ArrayList<Double>()
                repeat(200) { i ->
                    val start = System.nanoTime()
                    scene.sendPointerEvent(PointerEventType.Scroll, mid, scrollDelta = Offset(0f, if (i < 100) -1f else 1f))
                    scene.render(t); t += 16_000_000L
                    out += (System.nanoTime() - start) / 1e6
                }
                return out.drop(20).sorted()
            } finally { scene.close() }
        }
        run(true); run(false) // warm-up
        for (blur in listOf(false, true, false, true)) {
            val ms = run(blur)
            fun p(q: Double) = ms[((ms.size - 1) * q).toInt()]
            println("[blur-bench] blur=%-5s n=%d p50=%.2f ms p95=%.2f ms max=%.2f ms".format(blur, ms.size, p(0.5), p(0.95), ms.last()))
        }
    }
}
