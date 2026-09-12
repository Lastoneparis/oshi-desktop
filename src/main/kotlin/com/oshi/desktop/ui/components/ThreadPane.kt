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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ComposerState
import com.oshi.desktop.ui.state.ConversationKind
import com.oshi.desktop.ui.state.DesktopLimits
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.Notice
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
 * port. PARITY.md row 2.1: signalling works and reaches the REPL, and **no audio flows** —
 * `CallAudioSession` is referenced by nothing in the repository, so a connected call is two
 * devices agreeing and silence. A greyed-out phone icon would still tell a user calls are a
 * thing this app does, which is why there is no phone glyph in [Glyph] to draw one with.
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
    viewOnce: ViewOnceFacts,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember(thread.conversationId) { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        WallpaperBackdrop(wallpaper)

        // The viewer wraps the WHOLE thread rather than sitting inside the message log, so
        // a full-size picture covers the header and the composer too. Anything narrower
        // would put a photograph in a box beside a text field, which is a thumbnail with
        // extra steps. It provides `LocalMediaOpener`; the bubbles read it.
        WithMediaViewer {
            Column(Modifier.fillMaxSize()) {
                ThreadHeader(thread, wallpaper, pickerOpen) { pickerOpen = !pickerOpen }
                Hairline()

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (thread.messages.isEmpty()) {
                        EmptyThread(thread.kind)
                    } else {
                        MessageLog(thread, wallpaper, viewOnce, revealed, onReveal, today)
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
                    Composer(thread.composer, busy, draft, onDraft, onSend, onAttach)
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
    onWallpaper: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OshiTheme.background)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Monogram(thread.title, thread.address, Metrics.avatarHeader)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Text(
                    thread.title,
                    style = OshiTheme.typography.titleMedium,
                    color = Ink.strong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                KindBadge(thread.kind)
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
    today: LocalDate,
) {
    val listState = rememberLazyListState()

    // Smooth scroll to newest on a new message; a JUMP on a conversation switch, because
    // animating through a stranger's whole history is a second of scrolling nobody asked for.
    var lastConversation by remember { mutableStateOf(thread.conversationId) }
    LaunchedEffect(thread.conversationId, thread.messages.size) {
        if (thread.messages.isEmpty()) return@LaunchedEffect
        val target = thread.messages.lastIndex
        if (lastConversation != thread.conversationId) {
            lastConversation = thread.conversationId
            listState.scrollToItem(target)
        } else {
            listState.animateScrollToItem(target)
        }
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
    composer: ComposerState,
    busy: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
) {
    if (!composer.enabled && !busy) {
        DisabledComposer(composer)
        return
    }

    var whyOpen by remember { mutableStateOf(false) }
    val canSend = draft.isNotBlank() && !busy

    Column(Modifier.fillMaxWidth().background(OshiTheme.background)) {
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

            DraftField(
                draft = draft,
                busy = busy,
                onDraft = onDraft,
                onEnter = { if (canSend) onSend() },
                modifier = Modifier.weight(1f),
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
