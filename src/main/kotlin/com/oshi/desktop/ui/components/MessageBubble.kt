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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.store.DeliveryStatus
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
) {
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
        if (media != null) {
            MediaContent(media, row.fromMe, inkSoft, onReveal)
            if (row.body.isNotBlank()) Spacer(Modifier.height(OshiTheme.sm))
        }
        if (row.body.isNotBlank() || media == null) {
            Text(
                row.body,
                style = OshiTheme.typography.bodyLarge,
                fontStyle = if (row.deleted) FontStyle.Italic else FontStyle.Normal,
                color = if (row.deleted) inkSoft else ink,
            )
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
    val bitmap: ImageBitmap? = remember(media.path) {
        runCatching { SkiaImage.makeFromEncoded(File(media.path).readBytes()).toComposeImageBitmap() }.getOrNull()
    }
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
