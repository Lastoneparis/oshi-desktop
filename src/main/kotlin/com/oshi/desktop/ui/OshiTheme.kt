package com.oshi.desktop.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OSHI's design language, ported from the shipped app — NOT invented here.
 *
 * Every value below is transcribed from `OSHI/Theme.swift`, the tokens the iOS app and
 * therefore the Mac Catalyst app already draw with. That is the whole point: this window
 * is a third client of the same product, and a desktop that looked like a generic
 * Material app would be a different product wearing the same name.
 *
 * ============================================================ WHAT THE REFERENCE ACTUALLY IS
 *
 * Checked against `~/Desktop/OSHI_Mac_Captures/HABILLE-1-Messages.png`, a capture of the
 * shipped macOS build, because reading a token file tells you the palette and not the
 * composition. Two things in that screenshot contradict what a "2026 futuristic messenger"
 * brief would produce, and the screenshot wins:
 *
 *  - **It is LIGHT, not dark.** White surfaces, hairline separators, black text. A dark
 *    glass redesign would not be a better OSHI; it would be a different app.
 *  - **The brand is a single violet**, used sparingly — the avatar gradient, the unread
 *    badge, the unread timestamp, the outgoing bubble. Everything else is greyscale.
 *
 * Composition, also from the capture: pill segmented controls, a large bold section title,
 * a filled rounded search field, circular avatars, generous row height, and a right-hand
 * cluster of time + glyph + chevron. Reproduced here rather than reinterpreted.
 *
 * ============================================================ THE ONE DELIBERATE DEPARTURE
 *
 * `Theme.Font` is SF Rounded ("rounded for brand warmth"). SF Rounded is an Apple system
 * face and cannot be shipped on Windows or Linux, so the scale, weights and sizes are kept
 * exactly and the family falls back to the platform default. Matching the RHYTHM matters
 * more than matching the glyphs, and a wrong-but-similar bundled font would be worse than
 * an honest system one.
 */
object OshiTheme {

    // ------------------------------------------------------------------ colour
    // Theme.swift:18-19 — Color(red:0.337, green:0.086, blue:0.925) and the accent pink.

    val brand = Color(0.337f, 0.086f, 0.925f)
    val brandSecondary = Color(0.94f, 0.28f, 0.56f)

    /** Theme.swift:20-23 — brand → Color(red:0.20, green:0.40, blue:0.95). */
    val brandGradient = Brush.linearGradient(listOf(brand, Color(0.20f, 0.40f, 0.95f)))

    /**
     * iOS reads these from UIKit semantic colours, which have no desktop equivalent, so
     * they are pinned to the light-mode values those semantics resolve to on macOS. Named
     * after the iOS semantic they stand in for, so a future dark mode has an obvious seam.
     */
    val background = Color(0xFFFFFFFF)              // systemBackground
    val surface = Color(0xFFF2F2F7)                 // secondarySystemBackground
    val surfaceElevated = Color(0xFFFFFFFF)         // tertiarySystemBackground
    val separator = Color(0x4D3C3C43)               // separator (30% on light)
    val textPrimary = Color(0xFF000000)             // label
    /**
     * **Deliberately NOT iOS's `secondaryLabel`, and this is the one token that departs.**
     *
     * The faithful transcription is `0x993C3C43` — 60% of #3C3C43. Composited over white
     * that is #8A8A8E, which measures **3.45:1** against this background: below the 4.5:1
     * WCAG AA floor for body text, and this is the colour every conversation preview and
     * timestamp in the window is drawn in. iOS gets away with it because its rendering
     * stack, Dynamic Type and typical viewing distance are not a desktop's.
     *
     * 60% black is #666666 → **5.74:1**. The rhythm of the design is unchanged; the text
     * is legible. Fidelity to a palette is not worth failing contrast on the most-read
     * text in the app, and a token that fails AA must not sit in this file waiting for the
     * next surface to use it.
     */
    val textSecondary = Color(0x99000000)
    val success = Color(0xFF34C759)
    val danger = Color(0xFFFF3B30)
    val warning = Color(0xFFFF9500)

    /**
     * THE OUTGOING BUBBLE IS A GRADIENT, NOT A FLAT FILL.
     *
     * `Theme.swift:36` says `bubbleOutgoing = brand`, and transcribing only that line
     * produced a flat violet — which is not what the shipped app draws. The real bubble
     * comes from `BubbleStyleManager.swift:46-67`, where every accent is a *light-to-dark
     * diagonal pair*, and the flat brand is only the fallback tint used for small previews.
     *
     * These are the six shipped accents, verbatim. The user's choice is a stored
     * preference on the phone; this window ships [BubbleAccent.OSHI] and leaves the picker
     * for later rather than inventing a seventh.
     */
    enum class BubbleAccent(val label: String, val from: Color, val to: Color) {
        OSHI("OSHI", Color(0.42f, 0.20f, 0.98f), Color(0.29f, 0.09f, 0.86f)),
        OCEAN("Ocean", Color(0.16f, 0.52f, 0.98f), Color(0.05f, 0.31f, 0.80f)),
        SUNSET("Sunset", Color(0.98f, 0.40f, 0.52f), Color(0.90f, 0.28f, 0.24f)),
        FOREST("Forest", Color(0.16f, 0.72f, 0.52f), Color(0.05f, 0.50f, 0.42f)),
        GRAPHITE("Graphite", Color(0.26f, 0.27f, 0.31f), Color(0.14f, 0.15f, 0.18f)),
        ROSE("Rose", Color(0.93f, 0.30f, 0.66f), Color(0.76f, 0.15f, 0.52f));

        /** The diagonal the phone draws: top-leading to bottom-trailing. */
        fun brush(widthPx: Float, heightPx: Float): Brush =
            Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(widthPx, heightPx))
    }

    /**
     * The five shipped bubble SHAPES and their tail rule (`BubbleStyleManager.swift:113-135`).
     *
     * `tailCornerScale` tightens the corner nearest the speaker so a bubble "belongs to
     * someone rather than floating". Note the two 1.0 entries are not oversights: the
     * Swift says flattening a card-like shape "looks broken", so SQUARE and SLAB opt out.
     */
    enum class BubbleShape(val label: String, val radiusDp: Int, val tailCornerScale: Float) {
        ROUND("Round", 22, 0.30f),
        SOFT("Soft", 15, 0.30f),
        SQUARE("Square", 6, 1.00f),
        CAPSULE("Capsule", 26, 0.18f),
        SLAB("Slab", 10, 1.00f),
    }

    // Theme.swift:36-39 — incoming is the grey surface; outgoing is the gradient above.
    val bubbleOutgoing = brand
    val bubbleOutgoingText = Color.White
    val bubbleIncoming = surface
    val bubbleIncomingText = textPrimary

    // ------------------------------------------------------------------ spacing
    // Theme.swift:43-50, verbatim.

    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    // ------------------------------------------------------------------ radius
    // Theme.swift:55-59. `lg` is the message bubble and is commented as such there.

    val radiusSm = RoundedCornerShape(8.dp)
    val radiusMd = RoundedCornerShape(12.dp)
    val radiusBubble = RoundedCornerShape(18.dp)
    val radiusXl = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(999.dp)

    // ------------------------------------------------------------------ motion
    // Theme.swift:65-71. SwiftUI's spring(response:dampingFraction:) maps to Compose's
    // spring(dampingRatio, stiffness); response is a PERIOD, so stiffness ≈ (2π/r)².

    fun <T> snappy() = spring<T>(dampingRatio = 0.78f, stiffness = 438f)   // response 0.30
    fun <T> smooth() = spring<T>(dampingRatio = 0.85f, stiffness = 224f)   // response 0.42
    fun <T> bouncy() = spring<T>(dampingRatio = 0.62f, stiffness = 273f)   // response 0.38
    fun <T> fade() = tween<T>(durationMillis = 200)

    /** Theme.swift:103 — "subtle press-scale… the standard 'feels alive' interaction". */
    const val PRESS_SCALE = 0.96f

    // ------------------------------------------------------------------ elevation
    // Theme.swift:77-79. Compose has no y-offset shadow primitive, so these are the alpha
    // and blur to hand a Surface; the offsets are recorded for whoever draws them properly.

    val shadowLow = 4.dp      // black 8%,  y 2
    val shadowMedium = 10.dp  // black 12%, y 4
    val shadowHigh = 20.dp    // black 18%, y 8

    // ------------------------------------------------------------------ type
    // Theme.swift:86-91. Sizes and weights verbatim; family is the platform default —
    // see THE ONE DELIBERATE DEPARTURE above.

    private val family = FontFamily.SansSerif

    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = family, fontSize = 28.sp, fontWeight = FontWeight.Bold),
        headlineMedium = TextStyle(fontFamily = family, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = family, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = family, fontSize = 15.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontFamily = family, fontSize = 13.sp, fontWeight = FontWeight.Normal),
        labelSmall = TextStyle(fontFamily = family, fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )

    val colors = lightColorScheme(
        primary = brand,
        onPrimary = Color.White,
        secondary = brandSecondary,
        background = background,
        onBackground = textPrimary,
        surface = surfaceElevated,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        error = danger,
        outline = separator,
    )

    /**
     * __ONE_AVATAR_NOT_THREE_2026_11_07__ The deterministic avatar gradient — now the
     * SAME one the phones draw.
     *
     * What this used to be, and why it was replaced:
     *
     *  * the hue came from `h * 31 + c.code`, a different hash from the phones' FNV-1a,
     *    so one person was one colour on an iPhone and a different colour here. An avatar
     *    colour exists to be recognised, and three platforms disagreeing about it is the
     *    same failure as everyone being blue, one step subtler;
     *  * it spread hues at CONSTANT saturation and value (0.62/0.86). `AvatarIdentity`
     *    documents exactly what that costs: at constant S/V the yellow-green-teal band
     *    (45°-185°) lands between 2.85:1 and 3.77:1 against white, so the monogram on a
     *    third of the palette was under WCAG AA and hard to read.
     *
     * The fourteen pairs below are copied from `OSHI/AvatarIdentity.swift` value for
     * value, with the brightness solved per hue so white clears 5.2:1 everywhere. The
     * index comes from the same FNV-1a over the key's UTF-8, so the slot a peer lands in
     * is identical on iOS, Android and here. `AvatarParityTest` re-reads the Swift source
     * and fails on drift.
     *
     * (The comment that stood here described iOS as indexing "a six-entry palette by
     * `publicKey.hashValue.magnitude % 6`". That was true two revisions ago and had
     * quietly stopped being true — a note about another platform is not a check of it.)
     */
    val avatarGradients: List<Pair<Color, Color>> = listOf(
        Color(0.458f, 0.247f, 0.950f) to Color(0.602f, 0.144f, 0.798f),  // 268°
        Color(0.669f, 0.224f, 0.860f) to Color(0.723f, 0.130f, 0.663f),  // 292°
        Color(0.829f, 0.216f, 0.727f) to Color(0.696f, 0.125f, 0.373f),  // 320°
        Color(0.865f, 0.225f, 0.492f) to Color(0.727f, 0.131f, 0.141f),  // 345°
        Color(0.806f, 0.210f, 0.230f) to Color(0.677f, 0.326f, 0.122f),  // 8°
        Color(0.663f, 0.320f, 0.172f) to Color(0.557f, 0.420f, 0.100f),  // 28°
        Color(0.540f, 0.394f, 0.140f) to Color(0.441f, 0.454f, 0.082f),  // 48°
        Color(0.485f, 0.497f, 0.129f) to Color(0.269f, 0.417f, 0.075f),  // 72°
        Color(0.331f, 0.526f, 0.137f) to Color(0.116f, 0.442f, 0.079f),  // 100°
        Color(0.139f, 0.533f, 0.171f) to Color(0.081f, 0.448f, 0.258f),  // 135°
        Color(0.136f, 0.522f, 0.361f) to Color(0.079f, 0.438f, 0.432f),  // 165°
        Color(0.153f, 0.587f, 0.572f) to Color(0.089f, 0.345f, 0.493f),  // 188°
        Color(0.200f, 0.598f, 0.768f) to Color(0.116f, 0.275f, 0.645f),  // 208°
        Color(0.247f, 0.423f, 0.950f) to Color(0.242f, 0.144f, 0.798f),  // 235°
    )

    /**
     * FNV-1a over the key's UTF-8 — seedless, and identical on every platform.
     *
     * The modulus is taken UNSIGNED. iOS does this arithmetic on `UInt64`
     * throughout, so a signed modulus with a sign correction is a DIFFERENT
     * number whenever the hash comes out negative — which is about half the
     * time. The first version here did exactly that and put "hugo" in slot 12
     * where the phones put it in slot 0; the shared vectors in
     * `AvatarParityTest` are what caught it.
     */
    fun avatarSlot(key: String, modulo: Int = avatarGradients.size): Int {
        if (modulo <= 0) return 0
        var hash = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325 as a signed Long
        for (b in key.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (b.toLong() and 0xff)) * 0x100000001b3L
        }
        return (hash.toULong() % modulo.toULong()).toInt()
    }

    fun avatarBrush(address: String): Brush {
        val (start, end) = avatarGradients[avatarSlot(address)]
        return Brush.linearGradient(listOf(start, end))
    }

    /** The two-letter monogram the capture draws inside that circle. */
    fun monogram(name: String, address: String): String {
        val source = name.trim().ifEmpty { address }
        val words = source.split(' ', '-', '_').filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            source.length >= 2 -> source.take(2).uppercase()
            else -> source.take(1).uppercase().ifEmpty { "?" }
        }
    }
}
