package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oshi.desktop.gif.GiphyClient
import com.oshi.desktop.gif.LocalGifLibrary
import com.oshi.desktop.ticker.TickerQuoteCache
import com.oshi.desktop.ui.components.GifPicker
import com.oshi.desktop.ui.components.MessageBubbleRow
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.MessageRow
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `__GIF_PACK_2026_09_23__` / `__TICKER_QUOTES_2026_09_23__` — contact sheets for the GIF
 * picker, a GIF bubble and ticker chips. A TOOL, like `ScreenRenderer`, gated so the suite
 * never renders by accident:
 *
 *     OSHI_RENDER_GIF=1 ./gradlew test --tests 'com.oshi.desktop.ui.GifTickerScreens'
 *
 * The chips use LIVE Yahoo prices when the network answers (the renderer prints which);
 * the messages are fabricated demo rows.
 */
class GifTickerScreens {

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_GIF") == "1")
        val out = File("build/screens").apply { mkdirs() }

        render(File(out, "30-gif-picker.png"), 720, 460) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                GifPicker(onPick = { _, _ -> }, onClose = {}, library = LocalGifLibrary(language = "en"), giphy = GiphyClient(apiKey = null))
            }
        }

        // A real pack GIF, sent as a message: the bubble must animate it.
        val lib = LocalGifLibrary(language = "en")
        val item = lib.all(LocalGifLibrary.Kind.GIF).first { it.fileName == "p_m_fire.gif" }
        val gifFile = File(out, "demo-sent.gif").apply { writeBytes(lib.bytes(item)!!) }
        val live = listOf("AAPL", "BTC", "GOLD").map { it to (TickerQuoteCache.get(it) != null) }
        println("[screens] live Yahoo quotes: $live")
        fun row(id: String, fromMe: Boolean, body: String, attachment: String? = null) = MessageRow(
            id = id, fromMe = fromMe, who = if (fromMe) "me" else "Chloé", body = body, attachment = attachment,
            stamp = "10:0$id", status = if (fromMe) "delivered" else null, failed = false, edited = false,
            deleted = false, reactions = "",
        )
        render(File(out, "31-gif-and-ticker-bubbles.png"), 520, 620, frames = 12) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MessageBubbleRow(row("1", false, "Apple just reported — @AAPL and gold @gold today?"), false, true, WallpaperId.NONE, false, false, {})
                MessageBubbleRow(row("2", true, "and @btc 🚀"), false, true, WallpaperId.NONE, false, false, {})
                MessageBubbleRow(row("3", true, "", attachment = "[image] ${gifFile.absolutePath}"), false, true, WallpaperId.NONE, false, false, {})
            }
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, frames: Int = 8, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            // Effects decode GIFs and fetch prices off-thread: give them frames to land.
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(250) }
            val image = scene.render(frames * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[screens] wrote ${target.path}")
        } finally {
            scene.close()
        }
    }
}
