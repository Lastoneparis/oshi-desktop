package com.oshi.desktop.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.MessageRow
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * One message row: the bubble, its media, and the footer under it.
 *
 * ============================================================ TRANSCRIBED FROM `ChatView.swift`
 *
 * Max width `min(screenWidth * 0.55, 420)`; content padding 12; radius from
 * `BubbleStyleManager` with the corner on the SENDER'S side of the last bubble in a run
 * tightened to `max(radius * 0.28, 4)`; a 0.5pt rim, `white@0.14` outgoing and `black@0.07`
 * incoming; and the timestamp/checkmark footer OUTSIDE the bubble in the same column, shown
 * on the last bubble of a run or whenever the status is pending, failed or edited.
 *
 * One ordering rule from that file is load-bearing and easy to get wrong: **paint the
 * background before capping the width.** `.frame(maxWidth:)` does not shrink to its child, so
 * capping first makes a one-word message paint a paragraph-wide bubble. The Compose
 * equivalent is `widthIn(max = …)` on a wrap-content box, never `fillMaxWidth`.
 *
 * ============================================================ WHAT IT WILL NOT DRAW
 *
 * A deleted row is read for its DELETE flag before its content, exactly as `ChatShellModel`
 * does — a message its sender revoked is never drawn from `body` even if a body is present.
 * An inbound row carries no delivery state: the model refuses to produce one, because
 * painting our own receipt bookkeeping beside somebody else's bubble reads as a claim about
 * THEIR delivery. And a send that did not leave is red, always: `MessageRow.failed`.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun MessageBubbleRow(
    row: MessageRow,
    grouped: Boolean,
    lastInRun: Boolean,
    wallpaper: WallpaperId,
    viewOnce: Boolean,
    revealed: Boolean,
    onReveal: () -> Unit,
    onReact: ((String) -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** __GROUP_PARITY_2026_09_23__ start a reply to this message (iOS swipe-to-reply). */
    onReply: (() -> Unit)? = null,
    /** Group threads name the sender above the first bubble of each incoming run, as iOS does. */
    showSender: Boolean = false,
    /** __GROUP_PARITY_2026_09_23__ pin/unpin (true = this row is the pinned one). */
    onPin: ((pin: Boolean) -> Unit)? = null,
    pinned: Boolean = false,
    /** Delete on this device only. */
    onDeleteForMe: (() -> Unit)? = null,
    /**
     * Contacts a message can be forwarded to, and the action.
     * __FORWARD_MANY_DESKTOP_2027__ several at once: one sheet, one 1:1 forward per person.
     */
    forwardTargets: List<com.oshi.desktop.ui.state.GroupCandidateRow> = emptyList(),
    onForward: ((toAddresses: List<String>) -> Unit)? = null,
    /** __REPLY_PRIVATELY_DESKTOP_2027__ a group member's message: answer them in a 1:1. */
    onReplyPrivately: (() -> Unit)? = null,
    /** __DESKTOP_POLLS_2026_10_29__ cast or toggle a vote on this row's poll. */
    onVote: ((optionIndex: Int) -> Unit)? = null,
    /** __THREAD_CHROME_2027__ copy the text to the clipboard. */
    onCopy: (() -> Unit)? = null,
    /** __GROUP_COMMENTS_2026_11_21__ drawn under the bubble, at its width (the "💬 N comments" bar). */
    belowBubble: (@Composable () -> Unit)? = null,
    /** __GROUP_COMMENTS_2026_11_21__ the delete confirmation's title (`cm_delete_comment` on a comment). */
    deleteTitle: String? = null,
    /**
     * __BUBBLE_MEDIA_2026_11_21__ A channel post: a card (no tail, incoming colour, ours too),
     * start-aligned, up to 90 % of the thread / 560 dp, its time inside, and [belowBubble] (the
     * comments bar) inside it under a hairline.
     */
    channelPost: Boolean = false,
) {
    val chromePreviewA = LocalThreadChromePreview.current
    var forwarding by remember(row.id) { mutableStateOf(chromePreviewA.forwardMessageId == row.id) }
    var reactionPickerOpen by remember(row.id) { mutableStateOf(false) }
    var editing by remember(row.id) { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }
    var editedBody by remember(row.id, row.body) { mutableStateOf(row.body) }
    var confirmingDelete by remember(row.id) { mutableStateOf(false) }
    // __VIEW_ONCE_DESKTOP_2027__ one decision for the whole row: ours wears a badge, theirs is
    // sealed, open for this visit, or gone ("Viewed") — iOS `MediaMessageView`'s four states.
    val look = com.oshi.desktop.ui.state.ViewOnceRules.look(
        viewOnce = viewOnce || row.viewOnce, fromMe = row.fromMe, opened = row.viewOnceOpened,
        revealedNow = revealed, hasFile = row.attachment != null,
    )
    val media = remember(row.attachment, look) {
        when (look) {
            com.oshi.desktop.ui.state.ViewOnceRules.Look.VIEWED -> null
            com.oshi.desktop.ui.state.ViewOnceRules.Look.SEALED -> MediaPresentation.of(row.attachment, true, false)
            com.oshi.desktop.ui.state.ViewOnceRules.Look.OPEN -> MediaPresentation.of(row.attachment, true, true)
            else -> MediaPresentation.of(row.attachment, false, false)
        }
    }
    var reactorsOpen by remember(row.id) { mutableStateOf(chromePreviewA.reactorsMessageId == row.id) }
    // __THREAD_CHROME_2027__ hover/focus toolbar, right-click menu, keyboard shortcuts.
    val preview = LocalThreadChromePreview.current
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var reactBar by remember(row.id) { mutableStateOf(preview.reactBarMessageId == row.id) }
    var moreMenu by remember(row.id) { mutableStateOf(preview.moreMenuMessageId == row.id) }
    var contextAt by remember(row.id) { mutableStateOf<Offset?>(if (preview.contextMenuMessageId == row.id) Offset(160f, 24f) else null) }
    var infoOpen by remember(row.id) { mutableStateOf(false) }
    // __DESKTOP_PRIVACY_PARITY_2027__ Settings › Privacy › Block Forwarding & Copying — also hides
    // the multi-forward sheet (Mac-parity A); Reply privately is not a forward and stays.
    val blockForwardCopy = com.oshi.desktop.store.PrivacyPreferences.values.blockForwardCopy
    val canForward = !blockForwardCopy && onForward != null && forwardTargets.isNotEmpty() && row.body.isNotBlank() &&
        look == com.oshi.desktop.ui.state.ViewOnceRules.Look.NONE
    val actions = MessageActions(
        onReplyPrivately = onReplyPrivately,
        onReply = onReply,
        onReact = onReact,
        onForward = if (canForward) ({ forwarding = true }) else null,
        onPin = onPin?.let { pin -> { pin(!pinned) } },
        pinned = pinned,
        onCopy = onCopy.takeUnless { blockForwardCopy },
        onEdit = onEdit?.let { { editing = true } },
        onInfo = { infoOpen = true },
        onDelete = if (onDelete != null || onDeleteForMe != null) ({ confirmingDelete = true }) else null,
        onOpenPicker = { reactionPickerOpen = true },
    )
    val showActions = !row.deleted && !editing && actions.any
    val toolbarVisible = showActions && (hovered || focused || reactBar || moreMenu || forwarding || preview.toolbarMessageId == row.id)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // __BUBBLE_MEDIA_2026_11_21__ the real width the row is laid out in, so the bubble cap is
    // a fraction of the thread (75 % / 90 % for channels) and not only a fixed 420 dp.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val maxBubble = BubbleMediaLayout.maxBubbleWidth(
        if (constraints.hasBoundedWidth) maxWidth.value else 10_000f, channelPost,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .onFocusChanged { focused = it.hasFocus }
            .then(
                if (!showActions) Modifier else Modifier
                    .onPointerEvent(PointerEventType.Press) { e ->
                        if (e.buttons.isSecondaryPressed) {
                            val pos = e.changes.firstOrNull()?.position ?: Offset.Zero
                            contextAt = pos
                        }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || editing) return@onPreviewKeyEvent false
                        val meta = e.isMetaPressed || e.isCtrlPressed
                        when {
                            e.key == Key.R && !meta && onReply != null -> { onReply(); true }
                            e.key == Key.E && !meta && onReact != null -> { reactBar = true; true }
                            e.key == Key.F && !meta && canForward -> { forwarding = true; true }
                            e.key == Key.P && !meta && onPin != null -> { onPin(!pinned); true }
                            e.key == Key.I && !meta -> { infoOpen = true; true }
                            e.key == Key.C && meta && onCopy != null -> { onCopy(); true }
                            e.key == Key.Enter && !meta && onEdit != null && row.fromMe -> { editing = true; true }
                            (e.key == Key.Delete || e.key == Key.Backspace) && actions.onDelete != null -> { confirmingDelete = true; true }
                            (e.key == Key.F10 && e.isShiftPressed) || e.key == Key.Menu -> { contextAt = Offset(80f, 16f); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .semantics { contentDescription = listOf(row.who, row.body, row.stamp).filter { it.isNotBlank() }.joinToString(", ") },
            )
            // 2pt when grouped, 10pt otherwise — `ChatView.swift`'s own two values.
            .padding(top = if (grouped) OshiTheme.xxs else 10.dp),
        horizontalAlignment = if (row.fromMe && !channelPost) Alignment.End else Alignment.Start,
    ) {
      Box {
        // The right-click menu opens where the pointer was.
        contextAt?.let { at ->
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.toInt(), at.y.toInt()) }.size(1.dp)) {
                OshiDropdownMenu(expanded = true, onDismissRequest = { contextAt = null }) {
                    MessageActionMenuItems(actions) { contextAt = null }
                }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
        // The toolbar floats on the INNER side of the bubble without taking layout space,
        // so showing it never reflows the thread.
        if (row.fromMe && !channelPost) ToolbarAnchor(toolbarVisible, alignEnd = true, topInset = 0.dp) {
            MessageHoverToolbar(actions, reactBar, { reactBar = it }, moreMenu, { moreMenu = it }) { reactionPickerOpen = it }
        }
        Column(
            Modifier.widthIn(max = maxBubble.dp).weight(1f, fill = false),
            horizontalAlignment = if (row.fromMe && !channelPost) Alignment.End else Alignment.Start,
        ) {
            // __BUBBLE_MEDIA_2026_11_21__ The sender's name is drawn INSIDE the bubble now, in
            // their own hue (BubbleColors.senderNames), above the text or the picture.
            val footerWanted = lastInRun || row.failed || row.edited || row.status == DeliveryStatus.PENDING.wire
            val drewFooter = BubbleBody(
                row, lastInRun, wallpaper, media, onReveal, onVote, look,
                showSender = showSender && !row.fromMe,
                channelPost = channelPost,
                maxWidth = maxBubble,
                footerWanted = footerWanted,
                inside = if (channelPost) belowBubble else null,
            )
            // __BOTS_PHASE1_2026_11_24__ a bot post: BOT badge + its inline keyboard.
            if (!row.fromMe && !row.deleted) LocalBotPostOf.current(row.id)?.let { BotPostChrome(it) }

            if (row.reactionKeys.isNotEmpty() || row.reactions.isNotEmpty()) {
                // __WHO_REACTED_DESKTOP_2027__ the pill opens "Reactions" — who chose what
                // (iOS `ReactorsListView`), with each count beside its glyph as iOS draws it.
                Spacer(Modifier.height(OshiTheme.xs))
                ReactionPill(row, onOpen = if (row.reactors.isNotEmpty()) ({ reactorsOpen = true }) else null)
            }

            if (reactionPickerOpen && onReact != null && !row.deleted) {
                Spacer(Modifier.height(OshiTheme.xs))
                MascotReactionPicker(onReact, onClose = { reactionPickerOpen = false })
            }

            if (forwarding && canForward && onForward != null) {
                ForwardSheet(forwardTargets, onSend = { onForward(it) }, onDismiss = { forwarding = false })
            }

            if (editing && onEdit != null) {
                Spacer(Modifier.height(OshiTheme.xs))

                // __SHOW_WHAT_IS_BEING_EDITED_2026_11_05__ The BEFORE text,
                // labelled, and only once the draft has diverged from it.
                //
                // The original bubble does sit directly above this field, which
                // is better than the phones managed — but it is unlabelled, and
                // the moment the user starts typing, the field below it is the
                // only copy of what they are replacing. Keeping the "before"
                // explicitly means they can still check the change.
                if (editedBody != row.body && row.body.isNotEmpty()) {
                    Text(
                        t("edit.original_label"),
                        style = OshiTheme.typography.labelMedium,
                        color = Ink.soft,
                    )
                    Text(
                        row.body,
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(OshiTheme.xs))
                }

                // __DESKTOP_EDIT_FIELD_HAD_NO_AFFORDANCE_2026_11_05__ A bare
                // `BasicTextField` with no border, background or placeholder
                // read as the bubble's own text with a caret somewhere in it —
                // the user could not tell an edit box had opened, and had to
                // guess where to click. Given a surface, a border and focus.
                BasicTextField(
                    value = editedBody,
                    onValueChange = { editedBody = it },
                    textStyle = OshiTheme.typography.bodyMedium.copy(color = OshiTheme.textPrimary),
                    cursorBrush = SolidColor(OshiTheme.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OshiTheme.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .border(1.dp, OshiTheme.brand.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xs)
                        .focusRequester(editFocus),
                )
                // Focus on open, so the caret is in the box the user just asked
                // for rather than requiring a second click to find it.
                LaunchedEffect(editing) { if (editing) runCatching { editFocus.requestFocus() } }

                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    // Save refuses an empty or unchanged body, matching
                    // `MessageEditSheet` on iOS: an unchanged save still
                    // broadcasts an edit and stamps "edited" on everyone's copy.
                    val canSave = editedBody.isNotBlank() && editedBody != row.body
                    if (canSave) {
                        TextAction(t("common.save")) { onEdit(editedBody); editing = false }
                    } else {
                        Text(
                            t("common.save"),
                            style = OshiTheme.typography.labelMedium,
                            color = Ink.soft.copy(alpha = 0.4f),
                        )
                    }
                    TextAction(t("common.cancel")) { editedBody = row.body; editing = false }
                }
            }

            if (footerWanted && !drewFooter) {
                Spacer(Modifier.height(OshiTheme.xs))
                Footer(row)
            }
            if (row.restrictedNetwork && !row.fromMe && !row.deleted) {
                Spacer(Modifier.height(OshiTheme.xs))
                RestrictedNetworkChip()
            }
            if (!channelPost) belowBubble?.invoke()
        }
        if (!row.fromMe || channelPost) ToolbarAnchor(toolbarVisible, alignEnd = false, topInset = 0.dp) {
            MessageHoverToolbar(actions, reactBar, { reactBar = it }, moreMenu, { moreMenu = it }) { reactionPickerOpen = it }
        }
        }
      }
    }
    }

    if (infoOpen) MessageInfoDialog(row) { infoOpen = false }
    if (reactorsOpen) ReactorsDialog(row.reactors) { reactorsOpen = false }
    if (confirmingDelete) {
        ConfirmDestructiveDialog(
            title = deleteTitle ?: t("message.delete.confirm_title"),
            text = t("message.delete.confirm_message"),
            confirmLabel = if (onDelete != null) t("group.delete.for_everyone") else t("group.delete.for_me"),
            onConfirm = { (onDelete ?: onDeleteForMe)?.invoke() },
            onDismiss = { confirmingDelete = false },
            secondaryLabel = if (onDelete != null && onDeleteForMe != null) t("group.delete.for_me") else null,
            onSecondary = if (onDelete != null) onDeleteForMe else null,
        )
    }
}

/**
 * A zero-size anchor beside the bubble: the toolbar is measured and drawn, but takes no
 * space, so it can appear on hover without moving anything. [alignEnd] = it hangs to the
 * LEFT of the anchor (outgoing bubbles), otherwise to the right.
 */
@Composable
private fun ToolbarAnchor(visible: Boolean, alignEnd: Boolean, topInset: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    if (!visible) return
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val top = with(androidx.compose.ui.platform.LocalDensity.current) { topInset.roundToPx() }
    Box(
        Modifier
            .zIndex(2f)
            .layout { measurable, _ ->
                val p = measurable.measure(androidx.compose.ui.unit.Constraints())
                layout(0, 0) { p.place(if (alignEnd) -p.width - gap else gap, top) }
            },
    ) { content() }
}

/**
 * __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1: the discreet chip under an incoming bubble
 * whose sender had the disguise on ("Sent through a restricted network"). Click (or hover)
 * opens the explanation; it never says more than the sender chose to share.
 */
@Composable
fun RestrictedNetworkChip(initiallyOpen: Boolean = false) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(Modifier.widthIn(max = Metrics.bubbleMax)) {
        Row(
            Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .background(Ink.soft.copy(alpha = 0.10f))
                .clickable { open = !open }
                .padding(horizontal = OshiTheme.sm, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
        ) {
            GlyphIcon(Glyph.EYE_SLASH, Ink.soft, 12.dp)
            Text(t("rnhint.chip"), fontSize = 10.sp, color = Ink.soft)
        }
        if (open) {
            Text(
                t("rnhint.explain"),
                fontSize = 11.sp,
                color = Ink.soft,
                modifier = Modifier.padding(top = OshiTheme.xs, start = OshiTheme.xs),
            )
        }
    }
}

// ---------------------------------------------------------------------------- the bubble

/**
 * __BUBBLE_MEDIA_2026_11_21__ Whether [media] is drawn EDGE TO EDGE: a photo, a GIF or a video
 * poster fills the bubble's width with no padding. Everything else (file and voice cards, the
 * sealed view-once card, a round video note) keeps the padded inside of a bubble.
 */
private fun isFullBleed(row: MessageRow, media: MediaPresentation?): Boolean =
    media != null && !media.sealedViewOnce && !media.unparseable &&
        (media.inlineImage || media.posterVideo) &&
        !(row.videoNote && media.kind == com.oshi.desktop.store.MediaType.VIDEO)

/** The caption is only the file's NAME (a file sent with no caption stores its name as the body). */
private fun isNameOnly(row: MessageRow, media: MediaPresentation?): Boolean =
    media != null && row.body.isNotBlank() &&
        (media.fileName == row.body || media.fileName.endsWith("-" + row.body))

/**
 * The bubble. Returns whether it drew the time/ticks itself (inside a channel card, or on the
 * picture of a media-only bubble), so the row does not draw them a second time under it.
 *
 * Layout (top to bottom), each block with its own padding so a picture can run edge to edge:
 * the sender's name and the forwarded/reply header; the media (full-bleed, or a padded card);
 * the caption/body; for a channel card, its time and then the comments bar under a hairline.
 */
@Composable
private fun BubbleBody(
    row: MessageRow,
    lastInRun: Boolean,
    wallpaper: WallpaperId,
    media: MediaPresentation?,
    onReveal: () -> Unit,
    onVote: ((optionIndex: Int) -> Unit)? = null,
    look: com.oshi.desktop.ui.state.ViewOnceRules.Look = com.oshi.desktop.ui.state.ViewOnceRules.Look.NONE,
    showSender: Boolean = false,
    channelPost: Boolean = false,
    maxWidth: Float = Metrics.bubbleMax.value,
    footerWanted: Boolean = true,
    inside: (@Composable () -> Unit)? = null,
): Boolean {
    // __VIEW_ONCE_DESKTOP_2027__ opened before: the bytes are gone, only the fact remains.
    if (look == com.oshi.desktop.ui.state.ViewOnceRules.Look.VIEWED) {
        ViewOnceViewedPill()
        return false
    }
    // __VIDEO_NOTE_2026_09_24__ A round video note has no bubble, no tail and no caption box
    // (VIDEO_NOTE_SPEC §4): the circle IS the message. A sealed view-once row keeps the
    // ordinary sealed card below — the seal is decided in one place, `MediaPresentation`.
    if (row.videoNote && !row.deleted && media != null && media.kind == com.oshi.desktop.store.MediaType.VIDEO &&
        !media.sealedViewOnce && !media.unparseable
    ) {
        Column(horizontalAlignment = if (row.fromMe) Alignment.End else Alignment.Start) {
            VideoNoteBubble(
                path = media.path,
                announcedDurationMs = row.mediaDurationMs,
                missingNote = if (media.note == MediaPresentation.MISSING_FILE_NOTE) t("conv.videonote.missing") else null,
            )
            if (row.body.isNotBlank()) {
                Text(row.body, style = OshiTheme.typography.bodyMedium, color = Ink.strong, modifier = Modifier.padding(top = OshiTheme.xs))
            }
        }
        return false
    }
    val bubbles = OshiTheme.bubbles
    // A channel post is a card in the incoming colour, ours included (BUBBLE_MEDIA_SPEC §2).
    val mine = row.fromMe && !channelPost
    val shape = if (channelPost) OshiTheme.radiusBubble else bubbleShape(row.fromMe, lastInRun)
    val fill = if (mine) bubbles.outgoing else WallpaperPainter.incomingFill(wallpaper)
    val rim = if (mine) bubbles.outgoingRim else bubbles.incomingRim
    val ink = if (mine) bubbles.outgoingText else OshiTheme.bubbleIncomingText
    val inkSoft = if (mine) bubbles.outgoingTextSoft else Ink.soft
    val accent = if (mine) bubbles.outgoingAccent else OshiTheme.brand
    // Cards drawn INSIDE our bubble use light-on-dark tints only when the bubble is dark.
    val onDarkBubble = mine && bubbles.outgoingIsDark

    // __OLED_GLASS_2026_11_16__ With glass on and no wallpaper, the incoming bubble is raised
    // glass on the thread, and ours gets a rim lit from above instead of a flat one. Both are
    // a gradient and a stroke: nothing per-row is blurred.
    val glass = OshiTheme.glass
    val surfaceModifier = when {
        glass == null -> Modifier.clip(shape).background(fill).border(Metrics.hairline, rim, shape)
        !mine && wallpaper == WallpaperId.NONE -> Modifier.glass(shape, fill, raised = true)
        else -> Modifier.clip(shape).background(fill).border(
            Metrics.hairline,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (bubbles.outgoingIsDark) 0.30f else 0.70f), rim)),
            shape,
        )
    }

    val nameOnly = isNameOnly(row, media)
    val fullBleed = isFullBleed(row, media)
    val mediaSize: BubbleMediaLayout.Size? = if (fullBleed && media != null) {
        val natural = rememberNaturalSize(media)
        if (channelPost) BubbleMediaLayout.cardMediaSize(natural?.first, natural?.second, maxWidth)
        else BubbleMediaLayout.mediaSize(natural?.first, natural?.second, maxWidth)
    } else null
    val hasForward = !row.deleted && row.forwardedFrom != null
    val hasQuote = !row.deleted && row.quote != null
    val hasHeader = showSender || hasForward || hasQuote
    val bodyShown = row.poll == null && row.location == null && !nameOnly && (row.body.isNotBlank() || media == null)
    val senderBadge = media != null && look == com.oshi.desktop.ui.state.ViewOnceRules.Look.SENDER
    // A media-only bubble carries its time ON the picture; a channel card carries it inside.
    val mediaOnly = fullBleed && !bodyShown && !senderBadge && row.poll == null && row.location == null
    val pillFooter = mediaOnly && (footerWanted || channelPost)
    val cardFooter = channelPost && !mediaOnly
    val hasBody = bodyShown || row.poll != null || row.location != null || senderBadge || cardFooter
    val pad = OshiTheme.md

    val widthModifier = when {
        mediaSize != null -> Modifier.width(mediaSize.width.dp)
        // A channel post is a column of cards of one width, like a feed.
        channelPost -> Modifier.width(maxWidth.dp)
        else -> Modifier
    }
    Column(
        Modifier.then(widthModifier).then(surfaceModifier),
        horizontalAlignment = Alignment.Start,
    ) {
        if (hasHeader) {
            Column(
                Modifier.padding(
                    start = pad, end = pad,
                    top = if (showSender) OshiTheme.sm else pad,
                    bottom = if (fullBleed || (media != null && !hasBody)) OshiTheme.sm - 2.dp else 0.dp,
                ),
            ) {
                if (showSender) {
                    Text(
                        row.who,
                        style = OshiTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = bubbles.senderColor(row.senderAddress.ifBlank { row.who }),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasForward || hasQuote) Spacer(Modifier.height(OshiTheme.xxs))
                }
                // __GROUP_PARITY_2026_09_23__ forwarded label and reply quote, as iOS draws them
                // (`QuotedMessageView` / `groupReplyQuote`): above the body, inside the bubble.
                if (hasForward) {
                    Text(
                        if (row.forwardedFrom!!.isBlank()) t("message.forwarded") else t("message.forwarded") + " · " + row.forwardedFrom,
                        style = OshiTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = inkSoft,
                    )
                    if (hasQuote) Spacer(Modifier.height(OshiTheme.xxs))
                }
                if (hasQuote) {
                    QuoteBlock(row.quote!!, if (mine) bubbles.outgoingAccent else bubbles.senderColor(row.quote.who), ink, inkSoft)
                }
            }
        }
        if (media != null) {
            if (fullBleed && mediaSize != null) {
                Box {
                    BleedMedia(media, mediaSize, onDarkBubble, inkSoft)
                    if (pillFooter) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(OshiTheme.sm - 2.dp)) { Footer(row, onMedia = true) }
                    }
                }
            } else {
                Box(
                    Modifier.padding(
                        start = pad, end = pad,
                        top = if (hasHeader) OshiTheme.sm - 2.dp else pad,
                        bottom = if (hasBody) 0.dp else pad,
                    ),
                ) {
                    MediaContent(media, onDarkBubble, inkSoft, onReveal, row.mediaDurationMs)
                }
            }
        }
        if (hasBody) {
            Column(
                Modifier.padding(
                    start = pad, end = pad,
                    top = when {
                        fullBleed -> OshiTheme.sm
                        media != null -> OshiTheme.sm
                        hasHeader -> OshiTheme.sm - 2.dp
                        else -> pad
                    },
                    bottom = if (cardFooter) OshiTheme.sm else pad,
                ),
            ) {
                if (senderBadge) {
                    ViewOnceBadge(onBrand = onDarkBubble)
                    if (bodyShown) Spacer(Modifier.height(OshiTheme.sm))
                }
                // __DESKTOP_POLLS_2026_10_29__ A poll draws a card instead of a body. `row.body`
                // is blank for a poll (the model empties it), so without this the bubble would be
                // an empty box.
                row.poll?.let { card -> PollCard(card, onDarkBubble, ink, inkSoft, accent, onVote) }
                // __DESKTOP_1_6_PARITY__ a shared place: a map card, never "📍 Location: …" in English.
                row.location?.let { LocationCard(it, row.fromMe, ink, inkSoft) }
                if (bodyShown) {
                    // __MENTIONS_2026_09_23__ `@name` of a picked member is highlighted (MentionUi.kt).
                    MentionAwareText(
                        row.body,
                        if (row.deleted) emptyList() else row.mentions,
                        style = OshiTheme.typography.bodyLarge,
                        color = if (row.deleted) inkSoft else ink,
                        fontStyle = if (row.deleted) FontStyle.Italic else FontStyle.Normal,
                        tint = accent,
                    )
                    // __TICKER_QUOTES_2026_09_23__ `@AAPL` → price chip, under the text, 1:1 and groups (TickerChip.kt).
                    // A mentioned person's `@name` is never offered to the ticker parser.
                    if (!row.deleted) TickerChipsForText(row.body, row.id, Modifier.padding(top = OshiTheme.xs), tint = accent, ink = ink,
                        exclude = mentionRanges(row.body, row.mentions), autoFetch = row.fromMe)
                    // __CASHU_2026_10_24__ eCash card, same place as the ticker chips.
                    // Nothing is drawn unless a Cashu token actually DECODES in the
                    // body: a bubble must never offer to redeem a sentence.
                    if (!row.deleted) CashuTokenCardsForText(row.body, Modifier.padding(top = OshiTheme.xs))
                }
                if (cardFooter) {
                    Box(Modifier.fillMaxWidth().padding(top = OshiTheme.xs), contentAlignment = Alignment.CenterEnd) { Footer(row) }
                }
            }
        }
        if (inside != null) {
            Box(Modifier.fillMaxWidth().height(Metrics.hairline).background(OshiTheme.separator.copy(alpha = OshiTheme.separator.alpha * 0.7f)))
            inside()
        }
    }
    return pillFooter || cardFooter
}

/**
 * __BUBBLE_MEDIA_2026_11_21__ A reply quote: a tint of the bubble with a 3 px bar in the quoted
 * person's name colour (BUBBLE_MEDIA_SPEC §2), the name in that colour, the text soft.
 */
@Composable
private fun QuoteBlock(quote: com.oshi.desktop.ui.state.QuoteRow, bar: Color, ink: Color, inkSoft: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ink.copy(alpha = 0.06f))
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Column(Modifier.padding(start = OshiTheme.sm, end = OshiTheme.sm, top = OshiTheme.xs, bottom = OshiTheme.xs)) {
            if (quote.who.isNotBlank()) {
                Text(quote.who, style = OshiTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = bar, maxLines = 1)
            }
            Text(quote.text, style = OshiTheme.typography.bodySmall, color = inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The picture's natural size in pixels, read without decoding: the image header for a photo or
 * a GIF (one decrypted chunk, memoised in [InlineBitmaps]), the decoded poster for a video once
 * it is cached. Null while unknown — [BubbleMediaLayout] then reserves a 4:3 box.
 */
@Composable
private fun rememberNaturalSize(media: MediaPresentation): Pair<Int, Int>? {
    if (media.posterVideo) {
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { BubbleMediaLayout.decodeWidth.roundToPx() }
        val thumb by androidx.compose.runtime.produceState(
            com.oshi.desktop.media.VideoNoteThumbnails.cached(media.path, px, com.oshi.desktop.media.VideoNoteReader.Fit.CONTAIN),
            media.path, px,
        ) {
            if (value != null) return@produceState
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.oshi.desktop.media.VideoNoteThumbnails.load(media.path, px, com.oshi.desktop.media.VideoNoteReader.Fit.CONTAIN)
            }
        }
        return thumb?.image?.let { it.width to it.height } ?: (16 to 9)
    }
    return remember(media.path) { InlineBitmaps.size(media.path) }
}

/** __BUBBLE_MEDIA_2026_11_21__ The edge-to-edge picture: photo, GIF, or video poster. */
@Composable
private fun BleedMedia(media: MediaPresentation, size: BubbleMediaLayout.Size, onDarkBubble: Boolean, inkSoft: Color) {
    val w = size.width.dp
    val h = size.height.dp
    when {
        media.inlineImage && remember(media.path) { GifBubble.isGifFile(media.path) } -> GifInlineImage(media, w, h, inkSoft)
        media.inlineImage -> InlineImage(media, w, h, inkSoft)
        else -> VideoPoster(media, w, h, onDarkBubble, inkSoft)
    }
}

/**
 * `BubbleOutline`'s rule: full radius everywhere except the corner on the sender's side of
 * the last bubble in a run, which drops to `max(radius * 0.28, 4)` — 5dp at radius 18.
 * Leading/trailing on the phone so it mirrors under RTL; start/end here for the same reason.
 */
private fun bubbleShape(fromMe: Boolean, lastInRun: Boolean): Shape {
    // Not `lastInRun` — the token itself, so the ordinary case cannot drift from the theme.
    if (!lastInRun) return OshiTheme.radiusBubble
    val r = BUBBLE_RADIUS
    val tail = BUBBLE_TAIL
    return if (fromMe) RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = tail, bottomStart = r)
    else RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = r, bottomStart = tail)
}

/** `OshiTheme.radiusBubble`'s corner, restated as a Dp because a Shape cannot be taken apart. */
private val BUBBLE_RADIUS = 18.dp

/** `BubbleOutline`: `max(radius * 0.28, 4)` — 5.04 at 18, rounded to the point. */
private val BUBBLE_TAIL = 5.dp

// ---------------------------------------------------------------------------- footer

/**
 * __GROUP_RESEND_2026_11_25__ "Retry now" for a group message some members have not received;
 * provided by the window around the thread, null elsewhere (the label is then not clickable).
 */
val LocalGroupDeliveryRetry = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
private fun Footer(row: MessageRow, onMedia: Boolean = false) {
    // __BUBBLE_MEDIA_2026_11_21__ On a media-only bubble the time sits ON the picture, white in
    // a translucent dark pill (white on 55 % black over pure white is 4.6:1, the worst case).
    val soft = if (onMedia) Color.White else Ink.soft
    Row(
        modifier = if (!onMedia) Modifier else Modifier
            .clip(OshiTheme.pill)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        if (row.edited) {
            Text(t("message.edited"), fontSize = 10.sp, fontStyle = FontStyle.Italic, color = soft)
        }
        Text(DayBreaks.timeOf(row.stamp), fontSize = if (onMedia) 11.sp else 10.sp, color = soft)
        // Only outgoing rows carry a status; the model refuses to produce one for inbound.
        val partial = row.partialDelivery
        if (partial != null) {
            // __GROUP_RESEND_2026_11_25__ a clock and "Sent to X of Y" instead of the Sent check;
            // hover = who is still waiting, click = Retry now.
            val retry = LocalGroupDeliveryRetry.current
            GlyphIcon(if (partial.gaveUp) Glyph.ALERT else Glyph.CLOCK, if (partial.gaveUp) OshiTheme.danger else soft, 12.dp)
            WithTooltip(partial.detail + if (retry != null) "\n" + t("group.retry_now") else "") {
                Text(
                    partial.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (partial.gaveUp) OshiTheme.danger else soft,
                    modifier = if (retry == null) Modifier else Modifier.clickable { retry(row.id) },
                )
            }
        } else row.status?.let { DeliveryMark(it, row.failed, if (onMedia) Color.White else null) }
        // __PRIVACY_DISGUISE_2026_11_18__ The phones' words ("Waiting for disguised connection"):
        // a clock alone would read as "sending", and this row is deliberately NOT being sent
        // any other way.
        if (row.coverWaiting) {
            Text(
                t("conv.cover.waiting"),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = OshiTheme.brand,
            )
        }
        // __LORA_AIRTIME_HINT_2026_11_18__ Android's words; the radio is obeying the law, not stuck.
        if (row.awaitingAirtime) {
            Text(
                t("conv.lora.waiting_airtime"),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.soft,
            )
        }
        if (row.failed) {
            Text(
                t("desktop.bubble.not_delivered"),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                // `danger` (#FF3B30) is 3.77:1 on white and is the phone's own failure hue.
                // It is paired with a glyph and the word, never colour alone.
                color = OshiTheme.danger,
            )
        }
    }
}

/**
 * `deliveryStatusIcon`, transcribed: clock, one check, two checks, two checks in green, and a
 * red alert. `MessageRow.status` is the wire string, so an unrecognised value draws NOTHING
 * rather than guessing — `DeliveryStatus.fromWire` falls back to DELIVERED for storage
 * purposes, and inheriting that fallback into a checkmark would claim a delivery nobody saw.
 */
@Composable
private fun DeliveryMark(wire: String, failed: Boolean, onMedia: Color? = null) {
    if (failed) {
        GlyphIcon(Glyph.ALERT, OshiTheme.danger, 12.dp)
        return
    }
    val soft = onMedia ?: Ink.soft
    when (wire) {
        DeliveryStatus.PENDING.wire -> GlyphIcon(Glyph.CLOCK, soft, 12.dp)
        DeliveryStatus.SENT.wire -> GlyphIcon(Glyph.CHECK, soft, 12.dp)
        DeliveryStatus.DELIVERED.wire -> GlyphIcon(Glyph.DOUBLE_CHECK, soft, 14.dp)
        // __BUBBLE_MEDIA_2026_11_21__ `readTick`: #34C759 measured 2:1 on the light thread.
        DeliveryStatus.READ.wire -> GlyphIcon(Glyph.DOUBLE_CHECK, onMedia ?: OshiTheme.bubbles.readTick, 14.dp)
        DeliveryStatus.FAILED.wire -> GlyphIcon(Glyph.ALERT, OshiTheme.danger, 12.dp)
        else -> Unit
    }
}

// ---------------------------------------------------------------------------- media

@Composable
private fun MediaContent(
    media: MediaPresentation,
    fromMe: Boolean,
    inkSoft: Color,
    onReveal: () -> Unit,
    durationMs: Long? = null,
) {
    when {
        media.sealedViewOnce -> SealedViewOnce(media, onReveal)
        media.kind == com.oshi.desktop.store.MediaType.AUDIO && !media.unparseable ->
            VoiceNoteCard(media, durationMs, fromMe, inkSoft)
        // Photos, GIFs and posters are drawn edge to edge by `BleedMedia`; nothing reaches here
        // with one, but a file card is the honest fallback if it ever does.
        else -> FileCard(media, fromMe, inkSoft)
    }
}

/**
 * __VOICE_NOTE_CARD_2026_11_21__ A voice note as a voice note: a round play button, "Voice
 * Message", and its length — what both phones draw — instead of the generic file card, which
 * showed "Voice note — click to play", a UUID file name and the full path on disk (seen in the
 * running app). A click opens the same player as before ([LocalMediaOpener]: decrypted to a
 * temporary file only while it plays). The length is the row's when it has one, otherwise read
 * once from the file's own header (rows stored before durations were recorded).
 */
@Composable
private fun VoiceNoteCard(media: MediaPresentation, durationMs: Long?, fromMe: Boolean, inkSoft: Color) {
    val opener = LocalMediaOpener.current
    val target = remember(media) { MediaViewerTarget.of(media) }
    val missing = media.note == MediaPresentation.MISSING_FILE_NOTE
    val length by androidx.compose.runtime.produceState(durationMs, media.path, durationMs) {
        if (value == null && !missing) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VoiceNoteLengths.of(media.path) }
        }
    }
    val label = t("audio.voice_message")
    val shown = com.oshi.desktop.ui.state.ListPreview.duration(length)
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .widthIn(min = 200.dp, max = Metrics.bubbleMax)
            .clip(OshiTheme.radiusSm)
            .background(
                when {
                    fromMe -> Color.White.copy(alpha = if (hovered.value) 0.22f else 0.14f)
                    else -> OshiTheme.brand.copy(alpha = if (hovered.value) 0.14f else 0.08f)
                },
            )
            .clickable(source, null, enabled = target != null && !missing, onClickLabel = t("conv.media.play_voice")) { target?.let(opener::open) }
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.sm)
            .semantics { contentDescription = listOfNotNull(label, shown).joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (fromMe) Color.White else OshiTheme.brandFill),
            contentAlignment = Alignment.Center,
        ) { PlayGlyph(if (fromMe) OshiTheme.brandFill else Color.White, 16.dp) }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(OshiTheme.xxs)) {
            Text(
                label,
                style = OshiTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (fromMe) Ink.onBrandStrong else Ink.strong,
            )
            Text(
                if (missing) MediaPresentation.MISSING_FILE_NOTE else shown ?: "–:––",
                fontSize = 12.sp,
                color = if (fromMe) Ink.onBrandSoft else inkSoft,
            )
        }
        GlyphIcon(Glyph.MIC, if (fromMe) Ink.onBrandSoft else inkSoft, 16.dp)
    }
}

/** __VOICE_NOTE_CARD_2026_11_21__ Lengths read off sealed voice-note files, once per path. */
internal object VoiceNoteLengths {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun of(path: String): Long? {
        cache[path]?.let { return it.takeIf { v -> v > 0 } }
        val ms = runCatching {
            val bytes = com.oshi.desktop.store.MediaVault.readAny(File(path), 32L * 1024 * 1024)
            com.oshi.desktop.media.AudioDurationProbe.of(com.oshi.desktop.media.ArrayWindow(bytes))?.durationMs
        }.getOrNull() ?: 0L
        cache[path] = ms
        return ms.takeIf { it > 0 }
    }
}

/**
 * A view-once row before it is opened.
 *
 * The phone draws the picture at `blur(radius: 30)` behind an overlay. This does not decode
 * the file at all until [onReveal] — a blurred bitmap is still a bitmap in the window's
 * buffer, and "must not render until deliberately opened" is a stronger and cheaper promise
 * to keep than "render it softly". Nothing is loaded from disk while this is on screen.
 *
 * The gesture is a button with a label, not a bare tap on an image: on the phone a tap is
 * unambiguous, and on a desktop a pointer moves over a bubble constantly.
 */
@Composable
private fun SealedViewOnce(media: MediaPresentation, onReveal: () -> Unit) {
    Column(
        Modifier
            .width(Metrics.mediaBubble)
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.brandGradient)
            .padding(OshiTheme.md),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(OshiTheme.md))
        GlyphIcon(Glyph.EYE_SLASH, Color.White, 34.dp)
        Text(t("chat.view_once"), color = Color.White, style = OshiTheme.typography.titleMedium)
        Text(
            t("desktop.bubble.view_once_hidden", MediaPresentation.cardLabel(media.kind)),
            color = Ink.onBrandSoft,
            fontSize = 11.sp,
        )
        Text(
            t("desktop.bubble.reveal"),
            // On a WHITE pill in every theme, so the fill violet, not the dark-mode ink.
            color = OshiTheme.brandFill,
            style = OshiTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(OshiTheme.pill)
                .background(Color.White)
                .clickable(onClick = onReveal)
                .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
        )
        Text(
            MediaPresentation.SEALED_NOTE,
            color = Ink.onBrandSoft,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(OshiTheme.xxs))
    }
}

/**
 * The decrypted file, drawn edge to edge at [w] × [h] (from [BubbleMediaLayout]), centre-cropped.
 *
 * __BUBBLE_MEDIA_2026_11_21__ It was a fixed 250 dp picture with its file name printed under
 * it in monospace, inside a 12 dp-padded bubble: a frame of bubble colour round every photo and,
 * under a caption, a band beside it. The name stays in the accessibility label and the viewer.
 *
 * A decode that throws — a truncated write, a format skia does not carry — falls back to the
 * file card and says so; it never leaves a blank box, and it never takes the frame down.
 */
@Composable
private fun InlineImage(media: MediaPresentation, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, inkSoft: Color) {
    // __PHOTO_DECODE_OFF_UI_2026_09_23__ The read + decrypt + decode used to run INSIDE
    // `remember`, i.e. on the UI thread during composition — and a LazyColumn forgets a row
    // that scrolls away, so it ran again every time a photo came back into view. Measured with
    // `FluidityBench` (60 sealed 4032×3024 phone photos): opening that chat held ONE frame for
    // 194 ms, and scrolling spiked to 62 ms per photo entering. It now decodes on IO, is
    // downscaled to the widest bubble's pixel width, and is kept in a small cache so scrolling
    // back costs nothing. Until it lands, a placeholder holds EXACTLY the space the picture will
    // take: its size comes from the image header (one 64 KB chunk decrypted, no decode) — the
    // thread's LazyColumn anchors its first visible row, so rows that change height after
    // arriving pushed the newest messages off the bottom of a chat that had just opened.
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { BubbleMediaLayout.decodeWidth.roundToPx() }
    val loaded by androidx.compose.runtime.produceState(
        InlineBitmaps.cached(media.path, targetPx) ?: InlineBitmaps.LOADING, media.path, targetPx,
    ) {
        if (value !== InlineBitmaps.LOADING) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { InlineBitmaps.load(media.path, targetPx) }
    }
    if (loaded === InlineBitmaps.LOADING) {
        Box(Modifier.size(w, h).background(inkSoft.copy(alpha = 0.12f)))
        return
    }
    val bitmap: ImageBitmap? = (loaded as? InlineBitmaps.Result.Ready)?.bitmap
    if (bitmap == null) {
        Box(Modifier.width(w).padding(OshiTheme.md)) { FileCard(media, fromMe = false, inkSoft = inkSoft, overrideNote = DECODE_FAILED) }
        return
    }
    // Clicking the picture opens the full-size viewer. `MediaViewerTarget.of` returns null for
    // a sealed view-once row, which is what disables the click — the seal is enforced in ONE
    // place rather than re-decided at every call site.
    val opener = LocalMediaOpener.current
    val target = remember(media) { MediaViewerTarget.of(media) }
    Image(
        bitmap = bitmap,
        contentDescription = media.fileName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(w, h)
            .clickable(enabled = target != null) { target?.let(opener::open) },
    )
}

/**
 * A video's FIRST FRAME in the bubble, edge to edge, with a play badge and its length in a
 * pill — what the phones show.
 *
 * The decode is off the UI thread and the result is cached by path AND fit: a thread is a
 * `LazyColumn` and it forgets a row that scrolls away, so a poster with no cache would
 * re-decrypt and re-decode a video every time it came back into view.
 *
 * While the frame is still decoding this holds the final box (16:9 until the frame says
 * otherwise) rather than collapsing to nothing.
 */
@Composable
private fun VideoPoster(media: MediaPresentation, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, fromMe: Boolean, inkSoft: Color) {
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { BubbleMediaLayout.decodeWidth.roundToPx() }
    val fit = com.oshi.desktop.media.VideoNoteReader.Fit.CONTAIN
    val thumb by androidx.compose.runtime.produceState(
        com.oshi.desktop.media.VideoNoteThumbnails.cached(media.path, targetPx, fit), media.path, targetPx,
    ) {
        if (value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.oshi.desktop.media.VideoNoteThumbnails.load(media.path, targetPx, fit)
        }
    }

    val opener = LocalMediaOpener.current
    val target = remember(media) { MediaViewerTarget.of(media) }
    val bitmap = remember(thumb) { thumb?.image?.toImageBitmap() }

    // A video this window cannot decode falls back to the card it used to be, with the
    // reason on it. Silently drawing an empty box would be worse than the card ever was.
    if (thumb?.error != null) {
        Box(Modifier.width(w).padding(OshiTheme.md)) { FileCard(media, fromMe, inkSoft, overrideNote = t("conv.media.no_preview")) }
        return
    }

    Box(
        Modifier
            .size(w, h)
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = target != null) { target?.let(opener::open) },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = media.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(w, h),
            )
        }
        // The badge is drawn whether or not the frame has landed — it is what says this
        // row is a video rather than a photograph, and that is true before the decode.
        Box(
            Modifier
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(OshiTheme.sm),
        ) {
            PlayGlyph(Color.White, 20.dp)
        }
        thumb?.durationMs?.let {
            Text(
                "%d:%02d".format(it / 60_000, (it / 1000) % 60),
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(OshiTheme.sm - 2.dp)
                    .clip(OshiTheme.pill).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Inline photos, decoded off the UI thread and downscaled — see [InlineImage].
 *
 * Keyed by path AND target width: a density change must not serve a bitmap sized for another
 * screen. A FAILED decode is cached too, so an undecodable file is not re-read on every scroll.
 * Bounded (LRU): at the default bubble width a cached photo is ~1.7 MB, not ~48 MB.
 */
internal object InlineBitmaps {
    sealed class Result {
        object Loading : Result()
        object Failed : Result()
        class Ready(val bitmap: ImageBitmap) : Result()
    }

    val LOADING: Result = Result.Loading
    private const val MAX_ENTRIES = 48

    private val cache = object : LinkedHashMap<String, Result>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > MAX_ENTRIES
    }

    private fun key(path: String, px: Int) = "$px@$path"

    private val sizes = object : LinkedHashMap<String, Pair<Int, Int>?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>?>?) = size > 512
    }

    /**
     * (width, height) from the image HEADER — ImageIO reads only as far as the dimensions, so a
     * sealed file costs one decrypted chunk, not a decode. null when ImageIO cannot tell (a
     * format it lacks, a damaged file); the caller then reserves the maximum height.
     */
    fun size(path: String): Pair<Int, Int>? {
        synchronized(sizes) { if (sizes.containsKey(path)) return sizes[path] }
        val dims = runCatching {
            com.oshi.desktop.store.MediaVault.openAny(File(path)).use { raw ->
                javax.imageio.ImageIO.createImageInputStream(raw).use { iis ->
                    val reader = javax.imageio.ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return@use null
                    try {
                        reader.setInput(iis, true, true)
                        reader.getWidth(0) to reader.getHeight(0)
                    } finally { reader.dispose() }
                }
            }
        }.getOrNull()
        synchronized(sizes) { sizes[path] = dims }
        return dims
    }

    fun cached(path: String, px: Int): Result? = synchronized(cache) { cache[key(path, px)] }

    /** Blocking — call it off the UI thread. */
    fun load(path: String, px: Int): Result {
        cached(path, px)?.let { return it }
        // __LOCAL_DATA_AT_REST_2026_09_22__ decrypted in memory from the sealed file (and
        // capped at the inline ceiling BEFORE allocating); plaintext never touches the disk.
        val result = runCatching {
            val bytes = com.oshi.desktop.store.MediaVault.readAny(File(path), MediaPresentation.MAX_INLINE_BYTES)
            // __IMAGE_PIXEL_BOMB_2026_11_20__ header-checked before decoding.
            val full = com.oshi.desktop.media.SafeImage.decode(bytes) ?: error("image refused: unreadable or over the pixel ceiling")
            val bitmap = if (px <= 0 || full.width <= px) full.toComposeImageBitmap() else {
                val h = maxOf(1, (full.height.toLong() * px / full.width).toInt())
                val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(px, h)
                surface.canvas.drawImageRect(
                    full,
                    org.jetbrains.skia.Rect.makeWH(full.width.toFloat(), full.height.toFloat()),
                    org.jetbrains.skia.Rect.makeWH(px.toFloat(), h.toFloat()),
                    org.jetbrains.skia.SamplingMode.MITCHELL,
                    null,
                    true,
                )
                surface.makeImageSnapshot().toComposeImageBitmap().also { surface.close(); full.close() }
            }
            Result.Ready(bitmap) as Result
        }.getOrDefault(Result.Failed)
        synchronized(cache) { cache[key(path, px)] = result }
        return result
    }
}

private val DECODE_FAILED: String get() = t("conv.media.decode_failed")

/**
 * Everything that is not an inline image: a named file, its type, and where it is.
 *
 * The path is shown in full and in a monospaced face on purpose. There is no viewer and no
 * "Open" button in this window, so the path IS the affordance — it is what a person pastes
 * into a file manager. A button that opened nothing would be the promise this window refuses
 * to make.
 */
@Composable
private fun FileCard(
    media: MediaPresentation,
    fromMe: Boolean,
    inkSoft: Color,
    overrideNote: String? = null,
) {
    val onBrand = fromMe
    val opener = LocalMediaOpener.current
    val target = remember(media) { MediaViewerTarget.of(media) }
    Row(
        Modifier
            .widthIn(max = Metrics.bubbleMax)
            .clip(OshiTheme.radiusSm)
            .background(if (onBrand) Color.White.copy(alpha = 0.14f) else OshiTheme.background.copy(alpha = 0.6f))
            .clickable(enabled = target != null) { target?.let(opener::open) }
            .padding(OshiTheme.sm),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.FILE, if (onBrand) Color.White else Ink.soft, 20.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xxs)) {
            Text(
                MediaPresentation.cardLabel(media.kind),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (onBrand) Ink.onBrandSoft else Ink.soft,
            )
            Text(
                media.fileName,
                style = OshiTheme.typography.bodyMedium,
                color = if (onBrand) Ink.onBrandStrong else Ink.strong,
            )
            Text(
                media.path,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (onBrand) Ink.onBrandSoft else inkSoft,
            )
            (overrideNote ?: media.note)?.let {
                Text(it, fontSize = 10.sp, color = if (onBrand) Ink.onBrandSoft else inkSoft)
            }
        }
    }
}

/**
 * __DESKTOP_POLLS_2026_10_29__ A poll, drawn the way both phones draw one: the question, a
 * row per option with a filled bar behind it, and a footer counting PEOPLE.
 *
 * ## Three things here are correctness, not decoration
 *
 *  - **The bar is drawn against `voterCount`, never against the sum of the counts.** In a
 *    multiple-answer poll one person can pick three options, so the counts sum past the
 *    number of voters and bars drawn against that sum would all be too short. The percentage
 *    comes from [PollOptionRow.percent], which is computed once in the tally so this view
 *    and the phones cannot disagree.
 *  - **A closed poll still shows its results and stops being clickable.** `onVote` is simply
 *    not attached, so there is no disabled-looking control that silently does nothing — the
 *    footer says it is closed instead.
 *  - **An anonymous poll draws no names because it HAS none** — the tally never collected
 *    them. There is nothing here to omit, which is why this view does not take a flag that
 *    would have to be remembered.
 */
@Composable
private fun PollCard(
    card: com.oshi.desktop.ui.state.PollCardRow,
    fromMe: Boolean,
    ink: Color,
    inkSoft: Color,
    accent: Color,
    onVote: ((optionIndex: Int) -> Unit)?,
) {
    // [fromMe] = drawn on a DARK outgoing bubble; [accent] is the bubble's own link colour.
    val fill = if (fromMe) Color.White.copy(alpha = 0.22f) else accent.copy(alpha = 0.16f)
    val chosen = accent
    Column(Modifier.padding(top = OshiTheme.xs).widthIn(max = 320.dp)) {
        Text(
            card.question,
            style = OshiTheme.typography.bodyLarge,
            color = ink,
        )
        Spacer(Modifier.height(OshiTheme.xs))
        card.options.forEachIndexed { index, option ->
            val clickable = onVote != null && !card.closed
            Box(
                Modifier.fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .let { if (clickable) it.clickable { onVote!!(index) } else it },
            ) {
                // The bar. `fillMaxWidth` of a fraction rather than a measured width, so it
                // tracks the bubble when the window is resized.
                Box(
                    Modifier.fillMaxWidth(option.percent.coerceIn(0, 100) / 100f)
                        .height(26.dp)
                        .background(fill, RoundedCornerShape(6.dp)),
                )
                Row(
                    Modifier.fillMaxWidth().height(26.dp).padding(horizontal = OshiTheme.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A tick marks OUR pick. A multiple-answer poll can have several.
                    Text(
                        if (option.mine) "\u2713 " else "",
                        style = OshiTheme.typography.bodySmall,
                        color = chosen,
                    )
                    Text(
                        option.label,
                        style = OshiTheme.typography.bodySmall,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${option.percent}%",
                        style = OshiTheme.typography.labelMedium,
                        color = inkSoft,
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                // "people", counting VOTERS — see the class note on why this is not the sum.
                // __MESSAGES_PARITY_2027__ the phones' own words (poll.votes / poll.*.badge / poll.closed).
                append(if (card.voterCount == 1) t("poll.votes.one") else t("poll.votes", card.voterCount))
                if (card.allowsMultipleAnswers) append(" \u00b7 " + t("poll.multiple_answers.badge"))
                if (card.isAnonymous) append(" \u00b7 " + t("poll.anonymous.badge"))
                if (card.closed) append(" \u00b7 " + t("poll.closed"))
            },
            style = OshiTheme.typography.labelMedium,
            color = inkSoft,
        )
    }
}
