package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.GroupCandidateRow
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * __THREAD_CHROME_2027__ The conversation header, its "⋯" menu, the info panel, the message
 * hover toolbar and the right-click menu, rendered from the REAL ThreadPane with fabricated
 * state, in light, dark and OLED. A TOOL, gated like `MascotScreens`:
 *
 *     OSHI_RENDER_CHROME=1 ./gradlew test --tests 'com.oshi.desktop.ui.ThreadChromeScreens'
 *
 * Written to `$OSHI_CHROME_OUT` (default `build/screens/chrome`) as `desktop-header-*.png`.
 */
class ThreadChromeScreens {
    private val scale = 2f

    private fun addr(c: Char) = c.toString().repeat(43) + "="

    private fun msg(id: String, fromMe: Boolean, who: String, body: String, stamp: String, status: String? = null, keys: List<String> = emptyList(), rn: Boolean = false) =
        MessageRow(
            id = id, fromMe = fromMe, who = who, body = body, attachment = null, stamp = stamp,
            status = status, failed = false, edited = false, deleted = false, reactions = keys.joinToString(""),
            reactionKeys = keys, restrictedNetwork = rn, sentAtMs = 1_763_480_000_000L,
        )

    private val direct = ThreadView(
        conversationId = addr('a'), title = "Camille Laurent", address = addr('a'),
        kind = ConversationKind.DIRECT, groupAdmin = false,
        groupMembers = emptyList(), groupCandidates = emptyList(), safetyNumber = "37021 88410 22957 61403 99812 04476", verified = true,
        reach = Reach.REACHABLE, reachLabel = "V2 path available · relay", peerTyping = false,
        messages = listOf(
            msg("1", false, "Camille", "Are you coming tonight?", "17:40"),
            msg("2", true, "me", "Yes — I'll send the details in a minute.", "17:41", "read", keys = listOf("❤️")),
            msg("3", false, "Camille", "Internet is filtered here since this morning. Keep it short.", "17:43", rn = true),
            msg("4", true, "me", "North gate, 18:00.", "17:44", "pending"),
        ),
        composer = ComposerState(true, null, true, null),
        forwardTargets = listOf(GroupCandidateRow(addr('b'), "Bob"), GroupCandidateRow(addr('c'), "Inès")),
        pinCanToggle = true,
    )

    private val group = ThreadView(
        conversationId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", title = "Team Desk", address = "4C8579F8-9CD5-47E8-AFDD-77300D45171D",
        kind = ConversationKind.GROUP, groupAdmin = true, groupCanEditInfo = true,
        groupInviteLink = "oshi://join/4C8579F8-9CD5-47E8-AFDD-77300D45171D#k=Zm9vYmFy",
        groupDescription = "Coordination for the Thursday distribution. Be kind, be brief.",
        groupMembers = listOf(
            GroupMemberRow(addr('m'), "Desk A", isAdmin = true, isCreator = true, self = true),
            GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
            GroupMemberRow(addr('c'), "Inès", isAdmin = true, isCreator = false, self = false),
            GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false),
        ),
        groupCandidates = listOf(GroupCandidateRow(addr('e'), "Chloé")),
        safetyNumber = "", verified = false,
        reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = listOf(
            msg("g1", false, "Bob", "Who has the keys to the van?", "21:40"),
            msg("g2", false, "Inès", "I do. Leaving at 7.", "21:41", keys = listOf("👍", "⭕")),
            msg("g3", true, "me", "Perfect — I'll bring the boxes.", "21:47", "delivered"),
        ),
        composer = ComposerState(true, null, true, null),
        forwardTargets = listOf(GroupCandidateRow(addr('b'), "Bob")),
        pinCanToggle = true,
    )

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_CHROME") == "1")
        val out = File(System.getenv("OSHI_CHROME_OUT") ?: "build/screens/chrome").apply { mkdirs() }
        val themes = (System.getenv("OSHI_CHROME_THEMES") ?: "light,dark,oled").split(',')
        for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                else -> Appearance.DEFAULT
            }
            fun shot(name: String, thread: ThreadView, preview: ThreadChromePreview = ThreadChromePreview(), w: Int = 980, h: Int = 620) =
                render(File(out, "desktop-header-$name-$theme.png"), w, h) {
                    CompositionLocalProvider(LocalThreadChromePreview provides preview) { pane(thread) }
                }
            shot("1to1", direct)
            shot("group", group)
            // __GROUP_HEADER_ISLAND_2026_11_24__ a long name in a narrow window, and someone typing.
            shot("group-long-narrow", group.copy(title = "Climbing club — Fontainebleau weekend trips & carpooling 🧗", typingNames = listOf("Inès")), w = 640)
            shot("overflow-group", group, ThreadChromePreview(headerMenuOpen = true), h = 760)
            shot("overflow-1to1", direct, ThreadChromePreview(headerMenuOpen = true))
            shot("info-group", group, ThreadChromePreview(infoOpen = true), w = 1180, h = 900)
            shot("info-1to1", direct, ThreadChromePreview(infoOpen = true), w = 1180, h = 760)
            shot("hover-toolbar", direct, ThreadChromePreview(toolbarMessageId = "3"))
            shot("hover-toolbar-out", group, ThreadChromePreview(toolbarMessageId = "g3"))
            shot("react-bar", direct, ThreadChromePreview(toolbarMessageId = "3", reactBarMessageId = "3"))
            shot("more-menu", direct, ThreadChromePreview(toolbarMessageId = "2", moreMenuMessageId = "2"))
            shot("context-menu", group, ThreadChromePreview(contextMenuMessageId = "g2"), h = 700)
            // __COMPOSER_PILL_2026_11_23__ the pill empty (mic + camera) and with text (send).
            fun composerShot(name: String, thread: ThreadView, draft: String) =
                render(File(out, "desktop-composer-$name-$theme.png"), 980, 620) {
                    CompositionLocalProvider(LocalThreadChromePreview provides ThreadChromePreview()) { pane(thread, draft, withCamera = true) }
                }
            composerShot("1to1-empty", direct, "")
            composerShot("1to1-typed", direct, "On my way, see you at the north gate")
            composerShot("group-empty", group, "")
            composerShot("group-multiline", group, "Line one\nLine two\nLine three")
        }
    }

    @Composable
    private fun pane(thread: ThreadView, draft: String = "", withCamera: Boolean = false) {
        ThreadPane(
            thread = thread, notice = null, busy = false,
            wallpaper = WallpaperId.NONE, onPickWallpaper = {},
            draft = draft, onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
            onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
            onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
            viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
            today = LocalDate.of(2026, 11, 18),
            onCall = { },
            onReport = { _, _, _ -> },
            onSetOfflineOnly = {},
            onDisguise = {},
            onAttachFile = if (withCamera) ({ _, _, _ -> }) else null,
        )
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 14) { scene.render(f * 50_000_000L); Thread.sleep(30) }
            val image = scene.render(14 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
