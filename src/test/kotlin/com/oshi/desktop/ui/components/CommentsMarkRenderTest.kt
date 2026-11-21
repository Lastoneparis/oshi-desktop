package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import com.oshi.desktop.ui.Appearance
import com.oshi.desktop.ui.OshiMaterialTheme
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.ThemeMode
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * __COMMENTS_OSHI_MARK_2026_11_21__ The comments bar leads with the OSHI mark, in the brand
 * colour, in every look — not the 💬 emoji (a fixed-colour glyph from the system font).
 *
 * The mark sits in the bar's first ~30 px (12 dp padding + 18 dp mark, at density 1); the label
 * starts after it. So brand-coloured ink in that strip is the mark, and an emoji there would be
 * yellow/grey/blue depending on the OS font — not the theme's brand colour.
 */
class CommentsMarkRenderTest {
    companion object {
        init { System.setProperty("skiko.renderApi", "SOFTWARE") }
    }

    private fun brandInkInMarkStrip(appearance: Appearance): Int {
        val saved = OshiTheme.appearance
        OshiTheme.appearance = appearance
        try {
            var brand = 0
            val scene = ImageComposeScene(420, 70, Density(1f)) {
                OshiMaterialTheme {
                    brand = OshiTheme.brand.toArgb()
                    Surface(Modifier.fillMaxSize(), color = OshiTheme.background) {
                        Box(Modifier.fillMaxSize()) { CommentsBar(count = 3, open = false, onOpen = {}) }
                    }
                }
            }
            val img = try {
                for (f in 1 until 5) scene.render(f * 50_000_000L)
                val png = scene.render(5 * 50_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                ImageIO.read(ByteArrayInputStream(png))
            } finally { scene.close() }
            var n = 0
            for (y in 0 until img.height) for (x in 8 until 32) {
                val p = img.getRGB(x, y)
                val d = abs((p shr 16 and 0xFF) - (brand shr 16 and 0xFF)) +
                    abs((p shr 8 and 0xFF) - (brand shr 8 and 0xFF)) +
                    abs((p and 0xFF) - (brand and 0xFF))
                if (d < 40) n++
            }
            return n
        } finally {
            OshiTheme.appearance = saved
        }
    }

    @Test
    fun `the bar's icon is the OSHI mark in the brand colour, in light, dark, extra dark and glass`() {
        listOf(
            Appearance(ThemeMode.LIGHT, oledBlack = false, glass = false),
            Appearance(ThemeMode.LIGHT, oledBlack = false, glass = true),
            Appearance(ThemeMode.DARK, oledBlack = false, glass = false),
            Appearance(ThemeMode.DARK, oledBlack = false, glass = true),
            Appearance(ThemeMode.DARK, oledBlack = true),
        ).forEach { a ->
            val ink = brandInkInMarkStrip(a)
            // An 18 px ring (r 6.5, 2.6 px wide) + tail is ~85 px of ink, ~60 of it solid after
            // anti-aliasing; an emoji there has none in the brand colour.
            assertTrue("$a: only $ink brand-coloured pixels where the mark should be", ink > 35)
        }
    }

    @Test
    fun `the words carry no emoji — the mark is drawn, not typed`() {
        listOf(0, 1, 3).forEach { assertFalse(commentsBarLabel(it).contains("💬")) }
    }
}
