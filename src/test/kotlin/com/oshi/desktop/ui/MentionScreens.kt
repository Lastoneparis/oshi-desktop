package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oshi.desktop.group.MentionWire
import com.oshi.desktop.ui.components.MentionBadge
import com.oshi.desktop.ui.components.MentionHighlight
import com.oshi.desktop.ui.components.MentionPicker
import com.oshi.desktop.ui.components.MessageBubbleRow
import com.oshi.desktop.ui.components.UnreadBadge
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.components.mentionCandidates
import com.oshi.desktop.ui.state.MessageRow
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * __MENTIONS_2026_09_23__ contact sheets for the `@` picker, a mention bubble (next to a
 * ticker chip, to show a picked person winning over `@MAX` while `@AAPL` still chips) and the
 * chat-list "@" pill. A TOOL, gated:
 *
 *     OSHI_RENDER_MENTIONS=1 ./gradlew test --tests 'com.oshi.desktop.ui.MentionScreens'
 */
class MentionScreens {

    private val members = listOf(
        MentionWire.Mention("AliceKey+0123456789abcdefghijklmnopqrstuv=", "Alice"),
        MentionWire.Mention("MaxKey/0123456789abcdefghijklmnopqrstuvwxy=", "MAX"),
        MentionWire.Mention("MalikKey0123456789abcdefghijklmnopqrstuvw=", "Malik"),
        MentionWire.Mention("BobKey+0123456789abcdefghijklmnopqrstuvwx=", "Bob"),
    )

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_MENTIONS") == "1")
        val out = File("build/screens").apply { mkdirs() }

        render(File(out, "32-mention-picker.png"), 520, 300) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Bottom) {
                MentionPicker(mentionCandidates(members, "Al"), onPick = {})
                BasicTextField(
                    value = "Can someone ping @Al",
                    onValueChange = {},
                    textStyle = OshiTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        fun row(id: String, fromMe: Boolean, who: String, body: String, mentions: List<MentionWire.Mention>) = MessageRow(
            id = id, fromMe = fromMe, who = who, body = body, attachment = null,
            stamp = "10:0$id", status = if (fromMe) "delivered" else null, failed = false, edited = false,
            deleted = false, reactions = "", mentions = mentions,
        )
        render(File(out, "33-mention-bubbles.png"), 520, 460, frames = 10) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MessageBubbleRow(row("1", false, "Chloé", "@Alice can you look at this before Friday?", listOf(members[0])), false, true, WallpaperId.NONE, false, false, {})
                MessageBubbleRow(row("2", true, "me", "@MAX was right about @AAPL", listOf(members[1])), false, true, WallpaperId.NONE, false, false, {})
                BasicTextField(
                    value = "Thanks @Bob and @Alice",
                    onValueChange = {},
                    textStyle = OshiTheme.typography.bodyLarge,
                    visualTransformation = MentionHighlight(listOf(members[3], members[0]), OshiTheme.brand),
                    modifier = Modifier.padding(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Weekend plans", style = OshiTheme.typography.bodyLarge)
                    MentionBadge()
                    UnreadBadge(3)
                }
            }
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, frames: Int = 4, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until frames) { scene.render(f * 50_000_000L); Thread.sleep(250) }
            val image = scene.render(frames * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[screens] wrote ${target.path}")
        } finally {
            scene.close()
        }
    }
}
