package com.oshi.desktop.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /**
     * __OLED_BLACK_2026_11_16__ THE PALETTE IS STATE NOW. Every colour token below that
     * depends on light/dark is a GETTER over [palette], which reads two snapshot states
     * ([appearance] and [systemDark]). Call sites did not change: a composable that reads
     * `OshiTheme.surface` is subscribed to the palette by that read and recomposes when the
     * person flips the setting — which is what "applies live" needs, with no CompositionLocal
     * threaded through 36 files. See `OshiPalette` for the values and the reasoning.
     */
    var appearance: Appearance by mutableStateOf(Appearance.DEFAULT)

    /** What the OS reports; written by [OshiMaterialTheme] from `isSystemInDarkTheme()`. */
    var systemDark: Boolean by mutableStateOf(false)

    val palette: OshiPalette get() = OshiPalette.resolve(appearance, systemDark)

    /**
     * The shipped violet as a FILL — constant in every theme. White on it is 7.74:1, and the
     * outgoing bubble and primary buttons draw with it (via [brandGradient]).
     */
    val brandFill = Color(0.337f, 0.086f, 0.925f)

    /**
     * The violet as INK: text, glyphs, cursors, selection tints. It is [brandFill] in light;
     * on a dark ground #5616EC measures 2.7:1 against black, so the dark palettes lighten it
     * to #A98BFF — the same hue, readable. The accent stays purple.
     */
    val brand: Color get() = palette.brand
    val brandSecondary = Color(0.94f, 0.28f, 0.56f)

    /** Theme.swift:20-23 — brand → Color(red:0.20, green:0.40, blue:0.95). */
    val brandGradient = Brush.linearGradient(listOf(brandFill, Color(0.20f, 0.40f, 0.95f)))

    /**
     * iOS reads these from UIKit semantic colours, which have no desktop equivalent, so
     * they are pinned to the values those semantics resolve to on macOS — light ones in
     * [OshiPalette.LIGHT], dark ones in [OshiPalette.DARK] / [OshiPalette.OLED].
     */
    val background: Color get() = palette.background              // systemBackground
    val surface: Color get() = palette.surface                    // secondarySystemBackground
    val surfaceElevated: Color get() = palette.surfaceElevated    // tertiarySystemBackground
    val separator: Color get() = palette.separator                // separator (30% on light)
    val textPrimary: Color get() = palette.textPrimary            // label
    /** Hairline around cards; only OLED draws one (black card on a near-black ground). */
    val cardOutline: Color get() = palette.cardOutline
    /** The selected segment of a segmented control. White in light, lifted grey in dark. */
    val selectedFill: Color get() = palette.selectedFill
    /** The ground a whole pane is painted on — black in OLED (__OLED_GLASS_2026_11_16__). */
    val paneGround: Color get() = palette.paneGround
    /** The glass treatment, or null where the palette is flat. See `GlassTokens`. */
    val glass: GlassTokens? get() = palette.glass
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
     * next surface to use it. (Dark: 60% white, measured per surface in PaletteContrastTest.)
     */
    val textSecondary: Color get() = palette.textSecondary
    val success: Color get() = palette.success
    val danger: Color get() = palette.danger
    val warning: Color get() = palette.warning

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
    val bubbleOutgoing = brandFill
    val bubbleOutgoingText = Color.White
    val bubbleIncoming: Color get() = palette.bubbleIncoming
    val bubbleIncomingText: Color get() = palette.textPrimary

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

    /** The Material scheme for the current palette (built once per palette). */
    val colors: ColorScheme get() = palette.scheme

    /**
     * __AVATAR_PALETTE_V2_2026_11_17__ The default-avatar palette — the SAME one the
     * phones draw, from the one spec all three platforms share:
     * `docs/avatar-palette.json` (hex values, gradient direction, highlight geometry,
     * selection rule and identifier -> slot vectors). `AvatarParityTest` re-reads that
     * file, so this client cannot drift from iOS/Android alone.
     *
     * v1 (`__ONE_AVATAR_NOT_THREE_2026_11_07__`) ported the phones' fourteen flat hues and
     * FNV-1a slot, replacing a desktop-only `h * 31 + c.code` hash that made one person a
     * different colour here than on their iPhone. v2 (owner: "more agreeable and
     * diversified colours or texture") is sixteen two-tone soft gradients — nine mid tones
     * with WHITE initials, seven pastels with a deep ink of their own hue — plus a soft
     * radial light blob baked into the fill (see `Monogram`).
     *
     * Selection: trim; a UUID (group id) is lowercased — iOS spells `uuidString` in
     * UPPERCASE — anything else (a peer's base64 public key) is used verbatim; SHA-256 over
     * the UTF-8 bytes; slot = first digest byte mod 16.
     */
    data class AvatarEntry(val start: Color, val end: Color, val ink: Color)

    private fun rgb(hex: Long): Color = Color(0xFF000000L or hex)

    /** In spec order. Do not reorder: the index IS the identity. */
    val avatarEntries: List<AvatarEntry> = listOf(
        AvatarEntry(rgb(0xCB6C30), rgb(0xB63924), rgb(0xFFFFFF)),  //  0 coral
        AvatarEntry(rgb(0xFDD7AC), rgb(0xF6A97A), rgb(0x743B1F)),  //  1 apricot
        AvatarEntry(rgb(0xF8E9A3), rgb(0xF1BF5B), rgb(0x6B4716)),  //  2 honey
        AvatarEntry(rgb(0xD8EFB4), rgb(0x9AD387), rgb(0x31552A)),  //  3 pistachio
        AvatarEntry(rgb(0x43963C), rgb(0x027048), rgb(0xFFFFFF)),  //  4 fern
        AvatarEntry(rgb(0xC2F5E0), rgb(0x7ED8C6), rgb(0x07564F)),  //  5 mint
        AvatarEntry(rgb(0x179696), rgb(0x066674), rgb(0xFFFFFF)),  //  6 teal
        AvatarEntry(rgb(0xC2ECFE), rgb(0x87C6F2), rgb(0x264B7A)),  //  7 sky
        AvatarEntry(rgb(0x3E8CC9), rgb(0x2953A5), rgb(0xFFFFFF)),  //  8 ocean
        AvatarEntry(rgb(0x7B7FC9), rgb(0x5E45AE), rgb(0xFFFFFF)),  //  9 periwinkle
        AvatarEntry(rgb(0xE3DCFF), rgb(0xC9AFF4), rgb(0x553781)),  // 10 lavender
        AvatarEntry(rgb(0xB76BC3), rgb(0x7D32A3), rgb(0xFFFFFF)),  // 11 grape
        AvatarEntry(rgb(0xC96598), rgb(0x9E3082), rgb(0xFFFFFF)),  // 12 orchid
        AvatarEntry(rgb(0xFEDAE1), rgb(0xF0A9C4), rgb(0x7E314D)),  // 13 blush
        AvatarEntry(rgb(0xDB6371), rgb(0xA92552), rgb(0xFFFFFF)),  // 14 raspberry
        AvatarEntry(rgb(0x768997), rgb(0x47536A), rgb(0xFFFFFF)),  // 15 slate
    )

    /** `start to end` per entry. */
    val avatarGradients: List<Pair<Color, Color>> = avatarEntries.map { it.start to it.end }

    /** Highlight geometry, fractions of the diameter (spec `highlight`). */
    const val AVATAR_HIGHLIGHT_X = 0.30f
    const val AVATAR_HIGHLIGHT_Y = 0.20f
    const val AVATAR_HIGHLIGHT_RADIUS = 0.80f
    const val AVATAR_HIGHLIGHT_ALPHA = 0.24f

    private val UUID_RE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /** Spec `selection.normalise`: a UUID is lowercased, anything else verbatim. */
    fun avatarKey(key: String): String {
        val trimmed = key.trim()
        return if (UUID_RE.matches(trimmed)) trimmed.lowercase(java.util.Locale.ROOT) else key
    }

    private val avatarDigestCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** SHA-256(UTF-8(normalised key))[0] mod [modulo] — identical on every platform. */
    fun avatarSlot(key: String, modulo: Int = avatarEntries.size): Int {
        if (modulo <= 0) return 0
        val normalized = avatarKey(key)
        val byte = avatarDigestCache.getOrPut(normalized) {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))[0].toInt() and 0xff
        }
        return byte % modulo
    }

    fun avatarEntry(key: String): AvatarEntry = avatarEntries[avatarSlot(key)]

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
