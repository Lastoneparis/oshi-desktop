package com.oshi.desktop.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.oshi.desktop.ui.state.ContactRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.dt
import com.oshi.desktop.i18n.t
import com.oshi.desktop.media.RecordingStart
import com.oshi.desktop.media.VoiceNote
import com.oshi.desktop.media.VoiceNoteResult
import com.oshi.desktop.media.VoiceNotes
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.DesktopLimits
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Notice
import com.oshi.desktop.ui.state.QuoteRow
import com.oshi.desktop.ui.state.Severity
import com.oshi.desktop.ui.state.ThreadView
import java.time.LocalDate

/**
 * The right-hand column when a conversation is open: header, wallpaper, log, notice, composer.
 *
 * ============================================================ THE HEADER HAS NO CALL BUTTON
 *
 * `ChatView.swift`'s retractable action bar has four buttons and two of them are `phone.fill`
 * and `video.fill`. Those are the parts of that screen this window deliberately does not
 * port. PARITY.md row 2.1: the REPL's opt-in lane now opens audio devices and carries sealed
 * loopback PCM, but no call has crossed two real machines or a phone and there is no TURN.
 * A greyed-out phone icon would still advertise a call feature the window cannot yet prove,
 * which is why there is no phone glyph in [Glyph] to draw one with.
 *
 * What IS in the header: the peer, the address, the measured reachability (never a presence
 * dot — this client has no presence protocol), the conversation kind when it is not a plain
 * ratcheted 1:1, and the wallpaper control.
 *
 * ============================================================ THE DRAFT IS NOT IN THE MODEL
 *
 * `ChatShellModel.draft()` publishes, and `publish()` runs `build()`, which does a directory
 * scan plus a summary rebuild per conversation. Routing every keystroke through it was
 * measurable work per character. The composer below keeps its text in Compose state and tells
 * the model only when it matters — see [Composer] and `DesktopWindow`'s DRAFTS section.
 */
@Composable
fun ThreadPane(
    thread: ThreadView,
    notice: Notice?,
    busy: Boolean,
    wallpaper: WallpaperId,
    onPickWallpaper: (WallpaperId) -> Unit,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoiceNote: (VoiceNote) -> Unit,
    onRenameGroup: (String) -> Unit,
    onGif: ((java.io.File) -> Unit)? = null,   // __GIF_PACK_2026_09_23__
    onReact: (String, String) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onAddGroupMember: (String) -> Unit,
    onRemoveGroupMember: (String) -> Unit,
    onSetGroupMemberAdmin: (String, Boolean) -> Unit,
    viewOnce: ViewOnceFacts,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
    /** Start a call (true = video). Null = no call buttons (groups, bots, radio threads, no call lane). */
    onCall: ((video: Boolean) -> Unit)? = null,
    /** __GROUP_E2E_V2_2026_09_23__ Leave the open group (confirmed in the header). */
    onLeaveGroup: () -> Unit = {},
    /** __GROUP_PARITY_2026_09_23__ reply to a message, and the draft's current reply target. */
    onReply: (String) -> Unit = {},
    replyingTo: QuoteRow? = null,
    onCancelReply: () -> Unit = {},
    /** __GROUP_PARITY_2026_09_23__ group picture and local mute. */
    onSetGroupPicture: () -> Unit = {},
    onRemoveGroupPicture: () -> Unit = {},
    onSetGroupMuted: (Boolean) -> Unit = {},
    /** __GROUP_PARITY_2026_09_23__ the rest of iOS's group sheet. */
    onSetGroupDescription: (String) -> Unit = {},
    onSetGroupBlocked: (Boolean) -> Unit = {},
    onDeleteGroup: () -> Unit = {},
    onPin: (String?) -> Unit = {},
    onDeleteForMe: (String) -> Unit = {},
    onForward: (messageId: String, toAddress: String) -> Unit = { _, _ -> },
    /** __DESKTOP_POLLS_2026_10_29__ cast or toggle a vote on a poll row. */
    onVote: (messageId: String, optionIndex: Int) -> Unit = { _, _ -> },
    /** __DESKTOP_POLL_COMPOSER_2026_10_29__ create a poll in this conversation. */
    onCreatePoll: ((String, List<String>, Boolean, Boolean) -> Unit)? = null,
    /** __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ send one contact INTO this thread. */
    onSendContact: ((cardAddress: String) -> Unit)? = null,
    /** Everyone this machine knows, for that picker. */
    contacts: List<ContactRow> = emptyList(),
    /** __DESKTOP_OFFLINE_ONLY_2026_10_29__ take this conversation off the relay, or put it back. */
    onSetOfflineOnly: ((Boolean) -> Unit)? = null,
    onCopy: (String) -> Unit = {},
    /** __MENTIONS_2026_09_23__ a member picked from the `@` picker (groups only). */
    onPickMention: (com.oshi.desktop.group.MentionWire.Mention) -> Unit = {},
    /** __DESKTOP_REPORT_2026_09_23__ null = no report action (bot / radio threads). */
    onReport: ((com.oshi.desktop.net.V2ReportClient.Reason, String, Boolean) -> Unit)? = null,
    /** __VIDEO_NOTE_2026_09_24__ send a recorded round note (sealed MP4 + its metadata). Null = no button. */
    onVideoNote: ((java.io.File, com.oshi.desktop.media.VideoNoteWire.Meta) -> Unit)? = null,
) {
    var pickerOpen by remember(thread.conversationId) { mutableStateOf(false) }
    var reportOpen by remember(thread.conversationId) { mutableStateOf(false) }
    // __DESKTOP_MEDIA_GALLERY_2026_10_29__ keyed by conversation so switching chats closes it.
    var galleryOpen by remember(thread.conversationId) { mutableStateOf(false) }
    // __DESKTOP_THREAD_SEARCH_2026_10_29__ also keyed by conversation: a query typed in one
    // chat has no meaning in the next, and carrying it over would silently hide messages.
    var searchOpen by remember(thread.conversationId) { mutableStateOf(false) }
    var searchQuery by remember(thread.conversationId) { mutableStateOf("") }
    var searchDay by remember(thread.conversationId) { mutableStateOf<java.time.LocalDate?>(null) }
    val reportable = onReport != null &&
        (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP)

    Box(modifier.fillMaxSize()) {
        WallpaperBackdrop(wallpaper)

        // The viewer wraps the WHOLE thread rather than sitting inside the message log, so
        // a full-size picture covers the header and the composer too. Anything narrower
        // would put a photograph in a box beside a text field, which is a thumbnail with
        // extra steps. It provides `LocalMediaOpener`; the bubbles read it.
        WithMediaViewer {
            Column(Modifier.fillMaxSize()) {
                ThreadHeader(
                    thread, wallpaper, pickerOpen, onRenameGroup, onAddGroupMember, onRemoveGroupMember, onSetGroupMemberAdmin,
                    onLeaveGroup = onLeaveGroup,
                    onSetGroupPicture = onSetGroupPicture,
                    onRemoveGroupPicture = onRemoveGroupPicture,
                    onSetGroupMuted = onSetGroupMuted,
                    onSetGroupDescription = onSetGroupDescription,
                    onSetGroupBlocked = onSetGroupBlocked,
                    onDeleteGroup = onDeleteGroup,
                    onCopy = onCopy,
                    // __DESKTOP_CALL_UI_2026_09_23__ 1:1 only — like iOS, which offers no call in a
                    // group, and never in a bot or radio thread: neither has a peer that can answer.
                    onCall = onCall?.takeIf { thread.kind == ConversationKind.DIRECT },
                    onReport = if (reportable) ({ reportOpen = !reportOpen }) else null,
                    // 1:1 only: `setOfflineOnly` is a 1:1 path, and a control that sent
                    // nothing would be worse than none.
                    onSetOfflineOnly = onSetOfflineOnly?.takeIf { thread.kind == ConversationKind.DIRECT },
                    // Only when there is something to show: a control that opens an empty
                    // grid teaches the user it is broken.
                    onOpenGallery = if (thread.media.isEmpty()) null else ({ galleryOpen = !galleryOpen }),
                    onOpenSearch = if (thread.messages.isEmpty()) null else ({
                        searchOpen = !searchOpen
                        if (!searchOpen) { searchQuery = ""; searchDay = null }
                    }),
                ) { pickerOpen = !pickerOpen }
                Hairline()
                if (reportOpen && onReport != null) {
                    ReportPanel(
                        isGroup = thread.kind == ConversationKind.GROUP,
                        alreadyReported = false,
                        onSubmit = { r, d, b -> onReport(r, d, b) },
                        onClose = { reportOpen = false },
                    )
                    Hairline()
                }
                thread.pinned?.let { PinnedBar(it, canUnpin = thread.pinCanToggle) { onPin(null) } }
                // __DESKTOP_OFFLINE_ONLY_2026_10_29__ A restricted conversation says so, and
                // says how many others have confirmed. A number below the total is the HONEST
                // state — nothing can make another client refuse the internet — so it is shown
                // rather than rounded up to "everyone".
                if (thread.offlineOnly) OfflineOnlyBar(thread.offlineOnlyConfirmed)
                thread.botSealing?.let { BotSealLabel(it) }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // __DESKTOP_MEDIA_GALLERY_2026_10_29__ The grid REPLACES the log rather
                    // than floating over it: it is a different way of looking at the same
                    // conversation, not a dialog about one message, and a half-covered thread
                    // behind a grid is two things to read at once.
                    if (galleryOpen) {
                        MediaGalleryPane(thread.media) { galleryOpen = false }
                    } else if (searchOpen) {
                        // The matches REPLACE the log while searching, for the same reason the
                        // gallery does: a filtered view and the full thread behind it are two
                        // conversations to read at once.
                        val matches = remember(thread.messages, searchQuery, searchDay) {
                            com.oshi.desktop.ui.state.ThreadSearch.apply(thread.messages, searchQuery, searchDay)
                        }
                        val days = remember(thread.messages) {
                            com.oshi.desktop.ui.state.ThreadSearch.daysWithMessages(thread.messages)
                        }
                        ThreadSearchPane(
                            matches = matches,
                            total = thread.messages.size,
                            query = searchQuery,
                            onQuery = { searchQuery = it },
                            days = days,
                            day = searchDay,
                            onDay = { searchDay = it },
                            thread = thread,
                            wallpaper = wallpaper,
                            today = today,
                            onClose = { searchOpen = false; searchQuery = ""; searchDay = null },
                        )
                    } else if (thread.messages.isEmpty()) {
                        EmptyThread(thread.kind)
                    } else {
                        MessageLog(thread, wallpaper, viewOnce, revealed, onReveal, onReact, onEditMessage, onDeleteMessage, today, onReply, onPin, onDeleteForMe, onForward, onVote)
                    }
                    if (pickerOpen) {
                        Box(Modifier.fillMaxSize().padding(OshiTheme.lg), contentAlignment = Alignment.TopEnd) {
                            WallpaperPicker(
                                current = wallpaper,
                                onPick = { onPickWallpaper(it) },
                                onClose = { pickerOpen = false },
                            )
                        }
                    }
                }

                notice?.let { NoticeBar(it) }
                Hairline()
                if (thread.groupBlocked) BlockedGroupBanner { onSetGroupBlocked(false) }
                if (replyingTo != null && thread.composer.enabled) ReplyBanner(replyingTo, onCancelReply)
                Composer(thread.conversationId, thread.composer, busy, draft, onDraft, onSend, onAttach, onVoiceNote,
                    // Neither phone offers the GIF picker in a group chat (iOS ChatView only; Android the same).
                    onGif.takeIf { thread.kind != com.oshi.desktop.ui.state.ConversationKind.GROUP },
                    // __MENTIONS_2026_09_23__ `@` picker: group members other than ourselves.
                    mentionMembers = if (thread.kind == com.oshi.desktop.ui.state.ConversationKind.GROUP)
                        thread.groupMembers.filter { !it.self }.map { com.oshi.desktop.group.MentionWire.Mention(it.address, it.label.trim()) }
                    else emptyList(),
                    onPickMention = onPickMention,
                    onVideoNote = onVideoNote,
                    // __DESKTOP_POLL_COMPOSER_2026_10_29__ 1:1 only, because `votePoll` and
                    // `sendPoll` are 1:1 paths — a poll offered in a group would send
                    // nothing. Named here rather than hidden in the Composer so the reason
                    // sits with the kind check beside it.
                    onCreatePoll = onCreatePoll?.takeIf { thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT },
                    // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Direct only, and for a
                    // harder reason than the poll's: `sendContactCard` addresses ONE peer and
                    // has no group fan-out at all, so offering it in a group would be a
                    // control that cannot work rather than one that works badly.
                    onSendContact = onSendContact?.takeIf { thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT },
                    // The card's subject is never the person you are sending it to.
                    contacts = contacts.filterNot { it.address == thread.conversationId })
            }
        }
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun ThreadHeader(
    thread: ThreadView,
    wallpaper: WallpaperId,
    pickerOpen: Boolean,
    onRenameGroup: (String) -> Unit,
    onAddGroupMember: (String) -> Unit,
    onRemoveGroupMember: (String) -> Unit,
    onSetGroupMemberAdmin: (String, Boolean) -> Unit,
    onCall: ((video: Boolean) -> Unit)? = null,
    onLeaveGroup: () -> Unit = {},
    onSetGroupPicture: () -> Unit = {},
    onRemoveGroupPicture: () -> Unit = {},
    onSetGroupMuted: (Boolean) -> Unit = {},
    onSetGroupDescription: (String) -> Unit = {},
    onSetGroupBlocked: (Boolean) -> Unit = {},
    onDeleteGroup: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    onReport: (() -> Unit)? = null,
    onSetOfflineOnly: ((Boolean) -> Unit)? = null,
    /** __DESKTOP_MEDIA_GALLERY_2026_10_29__ null when the conversation has no attachments. */
    onOpenGallery: (() -> Unit)? = null,
    /** __DESKTOP_THREAD_SEARCH_2026_10_29__ null when there is nothing to search. */
    onOpenSearch: (() -> Unit)? = null,
    onWallpaper: () -> Unit,
) {
    var editingDescription by remember(thread.conversationId) { mutableStateOf(false) }
    var typedDescription by remember(thread.conversationId, thread.groupDescription) { mutableStateOf(thread.groupDescription.orEmpty()) }
    var showInvite by remember(thread.conversationId) { mutableStateOf(false) }
    var confirmDelete by remember(thread.conversationId) { mutableStateOf(false) }
    var confirmLeave by remember(thread.conversationId) { mutableStateOf(false) }
    var editingName by remember(thread.conversationId) { mutableStateOf(false) }
    var typedName by remember(thread.conversationId) { mutableStateOf(thread.title) }
    var showRoster by remember(thread.conversationId) { mutableStateOf(false) }
    var showCandidates by remember(thread.conversationId) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(OshiTheme.background)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        GroupAvatar(thread.groupPictureBase64, thread.title, thread.address, Metrics.avatarHeader)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                if (editingName) {
                    BasicTextField(
                        value = typedName,
                        onValueChange = { typedName = it },
                        singleLine = true,
                        textStyle = OshiTheme.typography.titleMedium.copy(color = Ink.strong),
                        cursorBrush = SolidColor(OshiTheme.brand),
                        modifier = Modifier.weight(1f),
                    )
                    TextAction(dt("desktop.group.rename.save")) { onRenameGroup(typedName); editingName = false }
                    TextAction(t("common.cancel")) { typedName = thread.title; editingName = false }
                } else {
                    Text(
                        thread.title,
                        style = OshiTheme.typography.titleMedium,
                        color = Ink.strong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (thread.groupAdmin) TextAction(t("mail.drive.rename")) { editingName = true }
                }
                KindBadge(thread.kind)
                // __DESKTOP_REPORT_2026_09_23__ "Signaler", like the phones' contact/group sheet.
                // __DESKTOP_OFFLINE_ONLY_2026_10_29__ The switch, and the honest count under
                // it. `offline_only.menu.{on,off}` and `.summary.{on,off}` already exist in
                // all 30-odd catalogs because they are extracted from the iOS tree, so this
                // invents no string.
                // __DESKTOP_MEDIA_GALLERY_2026_10_29__ Only when there is something to show:
                // a control that opens an empty grid teaches the user it is broken.
                onOpenGallery?.let { open -> TextAction(t("media_gallery.title")) { open() } }
                onOpenSearch?.let { open -> TextAction(t("chat.search")) { open() } }
                onSetOfflineOnly?.let { set ->
                    // One literal per `t(...)` — see the note at the same change in OshiClient.
                    TextAction(if (thread.offlineOnly) t("offline_only.menu.off") else t("offline_only.menu.on")) {
                        set(!thread.offlineOnly)
                    }
                }
                onReport?.let { open ->
                    TextAction(if (thread.kind == ConversationKind.GROUP) t("group.report_group") else t("report.contact_title")) { open() }
                }
            }
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(
                thread.address,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Ink.soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (thread.reachLabel.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(thread.reachLabel, fontSize = 11.sp, color = Ink.soft)
            }
            if (thread.peerTyping) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(t("typing.indicator", thread.title), fontSize = 11.sp, color = OshiTheme.brand)
            }
            if (thread.typingNames.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(t("typing.indicator", thread.typingNames.joinToString(", ")), fontSize = 11.sp, color = OshiTheme.brand)
            }
            if (thread.kind == ConversationKind.GROUP) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("group_member_count", thread.groupMembers.size), fontSize = 11.sp, color = Ink.soft)
                    if (thread.groupAdmin) Text(t("group_admin"), fontSize = 11.sp, color = OshiTheme.success)
                    TextAction(if (showRoster) dt("desktop.group.members.hide") else dt("desktop.group.members.show")) { showRoster = !showRoster }
                    // __GROUP_E2E_V2_2026_09_23__ leave, with the phones' confirmation wording.
                    if (!confirmLeave) TextAction(t("groups.leave")) { confirmLeave = true }
                }
                // __GROUP_PARITY_2026_09_23__ picture (iOS GroupInfoSheet) and local mute.
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (thread.groupCanEditInfo) {
                        TextAction(t("group.change_picture")) { onSetGroupPicture() }
                        if (thread.groupPictureBase64 != null) TextAction(t("group.remove_picture")) { onRemoveGroupPicture() }
                    }
                    TextAction(if (thread.groupMuted) t("chat.unmute") else t("chat.mute")) { onSetGroupMuted(!thread.groupMuted) }
                    TextAction(if (thread.groupBlocked) t("group.unblock") else t("group.block")) { onSetGroupBlocked(!thread.groupBlocked) }
                    if (thread.groupInviteLink != null) TextAction(t("group.invite_link")) { showInvite = !showInvite }
                    // iOS: an admin's destructive action is "Delete Group" (this device only); a member's is Leave.
                    if (thread.groupAdmin && !confirmDelete) TextAction(t("button_delete_group")) { confirmDelete = true }
                }
                if (confirmDelete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("confirm_delete_group"), fontSize = 11.sp, color = Ink.soft)
                        TextAction(t("button_delete_group")) { confirmDelete = false; onDeleteGroup() }
                        TextAction(t("common.cancel")) { confirmDelete = false }
                    }
                }
                // __GROUP_PARITY_2026_09_23__ description (iOS GroupInfoSheet), editable with the name's permission.
                if (editingDescription) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        BasicTextField(
                            value = typedDescription,
                            onValueChange = { typedDescription = it.take(500) },
                            textStyle = OshiTheme.typography.bodySmall.copy(color = Ink.strong),
                            cursorBrush = SolidColor(OshiTheme.brand),
                            modifier = Modifier.weight(1f),
                        )
                        TextAction(dt("desktop.group.rename.save")) { onSetGroupDescription(typedDescription); editingDescription = false }
                        TextAction(t("common.cancel")) { editingDescription = false }
                    }
                } else if (!thread.groupDescription.isNullOrBlank() || thread.groupCanEditInfo) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        Text(
                            thread.groupDescription?.takeIf { it.isNotBlank() } ?: t("group.description"),
                            style = OshiTheme.typography.bodySmall,
                            color = if (thread.groupDescription.isNullOrBlank()) Ink.soft else Ink.strong,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (thread.groupCanEditInfo) TextAction(t("mail.drive.rename")) { editingDescription = true }
                    }
                }
                if (showInvite && thread.groupInviteLink != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                        QrCode(thread.groupInviteLink, size = 120.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t("group_invite_scan_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            Text(thread.groupInviteLink, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Ink.strong, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextAction(t("common.copy")) { onCopy(thread.groupInviteLink) }
                        }
                    }
                }
                if (confirmLeave) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(t("confirm_leave_group"), fontSize = 11.sp, color = Ink.soft)
                        TextAction(t("groups.leave")) { confirmLeave = false; onLeaveGroup() }
                        TextAction(t("common.cancel")) { confirmLeave = false }
                    }
                }
                if (showRoster) {
                    for (member in thread.groupMembers) {
                        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append(member.label)
                                    if (member.self) append(" · you")
                                    if (member.isAdmin) append(" · admin")
                                },
                                fontSize = 11.sp,
                                color = Ink.soft,
                            )
                            if (thread.groupAdmin && !member.self) {
                                TextAction("Remove") { onRemoveGroupMember(member.address) }
                                if (member.isAdmin && !member.isCreator) TextAction("Demote") { onSetGroupMemberAdmin(member.address, false) }
                                if (!member.isAdmin) TextAction("Promote") { onSetGroupMemberAdmin(member.address, true) }
                            }
                        }
                    }
                    if (thread.groupAdmin && thread.groupCandidates.isNotEmpty()) {
                        TextAction(if (showCandidates) "Hide contacts" else "Add contact") { showCandidates = !showCandidates }
                        if (showCandidates) for (candidate in thread.groupCandidates) {
                            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                                Text(candidate.label, fontSize = 11.sp, color = Ink.soft)
                                TextAction("Add") { onAddGroupMember(candidate.address) }
                            }
                        }
                    }
                }
            }
        }
        if (onCall != null) {
            // Video first, voice second — the order of the phones' chat header.
            for ((video, glyph, label) in listOf(
                Triple(true, CallGlyph.CAMERA, t("chat.video_call")),
                Triple(false, CallGlyph.PHONE, t("call.contact.default")),
            )) {
                val (source, hovered) = rememberRowInteraction()
                Box(
                    Modifier
                        .size(Metrics.actionButton)
                        .clip(CircleShape)
                        .background(if (hovered.value) OshiTheme.surface else Color.Transparent)
                        .focusRing(CircleShape)
                        .clickable(interactionSource = source, indication = null, onClickLabel = label) { onCall(video) },
                    contentAlignment = Alignment.Center,
                ) { CallGlyphIcon(glyph, OshiTheme.brand, 20.dp) }
            }
        }
        HeaderButton(Glyph.PALETTE, t("wallpaper.title"), pickerOpen || wallpaper != WallpaperId.NONE, onWallpaper)
    }
}

@Composable
private fun HeaderButton(glyph: Glyph, label: String, active: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier
            .size(Metrics.actionButton)
            .clip(CircleShape)
            .background(
                when {
                    active -> OshiTheme.brand.copy(alpha = 0.12f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                }
            )
            .focusRing(CircleShape)
            .clickable(interactionSource = source, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (active) OshiTheme.brand else Ink.soft, 18.dp)
    }
}

// ---------------------------------------------------------------------------- the log

@Composable
private fun MessageLog(
    thread: ThreadView,
    wallpaper: WallpaperId,
    viewOnce: ViewOnceFacts,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    onReact: (String, String) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    today: LocalDate,
    onReply: (String) -> Unit = {},
    onPin: (String?) -> Unit = {},
    onDeleteForMe: (String) -> Unit = {},
    onForward: (String, String) -> Unit = { _, _ -> },
    onVote: (messageId: String, optionIndex: Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val targets = thread.forwardTargets.map { it.address to it.label }

    // __GROUP_OPEN_ANCHOR_2026_09_23__ Land on — and stay on — the newest message.
    // The old effect animated to `lastIndex` on EVERY new message (yanking a reader
    // who had scrolled up into history), `scrollToItem` top-aligned a last row
    // taller than the pane, and nothing followed rows that grew after layout.
    // `followNewest` pins while the reader is at the end and stops when they
    // scroll away; a conversation switch or my own send re-arms it.
    val followNewest = rememberFollowNewest(listState, key = thread.conversationId)
    LaunchedEffect(thread.conversationId, thread.messages.size) {
        if (thread.messages.lastOrNull()?.fromMe == true) followNewest.value = true
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = OshiTheme.xl),
        state = listState,
        contentPadding = PaddingValues(vertical = OshiTheme.lg),
    ) {
        itemsIndexedByKey(thread.messages) { index, row ->
            val previous = thread.messages.getOrNull(index - 1)
            val next = thread.messages.getOrNull(index + 1)
            val separator = DayBreaks.separatorAbove(previous?.stamp, row.stamp, today)
            if (separator != null) DaySeparator(separator)

            val grouped = separator == null && DayBreaks.isGrouped(
                previous?.let { DayBreaks.RowKey(it.fromMe, it.stamp) },
                DayBreaks.RowKey(row.fromMe, row.stamp),
            )
            val lastInRun = next == null || !DayBreaks.isGrouped(
                DayBreaks.RowKey(row.fromMe, row.stamp),
                DayBreaks.RowKey(next.fromMe, next.stamp),
            )

            MessageBubbleRow(
                row = row,
                grouped = grouped,
                lastInRun = lastInRun,
                wallpaper = wallpaper,
                viewOnce = viewOnce.isViewOnce(row.id),
                revealed = row.id in revealed,
                onReveal = { onReveal(row.id) },
                // __GROUP_E2E_V2_2026_09_23__ groups too (GROUP_E2E_V2_SPEC §2.3-2.4): edit own, delete own or as admin.
                onReact = if (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP) { emoji -> onReact(row.id, emoji) } else null,
                onEdit = if ((thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP) && row.fromMe) { body -> onEditMessage(row.id, body) } else null,
                onDelete = if ((thread.kind == ConversationKind.DIRECT && row.fromMe) ||
                    (thread.kind == ConversationKind.GROUP && (row.fromMe || thread.groupAdmin))) { { onDeleteMessage(row.id) } } else null,
                // __GROUP_PARITY_2026_09_23__ reply in 1:1 and groups; sender names in groups.
                onReply = if (thread.composer.enabled && (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP)) { { onReply(row.id) } } else null,
                // __DESKTOP_DIRECT_PIN_2026_10_29__ 1:1 too. `pinCanToggle` already carries the
                // group permission rule, so this no longer names a kind.
                onPin = if (thread.pinCanToggle && !row.deleted) { pin -> onPin(if (pin) row.id else null) } else null,
                // __DESKTOP_POLLS_2026_10_29__ votable only where a ballot can actually be
                // sent: the composer being open is the same condition `onReply` uses.
                onVote = if (row.poll != null && thread.composer.enabled) { i -> onVote(row.id, i) } else null,
                pinned = thread.pinned?.messageId.equals(row.id, ignoreCase = true),
                onDeleteForMe = if (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP) { { onDeleteForMe(row.id) } } else null,
                forwardTargets = targets,
                onForward = if (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP) { to -> onForward(row.id, to) } else null,
                showSender = thread.kind == ConversationKind.GROUP &&
                    (separator != null || previous == null || previous.fromMe || previous.who != row.who),
            )
        }
    }
}

/** `items(list, key)` with the index, which the stock overload does not hand back. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedByKey(
    rows: List<MessageRow>,
    content: @Composable (Int, MessageRow) -> Unit,
) = items(rows.size, key = { rows[it].id }) { content(it, rows[it]) }

/** `ChatView.swift`: caption semibold secondary in a `systemGray6` capsule, 12/8 padding. */
@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = OshiTheme.md), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.soft,
            modifier = Modifier
                .clip(OshiTheme.pill)
                .background(OshiTheme.surfaceElevated)
                .border(Metrics.hairline, OshiTheme.separator, OshiTheme.pill)
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        )
    }
}

/** `chatEmptyState`, copy included — it is the shipped app's own sentence. */
@Composable
private fun EmptyThread(kind: ConversationKind) {
    Column(
        Modifier.fillMaxSize().padding(OshiTheme.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandDisc(Glyph.LOCK, Metrics.avatarLarge)
        Spacer(Modifier.height(OshiTheme.lg))
        Text(
            // `chat.empty.title` and `chat.empty.subtitle` below are the SHIPPED app's own
            // empty-chat copy, word for word, in all 34 locales — this screen is the one place
            // the desktop and the phone are saying literally the same thing.
            if (kind == ConversationKind.DIRECT) t("chat.empty.title")
            else t("messages.empty"),
            style = OshiTheme.typography.headlineMedium,
            color = Ink.strong,
        )
        Spacer(Modifier.height(OshiTheme.sm))
        Text(
            when (kind) {
                // The shipped subtitle, unchanged: it is true of a V2 1:1 conversation here.
                ConversationKind.DIRECT -> t("chat.empty.subtitle")
                // The other three name PARITY.md rows and a wiring state. No phone has ever
                // said any of this, so there is no key upstream to reuse.
                ConversationKind.GROUP -> dt("desktop.thread.empty.group")
                ConversationKind.BOT -> dt("desktop.thread.empty.bot")
                ConversationKind.LORA -> dt("desktop.thread.empty.lora")
            },
            style = OshiTheme.typography.bodyLarge,
            color = Ink.soft,
            modifier = Modifier.widthIn(max = Metrics.bubbleMax),
        )
    }
}

// ---------------------------------------------------------------------------- notice

/**
 * __BOT_E2E_2026_09_23__ The honest label BOT_SEAL_SPEC.md §3 requires in a bot thread: who can
 * read these posts. Server-sealed is NOT end-to-end — the bot's operator writes them.
 */
@Composable
private fun BotSealLabel(sealing: com.oshi.desktop.bot.BotEnvelope.Sealing) {
    Text(
        when (sealing) {
            com.oshi.desktop.bot.BotEnvelope.Sealing.SERVER -> t("bot.seal.server.full")
            com.oshi.desktop.bot.BotEnvelope.Sealing.BOT -> t("bot.seal.bot.full")
            com.oshi.desktop.bot.BotEnvelope.Sealing.NONE -> t("bot.seal.legacy.full")
        },
        fontSize = 11.sp,
        color = Ink.soft,
        modifier = Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
    )
}

@Composable
private fun NoticeBar(notice: Notice) {
    val tint = when (notice.severity) {
        Severity.OK -> OshiTheme.success
        Severity.INFO -> OshiTheme.brand
        Severity.ERROR -> OshiTheme.danger
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(
            if (notice.severity == Severity.OK) Glyph.CHECK else Glyph.ALERT,
            tint,
            15.dp,
        )
        // The outcome text is the model's, word for word. "The bubble appeared" is what a
        // user reads as success and it is not evidence of anything — this line is where the
        // difference lives, so it is never paraphrased here.
        Text(notice.text, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
    }
}

// ---------------------------------------------------------------------------- composer

/**
 * Multiline, Enter sends, Shift+Enter is a newline, and a paperclip that actually attaches.
 *
 * ============================================================ THE PAPERCLIP IS REAL NOW
 *
 * It used to be a permanently disabled control that opened an explanation, because sending
 * media was something the CLIENT could do and this WINDOW had not been taught — the second
 * of `DesktopLimits`' two buckets, a schedule rather than a limit. It has been taught. The
 * button opens the platform file chooser and hands what comes back to
 * [com.oshi.desktop.ui.state.ChatShellModel.attach], which calls `OshiClient.sendFile` — the
 * same path `/sendfile` takes, the one PARITY.md rows 0.15 and 0.12 record crossing to a
 * shipped Android handset over the live relay.
 *
 * ============================================================ AND IN A GROUP
 *
 * There it attaches too, through `OshiClient.sendGroupFile` (GROUP_E2E_V2_SPEC §4): sealed
 * ONCE, one blob reservation per member because the blob store authorises exactly one
 * recipient, each member's envelope carrying the `v2file` key JSON plus `groupMessage`.
 * This block used to say the opposite — *"there is no group media fan-out anywhere in this
 * client, so a blob uploaded here would be fetched by nobody"* — and it was describing a gap
 * the client had already closed.
 *
 * The two gates are still separate fields on [ComposerState], and the second one still earns
 * its keep: a BLOCKED group closes the composer behind iOS's own banner. One boolean would
 * have forced a choice between a group with no composer and a group with a paperclip that lies.
 */
@Composable
private fun Composer(
    conversationId: String,
    composer: ComposerState,
    busy: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoiceNote: (VoiceNote) -> Unit,
    onGif: ((java.io.File) -> Unit)? = null,
    mentionMembers: List<com.oshi.desktop.group.MentionWire.Mention> = emptyList(),
    onPickMention: (com.oshi.desktop.group.MentionWire.Mention) -> Unit = {},
    onVideoNote: ((java.io.File, com.oshi.desktop.media.VideoNoteWire.Meta) -> Unit)? = null,
    /** __DESKTOP_POLL_COMPOSER_2026_10_29__ create a poll; null hides the control entirely. */
    onCreatePoll: ((question: String, options: List<String>, multi: Boolean, anon: Boolean) -> Unit)? = null,
    /**
     * __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ send one contact INTO this conversation.
     * Null hides the control: a card addresses one peer, so it is not offered in a group,
     * for the same reason the poll control is not offered where a ballot cannot come back.
     */
    onSendContact: ((cardAddress: String) -> Unit)? = null,
    contacts: List<ContactRow> = emptyList(),
) {
    if (!composer.enabled && !busy) {
        DisabledComposer(composer)
        return
    }

    var whyOpen by remember { mutableStateOf(false) }
    // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Reset per conversation: a picker left
    // open while the user switches threads would send into the wrong one.
    var contactOpen by remember(conversationId) { mutableStateOf(false) }
    val voice = remember { VoiceNotes() }
    var recording by remember(conversationId) { mutableStateOf(false) }
    var voiceProblem by remember(conversationId) { mutableStateOf<String?>(null) }
    DisposableEffect(conversationId) {
        onDispose { if (voice.isRecording) voice.cancelRecording() }
    }
    val canSend = draft.isNotBlank() && !busy
    // __VIDEO_NOTE_2026_09_24__ typing widens the field and moves the capture buttons aside
    // (ComposerChrome — the rule is plain Kotlin so a test holds it); clearing brings them back.
    val chrome = com.oshi.desktop.ui.state.ComposerChrome.of(draft, voiceRecording = recording)
    var videoOpen by remember(conversationId) { mutableStateOf(false) }
    val videoUnavailable by androidx.compose.runtime.produceState<String?>(VideoNoteAvailability.cachedOrNull()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VideoNoteAvailability.reason() }
    }
    var gifOpen by remember(conversationId) { mutableStateOf(false) }   // __GIF_PACK_2026_09_23__
    var pollOpen by remember(conversationId) { mutableStateOf(false) }  // __DESKTOP_POLL_COMPOSER_2026_10_29__
    // __MENTIONS_2026_09_23__ the typed `@query` (cursor = end of draft) and the members picked.
    var picked by remember(conversationId) { mutableStateOf(listOf<com.oshi.desktop.group.MentionWire.Mention>()) }
    LaunchedEffect(draft.isEmpty()) { if (draft.isEmpty()) picked = emptyList() }
    val query = if (mentionMembers.isEmpty()) null else com.oshi.desktop.group.MentionWire.activeQuery(draft, draft.length)
    val candidates = query?.let { mentionCandidates(mentionMembers, it.second) }.orEmpty()
    val pick: (com.oshi.desktop.group.MentionWire.Mention) -> Unit = { m ->
        val at = query?.first
        if (at != null) {
            picked = picked + m
            onPickMention(m)
            onDraft(insertMention(draft, at, m))
        }
    }

    Column(Modifier.fillMaxWidth().background(OshiTheme.background)) {
        if (gifOpen && onGif != null) {
            GifPicker(
                onPick = { bytes, name -> gifOpen = false; onGif(GifOutbox.write(bytes, name)) },
                onClose = { gifOpen = false },
            )
        }
        if (pollOpen && onCreatePoll != null) {
            PollComposer(
                onSend = { q, opts, multi, anon -> pollOpen = false; onCreatePoll(q, opts, multi, anon) },
                onClose = { pollOpen = false },
            )
        }
        // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Who to send. Blocked contacts are
        // left out: enforcement is a real rule here (row 0.21), and handing someone's card
        // around is exactly the thing a block is for.
        if (contactOpen && onSendContact != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.md)) {
                Text(
                    "Send a contact card — the person receives their key, so they can start " +
                        "a conversation with them.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                for (c in contacts.filterNot { it.blocked }) {
                    TextAction(c.label) { contactOpen = false; onSendContact(c.address) }
                }
                TextAction("Cancel") { contactOpen = false }
            }
        }
        // __DESKTOP_SLASH_MENU_2026_10_29__ The `/` menu, in the same slot as the mention
        // picker and never beside it: both are completions of what is being typed, and two
        // lists stacked over one composer is a menu about a menu. The mention picker wins
        // because an `@` is already an answer to a narrower question.
        val slash = remember(draft) { com.oshi.desktop.msg.SlashCommands.matches(draft) }
        if (candidates.isEmpty() && slash.isNotEmpty() && !busy) {
            SlashMenu(slash) { onDraft(com.oshi.desktop.msg.SlashCommands.completion(it)) }
        }
        if (candidates.isNotEmpty() && !busy) MentionPicker(candidates, pick)
        if (voiceProblem != null) {
            Text(voiceProblem!!, fontSize = 11.sp, color = OshiTheme.warning, modifier = Modifier.padding(horizontal = OshiTheme.lg, vertical = OshiTheme.xs))
        }
        if (whyOpen && composer.attachDisabledReason != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(OshiTheme.surface)
                    .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
            ) {
                Text(
                    dt("desktop.composer.attach.disabledInGroup.title"),
                    style = OshiTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.strong,
                )
                Spacer(Modifier.height(OshiTheme.xs))
                Text(composer.attachDisabledReason, fontSize = 11.sp, color = Ink.soft)
            }
        }

        if (videoOpen && onVideoNote != null) {
            VideoNoteRecorderPanel(
                onSend = { file, meta -> onVideoNote(file, meta) },
                onClose = { videoOpen = false },
            )
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(OshiTheme.md),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            AttachButton(
                enabled = composer.attachEnabled && !busy,
                open = whyOpen,
                onClick = {
                    if (composer.attachEnabled) onAttach() else whyOpen = !whyOpen
                },
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = chrome.captureButtonsVisible,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(220), expandFrom = Alignment.Start),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                    androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(220), shrinkTowards = Alignment.Start),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            if (onGif != null && composer.attachEnabled) {
                TextAction(dt("desktop.gif.button")) { if (!busy) gifOpen = !gifOpen }
            }

            // __DESKTOP_POLL_COMPOSER_2026_10_29__ Beside the GIF button and gated the same
            // way. Not shown at all when `onCreatePoll` is null — a poll can only be sent
            // where a ballot can come back, so offering it in a thread that cannot carry one
            // would be the greyed-out phone icon `DesktopLimits` argues against.
            if (onCreatePoll != null && composer.attachEnabled) {
                TextAction("Poll") { if (!busy) { gifOpen = false; pollOpen = !pollOpen } }
            }

            // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ The window could DISPLAY a
            // received contact card and had no way to send one; `sendContactCard` was
            // console-only. Hidden where there is nobody to send — a control that opens an
            // empty list is the greyed-out phone icon `DesktopLimits` argues against.
            if (onSendContact != null && composer.attachEnabled && contacts.any { !it.blocked }) {
                TextAction("Contact") { if (!busy) { gifOpen = false; pollOpen = false; contactOpen = !contactOpen } }
            }

            if (onVideoNote != null && composer.attachEnabled && !recording) {
                VideoNoteButton(videoUnavailable) { if (!busy) { gifOpen = false; videoOpen = true } }
            }

            TextAction(if (recording) "Stop" else "Record") {
                if (recording) {
                    recording = false
                    when (val result = voice.stopRecording()) {
                        is VoiceNoteResult.Ready -> {
                            voiceProblem = result.note.warning
                            onVoiceNote(result.note)
                        }
                        is VoiceNoteResult.Failure -> voiceProblem = result.reason.message
                    }
                } else {
                    when (val result = voice.startRecording()) {
                        RecordingStart.Started -> { recording = true; voiceProblem = null }
                        is RecordingStart.Failure -> voiceProblem = result.reason.message
                    }
                }
            }
            }
            }

            DraftField(
                draft = draft,
                busy = busy,
                onDraft = onDraft,
                // With the `@` picker open, Enter picks the first member instead of sending.
                onEnter = { if (candidates.isNotEmpty()) pick(candidates.first()) else if (canSend) onSend() },
                modifier = Modifier.weight(1f),
                expanded = chrome.fieldExpanded,
                highlight = if (picked.isEmpty()) null else MentionHighlight(picked, OshiTheme.brand),
            )

            SendButton(canSend, busy, onSend)
        }
    }
}

/**
 * The paperclip.
 *
 * When it can attach it is drawn at full strength and opens a file chooser. When it cannot,
 * it stays reachable by keyboard and answers with the reason instead of swallowing the
 * click — a disabled control that does nothing at all is read as broken, and the one thing
 * a user needs at that moment is the sentence explaining why.
 */
@Composable
private fun AttachButton(enabled: Boolean, open: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier
            .size(Metrics.actionButton)
            .clip(CircleShape)
            .background(if (open || hovered.value) OshiTheme.surface else Color.Transparent)
            .focusRing(CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = if (enabled) t("input.attach_file") else dt("desktop.composer.attach.why"),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.PLUS, if (enabled) Ink.strong else Ink.soft, 18.dp)
    }
}

@Composable
private fun DraftField(
    draft: String,
    busy: Boolean,
    onDraft: (String) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: androidx.compose.ui.text.input.VisualTransformation? = null,
    /** __VIDEO_NOTE_2026_09_24__ typing: the box grows (animated) into the width the capture buttons freed. */
    expanded: Boolean = false,
) {
    val (source, hovered) = rememberRowInteraction()
    val vPad by androidx.compose.animation.core.animateDpAsState(
        if (expanded) OshiTheme.md else OshiTheme.sm, androidx.compose.animation.core.tween(220), label = "composer-pad",
    )
    Box(
        modifier
            .animateContentSize(androidx.compose.animation.core.tween(220))
            .clip(OshiTheme.radiusXl)
            .background(if (hovered.value) OshiTheme.separator.copy(alpha = 0.12f) else OshiTheme.surface)
            .border(Metrics.hairline, if (expanded) OshiTheme.brand.copy(alpha = 0.35f) else Color.Transparent, OshiTheme.radiusXl)
            .focusRing(OshiTheme.radiusXl)
            .padding(horizontal = OshiTheme.md, vertical = vPad),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (draft.isEmpty()) {
            Text(
                if (busy) t("report.sending") else t("placeholder_message"),
                style = OshiTheme.typography.bodyLarge,
                color = Ink.soft,
            )
        }
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            enabled = !busy,
            interactionSource = source,
            textStyle = OshiTheme.typography.bodyLarge.copy(color = Ink.strong),
            cursorBrush = SolidColor(OshiTheme.brand),
            visualTransformation = highlight ?: androidx.compose.ui.text.input.VisualTransformation.None,
            // `lineLimit(1...5)` on the phone: grow to five lines, then scroll inside.
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OshiTheme.xl)
                // Enter sends and Shift+Enter is a newline. Consuming ONLY the unshifted
                // key-down is what makes that true: returning true for the key-up as well
                // would send twice, and consuming the shifted one would make a newline
                // impossible in a field the phone lets grow to five lines.
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                        onEnter()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

@Composable
private fun SendButton(canSend: Boolean, busy: Boolean, onSend: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier
            .size(Metrics.sendButton)
            .clip(CircleShape)
            .background(if (canSend) OshiTheme.brandGradient else SolidColor(OshiTheme.separator.copy(alpha = 0.35f)))
            .focusRing(CircleShape)
            .clickable(
                enabled = canSend,
                interactionSource = source,
                indication = null,
                onClickLabel = t("common.send"),
                onClick = onSend,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            GlyphIcon(Glyph.CLOCK, Color.White, 18.dp)
        } else {
            GlyphIcon(Glyph.ARROW_UP, if (canSend || hovered.value) Color.White else Ink.soft, 18.dp)
        }
    }
}

/**
 * A conversation this window will not send to, with the model's own reason under it.
 *
 * No text field at all — not a greyed-out one. A disabled box a user can click into and type
 * in is an invitation; the reason is the whole content of this state.
 */
@Composable
private fun DisabledComposer(composer: ComposerState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OshiTheme.surface)
            .padding(OshiTheme.lg),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(Glyph.LOCK, OshiTheme.danger, 18.dp)
        Column {
            Text(
                dt("desktop.composer.disabled.title"),
                style = OshiTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink.strong,
            )
            Spacer(Modifier.height(OshiTheme.xs))
            Text(composer.disabledReason.orEmpty(), fontSize = 11.sp, color = Ink.soft)
        }
    }
}

/** __GROUP_PARITY_2026_09_23__ "Replying to …" above the composer, with a way out. */
@Composable
private fun ReplyBanner(quote: QuoteRow, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated)
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t("message.replying_to", quote.who.ifBlank { "…" }),
                style = OshiTheme.typography.labelMedium,
                color = OshiTheme.brand,
            )
            Text(quote.text, style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1)
        }
        Text(
            t("common.cancel"),
            style = OshiTheme.typography.labelMedium,
            color = OshiTheme.brand,
            modifier = Modifier.clickable { onCancel() }.padding(OshiTheme.xs),
        )
    }
}

/** __GROUP_PARITY_2026_09_23__ the group picture when there is one, the monogram otherwise. */
@Composable
internal fun GroupAvatar(pictureBase64: String?, label: String, id: String, size: androidx.compose.ui.unit.Dp) {
    val picture = remember(pictureBase64) { groupPictureBitmap(pictureBase64) }
    if (picture != null) {
        androidx.compose.foundation.Image(
            bitmap = picture,
            contentDescription = label,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
        )
    } else {
        Monogram(label, id, size)
    }
}

/** A group picture from its definition, or null if absent or unreadable. */
private fun groupPictureBitmap(b64: String?): androidx.compose.ui.graphics.ImageBitmap? =
    com.oshi.desktop.group.GroupPicture.decodeBase64(b64)?.let { bytes ->
        runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }

/** __GROUP_PARITY_2026_09_23__ the pinned message, under the header (iOS pinned banner). */
@Composable
private fun PinnedBar(pinned: QuoteRow, canUnpin: Boolean, onUnpin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated)
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📌", fontSize = 13.sp)
        Spacer(Modifier.width(OshiTheme.sm))
        Column(Modifier.weight(1f)) {
            Text(pinned.who, style = OshiTheme.typography.labelMedium, color = OshiTheme.brand)
            Text(pinned.text, style = OshiTheme.typography.bodySmall, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canUnpin) TextAction(t("message.unpin")) { onUnpin() }
    }
}

/** iOS's blocked-group banner above the (closed) composer, with its unblock button. */
@Composable
private fun BlockedGroupBanner(onUnblock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(OshiTheme.danger.copy(alpha = 0.12f))
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t("group.blocked_title"), style = OshiTheme.typography.labelMedium, color = OshiTheme.danger)
            Text(t("group.blocked_message"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        TextAction(t("group.unblock")) { onUnblock() }
    }
}

/**
 * __GROUP_OPEN_ANCHOR_2026_09_23__ Keep a forward-layout chat LazyColumn pinned to its
 * newest row while the reader has not scrolled away (same rule as Android's
 * GroupChatScreen and iOS's `.defaultScrollAnchor(.bottom, for: .sizeChanges)`):
 * starts following, so opening a thread lands at the bottom on the first measured
 * layout and after every late batch or grown row; a drag, or any scroll (wheel,
 * scrollbar) that settles away from the end, stops it; one that settles at the end
 * re-arms it. Our own pinning scrolls never count, and a user scroll in progress is
 * never interrupted.
 */
@Composable
private fun rememberFollowNewest(
    listState: androidx.compose.foundation.lazy.LazyListState,
    key: Any,
): androidx.compose.runtime.MutableState<Boolean> {
    val follow = remember(key) { mutableStateOf(true) }
    // [0] = a pin is running; [1] = end of the grace window after it (nanoTime).
    val ownScroll = remember(key) { longArrayOf(0L, 0L) }
    LaunchedEffect(listState, key) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) follow.value = false
        }
    }
    LaunchedEffect(listState, key) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            val ours = ownScroll[0] != 0L || System.nanoTime() < ownScroll[1]
            if (!scrolling && !ours) follow.value = !listState.canScrollForward
        }
    }
    LaunchedEffect(listState, key) {
        androidx.compose.runtime.snapshotFlow {
            Triple(listState.layoutInfo.totalItemsCount, listState.canScrollForward, follow.value)
        }.collect { (total, canScrollForward, following) ->
            if (!following || total == 0 || !canScrollForward) return@collect
            if (listState.isScrollInProgress && ownScroll[0] == 0L) return@collect
            ownScroll[0] = 1L
            try {
                listState.scrollToItem(total - 1)
                var guard = 0
                while (listState.canScrollForward && guard++ < 8) {
                    val step = listState.layoutInfo.viewportSize.height.coerceAtLeast(1).toFloat()
                    listState.scrollBy(step)
                }
            } finally {
                ownScroll[0] = 0L
                ownScroll[1] = System.nanoTime() + 300_000_000L
            }
        }
    }
    return follow
}

/**
 * __DESKTOP_POLL_COMPOSER_2026_10_29__ Write a poll: a question, two to twelve options, and
 * the two switches both phones offer.
 *
 * ## The Send button and the wire cannot disagree
 *
 * Enablement is `PollPayload.sanitize(...) != null` — the SAME call `OshiClient.sendPoll`
 * makes before it builds anything. iOS gives the reason for having one definition of
 * "sendable" and it is exactly this: a composer that enables Send on its own idea of
 * validity eventually enables it for something the sender then refuses, and the user gets a
 * dead button or a silent failure. Nothing here re-decides a limit.
 *
 * ## Why the option rows grow rather than being a fixed twelve
 *
 * A poll needs two options and may have twelve. Twelve boxes on screen asks the person to
 * read eleven empty fields to find the one they want; a blank row appears as the last one is
 * filled, and the cap is enforced by not adding a thirteenth. The trailing blank is dropped
 * by `sanitize`, so it never becomes an option.
 */
@Composable
private fun PollComposer(
    onSend: (question: String, options: List<String>, multi: Boolean, anon: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var multi by remember { mutableStateOf(false) }
    var anon by remember { mutableStateOf(false) }

    // The one definition of sendable — see the class note.
    val sendable = remember(question, options) {
        com.oshi.desktop.msg.PollPayload.sanitize(question, options) != null
    }

    Column(
        Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated).padding(OshiTheme.lg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📊", fontSize = 13.sp)
            Spacer(Modifier.width(OshiTheme.sm))
            Text("New poll", style = OshiTheme.typography.labelMedium, color = OshiTheme.brand, modifier = Modifier.weight(1f))
            TextAction("Close") { onClose() }
        }
        Spacer(Modifier.height(OshiTheme.sm))

        PollField(
            value = question,
            placeholder = "Ask something…",
            max = com.oshi.desktop.msg.PollPayload.MAX_QUESTION_LENGTH,
            onChange = { question = it },
        )
        Spacer(Modifier.height(OshiTheme.xs))

        options.forEachIndexed { i, option ->
            PollField(
                value = option,
                placeholder = "Option ${i + 1}",
                max = com.oshi.desktop.msg.PollPayload.MAX_OPTION_LENGTH,
                onChange = { text ->
                    val next = options.toMutableList()
                    next[i] = text
                    // Grow by one when the last row is first filled, up to the cap. The
                    // trailing blank is dropped by `sanitize` and never ships.
                    if (i == next.lastIndex && text.isNotBlank() &&
                        next.size < com.oshi.desktop.msg.PollPayload.MAX_OPTIONS
                    ) {
                        next.add("")
                    }
                    options = next
                },
            )
            Spacer(Modifier.height(2.dp))
        }

        Spacer(Modifier.height(OshiTheme.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextAction(if (multi) "☑ Pick several" else "☐ Pick several") { multi = !multi }
            Spacer(Modifier.width(OshiTheme.md))
            TextAction(if (anon) "☑ Anonymous" else "☐ Anonymous") { anon = !anon }
        }
        // ANONYMOUS IS A PRESENTATION PROMISE, NOT A CRYPTOGRAPHIC ONE, and the composer says
        // so in as many words because iOS's does: a ballot still travels signed by its
        // sender — it cannot be otherwise on a peer-to-peer mesh — so what this buys is that
        // no name is collected or shown, not that the sender is unknowable.
        if (anon) {
            Text(
                "Names are never collected or shown. A ballot still travels signed by its " +
                    "sender, so this hides names rather than making a vote untraceable.",
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
                modifier = Modifier.padding(top = OshiTheme.xs),
            )
        }

        Spacer(Modifier.height(OshiTheme.sm))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sendable) "" else "A question and at least two options.",
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
                modifier = Modifier.weight(1f),
            )
            if (sendable) {
                TextAction("Send poll") {
                    onSend(question, options, multi, anon)
                }
            }
        }
    }
}

/** One bounded text box. The cap is enforced on INPUT so the counter can never disagree. */
@Composable
private fun PollField(value: String, placeholder: String, max: Int, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(OshiTheme.surface, RoundedCornerShape(6.dp))
            .padding(horizontal = OshiTheme.sm, vertical = 6.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(max)) },
            singleLine = true,
            textStyle = OshiTheme.typography.bodySmall.copy(color = Ink.strong),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ "This conversation is offline only", with the count of
 * other participants who have confirmed.
 *
 * The count is the whole reason this bar exists rather than a padlock icon. Nothing can make
 * another client refuse the internet, so "N of M confirmed" is the honest state of the
 * conversation and a padlock would be a privacy promise the app cannot keep. iOS says the
 * same of its own subtitle: a number smaller than the member count *"is the honest state of
 * the conversation, not a bug to hide"*.
 *
 * A null count means the conversation is not restricted and there is nothing to report, so
 * the caller does not draw this at all.
 */
@Composable
private fun OfflineOnlyBar(confirmed: Pair<Int, Int>?) {
    Row(
        Modifier.fillMaxWidth().background(OshiTheme.brand.copy(alpha = 0.10f))
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔒", fontSize = 13.sp)
        Spacer(Modifier.width(OshiTheme.sm))
        Column(Modifier.weight(1f)) {
            Text(t("offline_only.summary.on"), style = OshiTheme.typography.labelMedium, color = OshiTheme.brand)
            confirmed?.let { (yes, total) ->
                Text(
                    if (yes >= total && total > 0) "Confirmed by $yes of $total."
                    else "Confirmed by $yes of $total — the others may still be using the internet.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }
        }
    }
}

/**
 * __DESKTOP_THREAD_SEARCH_2026_10_29__ Finding a message inside the open conversation.
 *
 * The second of the three gaps PARITY row 0.0's sweep left open. This client could filter the
 * conversation LIST and could not search inside a thread at all, while iOS has a
 * calendar-based message search and Android a "Search" item in the same menu this sits in.
 *
 * The decision of WHAT matches is not here — it is [com.oshi.desktop.ui.state.ThreadSearch],
 * a pure function, so the cases nobody reproduces by hand (an accented query, midnight in a
 * named zone, a deleted row) are reachable from a test. This file only draws the result.
 *
 * Every string is an iOS key the catalogs already carry (`chat.search_placeholder`,
 * `chat.search_by_date`, `chat.search_results`, `chat.close_search`): a `desktop.` key where
 * an iOS key exists ships English to 33 locales for nothing.
 */
@Composable
private fun ThreadSearchPane(
    matches: List<com.oshi.desktop.ui.state.MessageRow>,
    total: Int,
    query: String,
    onQuery: (String) -> Unit,
    days: List<java.time.LocalDate>,
    day: java.time.LocalDate?,
    onDay: (java.time.LocalDate?) -> Unit,
    thread: ThreadView,
    wallpaper: WallpaperId,
    today: LocalDate,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(OshiTheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f)
                    .background(OshiTheme.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = OshiTheme.sm, vertical = 6.dp),
            ) {
                if (query.isEmpty()) {
                    Text(t("chat.search_placeholder"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = OshiTheme.typography.bodySmall.copy(color = Ink.strong),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(OshiTheme.sm))
            // The count uses the iOS format string, which is "%d/%d" — matches over total.
            Text(
                // The bare `t`, not a qualified call: `CatalogAuditTest`'s suspect scan looks
                // for a literal next to a REGISTERED accessor, and
                // `com.oshi.desktop.i18n.t(...)` is not one it knows — the audit flagged this
                // exact line. Second time today that its TRAP 3 earned its name.
                t("chat.search_results", matches.size, total),
                style = OshiTheme.typography.labelMedium,
                color = if (matches.isEmpty()) OshiTheme.warning else Ink.soft,
            )
            Spacer(Modifier.width(OshiTheme.sm))
            TextAction(t("chat.close_search")) { onClose() }
        }
        // The days that actually HAVE messages, rather than a calendar: a date picker that
        // offers every day of the month invites choosing one that can only ever be empty.
        if (days.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = OshiTheme.xl, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t("chat.search_by_date"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Spacer(Modifier.width(OshiTheme.sm))
                for (d in days.take(30)) {
                    val selected = d == day
                    Box(
                        Modifier.padding(end = 4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) OshiTheme.brand.copy(alpha = 0.18f) else OshiTheme.surface)
                            .clickable { onDay(if (selected) null else d) }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Text(
                            // `DayBreaks.label` takes an ISO day STRING and already says
                            // today / yesterday / the weekday / the long date, with the
                            // catalog keys that exist. The first version here invented
                            // `common.today` and `common.yesterday`, which are not keys —
                            // `t` would have rendered the key itself into the chip.
                            DayBreaks.label(d.toString(), today),
                            style = OshiTheme.typography.bodySmall,
                            color = if (selected) OshiTheme.brand else Ink.soft,
                        )
                    }
                }
            }
        }
        Hairline()
        if (matches.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Says what was searched rather than "no results": an empty result for a day
                // with no messages is a different thing from a word nobody wrote.
                Text(
                    if (query.isBlank() && day != null) t("chat.search_by_date") else t("chat.search_placeholder"),
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }
            return@Column
        }
        MessageLog(
            thread.copy(messages = matches),
            wallpaper,
            com.oshi.desktop.ui.components.ViewOnceFacts.NONE,
            emptySet(), {}, { _, _ -> }, { _, _ -> }, {}, today, {}, {}, {}, { _, _ -> }, { _, _ -> },
        )
    }
}

/**
 * __DESKTOP_SLASH_MENU_2026_10_29__ The list that opens when a message starts with `/`.
 *
 * Drawn above the composer, like the mention picker, because it completes what is being
 * typed. The rule for WHEN it appears is [com.oshi.desktop.msg.SlashCommands], a pure
 * function — a space closes it, a lone `/` opens the full list — so the behaviour is
 * testable without a window.
 *
 * Every row is a command this client already executes. A menu that advertises something the
 * composer will not honour is worse than no menu, and a test asserts the pairing rather than
 * trusting this comment.
 */
@Composable
private fun SlashMenu(
    commands: List<com.oshi.desktop.msg.SlashCommands.Command>,
    onPick: (com.oshi.desktop.msg.SlashCommands.Command) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated)) {
        Hairline()
        for (c in commands) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onPick(c) }
                    .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.command, style = OshiTheme.typography.labelMedium, color = OshiTheme.brand)
                Spacer(Modifier.width(OshiTheme.sm))
                Text(
                    c.description,
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(c.source, style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
    }
}
