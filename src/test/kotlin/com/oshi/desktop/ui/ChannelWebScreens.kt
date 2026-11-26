package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.components.ChannelWebPostDialog
import com.oshi.desktop.ui.components.GroupSettingsActions
import com.oshi.desktop.ui.components.GroupSettingsDialog
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ChannelWebView
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.GroupSettingsView
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import com.oshi.desktop.ui.state.WebLinkState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate

/**
 * __CHANNEL_WEB_2026_11_26__ The public web preview UI from the REAL ThreadPane / dialogs with
 * fabricated state (no client, nothing on the network):
 *
 *     ./gradlew renderChannelWebScreens -PchannelWebShots=<dir>   # en,fr × light,oled
 */
object ChannelWebScreens {
    private const val SCALE = 2f
    private fun addr(c: Char) = c.toString().repeat(43) + "="

    private val members = listOf(
        GroupMemberRow(addr('m'), "Desk A", isAdmin = true, isCreator = true, self = true),
        GroupMemberRow(addr('c'), "Inès Moreau", isAdmin = true, isCreator = false, self = false),
        GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
    )

    private val on = GroupSettings(
        visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant", showPreview = true,
        postPolicy = GroupSettings.PostPolicy.ADMINS, webPreview = true, webIndexing = false,
    )

    private fun view(admin: Boolean, s: GroupSettings, web: ChannelWebView = ChannelWebView()) = GroupSettingsView(
        isAdmin = admin, type = GroupType.ADMIN_ONLY, settings = s,
        publicLink = s.handle?.takeIf { s.isPublic }?.let { "https://oshi-messenger.com/g/$it" },
        canPost = admin, dissolved = false, admins = members.filter { it.isAdmin },
        banned = emptyList(), events = emptyList(), welcomeShown = null, web = web,
    )

    private fun post(id: String, body: String, web: Boolean) = MessageRow(
        id = id, fromMe = true, who = "Desk A", body = body, attachment = null, stamp = "09:02",
        status = null, failed = false, edited = false, deleted = false, reactions = "", sentAtMs = 1_795_708_790_000L, webLink = web,
    )

    private fun thread(v: GroupSettingsView) = ThreadView(
        conversationId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", title = "Swiss Quant", address = "4C8579F8-9CD5-47E8-AFDD-77300D45171D",
        kind = ConversationKind.GROUP, groupAdmin = v.isAdmin, groupCanEditInfo = v.isAdmin,
        groupInviteLink = null, groupDescription = "Quant research, daily notes.",
        groupMembers = if (v.isAdmin) members else members.map { it.copy(self = it.label == "Bob") },
        groupCandidates = emptyList(), safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = listOf(
            post("p1", "*Weekly position* — long BTC, see https://example.org/x", web = true),
            post("p2", "Rates steady, watch the 10-year.", web = true),
        ),
        composer = if (v.isAdmin) ComposerState(true, null, true, null) else ComposerState(false, t("gs_only_admins_banner"), false, null),
        groupSettings = v,
    )

    private val noop = GroupSettingsActions(
        onSetType = {}, onSetPublic = { _, _ -> }, onSetPrivate = {}, onResetLink = {}, onSetPostPolicy = {},
        onSetReactions = { _, _ -> }, onSetWelcome = {}, onSetAdminDMs = {}, onUnban = {}, onMessageAdmin = {},
        onDissolve = {}, onDeleteForMe = {}, onLinkEditorClosed = {}, onCopy = {},
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
            today = LocalDate.of(2026, 11, 26),
            groupSettingsActions = noop,
            onWebLink = {},
        )
    }

    fun renderSet(out: File) {
        out.mkdirs()
        fun shot(name: String, thread: ThreadView, preview: ThreadChromePreview = ThreadChromePreview(), w: Int = 1180, h: Int = 900) =
            render(File(out, "$name.png"), w, h) { CompositionLocalProvider(LocalThreadChromePreview provides preview) { pane(thread) } }
        val info = ThreadChromePreview(infoOpen = true)
        shot("01-admin-on", thread(view(true, on, ChannelWebView(pending = 2))), info, h = 1500)
        shot("02-admin-off", thread(view(true, on.copy(webPreview = false))), info, h = 1300)
        shot("03-admin-no-handle", thread(view(true, on.copy(visibility = GroupSettings.Visibility.PRIVATE, handle = null, webPreview = false))), info, h = 1300)
        shot("04-admin-error", thread(view(true, on.copy(webPreview = false), ChannelWebView(error = t("desktop.cw.err_not_controller")))), info, h = 1300)
        shot("05-member-readonly", thread(view(false, on)), info, h = 1300)
        shot("06-confirm-on", thread(view(true, on.copy(webPreview = false))), ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.WEB_ON))
        shot("07-confirm-off", thread(view(true, on)), ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.WEB_OFF))
        shot("08-post-context-menu", thread(view(true, on)), ThreadChromePreview(contextMenuMessageId = "p1"))
        val st = WebLinkState("G", "p1", "swiss_quant", loading = false, n = 7)
        render(File(out, "09-web-link.png"), 980, 820) { ChannelWebPostDialog(st, {}, {}, {}) }
        render(File(out, "10-web-link-dark-embed.png"), 980, 820) { ChannelWebPostDialog(st, {}, {}, {}, initialDark = true) }
        render(File(out, "11-web-link-loading.png"), 980, 520) { ChannelWebPostDialog(st.copy(loading = true, n = null), {}, {}, {}) }
        render(File(out, "12-web-link-not-on-web.png"), 980, 520) { ChannelWebPostDialog(st.copy(n = null), {}, {}, {}) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val base = File(args.firstOrNull() ?: "build/screens-channel-web")
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en,fr").split(',')
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,oled").split(',')
        for (lang in langs) for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                else -> Appearance(ThemeMode.LIGHT, oledBlack = false)
            }
            DisplaySettings.preview(lang)
            renderSet(File(base, "$lang-$theme"))
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * SCALE).toInt(), (hDp * SCALE).toInt(), Density(SCALE)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 10) { scene.render(f * 50_000_000L); Thread.sleep(20) }
            val image = scene.render(10 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
