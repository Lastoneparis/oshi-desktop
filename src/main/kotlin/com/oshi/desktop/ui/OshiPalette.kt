package com.oshi.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * __OLED_BLACK_2026_11_16__ Light, Dark, and "OLED black".
 *
 * Until this file the window had ONE palette, pinned to the light values the iOS semantics
 * resolve to (see `OshiTheme`). A user asked for "darker colours for OLED screens", which on
 * this client first meant there had to be a dark theme at all.
 *
 *  * [ThemeMode] is the phones' own three-way choice (`settings.appearance.theme.*`):
 *    follow the system, or force light or dark. **The default is LIGHT** — the window drew
 *    light-only for its whole life, and switching every dark-desktop user to a brand-new
 *    palette on upgrade is not something a settings row should do behind their back.
 *  * [Appearance.oledBlack] is a modifier on the DARK palette, never a fourth mode: when the
 *    resolved theme is dark (forced, or the system's), the backgrounds go to true #000000 so
 *    an OLED panel switches those pixels off. In light it does nothing. Default OFF.
 *
 * Tokens keep the names of the iOS semantics they stand in for, so a call site reading
 * `OshiTheme.surface` gets the dark `secondarySystemBackground` analogue without changing.
 */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        /** Unknown or missing → [LIGHT], the behaviour every existing install had. */
        fun fromId(raw: String?): ThemeMode = entries.firstOrNull { it.id == raw } ?: LIGHT
    }
}

/** What the person chose. Persisted by `AppearancePreferences`; never synced. */
data class Appearance(val mode: ThemeMode = ThemeMode.LIGHT, val oledBlack: Boolean = false) {
    /** Whether the dark palette applies, given what the OS currently says. */
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    companion object { val DEFAULT = Appearance() }
}

/**
 * __OLED_GLASS_2026_11_16__ "Crystal glass" on true black, in numbers.
 *
 * Compose Desktop cannot blur what lies behind an arbitrary node, so the glass is emulated
 * the way a still photograph of glass reads: a translucent fill that is a little lighter at
 * the top ([fillTop] → [fillBottom]), a 1 px rim lit from above ([rimTop] → [rimBottom]),
 * and a faint sheen just under the top edge ([sheen]). [raisedBoost] multiplies the fill for
 * things that sit on a card or carry text in a list (incoming bubbles, the composer field,
 * call controls). Floating things (menus, dialogs, the media viewer) are glass over CONTENT,
 * not over black, so their base is the opaque [floating] — a see-through menu with no blur
 * behind it is noise, not glass.
 *
 * [glow] is the one colour that is not white: the brand violet, blurred, behind the few
 * things that carry focus — the send button, the selected destination, primary buttons.
 *
 * Every text token is measured on the brightest composite these produce
 * ([brightestGround]) in `AppearanceThemeTest`.
 */
class GlassTokens(
    val fillTop: Color,
    val fillBottom: Color,
    val raisedBoost: Float,
    val rimTop: Color,
    val rimBottom: Color,
    val sheen: Color,
    val floating: Color,
    val glow: Color,
) {
    /** The fill colours for a [raised] or plain glass shape. */
    fun fill(raised: Boolean): Pair<Color, Color> =
        if (!raised) fillTop to fillBottom
        else fillTop.copy(alpha = (fillTop.alpha * raisedBoost).coerceAtMost(1f)) to
            fillBottom.copy(alpha = (fillBottom.alpha * raisedBoost).coerceAtMost(1f))

    /**
     * The lightest ground text can sit on, over [under]: the lighter of a RAISED shape under
     * its sheen (bubble, field, control) and a `surface` chip or field inside a plain card
     * under its sheen. Raised glass is itself the chip, so nothing stacks a `surface` on it.
     * Used by the contrast test, which is why it is not private.
     */
    fun brightestGround(under: Color, surface: Color): Color {
        val raised = sheen.compositeOver(fill(raised = true).first.compositeOver(under))
        val chipInCard = surface.compositeOver(sheen.compositeOver(fillTop.compositeOver(under)))
        return if (raised.luminance() > chipInCard.luminance()) raised else chipInCard
    }

    companion object {
        val OLED = GlassTokens(
            fillTop = Color(0x14FFFFFF),     // 7.8% white
            fillBottom = Color(0x08FFFFFF),  // 3.1%
            raisedBoost = 1.6f,
            rimTop = Color(0x2EFFFFFF),      // 18%
            rimBottom = Color(0x0AFFFFFF),   // 4%
            sheen = Color(0x0DFFFFFF),       // 5%
            floating = Color(0xFF0E0E11),
            glow = Color(0xA66A3BFF),        // brand violet, 65%
        )
    }
}

/**
 * One complete set of colour tokens. The three instances are constants, so the Material
 * [scheme] built from each is built once.
 */
class OshiPalette private constructor(
    val name: String,
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** The violet as INK — text, glyphs, cursors, tints. Fills use `OshiTheme.brandGradient`. */
    val brand: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val bubbleIncoming: Color,
    /** A hairline drawn around cards. Transparent where the fill alone separates them. */
    val cardOutline: Color,
    /** The chosen segment of a segmented control — lighter than its track in the dark. */
    val selectedFill: Color,
    /** Behind the call screen and the ringing card. */
    val callBackdropTop: Color,
    val callBackdropBottom: Color,
    val ringCard: Color,
    /**
     * __OLED_GLASS_2026_11_16__ The ground a whole PANE is painted on (More, Radio, Peers,
     * the empty thread…). It was `surface` everywhere, which in OLED made every pane a
     * #111113 slab; in OLED it is the window's own black so the glass cards on it have
     * something to be glass against.
     */
    val paneGround: Color,
    /**
     * The minimized-call bar, under WHITE text. It used to be `success`, which white
     * measures about 2:1 on; this green holds white at AA (`AppearanceThemeTest`).
     */
    val callBar: Color,
    /** The crystal-glass treatment, or null where the palette draws flat (light, dark). */
    val glass: GlassTokens?,
    /** Material containers — dialogs, menus, text fields. */
    private val container: Color,
    private val containerHigh: Color,
) {
    val scheme: ColorScheme by lazy {
        if (!isDark) lightColorScheme(
            primary = brand,
            onPrimary = Color.White,
            secondary = OshiTheme.brandSecondary,
            background = background,
            onBackground = textPrimary,
            surface = surfaceElevated,
            onSurface = textPrimary,
            surfaceVariant = surface,
            onSurfaceVariant = textSecondary,
            error = danger,
            outline = separator,
        ) else darkColorScheme(
            // On a dark ground the violet is ink, so Material's "on primary" is dark: a
            // Switch thumb or a filled button label reads against the lighter violet.
            primary = brand,
            onPrimary = Color(0xFF1B0B4D),
            primaryContainer = OshiTheme.brandFill,
            onPrimaryContainer = Color.White,
            secondary = OshiTheme.brandSecondary,
            background = background,
            onBackground = textPrimary,
            surface = background,
            onSurface = textPrimary,
            // Material composites nothing: a translucent token would let what is under a
            // menu or a field show through. Its variant is the composited equivalent.
            surfaceVariant = if (surface.alpha < 1f) surface.compositeOver(background) else surface,
            onSurfaceVariant = textSecondary,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = background,
            surfaceContainerLow = container,
            surfaceContainer = container,
            surfaceContainerHigh = containerHigh,
            surfaceContainerHighest = containerHigh,
            inverseSurface = Color(0xFFE5E5EA),
            inverseOnSurface = Color.Black,
            error = danger,
            outline = separator,
            outlineVariant = separator,
            scrim = Color.Black,
        )
    }

    override fun toString(): String = "OshiPalette($name)"

    companion object {
        // Shared dark tokens. White at 60% composites to #999999 on black (7.4:1) and stays
        // above 4.5:1 on every dark surface below; `PaletteContrastTest` measures each pair.
        private val darkText = Color.White
        private val darkTextSecondary = Color(0x99FFFFFF)
        /** #A98BFF: the brand violet, lightened until it reads as text on #2C2C2E (5.2:1). */
        private val darkBrand = Color(0xFFA98BFF)
        private val darkSuccess = Color(0xFF30D158)   // iOS systemGreen, dark
        private val darkDanger = Color(0xFFFF6961)    // iOS accessible red, dark
        private val darkWarning = Color(0xFFFF9F0A)   // iOS systemOrange, dark

        /** Exactly the values this window shipped with before there was a choice. */
        val LIGHT = OshiPalette(
            name = "light",
            isDark = false,
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFF2F2F7),
            surfaceElevated = Color(0xFFFFFFFF),
            separator = Color(0x4D3C3C43),
            textPrimary = Color(0xFF000000),
            textSecondary = Color(0x99000000),
            brand = Color(0.337f, 0.086f, 0.925f),
            success = Color(0xFF34C759),
            danger = Color(0xFFFF3B30),
            warning = Color(0xFFFF9500),
            bubbleIncoming = Color(0xFFF2F2F7),
            cardOutline = Color.Transparent,
            selectedFill = Color(0xFFFFFFFF),
            callBackdropTop = Color(0xFF1B1036),
            callBackdropBottom = Color(0xFF0B0B14),
            ringCard = Color(0xFF1C1B2E),
            paneGround = Color(0xFFF2F2F7),
            callBar = Color(0xFF1A7F37),
            glass = null,
            container = Color(0xFFF2F2F7),
            containerHigh = Color(0xFFFFFFFF),
        )

        /** iOS-style dark greys: `systemBackground` lifted to #1C1C1E so OLED has a contrast. */
        val DARK = OshiPalette(
            name = "dark",
            isDark = true,
            background = Color(0xFF1C1C1E),
            surface = Color(0xFF2C2C2E),
            surfaceElevated = Color(0xFF1C1C1E),
            separator = Color(0x40FFFFFF),
            textPrimary = darkText,
            textSecondary = darkTextSecondary,
            brand = darkBrand,
            success = darkSuccess,
            danger = darkDanger,
            warning = darkWarning,
            // #2C2C2E, not iOS's lighter #3A3A3C: the violet ink drawn inside an incoming
            // bubble (quotes, tickers) measures 4.25:1 on #3A3A3C and 5.1:1 here.
            bubbleIncoming = Color(0xFF2C2C2E),
            cardOutline = Color.Transparent,
            selectedFill = Color(0xFF5A5A5E),
            callBackdropTop = Color(0xFF1B1036),
            callBackdropBottom = Color(0xFF0B0B14),
            ringCard = Color(0xFF1C1B2E),
            paneGround = Color(0xFF2C2C2E),
            callBar = Color(0xFF1A7F37),
            glass = null,
            container = Color(0xFF2C2C2E),
            containerHigh = Color(0xFF3A3A3C),
        )

        /**
         * True black, with crystal glass on it (__OLED_GLASS_2026_11_16__).
         *
         * #000000 wherever a large area is painted — window, lists, thread, pane grounds, the
         * call screen — so an OLED panel switches those pixels off. Everything that sits ON
         * that black is glass ([GlassTokens.OLED]): a translucent white fill, a 1 px rim that
         * is brighter at the top, and a soft sheen under the top edge. `surface` (fields,
         * chips, hover) is itself translucent white, so a field inside a card reads one step
         * lighter than the card around it instead of being a flat #111113 hole.
         */
        val OLED = OshiPalette(
            name = "oled",
            isDark = true,
            background = Color(0xFF000000),
            // 8% white: #141414 over black, a touch more over a glass card. Not more: the
            // violet ink and the red on a chip inside a card are measured against it.
            surface = Color(0x14FFFFFF),
            surfaceElevated = Color(0xFF000000),
            // 15%: a separator inside glass is a seam, not a rule.
            separator = Color(0x26FFFFFF),
            textPrimary = darkText,
            textSecondary = darkTextSecondary,
            brand = darkBrand,
            success = darkSuccess,
            danger = darkDanger,
            warning = darkWarning,
            bubbleIncoming = Color(0xFF1C1C1E),
            cardOutline = Color(0x2EFFFFFF),
            selectedFill = Color(0xFF2C2C2E),
            callBackdropTop = Color(0xFF000000),
            callBackdropBottom = Color(0xFF000000),
            ringCard = Color(0xFF000000),
            paneGround = Color(0xFF000000),
            // A green tint the glass strip is drawn over; white on it is ~13:1.
            callBar = Color(0xFF0B2A14),
            glass = GlassTokens.OLED,
            container = Color(0xFF161618),
            containerHigh = Color(0xFF1C1C1E),
        )

        val ALL: List<OshiPalette> = listOf(LIGHT, DARK, OLED)

        /** The one decision: which palette a given choice + system state draws with. */
        fun resolve(appearance: Appearance, systemDark: Boolean): OshiPalette = when {
            !appearance.isDark(systemDark) -> LIGHT
            appearance.oledBlack -> OLED
            else -> DARK
        }

        /**
         * WCAG 2.x contrast ratio of [fg] over [bg]. A translucent [fg] is composited over
         * [bg] first — that is how `textSecondary` is actually painted.
         */
        fun contrast(fg: Color, bg: Color): Double {
            val solidBg = if (bg.alpha < 1f) bg.compositeOver(Color.Black) else bg
            val solidFg = fg.compositeOver(solidBg)
            val a = solidFg.luminance().toDouble()
            val b = solidBg.luminance().toDouble()
            return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }
    }
}
