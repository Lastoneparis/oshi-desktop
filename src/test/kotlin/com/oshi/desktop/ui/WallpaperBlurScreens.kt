package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.ui.components.GroupSettingsActions
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.GroupSettingsView
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * __WALLPAPER_BLUR_2026_11_21__ Before/after sheets for "wallpaper behind the whole chat, the
 * header and the composer frosted over it": a 1:1 and a GROUP, each with a wallpaper, Light and
 * Dark (plus OLED and Light glass), from the REAL `ThreadPane` with fabricated state. A tool,
 * gated so the suite never renders by accident:
 *
 *     OSHI_RENDER_WALLPAPER=1 OSHI_WALLPAPER_OUT=/abs/dir ./gradlew test --tests 'com.oshi.desktop.ui.WallpaperBlurScreens'
 *
 * The threads are long enough that the newest messages fill the pane, so whatever sits under
 * the header and the composer is real message content: that is where the frosting shows.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class WallpaperBlurScreens {

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_WALLPAPER") == "1")
        val base = File(System.getenv("OSHI_WALLPAPER_OUT") ?: "build/screens-wallpaper").apply { mkdirs() }
        val prefix = System.getenv("OSHI_WALLPAPER_PREFIX") ?: ""
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,dark").split(',')
        for (theme in themes) {
            OshiTheme.appearance = appearance(theme)
            DisplaySettings.preview("en")
            render(File(base, "${prefix}direct-$theme.png"), 900, 760) { pane(direct(), WallpaperId.VIOLET) }
            render(File(base, "${prefix}group-$theme.png"), 900, 760) { pane(group(), WallpaperId.BLUSH) }
        }
    }

    internal companion object {
        /** Wheel notches up from the newest message before the picture is taken. */
        val SCROLL_STEPS = System.getenv("OSHI_WALLPAPER_SCROLL")?.toIntOrNull() ?: 5

        fun appearance(theme: String) = when (theme) {
            "dark" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = false)
            "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
            "light-glass" -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = true)
            "dark-glass" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = true)
            else -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = false)
        }

        private fun addr(c: Char) = c.toString().repeat(43) + "="

        private val lines = listOf(
            "Are we still on for Saturday?",
            "Yes! Leaving at 8 sharp from the station.",
            "Bring the thermos, it will be cold up there ☕",
            "The forecast says clear skies until noon, then wind above 2000 m. We should be on the ridge before eleven.",
            "Perfect.",
            "I booked the hut for the night, two rooms.",
            "Who is driving back on Sunday?",
            "Me — I'll pick up Bob on the way.",
            "Great, see you all there 👋",
        )

        fun row(i: Int, fromMe: Boolean, who: String, sender: String = "") = MessageRow(
            id = "m$i", fromMe = fromMe, who = who, body = lines[i % lines.size],
            attachment = null, stamp = "18:%02d".format(i % 60), status = if (fromMe) "read" else null,
            failed = false, edited = false, deleted = false, reactions = "", sentAtMs = 1_763_632_800_000L + i * 60_000L,
            senderAddress = sender,
        )

        fun direct(n: Int = 24) = ThreadView(
            conversationId = "D-WALLPAPER", title = "Inès Moreau", address = addr('c'),
            kind = ConversationKind.DIRECT, groupAdmin = false, groupMembers = emptyList(), groupCandidates = emptyList(),
            safetyNumber = "12345 67890 12345 67890", verified = true, reach = Reach.REACHABLE, reachLabel = "Reachable",
            peerTyping = false,
            messages = (0 until n).map { row(it, it % 3 == 1, if (it % 3 == 1) "me" else "Inès Moreau") },
            composer = ComposerState(true, null, true, null),
        )

        private val members = listOf(
            GroupMemberRow(addr('m'), "Hugo", isAdmin = true, isCreator = true, self = true),
            GroupMemberRow(addr('c'), "Inès Moreau", isAdmin = true, isCreator = false, self = false),
            GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
            GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false),
        )

        fun group(n: Int = 24) = ThreadView(
            conversationId = "G-WALLPAPER", title = "Weekend hikers", address = "G-WALLPAPER",
            kind = ConversationKind.GROUP, groupAdmin = true, groupCanEditInfo = true,
            groupMembers = members, groupCandidates = emptyList(),
            safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
            messages = (0 until n).map {
                val m = members[it % members.size]
                row(it, m.self, if (m.self) "me" else m.label, if (m.self) "" else m.address)
            },
            composer = ComposerState(true, null, true, null),
            groupSettings = GroupSettingsView(
                isAdmin = true, type = GroupType.COLLABORATIVE, settings = GroupSettings(), publicLink = null,
                canPost = true, dissolved = false, admins = members.filter { it.isAdmin }, banned = emptyList(),
                events = emptyList(), welcomeShown = null,
            ),
        )

        private val noopSettings = GroupSettingsActions(
            onSetType = {}, onSetPublic = { _, _ -> }, onSetPrivate = {}, onResetLink = {}, onSetPostPolicy = {},
            onSetReactions = { _, _ -> }, onSetWelcome = {}, onSetAdminDMs = {}, onUnban = {}, onMessageAdmin = {},
            onDissolve = {}, onDeleteForMe = {}, onLinkEditorClosed = {}, onCopy = {}, onSetComments = {},
        )

        @Composable
        fun pane(thread: ThreadView, wallpaper: WallpaperId) {
            ThreadPane(
                thread = thread, notice = null, busy = false,
                wallpaper = wallpaper, onPickWallpaper = {},
                draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                today = LocalDate.of(2026, 11, 21),
                onCall = { },
                onReport = { _, _, _ -> },
                groupSettingsActions = noopSettings,
            )
        }

        fun scene(wDp: Int, hDp: Int, scale: Float = 2f, content: @Composable () -> Unit) =
            ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
                OshiMaterialTheme {
                    Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
                }
            }
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scene = scene(wDp, hDp, content = content)
        try {
            for (f in 1 until 12) { scene.render(f * 50_000_000L); Thread.sleep(20) }
            // Scroll up a little, off the newest message, so a bubble sits half under the
            // header and another half under the composer: that is what the frosting is for.
            val mid = Offset(wDp * 2f / 2, hDp * 2f / 2)
            repeat(SCROLL_STEPS) { i ->
                scene.sendPointerEvent(PointerEventType.Scroll, mid, scrollDelta = Offset(0f, -1f))
                scene.render((12 + i) * 50_000_000L)
            }
            // ...and take the pointer away, so no hover toolbar is in the picture.
            scene.sendPointerEvent(PointerEventType.Exit, Offset(-10f, -10f))
            for (f in 12 + SCROLL_STEPS until 30) scene.render(f * 50_000_000L)
            val image = scene.render(30 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("[wallpaper] wrote ${target.absolutePath}")
        } finally {
            scene.close()
        }
    }
}
