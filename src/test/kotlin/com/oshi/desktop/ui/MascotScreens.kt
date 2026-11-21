package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.gif.GiphyClient
import com.oshi.desktop.gif.LocalGifLibrary
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.components.GifPicker
import com.oshi.desktop.ui.components.MascotImage
import com.oshi.desktop.ui.components.MascotReactionPicker
import com.oshi.desktop.ui.components.MessageBubbleRow
import com.oshi.desktop.ui.components.ReactionsCard
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.MessageRow
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ Contact sheets for the O-bubble mascot: the reaction row and
 * chips, the full reaction picker, the GIF picker's OSHI section, the "Animated OSHI
 * reactions" card, an animation strip, and the launch splash timeline. A TOOL, gated like
 * `GifTickerScreens` so the suite never renders by accident:
 *
 *     OSHI_RENDER_MASCOT=1 ./gradlew test --tests 'com.oshi.desktop.ui.MascotScreens'
 *
 * Written to `build/screens/mascot-*.png`. The messages are fabricated demo rows.
 */
class MascotScreens {

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_MASCOT") == "1")
        // __OLED_SPLASH_2026_11_16__ OSHI_RENDER_THEME=light|dark|oled, like ScreenRenderer:
        // the same sheets in that appearance, under build/screens/<theme>/.
        val theme = System.getenv("OSHI_RENDER_THEME")?.lowercase()
        OshiTheme.appearance = when (theme) {
            "dark" -> Appearance(ThemeMode.DARK, oledBlack = false)
            "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
            else -> Appearance.DEFAULT
        }
        val out = File("build/screens").let { if (theme.isNullOrBlank()) it else File(it, theme) }.apply { mkdirs() }
        val sheetBg = OshiTheme.background.let { Color((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
        // Never touch the real profile's preferences from a renderer.
        MotionPreference.prefsFile = Files.createTempDirectory("mascot-screens").resolve("p.json").toFile()

        fun row(id: String, fromMe: Boolean, body: String, keys: List<String>) = MessageRow(
            id = id, fromMe = fromMe, who = if (fromMe) "me" else "Chloé", body = body, attachment = null,
            stamp = "10:0$id", status = if (fromMe) "delivered" else null, failed = false, edited = false,
            deleted = false, reactions = keys.joinToString(""), reactionKeys = keys,
        )
        render(File(out, "mascot-1-reactions.png"), 560, 640) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MessageBubbleRow(row("1", false, "Le train est annulé, j'arrive demain matin", listOf("😢", "🥲")),
                    false, true, WallpaperId.NONE, false, false, {}, onReact = {})
                MessageBubbleRow(row("2", true, "Ok on garde le dîner pour demain alors", listOf("❤️", "👍🏽", "😂")),
                    false, true, WallpaperId.NONE, false, false, {}, onReact = {})
                // Aliases (❤ without FE0F, 💜, 🤣), a non-mascot emoji and the OSHI smile.
                MessageBubbleRow(row("3", false, "Photos de la soirée 🎉", listOf("❤", "💜", "🤣", "😭", "😀", "⭕😊")),
                    false, true, WallpaperId.NONE, false, false, {}, onReact = {})
                Text("Full picker (+):", fontSize = 11.sp)
                MascotReactionPicker(onReact = {}, onClose = {})
            }
        }

        render(File(out, "mascot-2-gif-picker.png"), 720, 460) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                GifPicker(onPick = { _, _ -> }, onClose = {}, library = LocalGifLibrary(language = "en"), giphy = GiphyClient(apiKey = null))
            }
        }

        render(File(out, "mascot-3-setting.png"), 640, 200) {
            Column(Modifier.fillMaxSize().background(OshiTheme.paneGround).padding(16.dp)) { ReactionsCard() }
        }

        // Ten successive frames of three mascots (playing), to see the GIF path animate.
        val strip = ArrayList<BufferedImage>()
        for (k in 0 until 8) {
            val f = File(out, "tmp-anim-$k.png")
            render(f, 300, 100, frames = 6 + k) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("love", "laugh", "peek").forEach { n -> MascotImage(OshiMascot.named(n)!!, 84.dp, playing = true) }
                }
            }
            strip += ImageIO.read(f); f.delete()
        }
        writeGrid(File(out, "mascot-4-animation-strip.png"), strip, cols = 2, bg = sheetBg)

        // The splash AS THE WINDOW DRAWS IT: on its card in this theme, over a light and a
        // dark desktop.
        val ground = LaunchSplash.Ground.of(OshiTheme.appearance, systemDark = false)
        for ((name, bg) in listOf("light" to Color(0xD8, 0xDC, 0xE4), "dark" to Color(0x10, 0x12, 0x18))) {
            writeGrid(File(out, "mascot-8-splash-card-on-$name-desktop.png"),
                listOf(0L, 300L, 650L, 1150L, 2400L).map { LaunchSplash.renderCard(it, false, px = 220, ground = ground) },
                cols = 5, bg = bg)
        }

        // Launch splash timeline (mascot only), over a light and a dark desktop.
        val px = 220
        val stills = LaunchSplash.loadStills(px)
        val images = LaunchSplash.Images(stills.first, stills.second, LaunchSplash.loadWave(px))
        val times = listOf(0L, 150L, 250L, 350L, 400L, 650L, 900L, 1150L, 1400L, 2400L)
        for ((name, bg) in listOf("light" to Color(0xF2, 0xF2, 0xF7), "dark" to Color(0x1C, 0x1C, 0x1E))) {
            writeGrid(File(out, "mascot-5-launch-$name.png"),
                times.map { LaunchSplash.renderFrame(it, reducedMotion = false, px = px, images = images) }, cols = 5, bg = bg, labels = times.map { "${it} ms" })
        }
        writeGrid(File(out, "mascot-6-launch-reduced.png"),
            times.map { LaunchSplash.renderFrame(it, reducedMotion = true, px = px, images = images) }, cols = 5,
            bg = Color(0xF2, 0xF2, 0xF7), labels = times.map { "${it} ms" })
    }

    /**
     * The REAL splash window on a real display: shown, captured (if this process may record
     * the screen), and closed by the first other window that opens. Gated separately because
     * it puts windows on the screen:
     *
     *     OSHI_SPLASH_LIVE=1 ./gradlew test --tests 'com.oshi.desktop.ui.MascotScreens'
     */
    @Test
    fun liveSplash() {
        assumeTrue(System.getenv("OSHI_SPLASH_LIVE") == "1")
        val out = File("build/screens").apply { mkdirs() }
        fun splashWindows() = java.awt.Window.getWindows().filter { it is javax.swing.JWindow && it.isShowing }
        LaunchSplash.show()
        Thread.sleep(1_100)
        val shown = splashWindows()
        println("[splash] showing after 1.1 s: ${shown.size} at ${shown.firstOrNull()?.bounds}")
        shown.firstOrNull()?.let { w ->
            runCatching {
                val shot = java.awt.Robot().createScreenCapture(w.bounds)
                ImageIO.write(shot, "png", File(out, "mascot-7-live-splash.png"))
            }
        }
        val opened = System.nanoTime()
        val frame = arrayOfNulls<javax.swing.JFrame>(1)
        javax.swing.SwingUtilities.invokeAndWait {
            frame[0] = javax.swing.JFrame("stand-in main window").apply { setSize(200, 100); isVisible = true }
        }
        var waited = 0
        while (splashWindows().isNotEmpty() && waited < 2_000) { Thread.sleep(20); waited += 20 }
        println("[splash] closed ${(System.nanoTime() - opened) / 1_000_000} ms after another window opened; still showing: ${splashWindows().size}")
        javax.swing.SwingUtilities.invokeAndWait { frame[0]?.dispose() }
        org.junit.Assert.assertEquals(1, shown.size)
        org.junit.Assert.assertTrue(splashWindows().isEmpty())
    }

    private fun writeGrid(target: File, tiles: List<BufferedImage>, cols: Int, bg: Color, labels: List<String>? = null) {
        val tw = tiles.maxOf { it.width }; val th = tiles.maxOf { it.height } + if (labels != null) 22 else 0
        val rows = (tiles.size + cols - 1) / cols
        val img = BufferedImage(cols * tw, rows * th, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = bg; g.fillRect(0, 0, img.width, img.height)
        tiles.forEachIndexed { i, t ->
            val x = (i % cols) * tw; val y = (i / cols) * th
            g.drawImage(t, x, y, null)
            labels?.getOrNull(i)?.let { g.color = if (bg.red > 128) Color.DARK_GRAY else Color.LIGHT_GRAY; g.drawString(it, x + 8, y + th - 6) }
        }
        g.dispose()
        ImageIO.write(img, "png", target)
        println("[screens] wrote ${target.path}")
    }

    private fun render(target: File, wDp: Int, hDp: Int, frames: Int = 8, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(120) }
            val image = scene.render(frames * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[screens] wrote ${target.path}")
        } finally {
            scene.close()
        }
    }
}
