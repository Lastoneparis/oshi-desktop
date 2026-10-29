package com.oshi.desktop.ui.components

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
    /** Contacts a message can be forwarded to (address → label), and the action. */
    forwardTargets: List<Pair<String, String>> = emptyList(),
    onForward: ((toAddress: String) -> Unit)? = null,
    /** __DESKTOP_POLLS_2026_10_29__ cast or toggle a vote on this row's poll. */
    onVote: ((optionIndex: Int) -> Unit)? = null,
) {
    var forwarding by remember(row.id) { mutableStateOf(false) }
    var editing by remember(row.id) { mutableStateOf(false) }
    var editedBody by remember(row.id, row.body) { mutableStateOf(row.body) }
    var confirmingDelete by remember(row.id) { mutableStateOf(false) }
    val media = remember(row.attachment, viewOnce, revealed) {
        MediaPresentation.of(row.attachment, viewOnce, revealed)
    }
    Column(
        Modifier
            .fillMaxWidth()
            // 2pt when grouped, 10pt otherwise — `ChatView.swift`'s own two values.
            .padding(top = if (grouped) OshiTheme.xxs else 10.dp),
        horizontalAlignment = if (row.fromMe) Alignment.End else Alignment.Start,
    ) {
        Column(
            Modifier.widthIn(max = Metrics.bubbleMax),
            horizontalAlignment = if (row.fromMe) Alignment.End else Alignment.Start,
        ) {
            if (showSender && !row.fromMe) {
                Text(
                    row.who,
                    style = OshiTheme.typography.labelMedium,
                    color = OshiTheme.brand,
                    modifier = Modifier.padding(start = OshiTheme.sm, bottom = OshiTheme.xxs),
                )
            }
            BubbleBody(row, lastInRun, wallpaper, media, onReveal, onVote)

            if (row.reactions.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    row.reactions,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(OshiTheme.pill)
                        .background(OshiTheme.surfaceElevated)
                        .border(Metrics.hairline, OshiTheme.separator, OshiTheme.pill)
                        .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs),
                )
            }

            if (!row.deleted && (onReact != null || onEdit != null || onDelete != null || onReply != null)) {
                Spacer(Modifier.height(OshiTheme.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    onReply?.let { reply -> TextAction(t("message.action.reply")) { reply() } }
                    if (onForward != null && forwardTargets.isNotEmpty() && row.body.isNotBlank()) {
                        TextAction(t("message.action.forward")) { forwarding = !forwarding }
                    }
                    onPin?.let { pin -> TextAction(if (pinned) t("message.unpin") else t("message.pin")) { pin(!pinned) } }
                    onReact?.let { react ->
                        TextAction("♥") { react("♥") }
                        TextAction("👍") { react("👍") }
                        TextAction("😂") { react("😂") }
                    }
                    onEdit?.let { TextAction("Edit") { editing = true } }
                    onDelete?.let { delete ->
                        TextAction(if (confirmingDelete) "Confirm delete" else "Delete") {
                            if (confirmingDelete) delete() else confirmingDelete = true
                        }
                        if (confirmingDelete) TextAction("Cancel") { confirmingDelete = false }
                    }
                    onDeleteForMe?.let { hide -> TextAction(t("group.delete.for_me")) { hide() } }
                }
            }

            if (forwarding && onForward != null) {
                Spacer(Modifier.height(OshiTheme.xs))
                Text(t("message.forward_to"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
                for ((address, label) in forwardTargets) {
                    TextAction(label) { onForward(address); forwarding = false }
                }
            }

            if (editing && onEdit != null) {
                Spacer(Modifier.height(OshiTheme.xs))
                BasicTextField(
                    value = editedBody,
                    onValueChange = { editedBody = it },
                    textStyle = OshiTheme.typography.bodyMedium.copy(color = OshiTheme.textPrimary),
                    cursorBrush = SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    TextAction("Save") { onEdit(editedBody); editing = false }
                    TextAction("Cancel") { editedBody = row.body; editing = false }
                }
            }

            if (lastInRun || row.failed || row.edited || row.status == DeliveryStatus.PENDING.wire) {
                Spacer(Modifier.height(OshiTheme.xs))
                Footer(row)
            }
        }
    }
}

// ---------------------------------------------------------------------------- the bubble

@Composable
private fun BubbleBody(
    row: MessageRow,
    lastInRun: Boolean,
    wallpaper: WallpaperId,
    media: MediaPresentation?,
    onReveal: () -> Unit,
    onVote: ((optionIndex: Int) -> Unit)? = null,
) {
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
                missingNote = if (media.note == MediaPresentation.MISSING_FILE_NOTE) com.oshi.desktop.i18n.dt("desktop.videonote.missing") else null,
            )
            if (row.body.isNotBlank()) {
                Text(row.body, style = OshiTheme.typography.bodyMedium, color = Ink.strong, modifier = Modifier.padding(top = OshiTheme.xs))
            }
        }
        return
    }
    val shape = bubbleShape(row.fromMe, lastInRun)
    val fill = if (row.fromMe) OshiTheme.bubbleOutgoing else WallpaperPainter.incomingFill(wallpaper)
    val rim = if (row.fromMe) Color.White.copy(alpha = 0.14f) else OshiTheme.textPrimary.copy(alpha = 0.07f)
    val ink = if (row.fromMe) Ink.onBrandStrong else OshiTheme.bubbleIncomingText
    val inkSoft = if (row.fromMe) Ink.onBrandSoft else Ink.soft

    Column(
        Modifier
            .clip(shape)
            .background(fill)
            .border(Metrics.hairline, rim, shape)
            .padding(OshiTheme.md),
        horizontalAlignment = Alignment.Start,
    ) {
        // __GROUP_PARITY_2026_09_23__ forwarded label and reply quote, as iOS draws them
        // (`QuotedMessageView` / `groupReplyQuote`): above the body, inside the bubble.
        if (!row.deleted && row.forwardedFrom != null) {
            Text(
                if (row.forwardedFrom.isBlank()) t("message.forwarded") else t("message.forwarded") + " · " + row.forwardedFrom,
                style = OshiTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = inkSoft,
            )
            Spacer(Modifier.height(OshiTheme.xxs))
        }
        if (!row.deleted && row.quote != null) {
            Row(
                Modifier
                    .clip(OshiTheme.pill)
                    .background(ink.copy(alpha = 0.08f))
                    .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xs),
            ) {
                Column {
                    if (row.quote.who.isNotBlank()) {
                        Text(row.quote.who, style = OshiTheme.typography.labelMedium, color = ink)
                    }
                    Text(row.quote.text, style = OshiTheme.typography.bodySmall, color = inkSoft, maxLines = 2)
                }
            }
            Spacer(Modifier.height(OshiTheme.xs))
        }
        if (media != null) {
            MediaContent(media, row.fromMe, inkSoft, onReveal)
            if (row.body.isNotBlank()) Spacer(Modifier.height(OshiTheme.sm))
        }
        // __DESKTOP_POLLS_2026_10_29__ A poll draws a card instead of a body. `row.body` is
        // blank for a poll (the model empties it), so without this the bubble would be an
        // empty box — which is what an undecoded poll would look like once it no longer
        // prints its JSON.
        row.poll?.let { card ->
            PollCard(card, row.fromMe, ink, inkSoft, onVote)
        }
        if (row.poll == null && (row.body.isNotBlank() || media == null)) {
            // __MENTIONS_2026_09_23__ `@name` of a picked member is highlighted (MentionUi.kt).
            MentionAwareText(
                row.body,
                if (row.deleted) emptyList() else row.mentions,
                style = OshiTheme.typography.bodyLarge,
                color = if (row.deleted) inkSoft else ink,
                fontStyle = if (row.deleted) FontStyle.Italic else FontStyle.Normal,
                tint = if (row.fromMe) Color.White else OshiTheme.brand,
            )
            // __TICKER_QUOTES_2026_09_23__ `@AAPL` → price chip, under the text, 1:1 and groups (TickerChip.kt).
            // A mentioned person's `@name` is never offered to the ticker parser.
            if (!row.deleted) TickerChipsForText(row.body, row.id, Modifier.padding(top = OshiTheme.xs), tint = if (row.fromMe) Color.White else OshiTheme.brand, ink = ink,
                exclude = mentionRanges(row.body, row.mentions))
            // __CASHU_2026_10_24__ eCash card, same place as the ticker chips.
            // Nothing is drawn unless a Cashu token actually DECODES in the
            // body: a bubble must never offer to redeem a sentence.
            if (!row.deleted) CashuTokenCardsForText(row.body, Modifier.padding(top = OshiTheme.xs))
        }
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

@Composable
private fun Footer(row: MessageRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        if (row.edited) {
            Text("edited", fontSize = 10.sp, fontStyle = FontStyle.Italic, color = Ink.soft)
        }
        Text(DayBreaks.timeOf(row.stamp), fontSize = 10.sp, color = Ink.soft)
        // Only outgoing rows carry a status; the model refuses to produce one for inbound.
        row.status?.let { DeliveryMark(it, row.failed) }
        if (row.failed) {
            Text(
                "not delivered",
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
private fun DeliveryMark(wire: String, failed: Boolean) {
    if (failed) {
        GlyphIcon(Glyph.ALERT, OshiTheme.danger, 12.dp)
        return
    }
    when (wire) {
        DeliveryStatus.PENDING.wire -> GlyphIcon(Glyph.CLOCK, Ink.soft, 12.dp)
        DeliveryStatus.SENT.wire -> GlyphIcon(Glyph.CHECK, Ink.soft, 12.dp)
        DeliveryStatus.DELIVERED.wire -> GlyphIcon(Glyph.DOUBLE_CHECK, Ink.soft, 14.dp)
        DeliveryStatus.READ.wire -> GlyphIcon(Glyph.DOUBLE_CHECK, OshiTheme.success, 14.dp)
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
) {
    when {
        media.sealedViewOnce -> SealedViewOnce(media, onReveal)
        media.inlineImage -> InlineImage(media, inkSoft)
        media.posterVideo -> VideoPoster(media, fromMe, inkSoft)
        else -> FileCard(media, fromMe, inkSoft)
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
        Text("View once", color = Color.White, style = OshiTheme.typography.titleMedium)
        Text(
            MediaPresentation.cardLabel(media.kind) + " · not shown until you ask",
            color = Ink.onBrandSoft,
            fontSize = 11.sp,
        )
        Text(
            "Reveal",
            color = OshiTheme.brand,
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
 * The decrypted file, drawn.
 *
 * Decoding happens in `remember(path)` so a scroll does not re-read the disk. A decode that
 * throws — a truncated write, a format skia does not carry — falls back to the file card and
 * says so; it never leaves a blank box, and it never takes the frame down.
 */
@Composable
private fun InlineImage(media: MediaPresentation, inkSoft: Color) {
    // __PHOTO_DECODE_OFF_UI_2026_09_23__ The read + decrypt + decode used to run INSIDE
    // `remember`, i.e. on the UI thread during composition — and a LazyColumn forgets a row
    // that scrolls away, so it ran again every time a photo came back into view. Measured with
    // `FluidityBench` (60 sealed 4032×3024 phone photos): opening that chat held ONE frame for
    // 194 ms, and scrolling spiked to 62 ms per photo entering. It also kept every visible photo
    // as a full 12 MP bitmap (~48 MB each) to draw it 250dp wide. It now decodes on IO, is
    // downscaled to the bubble's pixel width, and is kept in a small cache so scrolling back
    // costs nothing. Until it lands, a placeholder holds EXACTLY the space the picture will
    // take: its size comes from the image header (one 64 KB chunk decrypted, no decode). A
    // guessed height is not good enough — the thread's LazyColumn anchors its first visible
    // row, so rows that change height after arriving pushed the newest messages off the
    // bottom of a chat that had just opened scrolled to them (seen in the bench's frames).
    // __GIF_PACK_2026_09_23__ a GIF animates (GifBubble.kt); decided from its bytes, like iOS.
    if (remember(media.path) { GifBubble.isGifFile(media.path) }) { GifInlineImage(media, inkSoft); return }
    val headerSize = remember(media.path) { InlineBitmaps.size(media.path) }
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { Metrics.mediaBubble.roundToPx() }
    val loaded by androidx.compose.runtime.produceState(
        InlineBitmaps.cached(media.path, targetPx) ?: InlineBitmaps.LOADING, media.path, targetPx,
    ) {
        if (value !== InlineBitmaps.LOADING) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { InlineBitmaps.load(media.path, targetPx) }
    }
    if (loaded === InlineBitmaps.LOADING) {
        // The Image below is `width(mediaBubble).heightIn(max = mediaBubble)`, so its height is
        // the picture's aspect at that width, capped — the same arithmetic, before the pixels.
        val height = headerSize?.let { (w, h) -> if (w > 0) Metrics.mediaBubble * (h.toFloat() / w).coerceAtMost(1f) else null }
            ?: Metrics.mediaBubble
        Column(Modifier.width(Metrics.mediaBubble), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            Box(Modifier.width(Metrics.mediaBubble).height(height).clip(OshiTheme.radiusMd).background(OshiTheme.surface))
            Text(media.fileName, fontSize = 10.sp, color = inkSoft, fontFamily = FontFamily.Monospace)
            media.note?.let { Text(it, fontSize = 10.sp, color = inkSoft) }
        }
        return
    }
    val bitmap: ImageBitmap? = (loaded as? InlineBitmaps.Result.Ready)?.bitmap
    if (bitmap == null) {
        FileCard(media, fromMe = false, inkSoft = inkSoft, overrideNote = DECODE_FAILED)
        return
    }
    // Clicking the thumbnail opens the full-size viewer. `MediaViewerTarget.of` returns
    // null for a sealed view-once row, which is what disables the click — the seal is
    // enforced in ONE place rather than re-decided at every call site, so a future bubble
    // cannot forget it.
    val opener = LocalMediaOpener.current
    val target = remember(media) { MediaViewerTarget.of(media) }
    Column(Modifier.width(Metrics.mediaBubble), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        Image(
            bitmap = bitmap,
            contentDescription = media.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(Metrics.mediaBubble)
                .heightIn(max = Metrics.mediaBubble)
                .clip(OshiTheme.radiusMd)
                .clickable(enabled = target != null) { target?.let(opener::open) },
        )
        Text(media.fileName, fontSize = 10.sp, color = inkSoft, fontFamily = FontFamily.Monospace)
        media.note?.let { Text(it, fontSize = 10.sp, color = inkSoft) }
    }
}

/**
 * A video's FIRST FRAME in the bubble, with a play badge — what the phones show.
 *
 * Until this existed a video was a file card: a glyph, a filename and a path. That was the
 * honest thing to draw while nothing here could decode one, and the class note of
 * [MediaPresentation] says why — "a thumbnail this window could not actually produce would
 * be a promise". It can produce one now, through the same `VideoNoteReader` the viewer and
 * the round note bubble use, so the card would be an UNDERSTATEMENT rather than a promise.
 *
 * The decode is off the UI thread and the result is cached by path AND fit, which matters
 * more here than in the viewer: a thread is a `LazyColumn` and it forgets a row that scrolls
 * away, so a poster with no cache would re-decrypt and re-decode a video every time it came
 * back into view. That is the same defect __PHOTO_DECODE_OFF_UI_2026_09_23__ records for
 * photographs, measured there at 62 ms per photo entering the viewport.
 *
 * While the frame is still decoding this holds the bubble's width at a 16:9 box rather than
 * collapsing to nothing: the thread's LazyColumn anchors its first visible row, so a row that
 * grows after it arrives pushes the newest messages off the bottom of a chat that had just
 * opened scrolled to them.
 */
@Composable
private fun VideoPoster(media: MediaPresentation, fromMe: Boolean, inkSoft: Color) {
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { Metrics.mediaBubble.roundToPx() }
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
        FileCard(media, fromMe, inkSoft, overrideNote = "No preview: ${thumb?.error}")
        return
    }

    Column(Modifier.width(Metrics.mediaBubble), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        Box(
            Modifier
                .width(Metrics.mediaBubble)
                .heightIn(max = Metrics.mediaBubble)
                .clip(OshiTheme.radiusMd)
                .background(OshiTheme.surface)
                .clickable(enabled = target != null) { target?.let(opener::open) },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = media.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(Metrics.mediaBubble),
                )
            } else {
                // The decoding placeholder: 16:9 at the bubble's width, so the row does not
                // change height when the frame lands.
                Box(Modifier.width(Metrics.mediaBubble).height(Metrics.mediaBubble * 9 / 16))
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
        }
        Text(media.fileName, fontSize = 10.sp, color = inkSoft, fontFamily = FontFamily.Monospace)
        thumb?.durationMs?.let {
            Text("%d:%02d".format(it / 60_000, (it / 1000) % 60), fontSize = 10.sp, color = inkSoft)
        }
        media.note?.let { Text(it, fontSize = 10.sp, color = inkSoft) }
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
            val full = SkiaImage.makeFromEncoded(bytes)
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

private const val DECODE_FAILED =
    "The file is on disk but this window could not decode it as an image. The bytes are not " +
        "damaged by being unreadable here — every OSHI client decodes with its own library."

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
    onVote: ((optionIndex: Int) -> Unit)?,
) {
    val fill = if (fromMe) Color.White.copy(alpha = 0.22f) else OshiTheme.brand.copy(alpha = 0.16f)
    val chosen = if (fromMe) Color.White else OshiTheme.brand
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
                append(if (card.voterCount == 1) "1 vote" else "${card.voterCount} votes")
                if (card.allowsMultipleAnswers) append(" \u00b7 pick several")
                if (card.isAnonymous) append(" \u00b7 anonymous")
                if (card.closed) append(" \u00b7 closed")
            },
            style = OshiTheme.typography.labelMedium,
            color = inkSoft,
        )
    }
}
