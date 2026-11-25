package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.oshi.desktop.group.PublicGroupDirectory
import com.oshi.desktop.media.MarkupDoc
import com.oshi.desktop.media.MarkupOp
import com.oshi.desktop.media.PixelRect
import com.oshi.desktop.store.AppLock
import com.oshi.desktop.store.PrivacyPreferences
import com.oshi.desktop.ui.components.AppLockScreen
import com.oshi.desktop.ui.components.CameraPanel
import com.oshi.desktop.ui.components.DiscoverGroupsHost
import com.oshi.desktop.ui.components.DiscoverGroupsPane
import com.oshi.desktop.ui.components.HelpNavigation
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.MarkupEditor
import com.oshi.desktop.ui.components.MarkupTool
import com.oshi.desktop.ui.components.SettingsActions
import com.oshi.desktop.ui.components.SettingsNavigation
import com.oshi.desktop.ui.components.SettingsPane
import com.oshi.desktop.ui.components.SettingsSection
import com.oshi.desktop.ui.components.SettingsSlots
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.GroupCandidateRow
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ShellState
import com.oshi.desktop.ui.state.ThreadView
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate

/**
 * __DESKTOP_MAC_PARITY_B_2027__ The new screens — privacy, security/passcode, lock screen,
 * help, discover groups, member moderation, camera, photo markup, first run — rendered from
 * the real composables with fabricated state, en/fr/ar × light/dark/OLED:
 *
 *     OSHI_RENDER_FEATB=1 OSHI_FEATB_OUT=… ./gradlew test --tests com.oshi.desktop.ui.FeatBScreens
 */
class FeatBScreens {
    private val scale = 1.5f
    private fun addr(c: Char) = c.toString().repeat(43) + "="

    private fun state() = ShellState(
        selfAddress = addr('m'), selfAddressShort = "mmmm…", displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com", gateOpen = true, gateAnswered = true,
        deliveryReceiptsEnabled = true, readReceiptsEnabled = true, syncNotice = null,
        destination = Destination.MORE, contacts = emptyList(), scheduled = emptyList(), shareText = "",
        pane = Pane.CONVERSATION, conversations = emptyList(), selectedId = null, thread = null,
        draft = "", busy = false, notice = null,
    )

    private val group = ThreadView(
        conversationId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", title = "Team Desk", address = "4C8579F8-9CD5-47E8-AFDD-77300D45171D",
        kind = ConversationKind.GROUP, groupAdmin = false, groupCanEditInfo = false,
        groupInviteLink = "https://oshi-messenger.com/group?gid=4C8579F8-9CD5-47E8-AFDD-77300D45171D&name=Team&inviter=abc",
        groupDescription = null,
        groupMembers = listOf(
            GroupMemberRow(addr('m'), "Desk A", isAdmin = false, isCreator = false, self = true),
            GroupMemberRow(addr('b'), "Bob", isAdmin = true, isCreator = true, self = false),
            GroupMemberRow(addr('s'), "Sarah", isAdmin = false, isCreator = false, self = false, blocked = false),
            GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false, blocked = true),
        ),
        groupCandidates = emptyList(),
        safetyNumber = "", verified = false,
        reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = emptyList(),
        composer = ComposerState(true, null, true, null),
        forwardTargets = listOf(GroupCandidateRow(addr('b'), "Bob")),
        pinCanToggle = false,
    )

    private fun photo(): BufferedImage = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB).also { img ->
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(0x2D, 0x6C, 0xDF), 1600f, 1000f, Color(0xF2, 0x9E, 0x4C))
        g.fillRect(0, 0, 1600, 1000)
        g.color = Color(255, 255, 255, 200); g.fillOval(980, 180, 360, 360)
        g.color = Color(30, 30, 40); g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 64)
        g.drawString("+33 6 12 34 56 78", 120, 820)
        g.dispose()
    }

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_FEATB") == "1")
        val base = File(System.getenv("OSHI_FEATB_OUT") ?: "build/screens/featb")
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en,fr,ar").split(',')
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,dark,oled").split(',')
        val img = photo()
        val markupDoc = MarkupDoc(img.width, img.height)
            .add(MarkupOp.Stroke(listOf(1000f to 160f, 1180f to 120f, 1360f to 200f, 1380f to 420f, 1180f to 560f, 980f to 440f, 1000f to 160f), 0xFFFF3B30.toInt(), 14f))
            .add(MarkupOp.Label(140f, 300f, "Thursday 7:00", 0xFFFFFFFF.toInt(), 80f))
            .add(MarkupOp.Blur(PixelRect(100f, 740f, 760f, 850f)))
        val ads = listOf(
            PublicGroupDirectory.Ad("6F1C2A3B-4D5E-4F60-8A7B-9C0D1E2F3A4B", "Lisbon riders", addr('b'), 12, addr('b'), 0),
            PublicGroupDirectory.Ad("7F1C2A3B-4D5E-4F60-8A7B-9C0D1E2F3A4B", "Bairro Alto neighbours", addr('c'), 48, addr('c'), 0),
            PublicGroupDirectory.Ad("8F1C2A3B-4D5E-4F60-8A7B-9C0D1E2F3A4B", "Climbing — Sintra", addr('d'), 1, addr('d'), 0),
        )
        for (lang in langs) for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                else -> Appearance(ThemeMode.LIGHT, oledBlack = false)
            }
            DisplaySettings.preview(lang)
            val out = File(base, "$lang/$theme").apply { mkdirs() }
            val st = state()
            fun settings(name: String, section: SettingsSection, h: Int = 900) {
                SettingsNavigation.section = section
                render(File(out, "$name.png"), 1180, h) { SettingsPane(st, SettingsSlots(version = "1.5.0"), SettingsActions()) }
            }
            PrivacyPreferences.preview(PrivacyPreferences.Values(lastSeen = false, callsFromContactsOnly = true))
            settings("01-privacy", SettingsSection.PRIVACY, 1300)
            AppLock.preview(AppLock.Config(), false)
            settings("02-security-off", SettingsSection.SECURITY)
            AppLock.preview(AppLock.Config(saltB64 = "AA==", hashB64 = "AA==", autoLockMinutes = 15), false)
            settings("03-security-on", SettingsSection.SECURITY)
            HelpNavigation.open = setOf(2, 6)
            settings("04-help", SettingsSection.HELP, 1200)
            HelpNavigation.open = emptySet()
            settings("05-tools-discover-link", SettingsSection.TOOLS)

            AppLock.preview(AppLock.Config(saltB64 = "AA==", hashB64 = "AA=="), true)
            render(File(out, "10-lock.png"), 1180, 780) { AppLockScreen({ false }) }
            render(File(out, "11-lock-wrong.png"), 1180, 780) {
                AppLockScreen({ false }, initialError = com.oshi.desktop.i18n.t("lock.incorrect_passcode") + " · " + com.oshi.desktop.i18n.t("lock.passcode_attempts", 2))
            }
            render(File(out, "12-lock-recovery.png"), 1180, 860) { AppLockScreen({ false }, recoveryOpen = true) }
            AppLock.preview(AppLock.Config(saltB64 = "AA==", hashB64 = "AA==", failures = 6, waitUntilMs = System.currentTimeMillis() + 59_000), true)
            render(File(out, "13-lock-wait.png"), 1180, 780) { AppLockScreen({ false }) }
            AppLock.preview(AppLock.Config(), false)

            render(File(out, "20-discover-off-empty.png"), 1100, 820) { DiscoverGroupsPane(host(emptyList(), running = false)) }
            render(File(out, "21-discover-list.png"), 1100, 820) { DiscoverGroupsPane(host(ads, running = true)) }

            fun pane(preview: ThreadChromePreview, name: String, h: Int = 860) =
                render(File(out, "$name.png"), 1180, h) {
                    CompositionLocalProvider(LocalThreadChromePreview provides preview) { threadPane() }
                }
            pane(ThreadChromePreview(infoOpen = true, memberMenuAddress = addr('s')), "30-member-menu", 980)
            pane(ThreadChromePreview(infoOpen = true, memberMenuAddress = addr('d')), "31-member-menu-blocked", 980)
            pane(ThreadChromePreview(reportMemberAddress = addr('s')), "32-report-member")
            pane(ThreadChromePreview(panelOpen = true), "33-attach-panel-camera")

            render(File(out, "40-camera.png"), 900, 620) {
                Column { CameraPanel({}, {}, {}, previewFrame = img.toComposeImageBitmap(), startCamera = false) }
            }
            render(File(out, "41-camera-unavailable.png"), 900, 620) {
                Column { CameraPanel({}, {}, {}, initialError = "the camera would not open — on macOS this is usually Camera permission", startCamera = false) }
            }
            render(File(out, "42-markup-draw.png"), 1040, 760) { MarkupEditor(img, {}, {}, initialDoc = markupDoc) }
            render(File(out, "43-markup-crop.png"), 1040, 760) {
                MarkupEditor(img, {}, {}, initialDoc = markupDoc.withCrop(PixelRect(60f, 80f, 1500f, 900f)), initialTool = MarkupTool.CROP)
            }
            render(File(out, "44-markup-blur.png"), 1040, 760) { MarkupEditor(img, {}, {}, initialDoc = markupDoc, initialTool = MarkupTool.BLUR) }

            render(File(out, "50-first-run.png"), 960, 640) { FirstRunScreen(false, "", null, {}, {}, {}, {}, {}, {}) }
        }
        DisplaySettings.preview(null)
        PrivacyPreferences.preview(PrivacyPreferences.Values())
        AppLock.preview(AppLock.Config(), false)
        println("feat-b screens → ${base.absolutePath}")
    }

    private fun host(ads: List<PublicGroupDirectory.Ad>, running: Boolean) = object : DiscoverGroupsHost {
        override fun heard() = ads
        override fun meshRunning() = running
        override fun startMesh() = true
        override fun isMember(groupId: String) = groupId.startsWith("7F")
        override fun join(groupId: String) {}
        override fun openInvite() {}
    }

    @Composable
    private fun threadPane() {
        ThreadPane(
            thread = group, notice = null, busy = false,
            wallpaper = WallpaperId.NONE, onPickWallpaper = {},
            draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
            onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
            onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
            viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
            today = LocalDate.of(2026, 11, 19),
            onReport = { _, _, _ -> },
            onReportMember = { _, _, _, _ -> },
            onSetMemberBlocked = { _, _ -> },
            onPickImage = { null },
            onAttachFile = { _, _, _ -> },
            onReplyPrivatelyToMember = {},
        )
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 10) { scene.render(f * 50_000_000L); Thread.sleep(25) }
            val image = scene.render(10 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
