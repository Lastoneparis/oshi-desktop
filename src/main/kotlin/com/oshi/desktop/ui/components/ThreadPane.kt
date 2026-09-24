package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
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
    onCopy: (String) -> Unit = {},
    /** __MENTIONS_2026_09_23__ a member picked from the `@` picker (groups only). */
    onPickMention: (com.oshi.desktop.group.MentionWire.Mention) -> Unit = {},
    /** __DESKTOP_REPORT_2026_09_23__ null = no report action (bot / radio threads). */
    onReport: ((com.oshi.desktop.net.V2ReportClient.Reason, String, Boolean) -> Unit)? = null,
) {
    var pickerOpen by remember(thread.conversationId) { mutableStateOf(false) }
    var reportOpen by remember(thread.conversationId) { mutableStateOf(false) }
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
                thread.groupPinned?.let { PinnedBar(it, canUnpin = thread.groupCanEditInfo) { onPin(null) } }
                thread.botSealing?.let { BotSealLabel(it) }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (thread.messages.isEmpty()) {
                        EmptyThread(thread.kind)
                    } else {
                        MessageLog(thread, wallpaper, viewOnce, revealed, onReveal, onReact, onEditMessage, onDeleteMessage, today, onReply, onPin, onDeleteForMe, onForward)
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
                    onPickMention = onPickMention)
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
                    if (thread.groupAdmin) TextAction(dt("desktop.group.rename.action")) { editingName = true }
                }
                KindBadge(thread.kind)
                // __DESKTOP_REPORT_2026_09_23__ "Signaler", like the phones' contact/group sheet.
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
                    Text(dt("desktop.group.members.count", thread.groupMembers.size), fontSize = 11.sp, color = Ink.soft)
                    if (thread.groupAdmin) Text(dt("desktop.group.members.admin"), fontSize = 11.sp, color = OshiTheme.success)
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
                        if (thread.groupCanEditInfo) TextAction(dt("desktop.group.rename.action")) { editingDescription = true }
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
                onPin = if (thread.kind == ConversationKind.GROUP && thread.groupCanEditInfo && !row.deleted) { pin -> onPin(if (pin) row.id else null) } else null,
                pinned = thread.groupPinned?.messageId == row.id,
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
            com.oshi.desktop.bot.BotEnvelope.Sealing.SERVER -> dt("desktop.bot.seal.server")
            com.oshi.desktop.bot.BotEnvelope.Sealing.BOT -> dt("desktop.bot.seal.bot")
            com.oshi.desktop.bot.BotEnvelope.Sealing.NONE -> dt("desktop.bot.seal.none")
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
 * ============================================================ EXCEPT IN A GROUP
 *
 * There it is still disabled, and for a reason that is a CLIENT fact rather than a schedule:
 * there is no group media fan-out anywhere in this client, so a blob uploaded here would be
 * fetched by nobody. The two gates are separate fields on [ComposerState] precisely so this
 * case can exist — one boolean would have forced a choice between a group with no composer
 * and a group with a paperclip that lies.
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
) {
    if (!composer.enabled && !busy) {
        DisabledComposer(composer)
        return
    }

    var whyOpen by remember { mutableStateOf(false) }
    val voice = remember { VoiceNotes() }
    var recording by remember(conversationId) { mutableStateOf(false) }
    var voiceProblem by remember(conversationId) { mutableStateOf<String?>(null) }
    DisposableEffect(conversationId) {
        onDispose { if (voice.isRecording) voice.cancelRecording() }
    }
    val canSend = draft.isNotBlank() && !busy
    var gifOpen by remember(conversationId) { mutableStateOf(false) }   // __GIF_PACK_2026_09_23__
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

            if (onGif != null && composer.attachEnabled) {
                TextAction(dt("desktop.gif.button")) { if (!busy) gifOpen = !gifOpen }
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

            DraftField(
                draft = draft,
                busy = busy,
                onDraft = onDraft,
                // With the `@` picker open, Enter picks the first member instead of sending.
                onEnter = { if (candidates.isNotEmpty()) pick(candidates.first()) else if (canSend) onSend() },
                modifier = Modifier.weight(1f),
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
) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        modifier
            .clip(OshiTheme.radiusXl)
            .background(if (hovered.value) OshiTheme.separator.copy(alpha = 0.12f) else OshiTheme.surface)
            .focusRing(OshiTheme.radiusXl)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
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
