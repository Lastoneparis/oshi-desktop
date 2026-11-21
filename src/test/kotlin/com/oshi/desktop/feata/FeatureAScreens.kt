package com.oshi.desktop.feata

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

import com.oshi.desktop.ui.Appearance
import com.oshi.desktop.ui.OshiMaterialTheme
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.ThemeMode
import com.oshi.desktop.ui.components.ForwardSheet
import com.oshi.desktop.store.MediaType

/**
 * __MAC_PARITY_A_2027__ The six Mac-parity features rendered from the REAL composables, per
 * language and appearance. A TOOL, gated:
 *
 *     OSHI_RENDER_FEATA=1 OSHI_FEATA_OUT=~/oshi-wt/shots/feat-a ./gradlew test --tests 'com.oshi.desktop.feata.FeatureAScreens'
 */
class FeatureAScreens {
    private fun addr(c: Char) = c.toString().repeat(43) + "="
    private val me = "3q2+7wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val tmp = java.nio.file.Files.createTempDirectory("oshi-feata-shots").toFile()

    private fun lang(en: String, fr: String, ar: String) = when (Strings.activeTag.substringBefore('-')) {
        "fr" -> fr; "ar" -> ar; else -> en
    }

    /** A soft two-tone "portrait" so a picture is visibly a picture and not a monogram. */
    private fun portrait(hue: Float): String {
        val s = 256
        val img = java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(hue, 0.35f, 0.95f), s.toFloat(), s.toFloat(), java.awt.Color.getHSBColor(hue + 0.1f, 0.6f, 0.7f))
        g.fillRect(0, 0, s, s)
        g.color = java.awt.Color(255, 224, 196); g.fillOval(78, 50, 100, 112)
        g.color = java.awt.Color.getHSBColor(hue + 0.5f, 0.5f, 0.45f); g.fillOval(38, 160, 180, 160)
        g.dispose()
        val out = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(img, "jpg", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }
    private val camille by lazy { portrait(0.02f) }
    private val ines by lazy { portrait(0.55f) }
    private val mine by lazy { portrait(0.75f) }

    private fun pngFile(name: String, hue: Float): String {
        val f = java.io.File(tmp, name)
        val img = java.awt.image.BufferedImage(480, 360, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color.getHSBColor(hue, 0.5f, 0.9f), 480f, 360f, java.awt.Color.getHSBColor(hue + 0.2f, 0.7f, 0.5f))
        g.fillRect(0, 0, 480, 360); g.dispose()
        javax.imageio.ImageIO.write(img, "png", f)
        return f.absolutePath
    }

    private fun msg(id: String, fromMe: Boolean, who: String, body: String, stamp: String, status: String? = null,
                    reactors: List<com.oshi.desktop.ui.state.ReactorGroup> = emptyList(), attachment: String? = null,
                    viewOnce: Boolean = false, opened: Boolean = false, sender: String = "", quote: QuoteRow? = null) = MessageRow(
        id = id, fromMe = fromMe, who = who, body = body, attachment = attachment, stamp = stamp, status = status,
        failed = false, edited = false, deleted = false, reactions = reactors.joinToString("") { it.emoji },
        reactionKeys = reactors.map { it.emoji }, reactors = reactors, sentAtMs = 1_763_480_000_000L,
        viewOnce = viewOnce, viewOnceOpened = opened, senderAddress = sender, quote = quote,
    )

    private fun targets() = listOf(
        GroupCandidateRow(addr('a'), "Camille Laurent", camille),
        GroupCandidateRow(addr('b'), "Bob"),
        GroupCandidateRow(addr('c'), "Inès", ines),
        GroupCandidateRow(addr('d'), "Yusuf"),
        GroupCandidateRow(addr('e'), "Chloé"),
    )

    private fun reactors() = listOf(
        com.oshi.desktop.ui.state.ReactorGroup("👍", listOf(lang("You", "Vous", "أنت"), "Bob", "Inès")),
        com.oshi.desktop.ui.state.ReactorGroup("❤️", listOf("Camille Laurent")),
        com.oshi.desktop.ui.state.ReactorGroup("😂", listOf("Yusuf")),
    )

    private fun direct(messages: List<MessageRow>, media: List<com.oshi.desktop.ui.state.MediaItemRow> = emptyList(),
                       links: List<com.oshi.desktop.ui.state.LinkItemRow> = emptyList()) = ThreadView(
        conversationId = addr('a'), title = "Camille Laurent", address = addr('a'),
        kind = ConversationKind.DIRECT, groupAdmin = false, groupPictureBase64 = camille,
        groupMembers = emptyList(), groupCandidates = emptyList(),
        safetyNumber = "37021 88410", verified = true, reach = Reach.REACHABLE,
        reachLabel = lang("Secure path available", "Chemin sécurisé disponible", "مسار آمن متاح"), peerTyping = false,
        messages = messages, composer = ComposerState(true, null, true, null),
        forwardTargets = targets().drop(1), media = media, links = links,
    )

    private fun group(): ThreadView = ThreadView(
        conversationId = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", title = lang("Team Desk", "Équipe Accueil", "فريق الاستقبال"),
        address = "4C8579F8-9CD5-47E8-AFDD-77300D45171D", kind = ConversationKind.GROUP, groupAdmin = true, groupCanEditInfo = true,
        groupMembers = listOf(
            GroupMemberRow(me, "Hugo", isAdmin = true, isCreator = true, self = true),
            GroupMemberRow(addr('b'), "Bob", isAdmin = false, isCreator = false, self = false),
            GroupMemberRow(addr('c'), "Inès", isAdmin = true, isCreator = false, self = false),
        ),
        groupCandidates = emptyList(), safetyNumber = "", verified = false, reach = Reach.NOT_APPLICABLE, reachLabel = "", peerTyping = false,
        messages = listOf(
            msg("g1", false, "Bob", lang("Who has the keys to the van?", "Qui a les clés du camion ?", "من لديه مفاتيح الشاحنة؟"), "21:40", sender = addr('b')),
            msg("g2", false, "Inès", lang("I do. Leaving at 7.", "Moi. Je pars à 7 h.", "أنا. سأغادر الساعة 7."), "21:41", sender = addr('c'), reactors = reactors()),
            msg("g3", true, "me", lang("Perfect — I'll bring the boxes.", "Parfait — j'apporte les cartons.", "ممتاز — سأحضر الصناديق."), "21:47", "delivered"),
        ),
        composer = ComposerState(true, null, true, null), forwardTargets = targets(),
    )

    private fun state(selected: String? = addr('a')) = ShellState(
        selfAddress = me, selfAddressShort = "3q2+7w…", displayName = "Hugo",
        relayUrl = "https://oshi-messenger.com", gateOpen = true, gateAnswered = true,
        deliveryReceiptsEnabled = true, readReceiptsEnabled = true, syncNotice = null,
        destination = Destination.MESSAGES, contacts = emptyList(), scheduled = emptyList(),
        shareText = "", pane = Pane.CONVERSATION,
        conversations = listOf(
            ConversationRow(addr('a'), "Camille Laurent", ConversationKind.DIRECT, 4, 0, "17:58", lang("North gate, 18:00.", "Porte nord, 18 h.", "البوابة الشمالية، الساعة 18:00."), 0, pictureBase64 = camille),
            ConversationRow(addr('c'), "Inès", ConversationKind.DIRECT, 4, 0, "17:20", lang("See you tomorrow", "À demain", "أراك غدًا"), 2, pictureBase64 = ines),
            ConversationRow(addr('b'), "Bob", ConversationKind.DIRECT, 4, 0, "16:02", "👍", 0),
        ),
        selectedId = selected, thread = null, draft = "", busy = false, notice = null,
        selfPictureBase64 = mine, ownNickname = "Hugo",
    )

    @Test
    fun render() {
        assumeTrue(System.getenv("OSHI_RENDER_FEATA") == "1")
        val base = File(System.getenv("OSHI_FEATA_OUT") ?: "build/screens/feat-a")
        val langs = (System.getenv("OSHI_FEATA_LANGS") ?: "en,fr,ar").split(',').map { it.trim() }
        val themes = (System.getenv("OSHI_FEATA_THEMES") ?: "light,dark,oled").split(',').map { it.trim() }
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
                renderAll { name, w, h, content -> render(File(out, "$name.png"), w, h, 2f, content) }
            }
        } finally {
            Strings.activeTag = savedTag
            OshiTheme.appearance = Appearance.DEFAULT
            tmp.deleteRecursively()
        }
    }

    private fun renderAll(shot: (String, Int, Int, @Composable () -> Unit) -> Unit) {
        val chat = listOf(
            msg("1", false, "Camille", lang("Are you coming tonight?", "Tu viens ce soir ?", "هل ستأتي الليلة؟"), "17:40", reactors = reactors()),
            msg("2", true, "me", lang("Yes — details: https://oshi-messenger.com/meet", "Oui — détails : https://oshi-messenger.com/meet", "نعم — التفاصيل: https://oshi-messenger.com/meet"), "17:41", "read"),
        )
        val d = direct(chat)
        shot("01-forward-to-several", 980, 760) {
            Box(Modifier.fillMaxSize()) {
                thread(d)
                ForwardSheet(targets().drop(1), onSend = {}, onDismiss = {}, initiallySelected = listOf(addr('b'), addr('c')))
            }
        }
        shot("02-who-reacted", 980, 760) { thread(d, ThreadChromePreview(reactorsMessageId = "1")) }
        shot("03-reply-privately-menu", 980, 760) { thread(group(), ThreadChromePreview(contextMenuMessageId = "g2")) }
        shot("04-reply-privately-composer", 980, 620) {
            thread(direct(emptyList()).copy(title = "Inès", groupPictureBase64 = ines),
                replyingTo = QuoteRow("g2", "Inès", lang("I do. Leaving at 7.", "Moi. Je pars à 7 h.", "أنا. سأغادر الساعة 7.")))
        }
        val p1 = pngFile("p1.png", 0.1f); val p2 = pngFile("p2.png", 0.6f); val p3 = pngFile("p3.png", 0.85f)
        val media = listOf(
            com.oshi.desktop.ui.state.MediaItemRow("m1", p1, MediaType.IMAGE, "beach.png", "18/11", false),
            com.oshi.desktop.ui.state.MediaItemRow("m2", p2, MediaType.IMAGE, "map.png", "17/11", true),
            com.oshi.desktop.ui.state.MediaItemRow("m3", p3, MediaType.IMAGE, "poster.png", "12/11", false),
            com.oshi.desktop.ui.state.MediaItemRow("m4", "/nope/clip.mp4", MediaType.VIDEO, "clip.mp4", "10/11", false),
            com.oshi.desktop.ui.state.MediaItemRow("m5", "/nope/plan.pdf", MediaType.DOCUMENT, lang("plan-thursday.pdf", "plan-jeudi.pdf", "خطة-الخميس.pdf"), "09/11", true),
            com.oshi.desktop.ui.state.MediaItemRow("m6", "/nope/voice.m4a", MediaType.AUDIO, "voice-message.m4a", "08/11", false),
        )
        val links = listOf(
            com.oshi.desktop.ui.state.LinkItemRow("2#a", "https://oshi-messenger.com/meet", "oshi-messenger.com", "17:41", true),
            com.oshi.desktop.ui.state.LinkItemRow("x#b", "https://www.openstreetmap.org/#map=17/48.85/2.35", "openstreetmap.org", "15/11", false),
            com.oshi.desktop.ui.state.LinkItemRow("y#c", "www.example.org/agenda", "example.org", "02/11", false),
        )
        val withMedia = direct(chat, media, links)
        for (tab in com.oshi.desktop.ui.state.GalleryCategory.entries) {
            shot("05-gallery-${tab.name.lowercase()}", 980, 700) { thread(withMedia, ThreadChromePreview(galleryOpen = true, galleryTab = tab)) }
        }
        shot("05-gallery-empty-videos", 980, 700) { thread(direct(chat, media.filter { it.kind == MediaType.IMAGE }), ThreadChromePreview(galleryOpen = true, galleryTab = com.oshi.desktop.ui.state.GalleryCategory.VIDEOS)) }
        val once = listOf(
            msg("v1", true, "me", "beach.png", "18:01", "delivered", attachment = "[image] $p1", viewOnce = true),
            msg("v2", false, "Camille", "photo.jpg", "18:03", attachment = "[image] $p2", viewOnce = true),
            msg("v3", false, "Camille", "", "18:05", viewOnce = true, opened = true),
            msg("v4", false, "Camille", "voice.m4a", "18:06", attachment = "[audio] /nope/voice.m4a", viewOnce = true),
        )
        shot("06-view-once-bubbles", 980, 760) { thread(direct(once)) }
        shot("06b-view-once-sent", 980, 620) { thread(direct(once.take(1))) }
        shot("07-view-once-switch", 980, 760) { thread(d, ThreadChromePreview(panelOpen = true, viewOnceArmed = true)) }
        shot("08-voice-preview", 980, 760) { thread(d, ThreadChromePreview(voicePreview = true)) }
        shot("09-profile-photo-settings", 1180, 820) {
            com.oshi.desktop.ui.components.SettingsNavigation.section = com.oshi.desktop.ui.components.SettingsSection.PROFILE
            com.oshi.desktop.ui.components.SettingsPane(state(), com.oshi.desktop.ui.components.SettingsSlots(),
                com.oshi.desktop.ui.components.SettingsActions(onSetPhoto = {}, onRemovePhoto = {}))
        }
        shot("10-contact-photos-in-list", 1280, 820) { window(state(), d) }
        // __MERGE_AB__ the seams with Mac-parity B.
        // Locked while the forward sheet (names + photos) was open: the sheet must not float
        // over the lock screen (it is a popup, drawn above every sibling, unless hidden).
        com.oshi.desktop.store.AppLock.preview(com.oshi.desktop.store.AppLock.Config(saltB64 = "AA==", hashB64 = "AA=="), true)
        shot("11-locked-over-open-forward-sheet", 980, 760) {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(com.oshi.desktop.ui.components.LocalHiddenByLock provides true) {
                    thread(d)
                    ForwardSheet(targets().drop(1), onSend = {}, onDismiss = {}, initiallySelected = listOf(addr('b'), addr('c')))
                }
                com.oshi.desktop.ui.components.AppLockScreen({ false })
            }
        }
        com.oshi.desktop.store.AppLock.preview(com.oshi.desktop.store.AppLock.Config(), false)
        // Block Forwarding & Copying on: the message menu has no Forward / Copy, Reply privately stays.
        com.oshi.desktop.store.PrivacyPreferences.preview(com.oshi.desktop.store.PrivacyPreferences.Values(blockForwardCopy = true))
        shot("12-menu-with-forward-blocked", 980, 760) { thread(group(), ThreadChromePreview(contextMenuMessageId = "g2")) }
        com.oshi.desktop.store.PrivacyPreferences.preview(com.oshi.desktop.store.PrivacyPreferences.Values())
    }

    @Composable
    private fun window(s: ShellState, t: ThreadView) {
        Row(Modifier.fillMaxSize().background(OshiTheme.background)) {
            ConversationListPane(state = s, query = "", onQuery = {}, half = ConversationFilter.Half.MESSAGES, onHalf = {}, onSelect = {}, onShow = {},
                today = LocalDate.of(2026, 11, 19))
            Box(Modifier.width(Metrics.hairline).fillMaxHeight().background(OshiTheme.separator))
            Box(Modifier.weight(1f).fillMaxHeight()) { thread(t) }
        }
    }

    @Composable
    private fun thread(t: ThreadView, preview: ThreadChromePreview = ThreadChromePreview(), replyingTo: QuoteRow? = null) {
        CompositionLocalProvider(LocalThreadChromePreview provides preview) {
            ThreadPane(
                thread = t, notice = null, busy = false, wallpaper = WallpaperId.NONE, onPickWallpaper = {},
                draft = "", onDraft = {}, onSend = {}, onAttach = {}, onVoiceNote = {},
                onRenameGroup = {}, onReact = { _, _ -> }, onEditMessage = { _, _ -> }, onDeleteMessage = {},
                onAddGroupMember = {}, onRemoveGroupMember = {}, onSetGroupMemberAdmin = { _, _ -> },
                viewOnce = ViewOnceFacts.NONE, revealed = emptySet(), onReveal = {},
                today = LocalDate.of(2026, 11, 19), onCall = {}, replyingTo = replyingTo,
                onAttachViewOnce = {}, onReplyPrivately = {}, onForward = { _, _ -> },
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
            for (f in 1 until 20) { scene.render(f * 50_000_000L); Thread.sleep(30) }
            val image = scene.render(20 * 50_000_000L)
            target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }
}
