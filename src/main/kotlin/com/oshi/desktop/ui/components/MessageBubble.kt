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
            BubbleBody(row, lastInRun, wallpaper, media, onReveal)

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
) {
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
        if (row.body.isNotBlank() || media == null) {
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
