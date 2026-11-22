package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.ui.components.CommentActions
import com.oshi.desktop.ui.components.GroupSettingsActions
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.GroupSettingsView
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.QuoteRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO

/**
 * __BUBBLE_MEDIA_2026_11_21__ Before/after sheets for BUBBLE_MEDIA_SPEC: a GROUP with text, a
 * landscape, a portrait, a small image, a panorama and a captioned photo, and a CHANNEL with
 * photo posts, captions and the comments bar — from the REAL `ThreadPane`, fabricated state,
 * nothing on the network. A tool, gated so the suite never renders by accident:
 *
 *     OSHI_RENDER_BUBBLES=1 OSHI_BUBBLES_OUT=/abs/dir ./gradlew test --tests 'com.oshi.desktop.ui.BubbleMediaScreens'
 *
 * Every fixture photo has a 6 px RED frame: any band of bubble colour between that frame and
 * the bubble's edge is the "empty part on the side" the spec is about.
 */
class BubbleMediaScreens {

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_BUBBLES") == "1")
        val base = File(System.getenv("OSHI_BUBBLES_OUT") ?: "build/screens-bubbles").apply { mkdirs() }
        val fx = File(base, "_fixtures").apply { mkdirs() }
        val photos = Photos(
            landscape = photo(File(fx, "landscape-1600x1000.png"), 1600, 1000, AwtColor(0x2E7DFF), AwtColor(0x7CE0C3)),
            portrait = photo(File(fx, "portrait-1000x1600.png"), 1000, 1600, AwtColor(0xFF7A45), AwtColor(0xFFD36E)),
            small = photo(File(fx, "small-160x120.png"), 160, 120, AwtColor(0x8E44AD), AwtColor(0xF39C12)),
            panorama = photo(File(fx, "panorama-2400x600.png"), 2400, 600, AwtColor(0x0B486B), AwtColor(0xF56217)),
            captioned = photo(File(fx, "captioned-1200x900.png"), 1200, 900, AwtColor(0x11998E), AwtColor(0x38EF7D)),
        )
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,dark,oled,light-glass").split(',')
        for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                "light-glass" -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = true)
                "dark-glass" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = true)
                else -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = false)
            }
            DisplaySettings.preview("en")
            render(File(base, "group-$theme.png"), 1100, 2300) { pane(group(photos)) }
            render(File(base, "channel-$theme.png"), 1100, 1900) { pane(channel(photos)) }
        }
    }

    private class Photos(val landscape: File, val portrait: File, val small: File, val panorama: File, val captioned: File)

    /** A gradient, a grid and a 6 px red frame, so both cropping and any side band are visible. */
    private fun photo(f: File, w: Int, h: Int, a: AwtColor, b: AwtColor): File {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, a, w.toFloat(), h.toFloat(), b)
        g.fillRect(0, 0, w, h)
        g.color = AwtColor(255, 255, 255, 70)
        val step = maxOf(20, minOf(w, h) / 6)
        var x = 0; while (x < w) { g.drawLine(x, 0, x, h); x += step }
        var y = 0; while (y < h) { g.drawLine(0, y, w, y); y += step }
        g.color = AwtColor(255, 255, 255, 200)
        g.fillOval(w / 2 - minOf(w, h) / 6, h / 2 - minOf(w, h) / 6, minOf(w, h) / 3, minOf(w, h) / 3)
        g.color = AwtColor(0xE0, 0x10, 0x10)
        g.stroke = BasicStroke(maxOf(3f, minOf(w, h) / 100f * 1.2f))
        g.drawRect(0, 0, w - 1, h - 1)
        g.dispose()
        ImageIO.write(img, "png", f)
        return f
    }

    private fun addr(c: Char) = c.toString().repeat(43) + "="

    private fun row(
        id: String, fromMe: Boolean, who: String, body: String, stamp: String,
        photo: File? = null, sender: String = "", comments: Int? = null, quote: QuoteRow? = null,
        reactions: List<String> = emptyList(),
    ) = MessageRow(
        id = id, fromMe = fromMe, who = who,
        // A file sent with no caption stores its NAME as the body — what the store really holds.
        body = if (photo != null && body.isEmpty()) photo.name else body,
        attachment = photo?.let { "[image] ${it.absolutePath}" }, stamp = stamp,
        status = if (fromMe) "read" else null, failed = false, edited = false, deleted = false,
        reactions = reactions.joinToString(""), reactionKeys = reactions, sentAtMs = 1_763_632_800_000L,
        senderAddress = sender, comments = comments, quote = quote,
    )

    private val members = listOf(
        GroupMemberRow(addr('m'), "Hugo", isAdmin = true, isCreator = true, self = true),
        GroupMemberRow(addr('c'), "Inès Moreau", isAdmin = true, isCreator = false, self = false),
        GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
        GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false),
        GroupMemberRow(addr('e'), "Anatole", isAdmin = false, isCreator = false, self = false),
    )

    private fun group(p: Photos) = ThreadView(
        conversationId = "G-BUBBLES", title = "Weekend hikers", address = "G-BUBBLES",
        kind = ConversationKind.GROUP, groupAdmin = true, groupCanEditInfo = true,
        groupMembers = members, groupCandidates = emptyList(),
        safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = listOf(
            row("1", false, "Inès Moreau", "Photos from Saturday's walk 👇", "18:02", sender = addr('c')),
            row("2", false, "Inès Moreau", "", "18:02", photo = p.landscape, sender = addr('c')),
            row("3", false, "Bob", "", "18:05", photo = p.portrait, sender = addr('b')),
            row("4", true, "me", "", "18:07", photo = p.small),
            row("5", true, "me", "The view from the ridge — we got there just before sunset, totally worth the climb.", "18:07", photo = p.captioned),
            row("6", false, "Yusuf", "", "18:10", photo = p.panorama, sender = addr('d')),
            row("7", false, "Anatole", "Amazing 😍 same route next month?", "18:12", sender = addr('e'),
                quote = QuoteRow("5", "Hugo", "The view from the ridge — we got there just before sunset")),
            row("8", true, "me", "Yes! I'll book the hut.", "18:13"),
        ),
        composer = ComposerState(true, null, true, null),
        groupSettings = GroupSettingsView(
            isAdmin = true, type = GroupType.COLLABORATIVE, settings = GroupSettings(), publicLink = null,
            canPost = true, dissolved = false, admins = members.filter { it.isAdmin }, banned = emptyList(),
            events = emptyList(), welcomeShown = null,
        ),
    )

    private fun channel(p: Photos): ThreadView {
        val settings = GroupSettings(
            visibility = GroupSettings.Visibility.PUBLIC, handle = "alpine_daily", showPreview = true,
            reactions = GroupSettings.ReactionMode.SOME, allowedReactions = listOf("👍", "❤️", "🔥"),
            postPolicy = GroupSettings.PostPolicy.ADMINS, adminDMs = true, comments = true,
        )
        return ThreadView(
            conversationId = "C-BUBBLES", title = "Alpine Daily", address = "C-BUBBLES",
            kind = ConversationKind.GROUP, groupAdmin = false, groupCanEditInfo = false,
            groupDescription = "Trail conditions, every morning.",
            groupMembers = members.map { it.copy(self = it.label == "Bob") }, groupCandidates = emptyList(),
            safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
            messages = listOf(
                row("P1", false, "Inès Moreau", "Col de la Forclaz this morning: dry trail, first snow above 2400 m. Microspikes recommended after the hut.", "07:40",
                    photo = p.landscape, sender = addr('c'), comments = 4, reactions = listOf("🔥", "👍")),
                row("P2", false, "Inès Moreau", "", "08:15", photo = p.portrait, sender = addr('c'), comments = 0),
                row("P3", false, "Inès Moreau", "Weekly note: avalanche risk 2/5. Next update Friday.", "09:02", sender = addr('c'), comments = 1),
                row("P4", false, "Inès Moreau", "Panorama from the summit.", "12:30", photo = p.panorama, sender = addr('c'), comments = 2),
            ),
            composer = ComposerState(false, "Only admins can post", false, "Only admins can post"),
            groupSettings = GroupSettingsView(
                isAdmin = false, type = GroupType.ADMIN_ONLY, settings = settings,
                publicLink = "https://oshi-messenger.com/g/alpine_daily", canPost = false, dissolved = false,
                admins = members.filter { it.isAdmin }.map { it.copy(self = false) }, banned = emptyList(),
                events = emptyList(), welcomeShown = null,
            ),
        )
    }

    private val noopSettings = GroupSettingsActions(
        onSetType = {}, onSetPublic = { _, _ -> }, onSetPrivate = {}, onResetLink = {}, onSetPostPolicy = {},
        onSetReactions = { _, _ -> }, onSetWelcome = {}, onSetAdminDMs = {}, onUnban = {}, onMessageAdmin = {},
        onDissolve = {}, onDeleteForMe = {}, onLinkEditorClosed = {}, onCopy = {}, onSetComments = {},
    )

    @Composable
    private fun pane(thread: ThreadView) {
        ThreadPane(
            thread = thread, notice = null, busy = false,
            wallpaper = WallpaperId.NONE, onPickWallpaper = {},
            draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
            onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
            onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
            viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
            today = LocalDate.of(2026, 11, 21),
            onReport = { _, _, _ -> },
            groupSettingsActions = noopSettings,
            commentActions = CommentActions({}, {}, {}, {}, {}, {}),
        )
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scale = 2f
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            // Photos decode off the UI thread: give them frames to land, and the list a second
            // pass to settle once their real heights are known.
            for (f in 1 until 30) { scene.render(f * 50_000_000L); Thread.sleep(60) }
            val image = scene.render(30 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[bubbles] wrote ${target.absolutePath}")
        } finally {
            scene.close()
        }
    }
}
