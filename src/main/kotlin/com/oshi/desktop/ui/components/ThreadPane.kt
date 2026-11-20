package com.oshi.desktop.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.oshi.desktop.ui.state.ContactRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.foundation.layout.fillMaxHeight
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
 * ============================================================ THE HEADER (__THREAD_CHROME_2027__)
 *
 * One compact row — avatar, name, subline, search / voice / video, and a "⋯" menu holding
 * every other action — plus a side info panel opened from the name. See `ThreadChrome.kt`.
 * Calls are offered only where `onCall` is wired (1:1, call lane present).
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
    /** __FORWARD_MANY_DESKTOP_2027__ forward one message to several contacts. */
    onForward: (messageId: String, toAddresses: List<String>) -> Unit = { _, _ -> },
    /** __REPLY_PRIVATELY_DESKTOP_2027__ answer a group member in a 1:1, quoting their message. */
    onReplyPrivately: (messageId: String) -> Unit = {},
    /** __VIEW_ONCE_DESKTOP_2027__ pick a photo/video and send it view-once (1:1 only; null hides the switch). */
    onAttachViewOnce: (() -> Unit)? = null,
    /** __DESKTOP_POLLS_2026_10_29__ cast or toggle a vote on a poll row. */
    onVote: (messageId: String, optionIndex: Int) -> Unit = { _, _ -> },
    /** __DESKTOP_POLL_COMPOSER_2026_10_29__ create a poll in this conversation. */
    onCreatePoll: ((String, List<String>, Boolean, Boolean, Long?) -> Unit)? = null,
    /** __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ send one contact INTO this thread. */
    onSendContact: ((cardAddress: String) -> Unit)? = null,
    /** Everyone this machine knows, for that picker. */
    contacts: List<ContactRow> = emptyList(),
    /**
     * __PRIVACY_DISGUISE_2026_11_18__ open "Disguise as photo" for this 1:1 peer. Null hides
     * it (groups: a STEGO-v1 image is addressed to ONE key).
     */
    onDisguise: (() -> Unit)? = null,
    /** __DESKTOP_OFFLINE_ONLY_2026_10_29__ take this conversation off the relay, or put it back. */
    onSetOfflineOnly: ((Boolean) -> Unit)? = null,
    onCopy: (String) -> Unit = {},
    /** __MENTIONS_2026_09_23__ a member picked from the `@` picker (groups only). */
    onPickMention: (com.oshi.desktop.group.MentionWire.Mention) -> Unit = {},
    /** __DESKTOP_REPORT_2026_09_23__ null = no report action (bot / radio threads). */
    onReport: ((com.oshi.desktop.net.V2ReportClient.Reason, String, Boolean) -> Unit)? = null,
    /** __VIDEO_NOTE_2026_09_24__ send a recorded round note (sealed MP4 + its metadata). Null = no button. */
    onVideoNote: ((java.io.File, com.oshi.desktop.media.VideoNoteWire.Meta) -> Unit)? = null,
    /** __CONVERSATION_PIN_MUTE_2027__ mute any conversation (a group through its own flag). */
    onSetMuted: ((Boolean) -> Unit)? = null,
    /** __CONVERSATION_PIN_MUTE_2027__ pin this conversation to the top of the list. */
    onSetChatPinned: ((Boolean) -> Unit)? = null,
    /** __CONVERSATION_BLOCK_2027__ block / unblock the peer of a 1:1 (confirmed). */
    onSetContactBlocked: ((Boolean) -> Unit)? = null,
    /** __CONVERSATION_VERIFY_2027__ mark verified after comparing, and the QR route. */
    onSetContactVerified: ((Boolean) -> Unit)? = null,
    onVerifyPayload: ((String) -> Boolean)? = null,
    /** __CONVERSATION_EXPORT_2027__ export this conversation (the window asks where). */
    onExport: (() -> Unit)? = null,
    /** __GROUP_MEMBER_MODERATION_2027__ report one member (as a contact), and block / unblock one. */
    onReportMember: ((address: String, com.oshi.desktop.net.V2ReportClient.Reason, String, Boolean) -> Unit)? = null,
    onSetMemberBlocked: ((address: String, Boolean) -> Unit)? = null,
    /** __MERGE_AB_MEMBER_MENU__ open a 1:1 with a group member from the member menu. */
    onReplyPrivatelyToMember: ((address: String) -> Unit)? = null,
    /**
     * __PHOTO_MARKUP_DESKTOP_2027__ / __DESKTOP_CAMERA_2027__ pick a picture (it opens in the
     * markup editor; anything else is attached as it is), and attach a file the composer
     * made — `discard` = a temporary file to delete once sent.
     */
    onPickImage: (() -> java.io.File?)? = null,
    onAttachFile: ((java.io.File, discard: Boolean, viewOnce: Boolean) -> Unit)? = null,
) {
    var pickerOpen by remember(thread.conversationId) { mutableStateOf(false) }
    var reportOpen by remember(thread.conversationId) { mutableStateOf(false) }
    val previewReport = LocalThreadChromePreview.current.reportMemberAddress
    var reportingMember by remember(thread.conversationId) { mutableStateOf(previewReport) }
    // __DESKTOP_MEDIA_GALLERY_2026_10_29__ keyed by conversation so switching chats closes it.
    val chromePreviewA = LocalThreadChromePreview.current
    var galleryOpen by remember(thread.conversationId) { mutableStateOf(chromePreviewA.galleryOpen) }
    // __DESKTOP_THREAD_SEARCH_2026_10_29__ also keyed by conversation: a query typed in one
    // chat has no meaning in the next, and carrying it over would silently hide messages.
    val searchPreview = LocalThreadChromePreview.current.searchQuery
    var searchOpen by remember(thread.conversationId) { mutableStateOf(searchPreview != null) }
    var searchQuery by remember(thread.conversationId) { mutableStateOf(searchPreview.orEmpty()) }
    var searchDay by remember(thread.conversationId) { mutableStateOf<java.time.LocalDate?>(null) }
    val reportable = onReport != null &&
        (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP)

    // __THREAD_CHROME_2027__ the header's menu, the info panel and their dialogs.
    val preview = LocalThreadChromePreview.current
    var infoOpen by remember(thread.conversationId) { mutableStateOf(preview.infoOpen) }
    var headerMenu by remember(thread.conversationId) { mutableStateOf(preview.headerMenuOpen) }
    var renaming by remember(thread.conversationId) { mutableStateOf(false) }
    var describing by remember(thread.conversationId) { mutableStateOf(false) }
    var inviting by remember(thread.conversationId) { mutableStateOf(false) }
    var confirmLeave by remember(thread.conversationId) { mutableStateOf(false) }
    var confirmDelete by remember(thread.conversationId) { mutableStateOf(false) }
    var confirmBlock by remember(thread.conversationId) { mutableStateOf(false) }
    var verifying by remember(thread.conversationId) { mutableStateOf(preview.verifyOpen) }
    var confirmExport by remember(thread.conversationId) { mutableStateOf(preview.exportOpen) }
    val isGroup = thread.kind == ConversationKind.GROUP
    val isDirect = thread.kind == ConversationKind.DIRECT
    val toggleSearch: (() -> Unit)? = if (thread.messages.isEmpty()) null else ({
        searchOpen = !searchOpen
        if (!searchOpen) { searchQuery = ""; searchDay = null }
    })
    val chrome = ThreadChromeActions(
        onOpenInfo = { infoOpen = true },
        onOpenSearch = toggleSearch,
        // __DESKTOP_CALL_UI_2026_09_23__ 1:1 only — like iOS, which offers no call in a
        // group, and never in a bot or radio thread: neither has a peer that can answer.
        onCall = onCall?.takeIf { thread.kind == ConversationKind.DIRECT },
        // Only when there is something to show: a control that opens an empty grid
        // teaches the user it is broken.
        onOpenGallery = if (thread.media.isEmpty() && thread.links.isEmpty()) null else ({ galleryOpen = true; searchOpen = false }),
        onWallpaper = { pickerOpen = !pickerOpen },
        onReport = if (reportable) ({ reportOpen = !reportOpen }) else null,
        // 1:1 only: `setOfflineOnly` is a 1:1 path, and a control that sent nothing would
        // be worse than none.
        onSetOfflineOnly = onSetOfflineOnly?.takeIf { thread.kind == ConversationKind.DIRECT },
        onUnpin = if (thread.pinned != null && thread.pinCanToggle) ({ onPin(null) }) else null,
        onRename = if (isGroup && thread.groupAdmin) ({ renaming = true }) else null,
        onEditDescription = if (isGroup && thread.groupCanEditInfo) ({ describing = true }) else null,
        onChangePicture = if (isGroup && thread.groupCanEditInfo) onSetGroupPicture else null,
        onRemovePicture = if (isGroup && thread.groupCanEditInfo && thread.groupPictureBase64 != null) onRemoveGroupPicture else null,
        onShowInvite = if (isGroup && thread.groupInviteLink != null) ({ inviting = true }) else null,
        onSetMuted = onSetMuted?.takeIf { isGroup || isDirect } ?: (if (isGroup) onSetGroupMuted else null),
        // A group block is reversible and quiet; a contact block is confirmed first (the phones' alert).
        onSetBlocked = when {
            isGroup -> onSetGroupBlocked
            isDirect && onSetContactBlocked != null -> { on -> if (on) confirmBlock = true else onSetContactBlocked(false) }
            else -> null
        },
        // iOS: a member's destructive action is Leave; an admin also has "Delete Group"
        // (this device only). Both confirmed with the phones' wording.
        onLeave = if (isGroup) ({ confirmLeave = true }) else null,
        onDelete = if (isGroup && thread.groupAdmin) ({ confirmDelete = true }) else null,
        onCopy = onCopy,
        onAddMember = onAddGroupMember,
        onRemoveMember = onRemoveGroupMember,
        onSetMemberAdmin = onSetGroupMemberAdmin,
        onSetChatPinned = onSetChatPinned,
        onVerify = if (isDirect && thread.safetyNumber.isNotBlank() && onSetContactVerified != null) ({ verifying = true }) else null,
        onExport = if (thread.messages.isNotEmpty() && onExport != null) ({ confirmExport = true }) else null,
        onReportMember = if (isGroup && onReportMember != null) ({ addr -> reportingMember = addr; reportOpen = false; infoOpen = false }) else null,
        onSetMemberBlocked = onSetMemberBlocked?.takeIf { isGroup },
        onReplyPrivatelyToMember = onReplyPrivatelyToMember?.takeIf { isGroup }?.let { f -> { addr: String -> infoOpen = false; f(addr) } },
    )

    Box(
        modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.Escape && infoOpen -> { infoOpen = false; true }
                e.key == Key.Escape && searchOpen -> { searchOpen = false; searchQuery = ""; searchDay = null; true }
                e.key == Key.F && (e.isMetaPressed || e.isCtrlPressed) && toggleSearch != null -> {
                    if (!searchOpen) toggleSearch(); true
                }
                else -> false
            }
        },
    ) {
        WallpaperBackdrop(wallpaper)

        // The viewer wraps the WHOLE thread rather than sitting inside the message log, so
        // a full-size picture covers the header and the composer too. Anything narrower
        // would put a photograph in a box beside a text field, which is a thumbnail with
        // extra steps. It provides `LocalMediaOpener`; the bubbles read it.
        WithMediaViewer {
          Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ThreadHeaderBar(
                    thread = thread,
                    actions = chrome,
                    infoOpen = infoOpen,
                    searchOpen = searchOpen,
                    wallpaperActive = pickerOpen || wallpaper != WallpaperId.NONE,
                    menuOpen = headerMenu,
                    onMenuOpen = { headerMenu = it },
                )
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
                // __GROUP_MEMBER_MODERATION_2027__ the same sheet, about ONE member.
                val member = reportingMember?.let { a -> thread.groupMembers.firstOrNull { it.address == a } }
                if (member != null && onReportMember != null) {
                    ReportPanel(
                        isGroup = false,
                        alreadyReported = false,
                        title = t("group.report_member") + " · " + member.label,
                        onSubmit = { r, d, b -> onReportMember(member.address, r, d, b) },
                        onClose = { reportingMember = null },
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
                        MediaGalleryPane(thread.media, thread.links, initialTab = LocalThreadChromePreview.current.galleryTab) { galleryOpen = false }
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
                        MessageLog(thread, wallpaper, viewOnce, revealed, onReveal, onReact, onEditMessage, onDeleteMessage, today, onReply, onPin, onDeleteForMe, onForward, onVote, onCopy, onReplyPrivately)
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
                if (thread.contactBlocked && onSetContactBlocked != null) BlockedContactBanner { onSetContactBlocked(false) }
                if (replyingTo != null && thread.composer.enabled) ReplyBanner(replyingTo, onCancelReply)
                // __CONVERSATION_BLOCK_2027__ the banner above already says why; no second, closed composer under it.
                if (!(thread.contactBlocked && onSetContactBlocked != null)) Composer(thread.conversationId, thread.composer, busy, draft, onDraft, onSend, onAttach, onVoiceNote,
                    // __GROUP_GIF_2026_11_16__ Both phones now offer GIFs & stickers in groups
                    // (Android ebddb827, iOS c8f0df22). The pick rides the group media path
                    // (`ChatShellModel.attach` → `OshiClient.sendGroupFile`): wire type `photo`,
                    // bytes untouched, name ending `.gif` (GifOutbox), MIME image/gif — exactly
                    // Android's `GroupManager.groupMime`/`groupGifFileName`. Bot and radio
                    // threads still take no GIF (their composers have attach disabled).
                    onGif,
                    // __MENTIONS_2026_09_23__ `@` picker: group members other than ourselves.
                    mentionMembers = if (thread.kind == com.oshi.desktop.ui.state.ConversationKind.GROUP)
                        thread.groupMembers.filter { !it.self }.map { com.oshi.desktop.group.MentionWire.Mention(it.address, it.label.trim()) }
                    else emptyList(),
                    onPickMention = onPickMention,
                    onVideoNote = onVideoNote,
                    onPickImage = onPickImage,
                    onAttachFile = onAttachFile,
                    // __DESKTOP_POLL_COMPOSER_2026_10_29__ 1:1 only, because `votePoll` and
                    // `sendPoll` are 1:1 paths — a poll offered in a group would send
                    // nothing. Named here rather than hidden in the Composer so the reason
                    // sits with the kind check beside it.
                    // __GROUP_POLLS_2027__ groups too now: a group poll is a group message and a
                    // ballot an ephemeral group body (Android GroupChatViewModel / GroupManager).
                    onCreatePoll = onCreatePoll?.takeIf { thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT || thread.kind == com.oshi.desktop.ui.state.ConversationKind.GROUP },
                    // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Direct only, and for a
                    // harder reason than the poll's: `sendContactCard` addresses ONE peer and
                    // has no group fan-out at all, so offering it in a group would be a
                    // control that cannot work rather than one that works badly.
                    onSendContact = onSendContact?.takeIf { thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT },
                    // The card's subject is never the person you are sending it to.
                    contacts = contacts.filterNot { it.address == thread.conversationId },
                    onDisguise = onDisguise?.takeIf { thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT },
                    // __VIEW_ONCE_DESKTOP_2027__ 1:1 only: iOS sends group media with isViewOnce false.
                    onAttachViewOnce = onAttachViewOnce?.takeIf { thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT },
                    voiceViewOnceAllowed = thread.kind == com.oshi.desktop.ui.state.ConversationKind.DIRECT)
            }
            if (infoOpen) ThreadInfoPanel(thread, chrome, onClose = { infoOpen = false })
          }
        }

        if (renaming) TextEditDialog(t("mail.drive.rename"), thread.title, 100, singleLine = true, onSave = onRenameGroup, onDismiss = { renaming = false })
        if (describing) TextEditDialog(t("group.edit_description"), thread.groupDescription.orEmpty(), 500, singleLine = false, onSave = onSetGroupDescription, onDismiss = { describing = false })
        if (inviting) thread.groupInviteLink?.let { InviteLinkDialog(it, onCopy) { inviting = false } }
        if (confirmLeave) ConfirmDestructiveDialog(t("confirm_leave_group"), null, t("groups.leave"), onConfirm = onLeaveGroup, onDismiss = { confirmLeave = false })
        if (confirmDelete) ConfirmDestructiveDialog(t("confirm_delete_group"), null, t("button_delete_group"), onConfirm = onDeleteGroup, onDismiss = { confirmDelete = false })
        if (confirmBlock && onSetContactBlocked != null) ConfirmDestructiveDialog(
            t("block_contact_confirm_title"), t("block_contact_confirm_message"), t("chat.block"),
            onConfirm = { onSetContactBlocked(true) }, onDismiss = { confirmBlock = false },
        )
        if (verifying && onSetContactVerified != null) SafetyNumberDialog(
            name = thread.title,
            safetyNumber = thread.safetyNumber,
            qrPayload = thread.safetyQr,
            verified = thread.verified,
            onMark = onSetContactVerified,
            onVerifyPayload = onVerifyPayload,
            onCopy = onCopy,
            onDismiss = { verifying = false },
        )
        if (confirmExport && onExport != null) ExportConversationDialog(
            title = thread.title,
            count = thread.messages.size,
            onExport = onExport,
            onDismiss = { confirmExport = false },
        )
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
    onForward: (String, List<String>) -> Unit = { _, _ -> },
    onVote: (messageId: String, optionIndex: Int) -> Unit = { _, _ -> },
    onCopy: (String) -> Unit = {},
    onReplyPrivately: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val targets = thread.forwardTargets

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

    // __UNREAD_DIVIDER_2027__ The first message that was unread when the conversation was
    // opened, counted from the end over messages from others (the phones' divider).
    val firstUnreadId = remember(thread.conversationId, thread.unreadAtOpen) {
        if (thread.unreadAtOpen <= 0) null
        else thread.messages.filter { !it.fromMe }.takeLast(thread.unreadAtOpen).firstOrNull()?.id
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
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
            if (row.id == firstUnreadId) UnreadDivider()

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
                // __EDIT_OFFERED_ON_THINGS_THE_PHONES_REFUSE_2026_11_05__
                // `row.attachment == null && row.poll == null` added. This used
                // to gate on ownership alone, so Edit appeared under a photo
                // (whose `body` is its CAPTION) and under a poll (whose `body`
                // is empty) — and `OshiClient.editMessage` does not re-check.
                // Saving either sent an `editMessage` action that a receiving
                // phone applies by assigning `plaintextContent = newContent` on
                // a media row, rewriting a photo's body with caption text.
                // iOS and Android both require `mediaType == nil`; this now
                // matches them.
                onEdit = if ((thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP)
                    && row.fromMe && row.attachment == null && row.poll == null) { body -> onEditMessage(row.id, body) } else null,
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
                onCopy = if (row.body.isNotBlank() && !row.deleted) ({ onCopy(row.body) }) else null,
                onForward = if (thread.kind == ConversationKind.DIRECT || thread.kind == ConversationKind.GROUP) { to -> onForward(row.id, to) } else null,
                // __REPLY_PRIVATELY_DESKTOP_2027__ someone else's message in a group (not deleted).
                onReplyPrivately = if (thread.kind == ConversationKind.GROUP && !row.fromMe && !row.deleted &&
                    row.senderAddress.isNotBlank() && thread.groupMembers.none { it.self && it.address == row.senderAddress }) { { onReplyPrivately(row.id) } } else null,
                showSender = thread.kind == ConversationKind.GROUP &&
                    (separator != null || previous == null || previous.fromMe || previous.who != row.who),
            )
        }
    }
    // __SCROLL_TO_LATEST_2027__ The phones' "Scroll to latest" button: shown once the reader
    // has scrolled away from the newest message; one click goes back and re-arms following.
    val awayFromEnd by remember { androidx.compose.runtime.derivedStateOf { listState.canScrollForward } }
    if (awayFromEnd && !followNewest.value) {
        val (src, hovered) = rememberRowInteraction()
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = OshiTheme.xl, bottom = OshiTheme.lg)
                .size(40.dp)
                .clip(CircleShape)
                .background(if (hovered.value) OshiTheme.brand else OshiTheme.surfaceElevated)
                .border(1.dp, OshiTheme.separator, CircleShape)
                .clickable(interactionSource = src, indication = null, role = androidx.compose.ui.semantics.Role.Button) {
                    followNewest.value = true
                    scope.launch { if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.size - 1) }
                }
                .semantics { contentDescription = t("chat.scroll_to_bottom") },
            contentAlignment = Alignment.Center,
        ) {
            Text("↓", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (hovered.value) Color.White else OshiTheme.brand)
        }
    }
    }
}

/** __UNREAD_DIVIDER_2027__ `chat_unread_divider`: a hairline with the label in the middle. */
@Composable
private fun UnreadDivider() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(OshiTheme.brand.copy(alpha = 0.45f)))
        Text(t("chat_unread_divider"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OshiTheme.brand)
        Box(Modifier.weight(1f).height(1.dp).background(OshiTheme.brand.copy(alpha = 0.45f)))
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

/** `chatEmptyState`, copy included — the shipped app's own sentence — under the mascot. */
@Composable
private fun EmptyThread(kind: ConversationKind) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MascotEmptyState(
            mascot = when (kind) {
                ConversationKind.DIRECT -> "wave"
                ConversationKind.GROUP -> "party"
                else -> "peek"
            },
            title = if (kind == ConversationKind.DIRECT) t("chat.empty.title") else t("messages.empty"),
            body = when (kind) {
                ConversationKind.DIRECT -> t("chat.empty.subtitle")
                ConversationKind.GROUP -> t("conv.thread.empty.group")
                ConversationKind.BOT -> t("conv.thread.empty.bot")
                ConversationKind.LORA -> t("conv.thread.empty.lora")
            },
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
    onCreatePoll: ((question: String, options: List<String>, multi: Boolean, anon: Boolean, closesInSeconds: Long?) -> Unit)? = null,
    /**
     * __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ send one contact INTO this conversation.
     * Null hides the control: a card addresses one peer, so it is not offered in a group,
     * for the same reason the poll control is not offered where a ballot cannot come back.
     */
    onSendContact: ((cardAddress: String) -> Unit)? = null,
    contacts: List<ContactRow> = emptyList(),
    onDisguise: (() -> Unit)? = null,
    onAttachViewOnce: (() -> Unit)? = null,
    voiceViewOnceAllowed: Boolean = false,
    onPickImage: (() -> java.io.File?)? = null,
    /** (file, discardAfterSend, viewOnce) — viewOnce only ever true when [onAttachViewOnce] is offered. */
    onAttachFile: ((java.io.File, Boolean, Boolean) -> Unit)? = null,
) {
    if (!composer.enabled && !busy) {
        DisabledComposer(composer)
        return
    }

    var whyOpen by remember { mutableStateOf(false) }
    // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Reset per conversation: a picker left
    // open while the user switches threads would send into the wrong one.
    val contactPreview = LocalThreadChromePreview.current.contactOpen
    var contactOpen by remember(conversationId) { mutableStateOf(contactPreview) }
    val voice = remember { VoiceNotes() }
    var recording by remember(conversationId) { mutableStateOf(false) }
    // __VOICE_EFFECTS_DESKTOP_2027__ a stopped recording waits here, in the preview, until sent or binned.
    var voicePreview by remember(conversationId) { mutableStateOf<com.oshi.desktop.media.Recording?>(null) }
    var voiceEffect by remember(conversationId) { mutableStateOf(com.oshi.desktop.call.media.CallVoiceEffects.Effect.NONE) }
    var voiceOnce by remember(conversationId) { mutableStateOf(false) }
    var voicePlaying by remember(conversationId) { mutableStateOf(false) }
    var voiceBusy by remember(conversationId) { mutableStateOf(false) }
    val voiceRenders = remember(conversationId) { HashMap<com.oshi.desktop.call.media.CallVoiceEffects.Effect, java.io.File>() }
    val voiceScope = androidx.compose.runtime.rememberCoroutineScope()
    // __VIEW_ONCE_DESKTOP_2027__ "View Once" armed for the next photo/video from the "+" panel.
    val chromePreviewA = LocalThreadChromePreview.current
    var attachOnce by remember(conversationId) { mutableStateOf(chromePreviewA.viewOnceArmed) }
    var voiceProblem by remember(conversationId) { mutableStateOf<String?>(null) }
    DisposableEffect(conversationId) {
        onDispose {
            if (voice.isRecording) voice.cancelRecording()
            voicePreview?.let { voice.discardPreview(it, voiceRenders.values.filter { f -> f != it.file }) }
        }
    }
    val canSend = draft.isNotBlank() && !busy
    // __VIDEO_NOTE_2026_09_24__ typing widens the field and moves the capture buttons aside
    // (ComposerChrome — the rule is plain Kotlin so a test holds it); clearing brings them back.
    val chrome = com.oshi.desktop.ui.state.ComposerChrome.of(draft, voiceRecording = recording)
    var videoOpen by remember(conversationId) { mutableStateOf(false) }
    // __DESKTOP_CAMERA_2027__ / __PHOTO_MARKUP_DESKTOP_2027__
    val cameraPreviewOpen = LocalThreadChromePreview.current.cameraOpen
    var cameraOpen by remember(conversationId) { mutableStateOf(cameraPreviewOpen) }
    var markup by remember(conversationId) { mutableStateOf<java.awt.image.BufferedImage?>(null) }
    val hiddenByLock = LocalHiddenByLock.current
    LaunchedEffect(hiddenByLock) {
        if (hiddenByLock) { cameraOpen = false; videoOpen = false }
    }
    // __MERGE_AB_VIEW_ONCE_MARKUP__ a view-once photo goes through markup too, as on iOS
    // (ChatView `sendMediaMessage` → PhotoMarkupView keeps `isViewOnce`). The flag rides
    // alongside the editor and is spent by Send/Cancel.
    var markupOnce by remember(conversationId) { mutableStateOf(false) }
    val pickPicture: (Boolean) -> Unit = { once ->
        val f = onPickImage?.invoke()
        if (f != null) {
            val img = if (com.oshi.desktop.media.ImageMarkup.isImageFile(f)) com.oshi.desktop.media.ImageMarkup.load(f) else null
            if (img != null) { markupOnce = once; markup = img } else onAttachFile?.invoke(f, false, once)
        }
    }
    // Gallery / "Choose files": with B's picker wired, every picture opens the editor (view-once
    // or not); without it, A's view-once picker or the plain attach.
    val chooseGallery: () -> Unit = {
        val once = attachOnce && onAttachViewOnce != null
        attachOnce = false
        when {
            onPickImage != null && onAttachFile != null -> pickPicture(once)
            once -> onAttachViewOnce?.invoke()
            else -> onAttach()
        }
    }
    val videoUnavailable by androidx.compose.runtime.produceState<String?>(VideoNoteAvailability.cachedOrNull()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VideoNoteAvailability.reason() }
    }
    val preview = LocalThreadChromePreview.current
    var gifOpen by remember(conversationId) { mutableStateOf(false) }   // __GIF_PACK_2026_09_23__
    var panelOpen by remember(conversationId) { mutableStateOf(preview.panelOpen) } // __ATTACH_PANEL_2027__
    var pollOpen by remember(conversationId) { mutableStateOf(preview.pollOpen) }  // __DESKTOP_POLL_COMPOSER_2026_10_29__
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
                onSend = { q, opts, multi, anon, closes -> pollOpen = false; onCreatePoll(q, opts, multi, anon, closes) },
                onClose = { pollOpen = false },
            )
        }
        // __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Who to send. Blocked contacts are
        // left out: enforcement is a real rule here (row 0.21), and handing someone's card
        // around is exactly the thing a block is for.
        if (contactOpen && onSendContact != null) {
            ContactCardPicker(
                contacts = contacts.filterNot { it.blocked },
                onPick = { contactOpen = false; onSendContact(it) },
                onClose = { contactOpen = false },
            )
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
                    t("conv.composer.attach.disabled.title"),
                    style = OshiTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.strong,
                )
                Spacer(Modifier.height(OshiTheme.xs))
                Text(composer.attachDisabledReason, fontSize = 11.sp, color = Ink.soft)
            }
        }

        // __ATTACH_PANEL_2027__ The "+" panel (the phones' 2027 attachment panel).
        if (panelOpen) {
            val tiles = AttachmentPanelSpec.tiles(
                attachEnabled = composer.attachEnabled,
                gif = onGif != null,
                contact = onSendContact != null && contacts.any { !it.blocked },
                poll = onCreatePoll != null,
                videoNote = onVideoNote != null && !recording,
                disguise = onDisguise != null,
                camera = onAttachFile != null && !recording,
            )
            if (onAttachViewOnce != null && composer.attachEnabled) {
                ViewOnceToggle(attachOnce, t("media_picker.view_once.description"), { attachOnce = it },
                    Modifier.padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs))
            }
            AttachmentPanel(
                tiles = tiles,
                attachEnabled = composer.attachEnabled,
                disabledReason = composer.attachDisabledReason,
                videoUnavailable = videoUnavailable,
                onChooseFiles = { panelOpen = false; if (!busy) chooseGallery() },
                onTile = { tile ->
                    panelOpen = false
                    gifOpen = false; pollOpen = false; contactOpen = false
                    when (tile) {
                        AttachmentPanelSpec.Tile.GALLERY -> if (!busy) chooseGallery()
                        AttachmentPanelSpec.Tile.CAMERA -> if (!busy) {
                            // The armed View Once switch applies to the camera photo too.
                            markupOnce = attachOnce && onAttachViewOnce != null
                            attachOnce = false
                            cameraOpen = true
                        }
                        AttachmentPanelSpec.Tile.FILE -> if (!busy) onAttach()
                        AttachmentPanelSpec.Tile.GIF -> if (!busy) gifOpen = true
                        AttachmentPanelSpec.Tile.CONTACT -> if (!busy) contactOpen = true
                        AttachmentPanelSpec.Tile.POLL -> if (!busy) pollOpen = true
                        AttachmentPanelSpec.Tile.VIDEO_NOTE -> if (!busy) videoOpen = true
                        AttachmentPanelSpec.Tile.DISGUISE -> onDisguise?.invoke()
                    }
                },
                onClose = { panelOpen = false },
            )
        }

        val pendingVoice = voicePreview
        if (pendingVoice != null || LocalThreadChromePreview.current.voicePreview) {
            fun stopPlay() { if (voicePlaying) { voice.stopPlayback(); voicePlaying = false } }
            VoicePreviewPanel(
                durationLabel = voice.formatDuration(pendingVoice?.durationMs ?: 7_000L),
                effect = voiceEffect,
                onEffect = { stopPlay(); voiceEffect = it },
                playing = voicePlaying,
                processing = voiceBusy,
                onPlay = {
                    val rec = pendingVoice
                    if (voicePlaying) stopPlay()
                    else if (rec != null) {
                        val e = voiceEffect
                        voiceBusy = true
                        voiceScope.launch {
                            val f = runCatching {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    voiceRenders.getOrPut(e) { voice.previewWav(rec, e) }
                                }
                            }.getOrNull()
                            voiceBusy = false
                            if (f != null) {
                                voicePlaying = true
                                voice.play(f) { voicePlaying = false }
                            }
                        }
                    }
                },
                viewOnce = voiceOnce,
                onViewOnce = if (voiceViewOnceAllowed) ({ voiceOnce = it }) else null,
                onSend = {
                    val rec = pendingVoice
                    stopPlay()
                    if (rec != null) {
                        val e = voiceEffect
                        val once = voiceOnce
                        voiceBusy = true
                        voiceScope.launch {
                            val note = runCatching {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { voice.render(rec, e) }
                            }
                            voiceRenders.values.filter { it != rec.file }.forEach { runCatching { it.delete() } }
                            voiceRenders.clear()
                            voiceBusy = false
                            voicePreview = null
                            voiceEffect = com.oshi.desktop.call.media.CallVoiceEffects.Effect.NONE
                            voiceOnce = false
                            note.onSuccess { voiceProblem = it.warning; onVoiceNote(it.copy(viewOnce = once && voiceViewOnceAllowed)) }
                                .onFailure { voiceProblem = it.message }
                        }
                    }
                },
                onDiscard = {
                    stopPlay()
                    pendingVoice?.let { voice.discardPreview(it, voiceRenders.values.filter { f -> f != it.file }) }
                    voiceRenders.clear()
                    voicePreview = null
                    voiceEffect = com.oshi.desktop.call.media.CallVoiceEffects.Effect.NONE
                    voiceOnce = false
                },
            )
            if (pendingVoice != null) return@Column
        }

        // __PHOTO_MARKUP_DESKTOP_2027__ the picture opens in its own window, big enough to draw on.
        markup?.let { img ->
            androidx.compose.ui.window.DialogWindow(
                onCloseRequest = { markup = null; markupOnce = false },
                // __MERGE_AB_LOCK_HIDES_POPUPS__ its own OS window: hidden (state kept) while locked.
                visible = !com.oshi.desktop.store.AppLock.locked,
                title = t("pc.markup.title"),
                icon = com.oshi.desktop.ui.oshiWindowIcon(),
                state = androidx.compose.ui.window.rememberDialogState(size = com.oshi.desktop.ui.ScreenFit.fitToScreen(androidx.compose.ui.unit.DpSize(1040.dp, 780.dp))),
            ) {
                com.oshi.desktop.ui.OshiMaterialTheme {
                    MarkupEditor(img, onSend = { f -> markup = null; onAttachFile?.invoke(f, true, markupOnce); markupOnce = false }, onCancel = { markup = null; markupOnce = false })
                }
            }
        }
        // __LOCK_STOPS_CAMERA__ the lock closes the camera panel in the same frame: CameraPanel
        // leaves composition, its DisposableEffect closes the session (device released, an
        // unfinished clip dropped, the preview frame was only in its state). The LaunchedEffect
        // below makes it stay closed — on unlock the user opens it again themselves.
        if (cameraOpen && !hiddenByLock && onAttachFile != null) {
            CameraPanel(
                onPhoto = { img -> cameraOpen = false; markup = img },
                onVideo = { f -> onAttachFile(f, true, markupOnce); markupOnce = false },
                onClose = { cameraOpen = false; markupOnce = false },
            )
            return@Column
        }
        if (videoOpen && !hiddenByLock && onVideoNote != null) {
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
                enabled = (composer.attachEnabled || onDisguise != null) && !busy,
                open = whyOpen || panelOpen,
                onClick = {
                    // __ATTACH_PANEL_2027__ "+" opens the panel; it used to open the chooser.
                    if (composer.attachEnabled || onDisguise != null) { whyOpen = false; panelOpen = !panelOpen }
                    else whyOpen = !whyOpen
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
            // __ATTACH_PANEL_2027__ GIF · Poll · Contact · Disguise · Video note live in the
            // "+" panel now (as on both phones); only the voice note stays one click away.
            VoiceButton(recording) {
                if (recording) {
                    recording = false
                    // __VOICE_EFFECTS_DESKTOP_2027__ to the preview (voice, view-once), not straight out.
                    when (val result = voice.stopForPreview()) {
                        is com.oshi.desktop.media.VoicePreviewStop.Captured -> { voiceProblem = null; voicePreview = result.recording }
                        is com.oshi.desktop.media.VoicePreviewStop.Failure -> voiceProblem = result.reason.message
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
/** __ATTACH_PANEL_2027__ The microphone; a red stop square while recording. */
@Composable
private fun VoiceButton(recording: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    val label = if (recording) t("voice.recording_stopped") else t("input.record_voice")
    Box(
        Modifier
            .size(Metrics.actionButton)
            .clip(CircleShape)
            .background(
                when {
                    recording -> OshiTheme.danger.copy(alpha = 0.16f)
                    hovered.value -> OshiTheme.surface
                    else -> Color.Transparent
                }
            )
            .focusRing(CircleShape)
            .clickable(interactionSource = source, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(OshiTheme.danger))
        else GlyphIcon(Glyph.MIC, Ink.strong, 20.dp)
    }
}

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
                onClickLabel = if (enabled) t("input.attach_file") else t("conv.composer.attach.why"),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.PLUS, if (enabled) Ink.strong else Ink.soft, 18.dp,
            Modifier.graphicsLayer { rotationZ = if (open) 45f else 0f })
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
            .then(
                // OLED: the field is raised glass, a shade brighter under the pointer.
                if (OshiTheme.glass != null) Modifier.glass(OshiTheme.radiusXl, OshiTheme.surface, raised = true)
                    .background(if (hovered.value) Color.White.copy(alpha = 0.04f) else Color.Transparent)
                else Modifier.clip(OshiTheme.radiusXl)
                    .background(if (hovered.value) OshiTheme.separator.copy(alpha = 0.12f) else OshiTheme.surface)
            )
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
            // OLED: the send button is the one thing in the composer that glows.
            .brandGlow(CircleShape, radius = 14.dp, active = canSend)
            .then(
                if (canSend || OshiTheme.glass == null) Modifier.clip(CircleShape)
                    .background(if (canSend) OshiTheme.brandGradient else SolidColor(OshiTheme.separator.copy(alpha = 0.35f)))
                else Modifier.glass(CircleShape, OshiTheme.separator, raised = true)
            )
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
                t("conv.composer.disabled.title"),
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
        GlyphIcon(Glyph.PIN, OshiTheme.brand, 15.dp)
        Spacer(Modifier.width(OshiTheme.sm))
        Column(Modifier.weight(1f)) {
            Text(if (pinned.who == "me") t("common.you") else pinned.who, style = OshiTheme.typography.labelMedium, color = OshiTheme.brand)
            Text(pinned.text, style = OshiTheme.typography.bodySmall, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canUnpin) GlyphButton(Glyph.CLOSE, t("message.unpin"), size = 28.dp, glyphSize = 13.dp) { onUnpin() }
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
    onSend: (question: String, options: List<String>, multi: Boolean, anon: Boolean, closesInSeconds: Long?) -> Unit,
    onClose: () -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var multi by remember { mutableStateOf(false) }
    var anon by remember { mutableStateOf(false) }
    // __GROUP_POLLS_2027__ the phones' "Closes" choice; null = no end.
    var closesIn by remember { mutableStateOf<Long?>(null) }

    // The one definition of sendable — see the class note.
    val sendable = remember(question, options) {
        com.oshi.desktop.msg.PollPayload.sanitize(question, options) != null
    }

    Column(
        Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)
            .glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, OshiTheme.separator)
            .padding(OshiTheme.lg),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(OshiTheme.brand.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.POLL, OshiTheme.brand, 16.dp)
            }
            Text(t("poll.create"), style = OshiTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink.strong, modifier = Modifier.weight(1f))
            GlyphButton(Glyph.CLOSE, t("common.close"), shortcut = "Esc", size = 28.dp, glyphSize = 14.dp, onClick = onClose)
        }

        Text(t("poll.question"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
        PollField(
            value = question,
            placeholder = t("poll.question.placeholder"),
            max = com.oshi.desktop.msg.PollPayload.MAX_QUESTION_LENGTH,
            onChange = { question = it },
            strong = true,
        )
        Text(t("poll.options"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
        options.forEachIndexed { i, option ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Box(Modifier.weight(1f)) {
                    PollField(
                        value = option,
                        placeholder = t("poll.option.placeholder", i + 1),
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
                }
                if (options.size > 2 && i != options.lastIndex) {
                    GlyphButton(Glyph.CLOSE, t("poll.remove_option"), size = 26.dp, glyphSize = 12.dp) {
                        options = options.toMutableList().also { it.removeAt(i) }
                    }
                }
            }
        }

        PollSwitchRow(t("poll.multiple_answers"), t("poll.multiple_answers.hint"), multi) { multi = it }
        // ANONYMOUS IS A PRESENTATION PROMISE, NOT A CRYPTOGRAPHIC ONE, and the hint says so
        // in the phones' own words: a ballot still travels from its sender.
        PollSwitchRow(t("poll.anonymous"), t("poll.anonymous.hint"), anon) { anon = it }

        Text(t("poll.duration"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            for ((label, secs) in listOf(
                t("poll.duration.never") to null,
                t("poll.duration.1h") to 3_600L,
                t("poll.duration.24h") to 86_400L,
                t("poll.duration.3d") to 259_200L,
                t("poll.duration.7d") to 604_800L,
            )) {
                val selected = closesIn == secs
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) OshiTheme.brand else Ink.strong,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(OshiTheme.pill)
                        .background(if (selected) OshiTheme.brand.copy(alpha = 0.16f) else OshiTheme.surface)
                        .clickable { closesIn = secs }
                        .padding(horizontal = OshiTheme.md, vertical = 5.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = OshiTheme.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sendable) "" else t("conv.poll.needs"),
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(t("poll.send"), enabled = sendable) { onSend(question, options, multi, anon, closesIn) }
        }
    }
}

/** A labelled switch with its one-line hint, the phones' poll option rows. */
@Composable
private fun PollSwitchRow(label: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).clickable { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
            Text(hint, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = OshiTheme.brand,
                checkedThumbColor = if (OshiTheme.palette.isDark) Color(0xFF14082E) else Color.White,
            ),
        )
    }
}

/** One bounded text box. The cap is enforced on INPUT so the counter can never disagree. */
@Composable
private fun PollField(value: String, placeholder: String, max: Int, onChange: (String) -> Unit, strong: Boolean = false) {
    Box(
        Modifier.fillMaxWidth()
            .clip(OshiTheme.radiusSm)
            .background(OshiTheme.surface)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
    ) {
        val style = if (strong) OshiTheme.typography.bodyLarge else OshiTheme.typography.bodyMedium
        if (value.isEmpty()) {
            Text(placeholder, style = style, color = Ink.soft)
        }
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(max)) },
            singleLine = true,
            textStyle = style.copy(color = Ink.strong),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * __DESKTOP_CONTACT_CARD_COMPOSER_2026_10_29__ Who to send, as people rather than links: the
 * avatar and the name, one click each. Blocked contacts never appear (row 0.21).
 */
@Composable
private fun ContactCardPicker(contacts: List<ContactRow>, onPick: (String) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)
            .glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, OshiTheme.separator)
            .padding(vertical = OshiTheme.sm),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = OshiTheme.lg, end = OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            GlyphIcon(Glyph.PERSON, OshiTheme.brand, 16.dp)
            Column(Modifier.weight(1f)) {
                Text(t("attach.option.contact"), style = OshiTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                Text(t("conv.contact_card.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
            GlyphButton(Glyph.CLOSE, t("common.close"), size = 28.dp, glyphSize = 14.dp, onClick = onClose)
        }
        Spacer(Modifier.height(OshiTheme.xs))
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            for (c in contacts) {
                val (source, hovered) = rememberRowInteraction()
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
                        .clickable(interactionSource = source, indication = null, onClickLabel = c.label) { onPick(c.address) }
                        .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
                ) {
                    Monogram(c.label, c.address, 32.dp)
                    Text(c.label, style = OshiTheme.typography.bodyMedium, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (c.verified) ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, OshiTheme.success, 15.dp)
                }
            }
        }
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
        GlyphIcon(Glyph.LOCK, OshiTheme.brand, 15.dp)
        Spacer(Modifier.width(OshiTheme.sm))
        Column(Modifier.weight(1f)) {
            Text(t("offline_only.summary.on"), style = OshiTheme.typography.labelMedium, color = OshiTheme.brand)
            confirmed?.let { (yes, total) ->
                Text(
                    if (yes >= total && total > 0) t("conv.offline_only.confirmed_all", yes, total)
                    else t("conv.offline_only.confirmed_some", yes, total),
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
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            Row(
                Modifier.weight(1f)
                    .clip(OshiTheme.radiusMd)
                    .background(OshiTheme.surface)
                    .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                GlyphIcon(Glyph.SEARCH, Ink.soft, 15.dp)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(t("chat.search_placeholder"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) GlyphButton(Glyph.CLOSE, t("chat.clear_search"), size = 22.dp, glyphSize = 11.dp) { onQuery("") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            // The count uses the iOS format string, which is "%d/%d" — matches over total.
            Text(
                // The bare `t`, not a qualified call: `CatalogAuditTest`'s suspect scan looks
                // for a literal next to a REGISTERED accessor.
                t("chat.search_results", matches.size, total),
                style = OshiTheme.typography.labelMedium,
                color = if (matches.isEmpty()) OshiTheme.warning else Ink.soft,
                maxLines = 1,
                softWrap = false,
            )
            GlyphButton(Glyph.CLOSE, t("chat.close_search"), shortcut = "Esc", onClick = onClose)
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
                MascotEmptyState(
                    mascot = "thinking",
                    title = if (query.isBlank() && day != null) t("info_no_messages_date") else t("empty.no_search_results"),
                    body = if (query.isBlank()) null else t("conv.search.no_match", query.trim()),
                    size = 88.dp,
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
