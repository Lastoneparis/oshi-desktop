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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
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
/**
 * __GLASS_HOVER_ALPHA_2026_11_21__ [this] at no more than [alpha] — never MORE opaque than the
 * token already is. `copy(alpha = x)` SETS the alpha, and the glass palettes' `surface` and every
 * palette's `separator` are already translucent (Light glass `surface` = 7 % indigo, OLED = 8 %
 * white): `surface.copy(alpha = 0.6f)` made a settings row's hover a 60 % slate slab in Light
 * glass and a 60 % white slab in Extra dark (seen in the running app, 21/11). On an opaque token
 * (flat Light/Dark `surface`) this is exactly `copy(alpha = x)`.
 */
fun Color.atMostAlpha(alpha: Float): Color = if (this.alpha <= alpha) this else copy(alpha = alpha)

object Ink {
    // Getters, not stored values (__OLED_BLACK_2026_11_16__): the palette changes at runtime,
    // and a `val` captured at class-init would keep painting black text on a black window.
    val strong: Color get() = OshiTheme.textPrimary
    val soft: Color get() = OshiTheme.textPrimary.copy(alpha = 0.60f)

    /** On a brand-FILLED surface (buttons, badges). White on `#5616EC` measures 7.74:1. */
    // __BUBBLE_MEDIA_2026_11_21__ No longer read from the outgoing bubble's text colour, which
    // is near-black in Light now; brand fills keep white.
    val onBrandStrong: Color = Color.White
    val onBrandSoft: Color = Color.White.copy(alpha = 0.78f)
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
    // __DESKTOP_1_6_PARITY__ 344 → 360: the four filter chips fit on one line in English
    // and French (measured, ConversationListPane.MergedFilterChips) and previews show more.
    val sidebar = 360.dp

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

    /**
     * __COMPOSER_PILL_2026_11_23__ The chat composer pill and the round mic/send beside it.
     * 56dp on Android; 44 sits right next to a 36dp glyph button and the desktop's type size.
     */
    val composerPill = 44.dp

    /** Inset of the pill's content: (44 − 36) / 2, so a 36dp glyph button sits centred on one line. */
    val composerPillInset = 4.dp

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
 * `OshiTheme.avatarBrush` derives the colour from the address, so the same peer is the same
 * colour on every launch, on every machine — and, since
 * `__ONE_AVATAR_NOT_THREE_2026_11_07__`, on every PLATFORM: it is the same
 * palette and the same slot the iPhone and Android apps use (v2: SHA-256, sixteen soft
 * gradients, `docs/avatar-palette.json`), pinned by shared test vectors in `AvatarParityTest`.
 *
 * (The text here used to say the shipped app indexed "a six-entry palette by
 * `publicKey.hashValue.magnitude % 6`", salted per process. That was true of a version of
 * iOS two revisions old and had quietly stopped being true. A note describing another
 * platform is not a check of it — this one was wrong for weeks.)
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
    val entry = remember(address) { OshiTheme.avatarEntry(address) }
    val text = remember(name, address) { OshiTheme.monogram(name, address) }
    // __AVATAR_PALETTE_V2_2026_11_17__ the diagonal gradient plus the soft top-left light
    // blob, brushes built once per size by drawWithCache; the slot's own ink.
    val fill = Modifier.drawWithCache {
        val linear = Brush.linearGradient(listOf(entry.start, entry.end), Offset.Zero, Offset(this.size.width, this.size.height))
        val light = Brush.radialGradient(
            listOf(Color.White.copy(alpha = OshiTheme.AVATAR_HIGHLIGHT_ALPHA), Color.White.copy(alpha = 0f)),
            center = Offset(this.size.width * OshiTheme.AVATAR_HIGHLIGHT_X, this.size.height * OshiTheme.AVATAR_HIGHLIGHT_Y),
            radius = this.size.minDimension * OshiTheme.AVATAR_HIGHLIGHT_RADIUS,
        )
        onDrawBehind {
            drawCircle(linear)
            drawCircle(light)
        }
    }
    Box(modifier.size(size).clip(CircleShape).then(fill), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = entry.ink,
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
 * PARITY.md row 2.1 records only loopback proof for the opt-in audio lane, with no TURN and
 * no real-device call. Those two buttons are therefore the one part of that screen this
 * window deliberately does not port. See
 * `DesktopLimits.MISSING`.
 */
/** __RTL_2027__ glyphs whose meaning has a direction, flipped in RTL by [GlyphIcon]. */
private val DIRECTIONAL_GLYPHS = setOf(Glyph.CHEVRON_RIGHT, Glyph.REPLY, Glyph.FORWARD, Glyph.LEAVE)

enum class Glyph {
    SEARCH, PLUS, ARROW_UP, CHEVRON_RIGHT, CHECK, DOUBLE_CHECK, CLOCK, ALERT, LOCK, PALETTE, EYE_SLASH, FILE, CLOSE, COPY,
    // __ATTACH_PANEL_2027__ / __MERGED_CHATS_2026_11_18__ the "+" panel tiles and the "New…" menu.
    PEOPLE, PERSON, IMAGE, POLL, CAMERA, MIC, QR, MASK, COMPOSE,
    // __THREAD_CHROME_2027__ the conversation header's overflow menu and the message hover toolbar.
    MORE, REPLY, FORWARD, PIN, SMILE, TRASH, INFO, BELL, BELL_SLASH, BLOCK, FLAG, LEAVE, PENCIL, LINK, TEXT_LINES,
    // __DESKTOP_1_6_PARITY__ a place (location ping), the safety check-in, a video file.
    MAP_PIN, HEART, VIDEO,
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    tint: Color,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    // __RTL_2027__ Arrows that point "forward" or "back" are mirrored in a right-to-left
    // window, like SF Symbols and Material icons do; symmetric glyphs are left alone.
    val mirror = glyph in DIRECTIONAL_GLYPHS &&
        androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(modifier.size(size).then(if (mirror) Modifier.graphicsLayer(scaleX = -1f) else Modifier)) {
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
            Glyph.PERSON -> {
                drawCircle(tint, radius = s * 0.16f, center = p(0.5f, 0.34f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.20f * s, 0.84f * s); cubicTo(0.22f * s, 0.58f * s, 0.78f * s, 0.58f * s, 0.80f * s, 0.84f * s)
                }, tint, style = stroke)
            }
            Glyph.PEOPLE -> {
                drawCircle(tint, radius = s * 0.13f, center = p(0.38f, 0.36f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.12f * s, 0.82f * s); cubicTo(0.14f * s, 0.60f * s, 0.62f * s, 0.60f * s, 0.64f * s, 0.82f * s)
                }, tint, style = stroke)
                drawCircle(tint, radius = s * 0.11f, center = p(0.68f, 0.30f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.70f * s, 0.54f * s); cubicTo(0.82f * s, 0.55f * s, 0.88f * s, 0.62f * s, 0.90f * s, 0.74f * s)
                }, tint, style = stroke)
            }
            Glyph.IMAGE -> {
                drawRoundRect(tint, topLeft = p(0.14f, 0.20f), size = Size(s * 0.72f, s * 0.60f), cornerRadius = CornerRadius(s * 0.10f), style = stroke)
                drawCircle(tint, radius = s * 0.07f, center = p(0.36f, 0.40f))
                drawPath(Path().apply {
                    moveTo(0.18f * s, 0.74f * s); lineTo(0.42f * s, 0.54f * s); lineTo(0.56f * s, 0.66f * s)
                    lineTo(0.66f * s, 0.56f * s); lineTo(0.82f * s, 0.72f * s)
                }, tint, style = stroke)
            }
            Glyph.POLL -> {
                drawLine(tint, p(0.24f, 0.30f), p(0.76f, 0.30f), strokeWidth = w * 1.4f, cap = StrokeCap.Round)
                drawLine(tint, p(0.24f, 0.50f), p(0.56f, 0.50f), strokeWidth = w * 1.4f, cap = StrokeCap.Round)
                drawLine(tint, p(0.24f, 0.70f), p(0.66f, 0.70f), strokeWidth = w * 1.4f, cap = StrokeCap.Round)
            }
            Glyph.CAMERA -> {
                drawRoundRect(tint, topLeft = p(0.12f, 0.30f), size = Size(s * 0.54f, s * 0.42f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.66f * s, 0.44f * s); lineTo(0.88f * s, 0.32f * s); lineTo(0.88f * s, 0.70f * s); lineTo(0.66f * s, 0.58f * s)
                }, tint, style = stroke)
            }
            Glyph.MIC -> {
                drawRoundRect(tint, topLeft = p(0.38f, 0.12f), size = Size(s * 0.24f, s * 0.44f), cornerRadius = CornerRadius(s * 0.12f), style = stroke)
                drawPath(Path().apply {
                    moveTo(0.26f * s, 0.46f * s); cubicTo(0.26f * s, 0.74f * s, 0.74f * s, 0.74f * s, 0.74f * s, 0.46f * s)
                }, tint, style = stroke)
                drawLine(tint, p(0.5f, 0.70f), p(0.5f, 0.86f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.QR -> {
                for ((x, y) in listOf(0.16f to 0.16f, 0.58f to 0.16f, 0.16f to 0.58f)) {
                    drawRoundRect(tint, topLeft = p(x, y), size = Size(s * 0.26f, s * 0.26f), cornerRadius = CornerRadius(s * 0.04f), style = stroke)
                }
                drawCircle(tint, radius = w * 0.8f, center = p(0.66f, 0.66f))
                drawCircle(tint, radius = w * 0.8f, center = p(0.82f, 0.82f))
                drawCircle(tint, radius = w * 0.8f, center = p(0.82f, 0.62f))
            }
            Glyph.MASK -> {
                // A photo frame with a lock in it: "Disguise as photo".
                drawRoundRect(tint, topLeft = p(0.12f, 0.18f), size = Size(s * 0.76f, s * 0.64f), cornerRadius = CornerRadius(s * 0.10f), style = stroke)
                drawRoundRect(tint, topLeft = p(0.38f, 0.48f), size = Size(s * 0.24f, s * 0.18f), cornerRadius = CornerRadius(s * 0.04f))
                drawPath(Path().apply {
                    moveTo(0.43f * s, 0.48f * s); lineTo(0.43f * s, 0.42f * s)
                    cubicTo(0.43f * s, 0.32f * s, 0.57f * s, 0.32f * s, 0.57f * s, 0.42f * s); lineTo(0.57f * s, 0.48f * s)
                }, tint, style = stroke)
            }
            Glyph.COMPOSE -> {
                drawRoundRect(tint, topLeft = p(0.14f, 0.20f), size = Size(s * 0.62f, s * 0.66f), cornerRadius = CornerRadius(s * 0.10f), style = stroke)
                drawLine(tint, p(0.46f, 0.54f), p(0.84f, 0.16f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.MORE -> {
                for (x in listOf(0.24f, 0.5f, 0.76f)) drawCircle(tint, radius = w * 0.95f, center = p(x, 0.5f))
            }
            Glyph.REPLY -> {
                drawPath(Path().apply { moveTo(0.40f * s, 0.24f * s); lineTo(0.16f * s, 0.46f * s); lineTo(0.40f * s, 0.68f * s) }, tint, style = stroke)
                drawPath(Path().apply {
                    moveTo(0.18f * s, 0.46f * s); lineTo(0.56f * s, 0.46f * s)
                    cubicTo(0.76f * s, 0.46f * s, 0.86f * s, 0.58f * s, 0.86f * s, 0.80f * s)
                }, tint, style = stroke)
            }
            Glyph.FORWARD -> {
                drawPath(Path().apply { moveTo(0.60f * s, 0.24f * s); lineTo(0.84f * s, 0.46f * s); lineTo(0.60f * s, 0.68f * s) }, tint, style = stroke)
                drawPath(Path().apply {
                    moveTo(0.82f * s, 0.46f * s); lineTo(0.44f * s, 0.46f * s)
                    cubicTo(0.24f * s, 0.46f * s, 0.14f * s, 0.58f * s, 0.14f * s, 0.80f * s)
                }, tint, style = stroke)
            }
            Glyph.PIN -> {
                drawPath(Path().apply {
                    moveTo(0.36f * s, 0.14f * s); lineTo(0.64f * s, 0.14f * s); lineTo(0.60f * s, 0.40f * s)
                    lineTo(0.74f * s, 0.56f * s); lineTo(0.26f * s, 0.56f * s); lineTo(0.40f * s, 0.40f * s); close()
                }, tint, style = stroke)
                drawLine(tint, p(0.5f, 0.56f), p(0.5f, 0.88f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.SMILE -> {
                drawCircle(tint, radius = s * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawCircle(tint, radius = w * 0.75f, center = p(0.37f, 0.42f))
                drawCircle(tint, radius = w * 0.75f, center = p(0.63f, 0.42f))
                drawArc(tint, 25f, 130f, false, topLeft = p(0.32f, 0.38f), size = Size(s * 0.36f, s * 0.28f), style = stroke)
            }
            Glyph.TRASH -> {
                drawLine(tint, p(0.18f, 0.26f), p(0.82f, 0.26f), strokeWidth = w, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(0.40f * s, 0.26f * s); lineTo(0.40f * s, 0.16f * s); lineTo(0.60f * s, 0.16f * s); lineTo(0.60f * s, 0.26f * s) }, tint, style = stroke)
                drawPath(Path().apply { moveTo(0.26f * s, 0.26f * s); lineTo(0.30f * s, 0.86f * s); lineTo(0.70f * s, 0.86f * s); lineTo(0.74f * s, 0.26f * s) }, tint, style = stroke)
                drawLine(tint, p(0.44f, 0.42f), p(0.44f, 0.72f), strokeWidth = w * 0.8f, cap = StrokeCap.Round)
                drawLine(tint, p(0.56f, 0.42f), p(0.56f, 0.72f), strokeWidth = w * 0.8f, cap = StrokeCap.Round)
            }
            Glyph.INFO -> {
                drawCircle(tint, radius = s * 0.40f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.5f, 0.46f), p(0.5f, 0.74f), strokeWidth = w, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.62f, center = p(0.5f, 0.30f))
            }
            Glyph.BELL, Glyph.BELL_SLASH -> {
                drawPath(Path().apply {
                    moveTo(0.20f * s, 0.72f * s); lineTo(0.28f * s, 0.60f * s); lineTo(0.28f * s, 0.44f * s)
                    cubicTo(0.28f * s, 0.18f * s, 0.72f * s, 0.18f * s, 0.72f * s, 0.44f * s)
                    lineTo(0.72f * s, 0.60f * s); lineTo(0.80f * s, 0.72f * s); close()
                }, tint, style = stroke)
                drawLine(tint, p(0.43f, 0.84f), p(0.57f, 0.84f), strokeWidth = w, cap = StrokeCap.Round)
                if (glyph == Glyph.BELL_SLASH) drawLine(tint, p(0.14f, 0.14f), p(0.86f, 0.86f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.BLOCK -> {
                drawCircle(tint, radius = s * 0.38f, center = p(0.5f, 0.5f), style = stroke)
                drawLine(tint, p(0.24f, 0.76f), p(0.76f, 0.24f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.FLAG -> {
                drawLine(tint, p(0.24f, 0.14f), p(0.24f, 0.88f), strokeWidth = w, cap = StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(0.24f * s, 0.18f * s); lineTo(0.78f * s, 0.18f * s); lineTo(0.66f * s, 0.36f * s)
                    lineTo(0.78f * s, 0.54f * s); lineTo(0.24f * s, 0.54f * s)
                }, tint, style = stroke)
            }
            Glyph.LEAVE -> {
                drawPath(Path().apply {
                    moveTo(0.50f * s, 0.16f * s); lineTo(0.20f * s, 0.16f * s); lineTo(0.20f * s, 0.84f * s); lineTo(0.50f * s, 0.84f * s)
                }, tint, style = stroke)
                drawLine(tint, p(0.40f, 0.50f), p(0.86f, 0.50f), strokeWidth = w, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(0.70f * s, 0.34f * s); lineTo(0.86f * s, 0.50f * s); lineTo(0.70f * s, 0.66f * s) }, tint, style = stroke)
            }
            Glyph.PENCIL -> {
                drawPath(Path().apply {
                    moveTo(0.18f * s, 0.82f * s); lineTo(0.22f * s, 0.64f * s); lineTo(0.66f * s, 0.20f * s)
                    lineTo(0.80f * s, 0.34f * s); lineTo(0.36f * s, 0.78f * s); close()
                }, tint, style = stroke)
                drawLine(tint, p(0.56f, 0.30f), p(0.70f, 0.44f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.LINK -> {
                drawRoundRect(tint, topLeft = p(0.10f, 0.36f), size = Size(s * 0.44f, s * 0.28f), cornerRadius = CornerRadius(s * 0.14f), style = stroke)
                drawRoundRect(tint, topLeft = p(0.46f, 0.36f), size = Size(s * 0.44f, s * 0.28f), cornerRadius = CornerRadius(s * 0.14f), style = stroke)
            }
            Glyph.TEXT_LINES -> {
                drawLine(tint, p(0.18f, 0.28f), p(0.82f, 0.28f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, p(0.18f, 0.46f), p(0.82f, 0.46f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, p(0.18f, 0.64f), p(0.82f, 0.64f), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(tint, p(0.18f, 0.82f), p(0.56f, 0.82f), strokeWidth = w, cap = StrokeCap.Round)
            }
            Glyph.COPY -> {
                drawRoundRect(tint, topLeft = p(0.16f, 0.16f), size = Size(s * 0.46f, s * 0.46f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
                drawRoundRect(tint, topLeft = p(0.38f, 0.38f), size = Size(s * 0.46f, s * 0.46f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
            }
            Glyph.MAP_PIN -> {
                drawPath(Path().apply {
                    moveTo(0.5f * s, 0.90f * s)
                    cubicTo(0.30f * s, 0.66f * s, 0.20f * s, 0.52f * s, 0.20f * s, 0.40f * s)
                    cubicTo(0.20f * s, 0.22f * s, 0.34f * s, 0.10f * s, 0.5f * s, 0.10f * s)
                    cubicTo(0.66f * s, 0.10f * s, 0.80f * s, 0.22f * s, 0.80f * s, 0.40f * s)
                    cubicTo(0.80f * s, 0.52f * s, 0.70f * s, 0.66f * s, 0.5f * s, 0.90f * s); close()
                }, tint, style = stroke)
                drawCircle(tint, radius = s * 0.10f, center = p(0.5f, 0.40f), style = stroke)
            }
            Glyph.HEART -> {
                drawPath(Path().apply {
                    moveTo(0.5f * s, 0.84f * s)
                    cubicTo(0.20f * s, 0.62f * s, 0.12f * s, 0.46f * s, 0.12f * s, 0.34f * s)
                    cubicTo(0.12f * s, 0.20f * s, 0.24f * s, 0.14f * s, 0.33f * s, 0.14f * s)
                    cubicTo(0.42f * s, 0.14f * s, 0.48f * s, 0.20f * s, 0.5f * s, 0.26f * s)
                    cubicTo(0.52f * s, 0.20f * s, 0.58f * s, 0.14f * s, 0.67f * s, 0.14f * s)
                    cubicTo(0.76f * s, 0.14f * s, 0.88f * s, 0.20f * s, 0.88f * s, 0.34f * s)
                    cubicTo(0.88f * s, 0.46f * s, 0.80f * s, 0.62f * s, 0.5f * s, 0.84f * s); close()
                }, tint, style = stroke)
            }
            Glyph.VIDEO -> {
                drawRoundRect(tint, topLeft = p(0.10f, 0.28f), size = Size(s * 0.56f, s * 0.44f), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
                drawPath(Path().apply { moveTo(0.66f * s, 0.44f * s); lineTo(0.90f * s, 0.30f * s); lineTo(0.90f * s, 0.70f * s); lineTo(0.66f * s, 0.56f * s) }, tint, style = stroke)
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
