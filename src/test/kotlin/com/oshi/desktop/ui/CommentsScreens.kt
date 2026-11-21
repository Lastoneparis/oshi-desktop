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
import com.oshi.desktop.ui.components.CommentActions
import com.oshi.desktop.ui.components.GroupSettingsActions
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.CommentThreadView
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.GroupSettingsView
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.QuoteRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ThreadView
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate

/**
 * __GROUP_COMMENTS_2026_11_21__ Comments under channel posts (GROUP_INFO_SETTINGS_SPEC §2.12.6), from
 * the REAL ThreadPane with fabricated state (no client, nothing on the network):
 *
 *     ./gradlew renderCommentsScreens     # en,fr × light,oled (OSHI_RENDER_LANGS / OSHI_RENDER_THEMES)
 */
object CommentsScreens {
    private const val SCALE = 2f
    private const val GID = "4C8579F8-9CD5-47E8-AFDD-77300D45171D"

    private fun addr(c: Char) = c.toString().repeat(43) + "="

    private fun msg(
        id: String, fromMe: Boolean, who: String, body: String, stamp: String,
        comments: Int? = null, quote: QuoteRow? = null, reactions: List<String> = emptyList(), edited: Boolean = false,
        sender: String = "",
    ) = MessageRow(
        id = id, fromMe = fromMe, who = who, body = body, attachment = null, stamp = stamp,
        status = if (fromMe) "delivered" else null, failed = false, edited = edited, deleted = false,
        reactions = reactions.joinToString(""), reactionKeys = reactions, sentAtMs = 1_763_632_800_000L,
        comments = comments, quote = quote, senderAddress = sender,
    )

    private val members = listOf(
        GroupMemberRow(addr('m'), "Hugo", isAdmin = true, isCreator = true, self = true),
        GroupMemberRow(addr('c'), "Inès Moreau", isAdmin = true, isCreator = false, self = false),
        GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
        GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false),
    )

    private val settings = GroupSettings(
        visibility = GroupSettings.Visibility.PUBLIC, handle = "swiss_quant", showPreview = true,
        reactions = GroupSettings.ReactionMode.SOME, allowedReactions = listOf("👍", "❤️", "🔥"),
        postPolicy = GroupSettings.PostPolicy.ADMINS, adminDMs = true, comments = true,
    )

    private fun view(admin: Boolean) = GroupSettingsView(
        isAdmin = admin, type = GroupType.ADMIN_ONLY, settings = settings,
        publicLink = "https://oshi-messenger.com/g/swiss_quant", canPost = admin, dissolved = false,
        admins = members.filter { it.isAdmin }.map { if (admin) it else it.copy(self = false) },
        banned = emptyList(), events = emptyList(), welcomeShown = null,
    )

    private val posts = listOf(
        msg("P1", false, "Inès Moreau", "Weekly note: rates steady, watch the 10-year. Next update Friday.", "08:40", comments = 0, sender = addr('c')),
        msg("P2", true, "me", "Daily note: BTC weekly position 40% long. Vol targeting is on, so the size moves with realised volatility.", "09:02", comments = 3, reactions = listOf("🔥", "👍")),
        msg("P3", true, "me", "Short one today: no change.", "17:30", comments = 1),
    )

    private val c1 = msg("C1", false, "Bob", "Why 40 and not 50?", "09:14", reactions = listOf("👍"), sender = addr('b'))
    private val c2 = msg("C2", true, "me", "Vol targeting caps it at 40. Above that the weekly drawdown limit binds.", "09:20",
        quote = QuoteRow("C1", "Bob", "Why 40 and not 50?"), edited = true)
    private val c3 = msg("C3", false, "Yusuf", "Makes sense — thanks for the detail, it helps a lot to see the reasoning.", "09:31", sender = addr('d'))

    private fun thread(
        admin: Boolean,
        open: CommentThreadView? = null,
        composer: ComposerState = if (admin) ComposerState(true, null, true, null) else ComposerState(false, t("gs_only_admins_banner"), false, t("gs_only_admins_banner")),
    ) = ThreadView(
        conversationId = GID, title = "Swiss Quant", address = GID,
        kind = ConversationKind.GROUP, groupAdmin = admin, groupCanEditInfo = admin,
        groupDescription = "Quant research, daily notes.",
        groupMembers = if (admin) members else members.map { it.copy(self = it.label == "Bob") },
        groupCandidates = emptyList(),
        safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = posts, composer = composer, groupSettings = view(admin), commentThread = open,
    )

    private fun openThread(state: CommentThreadView.State = CommentThreadView.State.OPEN, replying: Boolean = true, empty: Boolean = false, admin: Boolean = false) =
        CommentThreadView(
            postId = if (empty) "P1" else "P2",
            post = if (empty) posts[0].copy(comments = null) else posts[1].copy(comments = null),
            comments = if (empty) emptyList() else listOf(c1, c2, c3),
            state = state,
            draft = if (replying) "And the drawdown limit is per week?" else "",
            busy = false,
            replyingTo = if (replying) QuoteRow("C2", "Hugo", "Vol targeting caps it at 40. Above that the weekly drawdown limit binds.") else null,
            canModerate = admin,
        )

    private val noopSettings = GroupSettingsActions(
        onSetType = {}, onSetPublic = { _, _ -> }, onSetPrivate = {}, onResetLink = {}, onSetPostPolicy = {},
        onSetReactions = { _, _ -> }, onSetWelcome = {}, onSetAdminDMs = {}, onUnban = {}, onMessageAdmin = {},
        onDissolve = {}, onDeleteForMe = {}, onLinkEditorClosed = {}, onCopy = {}, onSetComments = {},
    )
    private val noopComments = CommentActions({}, {}, {}, {}, {}, {})

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
            commentActions = noopComments,
        )
    }

    fun renderSet(out: File) {
        out.mkdirs()
        fun shot(name: String, thread: ThreadView, preview: ThreadChromePreview = ThreadChromePreview(), w: Int = 1280, h: Int = 860) =
            render(File(out, "$name.png"), w, h) {
                CompositionLocalProvider(LocalThreadChromePreview provides preview) { pane(thread) }
            }
        shot("01-channel-posts-with-comments-bar", thread(admin = false))
        shot("02-comment-thread-replying", thread(admin = false, open = openThread()))
        shot("03-comment-thread-admin-empty", thread(admin = true, open = openThread(replying = false, empty = true, admin = true)))
        shot("04-comment-thread-turned-off", thread(admin = false, open = openThread(state = CommentThreadView.State.DISABLED, replying = false)))
        shot("05-comment-thread-post-gone", thread(admin = false, open = CommentThreadView("PX", null, emptyList(), CommentThreadView.State.POST_UNAVAILABLE, "", false, null, false)))
        shot("06-settings-comments-switch", thread(admin = true), ThreadChromePreview(infoOpen = true), h = 1500)
        // The narrowest window the app allows, so the bar and the panel are seen wrapping.
        shot("07-comment-thread-narrow", thread(admin = false, open = openThread()), w = 980)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val base = File(args.firstOrNull() ?: "build/screens-comments")
        val langs = (System.getenv("OSHI_RENDER_LANGS") ?: "en,fr").split(',')
        val themes = (System.getenv("OSHI_RENDER_THEMES") ?: "light,oled").split(',')
        for (lang in langs) for (theme in themes) {
            OshiTheme.appearance = when (theme) {
                "dark" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = false)
                "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                // __GLASS_ALL_THEMES__ the glass switch on in Light / Dark.
                "light-glass" -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = true)
                "dark-glass" -> Appearance(ThemeMode.DARK, oledBlack = false, glass = true)
                else -> Appearance(ThemeMode.LIGHT, oledBlack = false, glass = false)
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
