package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.ui.OshiTheme

/**
 * The primitives every other file in this package draws with.
 *
 * ============================================================ THE GREY IS A MEASUREMENT
 *
 * `OshiTheme.textSecondary` is `0x993C3C43` — iOS's `secondaryLabel`, 60% of `(60,60,67)`.
 * Composited on `OshiTheme.background` that resolves to `#8A8A8E`, whose contrast against
 * white is **3.45:1**. That is below the 4.5:1 this window is required to hold on body text,
 * and it is below it on the phone too — the difference is that a phone reviewer measures a
 * 13pt caption and this window has a conversation PREVIEW at body size in that colour.
 *
 * So the greys below are alphas of `OshiTheme.textPrimary`, not new hues, chosen by
 * measurement and with the measurement written down:
 *
 *   * [Ink.strong] — `textPrimary`, 21:1. Names, message bodies, field values.
 *   * [Ink.soft] — `textPrimary` at 60%, composited `#666666`, **5.74:1**. Previews,
 *     captions, timestamps, every explanatory paragraph. Passes AA at any size.
 *
 * There is deliberately no third, lighter step. A 3.4:1 grey is where an accessible palette
 * goes wrong, and `textSecondary` is left unused by this package for exactly that reason.
 * (`OshiTheme.textSecondary` is still correct as a token — it is what the phone resolves —
 * and nothing here changes it.)
 */
object Ink {
    val strong: Color = OshiTheme.textPrimary
    val soft: Color = OshiTheme.textPrimary.copy(alpha = 0.60f)

    /** On the brand-filled outgoing bubble. White on `#5616EC` measures 7.74:1. */
    val onBrandStrong: Color = OshiTheme.bubbleOutgoingText
    val onBrandSoft: Color = OshiTheme.bubbleOutgoingText.copy(alpha = 0.78f)
}

/**
 * Sizes that are not spacing.
 *
 * `OshiTheme` is a SPACING scale (`xs`…`xxxl`) and a radius scale; it has no token for "how
 * wide is an avatar". Every padding and gap in this package comes from those tokens; the
 * dimensions below are the ones a spacing scale cannot express, each carrying where it came
 * from so it is a transcription rather than a guess.
 */
object Metrics {
    /** One device pixel of separator. */
    val hairline = 1.dp

    /** `MessagesListView` caps its content at 700pt; a desktop sidebar wants a fixed column. */
    val sidebar = 344.dp

    /** `ConversationRow`: 44×44. */
    val avatarList = 44.dp
    val avatarHeader = 36.dp
    val avatarLarge = 64.dp

    /**
     * `ConversationRow`'s separator is pinned to `alignmentGuide 56` — avatar 44 + spacing 12.
     * Expressed as the sum so it cannot drift from [avatarList].
     */
    val separatorInset = avatarList + OshiTheme.md

    /** `BubbleStyleManager.maxBubbleWidth = min(screenWidth * 0.55, 420)`. */
    val bubbleMax = 420.dp

    /** `MediaChatBubble.bubbleWidth = 250` — fixed, so a caption cannot outgrow the picture. */
    val mediaBubble = 250.dp

    /** Composer glyph buttons: 36×36 on the phone. */
    val actionButton = 36.dp

    /** Send is a 44pt circle on the phone; 40 reads right against a desktop text field. */
    val sendButton = 40.dp

    /** The visible keyboard-focus ring. Two points is what stays visible on a hairline row. */
    val focusRing = 2.dp

    /** `ConversationRow` measures 61.8pt on the phone; desktop pointer targets want more. */
    val rowHeight = 72.dp
}

/** The 30%-alpha separator, full bleed or inset. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(Metrics.hairline)
            .background(OshiTheme.separator)
    )
}

/**
 * The circular gradient monogram from the capture.
 *
 * `OshiTheme.avatarBrush` derives the hue from the address, so the same peer is the same
 * colour on every launch and on every machine with nothing stored. The shipped app indexes a
 * six-entry palette by `publicKey.hashValue.magnitude % 6`; `hashValue` is salted per process
 * on Apple platforms, so that palette is stable within a launch and not across them. The
 * theme's function is the deterministic version of the same idea and is what this draws.
 *
 * There is no profile photo here and there is not going to be one: PARITY.md row 0.18 records
 * that an Android peer's picture rides the legacy lane and never arrives.
 */
@Composable
fun Monogram(
    name: String,
    address: String,
    size: Dp = Metrics.avatarList,
    modifier: Modifier = Modifier,
) {
    val brush = remember(address) { OshiTheme.avatarBrush(address) }
    val text = remember(name, address) { OshiTheme.monogram(name, address) }
    Box(modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** The violet capsule carrying an unread count. White on brand measures 7.74:1. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .clip(OshiTheme.pill)
            .background(OshiTheme.brandGradient)
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Ink.onBrandStrong,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------------------- interaction

/** The hover source and its state, so a row can light up under the pointer. */
@Composable
fun rememberRowInteraction(): Pair<MutableInteractionSource, State<Boolean>> {
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState()
    return source to hovered
}

/**
 * The keyboard focus ring.
 *
 * A brand outline, because Compose Desktop draws no focus indication of its own on a
 * `Modifier.clickable` and an invisible focus ring is the single most common way a window
 * turns out not to be keyboard-navigable after all.
 *
 * It reads [onFocusChanged] rather than a `MutableInteractionSource`, and that is not a style
 * preference. Whether `clickable` forwards its interaction source to the `focusable` it wraps
 * is a Compose implementation detail that has changed across versions; if it ever stopped, a
 * ring built on `collectIsFocusedAsState` would go silently invisible and nothing in a
 * headless suite could notice, because this whole file is undrawn by any test. `onFocusChanged`
 * is the public contract of the focus system itself and cannot drift the same way.
 *
 * **Apply it BEFORE `.clickable`/`.focusable` in the chain** — `onFocusChanged` observes the
 * focus target below it, so an ordering mistake here is a ring that never appears.
 */
@Composable
fun Modifier.focusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(Metrics.focusRing, OshiTheme.brand, shape) else Modifier)
}

// ---------------------------------------------------------------------------- glyphs

/**
 * The icon set, drawn rather than imported.
 *
 * `material-icons-core` is not a declared dependency of this build (`build.gradle.kts` pulls
 * `compose.desktop.currentOs` and `compose.material3`, nothing else), and adding one so a
 * window could have a magnifying glass would be a new artifact in an installer that already
 * carries a 28 MB skiko native. Every glyph below is a handful of strokes on a 24×24 grid.
 *
 * **There is no phone and no video glyph in this enum and there must never be one.** The
 * shipped `ChatView` has a four-button action bar with `phone.fill` and `video.fill` in it;
 * PARITY.md row 2.1 is unambiguous that no audio flows on this client, so those two buttons
 * are the one part of that screen this window deliberately does not port. See
 * `DesktopLimits.MISSING`.
 */
enum class Glyph { SEARCH, PLUS, ARROW_UP, CHEVRON_RIGHT, CHECK, DOUBLE_CHECK, CLOCK, ALERT, LOCK, PALETTE, EYE_SLASH, FILE, CLOSE, COPY }

@Composable
fun GlyphIcon(
    glyph: Glyph,
    tint: Color,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        when (glyph) {
            Glyph.SEARCH -> {
                drawCircle(tint, radius = s * 0.30f, center = p(0.44f, 0.44f), style = stroke)
                drawLine(tint, p(0.66f, 0.66f), p(0.88f, 0.88f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.PLUS -> {
                drawLine(tint, p(0.5f, 0.18f), p(0.5f, 0.82f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, p(0.18f, 0.5f), p(0.82f, 0.5f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.ARROW_UP -> {
                drawLine(tint, p(0.5f, 0.82f), p(0.5f, 0.20f), strokeWidth = w, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(0.24f * s, 0.46f * s); lineTo(0.5f * s, 0.19f * s); lineTo(0.76f * s, 0.46f * s) }, tint, style = stroke)
            }
            Glyph.CHEVRON_RIGHT ->
                drawPath(Path().apply { moveTo(0.38f * s, 0.22f * s); lineTo(0.66f * s, 0.5f * s); lineTo(0.38f * s, 0.78f * s) }, tint, style = stroke)
            Glyph.CHECK -> drawCheck(tint, s, w, 0f)
            Glyph.DOUBLE_CHECK -> { drawCheck(tint, s, w, -0.14f); drawCheck(tint, s, w, 0.14f) }
            Glyph.CLOCK -> {
                drawCircle(tint, radius = s * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.5f, 0.5f), p(0.5f, 0.26f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, p(0.5f, 0.5f), p(0.70f, 0.58f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.ALERT -> {
                drawCircle(tint, radius = s * 0.40f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.5f, 0.26f), p(0.5f, 0.56f), strokeWidth = w, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.62f, center = p(0.5f, 0.72f))
            }
            Glyph.LOCK -> {
                drawRoundRect(
                    tint,
                    topLeft = p(0.22f, 0.44f),
                    size = Size(s * 0.56f, s * 0.38f),
                    cornerRadius = CornerRadius(s * 0.10f),
                    style = stroke,
                )
                drawPath(
                    Path().apply {
                        moveTo(0.33f * s, 0.44f * s); lineTo(0.33f * s, 0.31f * s)
                        cubicTo(0.33f * s, 0.15f * s, 0.67f * s, 0.15f * s, 0.67f * s, 0.31f * s)
                        lineTo(0.67f * s, 0.44f * s)
                    },
                    tint, style = stroke,
                )
            }
            Glyph.PALETTE -> {
                drawCircle(tint, radius = s * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawCircle(tint, radius = w * 0.85f, center = p(0.36f, 0.36f))
                drawCircle(tint, radius = w * 0.85f, center = p(0.62f, 0.32f))
                drawCircle(tint, radius = w * 0.85f, center = p(0.70f, 0.58f))
            }
            Glyph.EYE_SLASH -> {
                drawPath(
                    Path().apply {
                        moveTo(0.10f * s, 0.50f * s)
                        cubicTo(0.28f * s, 0.24f * s, 0.72f * s, 0.24f * s, 0.90f * s, 0.50f * s)
                        cubicTo(0.72f * s, 0.76f * s, 0.28f * s, 0.76f * s, 0.10f * s, 0.50f * s)
                    },
                    tint, style = stroke,
                )
                drawCircle(tint, radius = s * 0.11f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.16f, 0.84f), p(0.84f, 0.16f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.FILE -> {
                drawPath(
                    Path().apply {
                        moveTo(0.26f * s, 0.14f * s); lineTo(0.58f * s, 0.14f * s); lineTo(0.76f * s, 0.34f * s)
                        lineTo(0.76f * s, 0.86f * s); lineTo(0.26f * s, 0.86f * s); close()
                    },
                    tint, style = stroke,
                )
                drawPath(Path().apply { moveTo(0.58f * s, 0.14f * s); lineTo(0.58f * s, 0.34f * s); lineTo(0.76f * s, 0.34f * s) }, tint, style = stroke)
            }
            Glyph.CLOSE -> {
                drawLine(tint, p(0.24f, 0.24f), p(0.76f, 0.76f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, p(0.76f, 0.24f), p(0.24f, 0.76f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.COPY -> {
                drawRoundRect(tint, topLeft = p(0.16f, 0.16f), size = Size(s * 0.46f, s * 0.46f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
                drawRoundRect(tint, topLeft = p(0.38f, 0.38f), size = Size(s * 0.46f, s * 0.46f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
            }
        }
    }
}

private fun DrawScope.drawCheck(tint: Color, s: Float, w: Float, dx: Float) {
    drawPath(
        Path().apply {
            moveTo((0.22f + dx) * s, 0.52f * s)
            lineTo((0.42f + dx) * s, 0.72f * s)
            lineTo((0.78f + dx) * s, 0.28f * s)
        },
        tint,
        style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** A soft violet disc behind a glyph — the capture's circular icon badge. */
@Composable
fun BrandDisc(glyph: Glyph, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(OshiTheme.brand.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, OshiTheme.brand, size * 0.46f)
    }
}

/** The window's one accent gradient, exposed so the send button and badges cannot diverge. */
val brandSweep: Brush
    get() = OshiTheme.brandGradient
