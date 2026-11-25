package com.oshi.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.oshi.desktop.group.GroupHandleRegistry
import com.oshi.desktop.group.GroupSettings
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.ui.components.GroupSettingsActions
import com.oshi.desktop.ui.components.GroupSettingsDialog
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.PublicJoinDialog
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.BannedRow
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.EventRow
import com.oshi.desktop.ui.state.GroupCandidateRow
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.GroupSettingsView
import com.oshi.desktop.ui.state.LinkResult
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.PublicJoinState
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import com.oshi.desktop.i18n.t
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ The group info settings of GROUP_INFO_SETTINGS_SPEC §5, from
 * the REAL ThreadPane with fabricated state (no client, no registry, nothing on the network):
 *
 *     ./gradlew renderGroupInfoScreens            # en,fr × light,oled (OSHI_RENDER_LANGS / OSHI_RENDER_THEMES)
 *
 * and, in the current language and theme, as part of `./gradlew renderScreens` (group-info/).
 */
object GroupInfoScreens {
    private const val SCALE = 2f

    private fun addr(c: Char) = c.toString().repeat(43) + "="

    private fun msg(id: String, fromMe: Boolean, who: String, body: String, stamp: String) = MessageRow(
        id = id, fromMe = fromMe, who = who, body = body, attachment = null, stamp = stamp,
        status = null, failed = false, edited = false, deleted = false, reactions = "", sentAtMs = 1_763_480_000_000L,
    )

    private val members = listOf(
        GroupMemberRow(addr('m'), "Desk A", isAdmin = true, isCreator = true, self = true),
        GroupMemberRow(addr('c'), "Inès Moreau", isAdmin = true, isCreator = false, self = false),
        GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
        GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false),
    )

    private val configured = GroupSettings(
        visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant", showPreview = true,
        reactions = GroupSettings.ReactionMode.SOME, allowedReactions = listOf("👍", "❤️", "🔥"),
        welcome = "Bienvenue ! Lisez le message épinglé avant de poster.",
        postPolicy = GroupSettings.PostPolicy.ADMINS, adminDMs = true,
    )

    private fun events() = listOf(
        EventRow(t("gs_ev_joined_link", "Yusuf"), "21:52"),
        EventRow(t("gs_ev_settings", t("gs_you"), t("gs_who_can_post")), "21:30"),
        EventRow(t("gs_ev_admin_promoted", t("gs_you"), "Inès Moreau"), "21:12"),
        EventRow(t("gs_ev_renamed", "Inès Moreau", "Swiss Quant"), "20:58"),
        EventRow(t("gs_ev_member_removed", t("gs_you"), "Mallory"), "Yesterday"),
    )

    private fun adminView(linkResult: LinkResult? = null, s: GroupSettings = configured) = GroupSettingsView(
        isAdmin = true, type = GroupType.ADMIN_ONLY, settings = s,
        publicLink = s.handle?.takeIf { s.isPublic }?.let { "https://oshi-messenger.com/g/$it" },
        canPost = true, dissolved = false,
        admins = members.filter { it.isAdmin },
        banned = listOf(BannedRow(addr('x'), "Mallory"), BannedRow(addr('y'), "yyyyyy…yyy=")),
        events = events(), welcomeShown = null, linkResult = linkResult,
    )

    private fun memberView(dissolved: Boolean = false) = GroupSettingsView(
        isAdmin = false, type = GroupType.ADMIN_ONLY, settings = configured.copy(dissolved = dissolved),
        publicLink = "https://oshi-messenger.com/g/swiss_quant",
        canPost = false, dissolved = dissolved,
        admins = listOf(
            GroupMemberRow(addr('m'), "Desk A", isAdmin = true, isCreator = true, self = false),
            GroupMemberRow(addr('c'), "Inès Moreau", isAdmin = true, isCreator = false, self = false),
        ),
        banned = emptyList(), events = emptyList(),
        welcomeShown = "Bienvenue ! Lisez le message épinglé avant de poster.",
    )

    private fun group(view: GroupSettingsView, composer: ComposerState = ComposerState(true, null, true, null), messages: Boolean = true) = ThreadView(
        conversationId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", title = "Swiss Quant", address = "4C8579F8-9CD5-47E8-AFDD-77300D45171D",
        kind = ConversationKind.GROUP, groupAdmin = view.isAdmin, groupCanEditInfo = view.isAdmin,
        groupInviteLink = "https://oshi-messenger.com/group?gid=4C8579F8-9CD5-47E8-AFDD-77300D45171D&name=Swiss%20Quant&inviter=${addr('m')}&t=q1w2e3r4t5y6u7i8o9p0aA",
        groupDescription = "Quant research, daily notes. Markets, models and the occasional chart.",
        groupMembers = if (view.isAdmin) members else members.map { it.copy(self = it.label == "Bob") },
        groupCandidates = listOf(GroupCandidateRow(addr('e'), "Chloé")),
        safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = if (!messages) emptyList() else listOf(
            msg("g1", false, "Inès Moreau", "Today's note: rates steady, watch the 10-year.", "09:02"),
            msg("g2", true, "me", "Thanks — pinned.", "09:05"),
        ),
        composer = composer,
        groupSettings = view,
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
            today = LocalDate.of(2026, 11, 20),
            onReport = { _, _, _ -> },
            onReplyPrivatelyToMember = {},
            groupSettingsActions = noop,
        )
    }

    /** Every state, in the current language and theme, into [out]. */
    fun renderSet(out: File) {
        out.mkdirs()
        fun shot(name: String, thread: ThreadView, preview: ThreadChromePreview = ThreadChromePreview(), w: Int = 1180, h: Int = 900) =
            render(File(out, "$name.png"), w, h) {
                CompositionLocalProvider(LocalThreadChromePreview provides preview) { pane(thread) }
            }
        val admin = group(adminView())
        val channelMember = group(memberView(), ComposerState(false, t("gs_only_admins_banner"), false, t("gs_only_admins_banner")))
        // The panel scrolls: a tall frame shows all of it at once.
        shot("01-info-admin", admin, ThreadChromePreview(infoOpen = true), h = 2050)
        shot("02-info-member", channelMember, ThreadChromePreview(infoOpen = true), h = 1500)
        shot("03-member-channel-thread", channelMember)
        shot("04-dialog-type-public-taken", group(adminView(LinkResult.TAKEN)), ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.TYPE))
        shot("05-dialog-type-private", group(adminView(s = configured.copy(visibility = GroupSettings.Visibility.PRIVATE, handle = null))),
            ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.TYPE))
        shot("06-dialog-permissions", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.PERMISSIONS))
        shot("07-dialog-who-can-post", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.POST))
        shot("08-dialog-reactions-some", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.REACTIONS))
        shot("09-dialog-welcome", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.WELCOME))
        shot("10-dialog-banned", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.BANNED))
        shot("11-dialog-recent-events", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.EVENTS))
        shot("12-dialog-delete", admin, ThreadChromePreview(infoOpen = true, groupDialog = GroupSettingsDialog.DELETE))
        shot("13-dissolved-member", group(memberView(dissolved = true), ComposerState(false, t("gs_dissolved_banner"), false, null)))
        shot("14-welcome-new-chat", group(memberView(), ComposerState(false, t("gs_only_admins_banner"), false, null), messages = false))
        shot("15-reactions-some-bar", admin, ThreadChromePreview(toolbarMessageId = "g1", reactBarMessageId = "g1"))
        val found = GroupHandleRegistry.Lookup.Found("swiss_quant", listOf(addr('m')), GroupHandleRegistry.Preview("Swiss Quant", "Quant research, daily notes. Markets, models and the occasional chart.", 42))
        render(File(out, "16-public-join-confirm.png"), 980, 620) {
            PublicJoinDialog(PublicJoinState.Found("swiss_quant", "Swiss Quant", found.preview!!.description, 42, found), {}, {})
        }
        render(File(out, "17-public-join-not-found.png"), 980, 620) {
            PublicJoinDialog(PublicJoinState.Problem("swiss_quant", t("gs_link_not_found")), {}, {})
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val base = File(args.firstOrNull() ?: "build/screens-group-info")
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
