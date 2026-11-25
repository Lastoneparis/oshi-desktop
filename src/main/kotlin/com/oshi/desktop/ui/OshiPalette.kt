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

/**
 * What the person chose. Persisted by `AppearancePreferences`; never synced.
 *
 * __GLASS_ALL_THEMES__ [glass] is the "Glass effect" switch. `null` = never touched, which
 * means the per-theme default ([GlassDefaults]); a stored true/false applies to every theme.
 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.LIGHT,
    val oledBlack: Boolean = false,
    val glass: Boolean? = null,
) {
    /** Whether the dark palette applies, given what the OS currently says. */
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** Which of the three looks applies, given what the OS currently says. */
    fun look(systemDark: Boolean): Look = when {
        !isDark(systemDark) -> Look.LIGHT
        oledBlack -> Look.OLED
        else -> Look.DARK
    }

    /** Whether glass is drawn, given what the OS currently says. */
    fun glassOn(systemDark: Boolean): Boolean = glass ?: GlassDefaults.of(look(systemDark))

    /** Light, Dark, Extra dark — the three looks the "Glass effect" switch applies to. */
    enum class Look { LIGHT, DARK, OLED }

    companion object { val DEFAULT = Appearance() }
}

/**
 * __GLASS_ALL_THEMES__ What "Glass effect" is when the person never touched it.
 *
 * OLED: ON — it is what Extra dark has looked like since it shipped, and nobody who chose it
 * should see it change on upgrade. Light and Dark: see the values' own comments.
 */
object GlassDefaults {
    const val OLED = true
    const val DARK = GLASS_DEFAULT_DARK
    const val LIGHT = GLASS_DEFAULT_LIGHT

    fun of(look: Appearance.Look): Boolean = when (look) {
        Appearance.Look.LIGHT -> LIGHT
        Appearance.Look.DARK -> DARK
        Appearance.Look.OLED -> OLED
    }
}

// Decided on renders (__GLASS_ALL_THEMES__, 2026-11-21: every screen of renderScreens,
// renderSettingsScreens and the messages sheets, Light/Dark x glass on/off, en + fr), see
// GlassDefaults:
//  * LIGHT = ON. Frosted white on the pale lavender-grey ground reads as a real material —
//    white bubbles, cards, menus and fields in recessed wells — and every text token holds AA
//    on every composite (AppearanceThemeTest). The flat Light stays one switch away.
//  * DARK = ON. Same verdict, quieter: contrast caps the fill at ~6% white on #1C1C1E, so the
//    look is carried by the lit rims; nothing gets harder to read than flat Dark.
// Decision 2026-11-21: Light/Dark glass is OPT-IN so an update never changes an existing user's look.
private const val GLASS_DEFAULT_LIGHT = false
private const val GLASS_DEFAULT_DARK = false

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
    /**
     * __GLASS_ALL_THEMES__ The 1 px edge of a recessed field well (`glassField`). White on
     * the dark grounds; a faint dark line in Light, where a white one would vanish.
     */
    val wellRim: Color = Color.White.copy(alpha = 0.06f),
    /** Under a coloured strip (the minimized-call bar): how much of [fillTop] it takes. */
    val stripTint: Color = fillTop,
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

    /**
     * __GLASS_ALL_THEMES__ EVERY opaque ground text can end up on over [under]: the plain and
     * raised fill at their top (under the sheen) and bottom, a `surface` chip inside a card,
     * and the floating base. In Light the risky one is the DARKEST, on the dark looks the
     * LIGHTEST, so the contrast test measures all of them instead of guessing which.
     */
    fun textGrounds(under: Color, surface: Color): Map<String, Color> {
        val (rt, rb) = fill(raised = true)
        return mapOf(
            "glassTop" to sheen.compositeOver(fillTop.compositeOver(under)),
            "glassBottom" to fillBottom.compositeOver(under),
            "glassRaisedTop" to sheen.compositeOver(rt.compositeOver(under)),
            "glassRaisedBottom" to rb.compositeOver(under),
            "glassChipInCard" to surface.compositeOver(sheen.compositeOver(fillTop.compositeOver(under))),
            "glassChipInCardBottom" to surface.compositeOver(fillBottom.compositeOver(under)),
            "glassFloating" to floating,
        )
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

        /**
         * __GLASS_ALL_THEMES__ Smoked glass on the classic dark grey (#1C1C1E). The same recipe
         * as [OLED], but the FILL is capped by contrast, not taste: grey starts where OLED's
         * glass ends, and the red (#FF6961) and violet (#A98BFF) inks hold 4.5:1 only up to
         * about #333335. The raised top under its sheen lands at #333334. So on grey the
         * glass is carried by a brighter lit RIM (24% → 6%) rather than by a thicker fill.
         */
        val DARK = GlassTokens(
            fillTop = Color(0x0FFFFFFF),     // 5.9%
            fillBottom = Color(0x07FFFFFF),  // 2.7%
            raisedBoost = 1.3f,
            rimTop = Color(0x3DFFFFFF),      // 24%
            rimBottom = Color(0x0FFFFFFF),   // 6%
            sheen = Color(0x05FFFFFF),       // 2%
            floating = Color(0xFF262629),
            glow = Color(0x996A3BFF),        // brand violet, 60%
            // The green call strip takes less white than a card: white on it stays above AA.
            stripTint = Color(0x08FFFFFF),
        )

        /**
         * __GLASS_ALL_THEMES__ Frosted white on a cool pale ground. On white, a translucent
         * white shape is invisible, so the Light glass palette lays a faint lavender-grey
         * ground under everything and the glass is high-luminance white on it, lighter at the
         * top. The rim cannot be lit white alone (white on near-white vanishes): it goes from
         * a white highlight at the top to a faint indigo hairline at the bottom, which is how
         * frosted glass on a pale table actually photographs.
         */
        val LIGHT = GlassTokens(
            fillTop = Color(0xD9FFFFFF),     // 85% white
            fillBottom = Color(0x9EFFFFFF),  // 62%
            raisedBoost = 1.18f,
            rimTop = Color(0xFFFFFFFF),
            rimBottom = Color(0x2423204A),   // 14% indigo-grey
            sheen = Color(0x80FFFFFF),       // 50% white
            floating = Color(0xFFFBFBFE),
            glow = Color(0x596A3BFF),        // brand violet, 35%: softer on a pale ground
            wellRim = Color(0x1A23204A),
            stripTint = Color(0x00FFFFFF),
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
    /** __BUBBLE_MEDIA_2026_11_21__ the bubble tokens; see [BubbleColors]. */
    val bubbles: BubbleColors,
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
            surfaceVariant = if (surface.alpha < 1f) surface.compositeOver(background) else surface,
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

    /** __GLASS_ALL_THEMES__ This palette with the glass switched on or off, and its grounds. */
    private fun variant(
        name: String,
        glass: GlassTokens?,
        bubbles: BubbleColors = this.bubbles,
        background: Color = this.background,
        surface: Color = this.surface,
        paneGround: Color = this.paneGround,
        separator: Color = this.separator,
        container: Color = this.container,
        containerHigh: Color = this.containerHigh,
    ) = OshiPalette(
        name = name, isDark = isDark, background = background, surface = surface,
        surfaceElevated = surfaceElevated,
        separator = separator, textPrimary = textPrimary, textSecondary = textSecondary, brand = brand,
        success = success, danger = danger, warning = warning, bubbleIncoming = bubbleIncoming,
        cardOutline = cardOutline, selectedFill = selectedFill, callBackdropTop = callBackdropTop,
        callBackdropBottom = callBackdropBottom, ringCard = ringCard, paneGround = paneGround,
        callBar = callBar, glass = glass, container = container, containerHigh = containerHigh,
        bubbles = bubbles,
    )

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
            // __BUBBLE_MEDIA_2026_11_21__ white on the soft lavender thread (was #F2F2F7 on white).
            bubbleIncoming = Color(0xFFFFFFFF),
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
            bubbles = BubbleColors.LIGHT,
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
            // __BUBBLE_MEDIA_2026_11_21__ #23262C: an elevated cool surface on the deeper #131418
            // thread (was #2C2C2E on #1C1C1E). The violet ink inside it (quotes, tickers,
            // mentions) measures 5.6:1 here; on iOS's lighter #3A3A3C it was 4.25:1.
            bubbleIncoming = Color(0xFF23262C),
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
            bubbles = BubbleColors.DARK,
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
            bubbles = BubbleColors.OLED,
        )

        /**
         * __GLASS_ALL_THEMES__ Light with "Glass effect" on. A white window gives white glass
         * nothing to be glass against, so the large areas take a faint cool lavender-grey
         * ([background] = [paneGround]) and the frosted white of [GlassTokens.LIGHT] sits on it.
         * `surface` (fields, chips, hover) becomes a translucent indigo-grey WELL: a step
         * darker than whatever it is on, the ground or a white card, so a field never vanishes
         * into a card the way an opaque #F2F2F7 would.
         */
        val LIGHT_GLASS = LIGHT.variant(
            name = "light-glass",
            glass = GlassTokens.LIGHT,
            background = Color(0xFFEFEEF5),
            surface = Color(0x1223204A),
            paneGround = Color(0xFFEFEEF5),
            separator = Color(0x3323204A),
            container = Color(0xFFF7F7FB),
            containerHigh = Color(0xFFFFFFFF),
            // The glass ground is already the lavender-grey; the thread keeps it.
            bubbles = BubbleColors.LIGHT.copy(chatBackground = Color(0xFFEFEEF5)),
        )

        /**
         * __GLASS_ALL_THEMES__ Dark with "Glass effect" on: the same #1C1C1E window, panes on
         * that ground instead of on #2C2C2E slabs (as OLED does on black). `surface` stays the
         * opaque #2C2C2E: a translucent one stacked on a glass card would push the red and
         * violet inks under 4.5:1.
         */
        val DARK_GLASS = DARK.variant(
            name = "dark-glass",
            glass = GlassTokens.DARK,
            paneGround = Color(0xFF1C1C1E),
            separator = Color(0x33FFFFFF),
        )

        /** __GLASS_ALL_THEMES__ Extra dark with "Glass effect" off: the black, drawn flat. */
        val OLED_FLAT = OLED.variant(name = "oled-flat", glass = null)

        /** The three shipped looks with their default glass (Light, Dark flat; OLED glass). */
        val ALL: List<OshiPalette> = listOf(LIGHT, DARK, OLED)

        /** Every palette a choice can resolve to — what the contrast tests walk. */
        val EVERY: List<OshiPalette> = listOf(LIGHT, LIGHT_GLASS, DARK, DARK_GLASS, OLED, OLED_FLAT)

        /** The one decision: which palette a given choice + system state draws with. */
        fun resolve(appearance: Appearance, systemDark: Boolean): OshiPalette {
            val glass = appearance.glassOn(systemDark)
            return when (appearance.look(systemDark)) {
                Appearance.Look.LIGHT -> if (glass) LIGHT_GLASS else LIGHT
                Appearance.Look.DARK -> if (glass) DARK_GLASS else DARK
                Appearance.Look.OLED -> if (glass) OLED else OLED_FLAT
            }
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

/**
 * __BUBBLE_MEDIA_2026_11_21__ The conversation's own colours (BUBBLE_MEDIA_SPEC §2), one set
 * per look. Values, not a second theme system: [OshiPalette.bubbles] carries them and
 * `OshiTheme` exposes them as getters, so a theme switch applies live like every other token.
 *
 * Every pair is measured in `BubbleColorsContrastTest`: body text ≥ 4.5:1 on its bubble,
 * timestamps and ticks ≥ 3:1 (they are drawn ≥ 4.5:1 anyway), each sender hue ≥ 4.5:1 on the
 * incoming bubble it is drawn in — flat and, where the look has glass, on every glass ground.
 */
data class BubbleColors(
    /** Behind the thread. Not the window white/black: a bubble needs a ground to sit on. */
    val chatBackground: Color,
    /** Our bubble. Light: a pale OSHI lavender under near-black text; dark: a deep violet. */
    val outgoing: Color,
    val outgoingText: Color,
    /** Secondary text in our bubble (forwarded label, quote body, file sizes). */
    val outgoingTextSoft: Color,
    /** Links, mentions, ticker symbols, chosen poll options in our bubble. */
    val outgoingAccent: Color,
    /** The 1 px rim of our bubble. */
    val outgoingRim: Color,
    /** The 1 px rim of theirs. */
    val incomingRim: Color,
    /** The "read" double tick beside a bubble, on [chatBackground]. */
    val readTick: Color,
    /** Group sender names: one hue per member, stable per member key ([senderColor]). */
    val senderNames: List<Color>,
) {
    /** Whether [outgoing] is a dark fill (white-on-violet), i.e. cards inside it use light tints. */
    val outgoingIsDark: Boolean get() = outgoing.luminance() < 0.4f

    /** The name hue for a member: the same key always gets the same hue, on every launch. */
    fun senderColor(key: String): Color = senderNames[senderIndex(key, senderNames.size)]

    companion object {
        /** FNV-1a over the key's UTF-16 units — deterministic across JVMs and platforms. */
        fun senderIndex(key: String, n: Int): Int {
            var h = 0x811C9DC5.toInt()
            for (ch in key) { h = h xor ch.code; h *= 0x01000193 }
            return ((h % n) + n) % n
        }

        private val nameLight = listOf(
            Color(0xFFC62828), // red      5.6:1 on white
            Color(0xFFB45309), // amber    5.0
            Color(0xFF2E7D32), // green    5.1
            Color(0xFF00796B), // teal     5.3
            Color(0xFF1565C0), // blue     5.8
            Color(0xFF6D28D9), // violet   7.1
            Color(0xFFAD1457), // magenta  7.0
        )
        private val nameDark = listOf(
            Color(0xFFFF8A80), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFF4DD0E1),
            Color(0xFF64B5F6), Color(0xFFB39DFF), Color(0xFFF48FB1),
        )

        val LIGHT = BubbleColors(
            chatBackground = Color(0xFFF1EEF8),
            outgoing = Color(0xFFEBE4FF),
            outgoingText = Color(0xFF1C1433),        // 14.3:1 on #EBE4FF
            outgoingTextSoft = Color(0xAD1C1433),    // 68% → #5E5774, 5.5:1
            outgoingAccent = Color(0xFF5616EC),      // brand fill, 6.3:1
            outgoingRim = Color(0x1F5616EC),
            incomingRim = Color(0x14000000),
            readTick = Color(0xFF1A7F37),            // 4.6:1 on the thread; #34C759 was 2.0:1
            senderNames = nameLight,
        )

        val DARK = BubbleColors(
            chatBackground = Color(0xFF131418),
            outgoing = Color(0xFF5340AE),
            outgoingText = Color.White,              // 7.7:1
            outgoingTextSoft = Color(0xC7FFFFFF),    // 78% → 5.4:1
            outgoingAccent = Color.White,
            outgoingRim = Color(0x24FFFFFF),
            incomingRim = Color(0x14FFFFFF),
            readTick = Color(0xFF30D158),
            senderNames = nameDark,
        )

        /** Black thread; our bubble a deeper violet than Dark's so it does not glow on black. */
        val OLED = DARK.copy(
            chatBackground = Color(0xFF000000),
            outgoing = Color(0xFF45339A),            // white 9.6:1
        )
    }
}
