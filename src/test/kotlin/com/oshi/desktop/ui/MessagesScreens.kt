package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.components.ContactsCard
import com.oshi.desktop.ui.components.ConversationFilter
import com.oshi.desktop.ui.components.ConversationListPane
import com.oshi.desktop.ui.components.LocalThreadChromePreview
import com.oshi.desktop.ui.components.Metrics
import com.oshi.desktop.ui.components.NewPane
import com.oshi.desktop.ui.components.NoConversationPane
import com.oshi.desktop.ui.components.ThreadChromePreview
import com.oshi.desktop.ui.components.ThreadPane
import com.oshi.desktop.ui.components.ViewOnceFacts
import com.oshi.desktop.ui.components.WallpaperId
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.ConversationRow
import com.oshi.desktop.ui.state.Destination
import com.oshi.desktop.ui.state.GroupCandidateRow
import com.oshi.desktop.ui.state.GroupMemberRow
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.PollCardRow
import com.oshi.desktop.ui.state.PollOptionRow
import com.oshi.desktop.ui.state.QuoteRow
import com.oshi.desktop.ui.state.Reach
import com.oshi.desktop.ui.state.ShellState
import com.oshi.desktop.ui.state.ThreadView
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * __MESSAGES_SCREENS_2027__ Every screen of the messaging side — the conversation list, a 1:1
 * and a group conversation, their menus and info panels, the safety-number and export sheets,
 * the "+" panel, the poll composer, the contact picker, in-conversation search, a blocked
 * contact, new chat, new group, contacts and the empty states — rendered from the REAL
 * composables with fabricated state, per language and per appearance. A TOOL, gated:
 *
 *     OSHI_RENDER_MESSAGES=1 OSHI_MESSAGES_OUT=~/oshi-wt/shots/messages \
 *       ./gradlew test --tests 'com.oshi.desktop.ui.MessagesScreens'
 *
 * `OSHI_MESSAGES_LANGS` (default `en,fr,ar`) and `OSHI_MESSAGES_THEMES` (default
 * `light,dark,oled`) narrow the matrix. Output: `<out>/<lang>/<theme>/NN-name.png`.
 */
class MessagesScreens {

    private fun addr(c: Char) = c.toString().repeat(43) + "="
    private val me = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val now = System.currentTimeMillis()
    private val safety = "37021 88410 22957 61403 99812 04476 51230 77801 33904 12877 60215 48390"

    private fun msg(
        id: String, fromMe: Boolean, who: String, body: String, stamp: String,
        status: String? = null, keys: List<String> = emptyList(), poll: PollCardRow? = null,
        quote: QuoteRow? = null, attachment: String? = null,
    ) = MessageRow(
        id = id, fromMe = fromMe, who = who, body = body, attachment = attachment, stamp = stamp,
        status = status, failed = false, edited = false, deleted = false, reactions = keys.joinToString(""),
        reactionKeys = keys, sentAtMs = 1_763_480_000_000L, poll = poll, quote = quote,
    )

    private fun lang(en: String, fr: String, ar: String) = when (Strings.activeTag.substringBefore('-')) {
        "fr" -> fr
        "ar" -> ar
        else -> en
    }

    private fun poll(): PollCardRow = PollCardRow(
        pollId = "p1",
        question = lang("Where do we meet on Thursday?", "On se retrouve où jeudi ?", "أين نلتقي يوم الخميس؟"),
        options = listOf(
            PollOptionRow(lang("North gate", "Porte nord", "البوابة الشمالية"), 3, 60, mine = true),
            PollOptionRow(lang("The café", "Le café", "المقهى"), 2, 40, mine = false),
            PollOptionRow(lang("Online", "En ligne", "عبر الإنترنت"), 0, 0, mine = false),
        ),
        voterCount = 5, allowsMultipleAnswers = false, isAnonymous = false, closed = false,
    )

    private fun direct(): ThreadView {
        val love = OshiMascot.named("love")?.let(OshiMascot::wireEmoji) ?: "❤️"
        return ThreadView(
            conversationId = addr('a'), title = "Camille Laurent", address = addr('a'),
            kind = ConversationKind.DIRECT, groupAdmin = false,
            groupMembers = emptyList(), groupCandidates = emptyList(),
            safetyNumber = safety, safetyQr = "OSHI-SN:" + addr('a') + me, verified = false,
            reach = Reach.REACHABLE, reachLabel = lang("Secure path available", "Chemin sécurisé disponible", "مسار آمن متاح"), peerTyping = false,
            messages = listOf(
                msg("1", false, "Camille", lang("Are you coming tonight?", "Tu viens ce soir ?", "هل ستأتي الليلة؟"), "17:40"),
                msg("2", true, "me", lang("Yes — I'll send the details in a minute.", "Oui — je t'envoie les détails dans une minute.", "نعم — سأرسل التفاصيل بعد دقيقة."), "17:41", "read", keys = listOf(love)),
                msg("3", false, "Camille", "", "17:43", poll = poll()),
                msg("4", true, "me", lang("North gate, 18:00.", "Porte nord, 18 h.", "البوابة الشمالية، الساعة 18:00."), "17:44", "delivered",
                    quote = QuoteRow("1", "Camille", lang("Are you coming tonight?", "Tu viens ce soir ?", "هل ستأتي الليلة؟"))),
            ),
            composer = ComposerState(true, null, true, null),
            forwardTargets = listOf(GroupCandidateRow(addr('b'), "Bob"), GroupCandidateRow(addr('c'), "Inès")),
            pinCanToggle = true,
            pinned = QuoteRow("4", "me", lang("North gate, 18:00.", "Porte nord, 18 h.", "البوابة الشمالية، الساعة 18:00.")),
            conversationPinned = true,
            groupMuted = true,
        )
    }

    private fun group(): ThreadView = ThreadView(
        conversationId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", title = lang("Team Desk", "Équipe Accueil", "فريق الاستقبال"),
        address = "4C8579F8-9CD5-47E8-AFDD-77300D45171D",
        kind = ConversationKind.GROUP, groupAdmin = true, groupCanEditInfo = true,
        groupInviteLink = "oshi://join/4C8579F8-9CD5-47E8-AFDD-77300D45171D#k=Zm9vYmFy",
        groupDescription = lang("Coordination for the Thursday distribution.", "Coordination de la distribution du jeudi.", "تنسيق توزيع يوم الخميس."),
        groupMembers = listOf(
            GroupMemberRow(me, "Hugo", isAdmin = true, isCreator = true, self = true),
            GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
            GroupMemberRow(addr('c'), "Inès", isAdmin = true, isCreator = false, self = false),
            GroupMemberRow(addr('d'), "Yusuf", isAdmin = false, isCreator = false, self = false),
        ),
        groupCandidates = listOf(GroupCandidateRow(addr('e'), "Chloé")),
        safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = listOf(
            msg("g1", false, "Bob", lang("Who has the keys to the van?", "Qui a les clés du camion ?", "من لديه مفاتيح الشاحنة؟"), "21:40"),
            msg("g2", false, "Inès", lang("I do. Leaving at 7.", "Moi. Je pars à 7 h.", "أنا. سأغادر الساعة 7."), "21:41", keys = listOf("👍", "⭕")),
            msg("g3", true, "me", "", "21:45", "delivered", poll = poll()),
            msg("g4", true, "me", lang("Perfect — I'll bring the boxes.", "Parfait — j'apporte les cartons.", "ممتاز — سأحضر الصناديق."), "21:47", "delivered"),
        ),
        composer = ComposerState(true, null, true, null),
        forwardTargets = listOf(GroupCandidateRow(addr('b'), "Bob")),
        pinCanToggle = true,
    )

    private fun contacts() = listOf(
        ContactRow(addr('a'), "aaaa…", "Camille Laurent", verified = false, blocked = false, safetyNumber = safety),
        ContactRow(addr('b'), "bbbb…", "Bob", verified = true, blocked = false, safetyNumber = safety, sharedNickname = "Bobby"),
        ContactRow(addr('c'), "cccc…", "Inès", verified = true, blocked = false, safetyNumber = safety),
        ContactRow(addr('d'), "dddd…", "Yusuf", verified = false, blocked = false, safetyNumber = safety),
        ContactRow(addr('f'), "ffff…", "Spam Bot 3000", verified = false, blocked = true, safetyNumber = safety),
    )

    private fun row(id: String, label: String, preview: String, unread: Int, kind: ConversationKind, minutes: Long, pinned: Boolean = false, muted: Boolean = false, type: com.oshi.desktop.group.GroupType? = null) =
        ConversationRow(
            id = id, label = label, kind = kind, messageCount = 4,
            lastActivityMs = now - minutes * 60_000, lastActivity = "%02d:%02d".format(17 - (minutes / 60).toInt(), (59 - minutes % 60).toInt()),
            preview = preview, unread = unread, pinned = pinned, muted = muted, groupType = type,
        )

    private fun state(empty: Boolean = false, selected: String? = addr('a')) = ShellState(
        selfAddress = me, selfAddressShort = "3q2+7w…", displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com", gateOpen = true, gateAnswered = true,
        deliveryReceiptsEnabled = true, readReceiptsEnabled = true, syncNotice = null,
        destination = Destination.MESSAGES, contacts = if (empty) emptyList() else contacts(), scheduled = emptyList(),
        shareText = "Add me on OSHI: oshi://add?key=$me", pane = Pane.CONVERSATION,
        conversations = if (empty) emptyList() else listOf(
            row(addr('a'), "Camille Laurent", lang("North gate, 18:00.", "Porte nord, 18 h.", "البوابة الشمالية، الساعة 18:00."), 0, ConversationKind.DIRECT, 2, pinned = true, muted = true),
            row("4C8579F8-9CD5-47E8-AFDD-77300D45171D", lang("Team Desk", "Équipe Accueil", "فريق الاستقبال"), lang("Inès: I do. Leaving at 7.", "Inès : Moi. Je pars à 7 h.", "إيناس: أنا. سأغادر الساعة 7."), 3, ConversationKind.GROUP, 5, pinned = true, type = com.oshi.desktop.group.GroupType.COLLABORATIVE),
            row(addr('b'), "Bob", lang("See you tomorrow 👋", "À demain 👋", "أراك غدًا 👋"), 1, ConversationKind.DIRECT, 40),
            row(addr('c'), "Inès", lang("📊 Where do we meet on Thursday?", "📊 On se retrouve où jeudi ?", "📊 أين نلتقي يوم الخميس؟"), 0, ConversationKind.DIRECT, 95, muted = true),
            row(addr('d'), "Yusuf", "GIF", 0, ConversationKind.DIRECT, 300),
            row("lora!deadbeef", "radio: deadbeef", lang("unverified radio text", "texte radio non vérifié", "نص لاسلكي غير موثق"), 0, ConversationKind.LORA, 700),
        ),
        selectedId = selected, thread = null, draft = "", busy = false, notice = null,
    )

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_MESSAGES") == "1")
        val base = File(System.getenv("OSHI_MESSAGES_OUT") ?: "build/screens/messages")
        val langs = (System.getenv("OSHI_MESSAGES_LANGS") ?: "en,fr,ar").split(',').map { it.trim() }
        val themes = (System.getenv("OSHI_MESSAGES_THEMES") ?: "light,dark,oled").split(',').map { it.trim() }
        val only = System.getenv("OSHI_MESSAGES_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val savedTag = Strings.activeTag
        try {
            for (l in langs) for (theme in themes) {
                Strings.activeTag = l
                OshiTheme.appearance = when (theme) {
                    "dark" -> Appearance(ThemeMode.DARK, oledBlack = false)
                    "oled" -> Appearance(ThemeMode.DARK, oledBlack = true)
                    else -> Appearance.DEFAULT
                }
                val out = File(base, "$l/$theme").apply { mkdirs() }
                fun shot(name: String, w: Int, h: Int, scale: Float = 2f, content: @Composable () -> Unit) {
                    if (only != null && name !in only) return
                    render(File(out, "$name.png"), w, h, scale, content)
                }
                renderAll(::shot)
            }
        } finally {
            Strings.activeTag = savedTag
            OshiTheme.appearance = Appearance.DEFAULT
        }
    }

    private fun renderAll(shot: (String, Int, Int, Float, @Composable () -> Unit) -> Unit) {
        val d = direct()
        val g = group()
        // The whole window, at the narrowest supported width, a common one, and the widest.
        ChatListSettings.overrideForRender(true)
        shot("01-window-1280-direct", 1280, 820, 2f) { window(state(), d) }
        shot("02-window-1024-group", 1024, 760, 2f) { window(state(selected = g.conversationId), g) }
        shot("03-window-2560-direct-info", 2560, 1300, 1f) { window(state(), d, ThreadChromePreview(infoOpen = true)) }
        shot("04-list-row-menu", 1280, 820, 2f) { window(state(), d, rowMenuOpen = true) }
        shot("05-direct-overflow-menu", 980, 760, 2f) { thread(d, ThreadChromePreview(headerMenuOpen = true)) }
        shot("06-group-overflow-menu", 980, 820, 2f) { thread(g, ThreadChromePreview(headerMenuOpen = true)) }
        shot("07-group-info", 1180, 900, 2f) { thread(g, ThreadChromePreview(infoOpen = true)) }
        shot("08-safety-number", 980, 760, 2f) { thread(d, ThreadChromePreview(verifyOpen = true)) }
        shot("09-export-chat", 980, 620, 2f) { thread(d, ThreadChromePreview(exportOpen = true)) }
        shot("10-plus-panel", 980, 760, 2f) { thread(d, ThreadChromePreview(panelOpen = true)) }
        shot("11-poll-composer", 980, 900, 2f) { thread(g, ThreadChromePreview(pollOpen = true)) }
        shot("12-contact-picker", 980, 760, 2f) { thread(d, ThreadChromePreview(contactOpen = true), contacts = contacts()) }
        shot("13-search-in-chat", 980, 620, 2f) { thread(d, ThreadChromePreview(searchQuery = lang("gate", "porte", "البوابة"))) }
        shot("14-search-no-match", 980, 620, 2f) { thread(d, ThreadChromePreview(searchQuery = "zzz")) }
        shot("15-blocked-contact", 980, 620, 2f) {
            thread(d.copy(contactBlocked = true, composer = ComposerState(false, com.oshi.desktop.i18n.t("chat.contact_blocked_message"), false, null)))
        }
        shot("16-empty-chat", 980, 620, 2f) { thread(d.copy(messages = emptyList(), pinned = null)) }
        shot("17-new-chat", 1180, 820, 2f) {
            NewPane(state(), onStartConversation = {}, onCopy = {}, onPickQrImage = { null })
        }
        shot("18-new-chat-narrow", 700, 1100, 2f) {
            NewPane(state(), onStartConversation = {}, onCopy = {}, onPickQrImage = { null })
        }
        shot("19-new-group", 1280, 820, 2f) { window(state(), d, creatingGroup = true) }
        shot("20-contacts", 900, 820, 2f) { contactsCard(state()) }
        shot("21-contacts-empty", 900, 600, 2f) { contactsCard(state(empty = true)) }
        shot("22-window-empty", 1280, 820, 2f) { window(state(empty = true, selected = null), null) }
        shot("23-window-nothing-selected", 1280, 820, 2f) { window(state(selected = null), null) }
        ChatListSettings.overrideForRender(false)
        shot("24-window-classic-groups", 1280, 820, 2f) { window(state(selected = g.conversationId), g, half = ConversationFilter.Half.GROUPS) }
        ChatListSettings.overrideForRender(true)
    }

    @Composable
    private fun contactsCard(s: ShellState) {
        Box(Modifier.fillMaxSize().background(OshiTheme.paneGround).verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp)) {
                ContactsCard(s, onSelect = {}, onCopy = {}, onRename = { _, _ -> }, onSetBlocked = { _, _ -> }, onSetVerified = { _, _ -> }, onVerifySafetyNumber = { _, _ -> true }, onNewChat = {})
            }
        }
    }

    @Composable
    private fun window(
        s: ShellState,
        t: ThreadView?,
        preview: ThreadChromePreview = ThreadChromePreview(),
        half: ConversationFilter.Half = ConversationFilter.Half.MESSAGES,
        rowMenuOpen: Boolean = false,
        creatingGroup: Boolean = false,
    ) {
        Row(Modifier.fillMaxSize().background(OshiTheme.background)) {
            CompositionLocalProvider(
                com.oshi.desktop.ui.components.LocalConversationListPreview provides
                    com.oshi.desktop.ui.components.ConversationListPreview(rowMenuId = if (rowMenuOpen) s.conversations.getOrNull(1)?.id else null, creatingGroup = creatingGroup),
            ) {
                ConversationListPane(
                    state = s, query = "", onQuery = {}, half = half, onHalf = {}, onSelect = {}, onShow = {},
                    today = LocalDate.of(2026, 11, 19),
                )
            }
            Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(OshiTheme.separator))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (t == null) NoConversationPane(s, onNewChat = {})
                else thread(t, preview)
            }
        }
    }

    @Composable
    private fun thread(t: ThreadView, preview: ThreadChromePreview = ThreadChromePreview(), contacts: List<ContactRow> = emptyList()) {
        CompositionLocalProvider(LocalThreadChromePreview provides preview) {
            ThreadPane(
                thread = t, notice = null, busy = false,
                wallpaper = WallpaperId.NONE, onPickWallpaper = {},
                draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                today = LocalDate.of(2026, 11, 19),
                onCall = { },
                onReport = { _, _, _ -> },
                onSetOfflineOnly = {},
                onDisguise = if (t.kind == ConversationKind.DIRECT) ({}) else null,
                onGif = {},
                onCreatePoll = { _, _, _, _, _ -> },
                onSendContact = {},
                contacts = contacts,
                onSetMuted = {},
                onSetChatPinned = {},
                onSetContactBlocked = {},
                onSetContactVerified = {},
                onVerifyPayload = { true },
                onExport = {},
            )
        }
    }

    private fun render(target: File, wDp: Int, hDp: Int, scale: Float, content: @Composable () -> Unit) {
        val scene = ImageComposeScene((wDp * scale).toInt(), (hDp * scale).toInt(), Density(scale)) {
            OshiMaterialTheme {
                Surface(Modifier.fillMaxSize(), color = OshiTheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            for (f in 1 until 14) { scene.render(f * 50_000_000L); Thread.sleep(25) }
            val image = scene.render(14 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
