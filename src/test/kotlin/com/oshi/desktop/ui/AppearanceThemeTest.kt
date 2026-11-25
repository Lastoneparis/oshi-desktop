package com.oshi.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.oshi.desktop.ui.components.Ink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __OLED_BLACK_2026_11_16__ Which palette a choice draws with, what "OLED black" means in
 * numbers, and that every text token clears WCAG AA on every ground it is painted on.
 */
class AppearanceThemeTest {

    @After fun reset() {
        OshiTheme.appearance = Appearance.DEFAULT
        OshiTheme.systemDark = false
    }

    // ------------------------------------------------------------------ resolution

    @Test fun `the default is light, OLED off, glass at its per-theme default (off in Light and Dark)`() {
        assertEquals(ThemeMode.LIGHT, Appearance.DEFAULT.mode)
        assertFalse(Appearance.DEFAULT.oledBlack)
        // __GLASS_ALL_THEMES__ Light/Dark glass is opt-in (default OFF): an update never changes a user's look.
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance.DEFAULT, systemDark = false))
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance.DEFAULT, systemDark = true))
        assertSame(OshiPalette.LIGHT_GLASS, OshiPalette.resolve(Appearance.DEFAULT.copy(glass = true), systemDark = true))
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance.DEFAULT.copy(glass = false), systemDark = true))
    }

    @Test fun `OLED black does nothing while the theme is light`() {
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance(ThemeMode.LIGHT, oledBlack = true, glass = false), systemDark = true))
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM, oledBlack = true, glass = false), systemDark = false))
        assertSame(OshiPalette.LIGHT_GLASS, OshiPalette.resolve(Appearance(ThemeMode.LIGHT, oledBlack = true, glass = true), systemDark = true))
    }

    @Test fun `OLED black applies to forced dark and to the system's dark`() {
        assertSame(OshiPalette.OLED, OshiPalette.resolve(Appearance(ThemeMode.DARK, oledBlack = true), systemDark = false))
        assertSame(OshiPalette.OLED, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM, oledBlack = true), systemDark = true))
    }

    @Test fun `dark without OLED black is the grey dark palette`() {
        assertSame(OshiPalette.DARK, OshiPalette.resolve(Appearance(ThemeMode.DARK, glass = false), systemDark = false))
        assertSame(OshiPalette.DARK, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM, glass = false), systemDark = true))
        assertSame(OshiPalette.DARK, OshiPalette.resolve(Appearance(ThemeMode.DARK), systemDark = false))
        assertSame(OshiPalette.DARK_GLASS, OshiPalette.resolve(Appearance(ThemeMode.DARK, glass = true), systemDark = false))
    }

    @Test fun `an unknown stored mode reads as light, never a crash`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromId("sepia"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromId(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromId("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("system"))
    }

    // ------------------------------------------------------------------ what OLED means

    @Test fun `OLED paints the large areas true black`() {
        val p = OshiPalette.OLED
        for (c in listOf(p.background, p.surfaceElevated, p.callBackdropTop, p.callBackdropBottom, p.ringCard)) {
            assertEquals(Color.Black, c)
        }
        assertEquals(Color.Black, p.scheme.background)
        assertEquals(Color.Black, p.scheme.surface)
    }

    @Test fun `OLED near-blacks stay near black and dimmer than the grey dark palette`() {
        val oled = OshiPalette.OLED
        val dark = OshiPalette.DARK
        for ((raw, d) in listOf(oled.surface to dark.surface, oled.bubbleIncoming to dark.bubbleIncoming)) {
            // `surface` is translucent in OLED (glass); what is painted is it over black.
            val o = raw.compositeOver(oled.background)
            assertTrue("$o should be near-black", o.red < 0.12f && o.green < 0.12f && o.blue < 0.12f)
            assertTrue("$o should be darker than $d", o.red < d.red)
        }
        // ...but the incoming bubble is still a visible shape on the black thread.
        assertTrue(oled.bubbleIncoming != oled.background)
        // Cards are black on a near-black ground, so OLED (and only OLED) draws their edge.
        assertTrue(oled.cardOutline.alpha > 0f)
        assertEquals(0f, OshiPalette.LIGHT.cardOutline.alpha)
        assertEquals(0f, OshiPalette.DARK.cardOutline.alpha)
    }

    @Test fun `the light palette is byte-for-byte what the window drew before`() {
        val p = OshiPalette.LIGHT
        assertEquals(Color(0xFFFFFFFF), p.background)
        assertEquals(Color(0xFFF2F2F7), p.surface)
        assertEquals(Color(0xFFFFFFFF), p.surfaceElevated)
        assertEquals(Color(0x4D3C3C43), p.separator)
        assertEquals(Color(0xFF000000), p.textPrimary)
        assertEquals(Color(0x99000000), p.textSecondary)
        assertEquals(OshiTheme.brandFill, p.brand)
        assertFalse(p.isDark)
    }

    @Test fun `purple accents are kept - the fill is the shipped violet in every theme`() {
        for (p in OshiPalette.ALL) {
            OshiTheme.appearance = if (p === OshiPalette.LIGHT) Appearance.DEFAULT
            else Appearance(ThemeMode.DARK, oledBlack = p === OshiPalette.OLED)
            assertEquals(Color(0.337f, 0.086f, 0.925f), OshiTheme.brandFill)
            // __BUBBLE_MEDIA_2026_11_21__ the outgoing bubble is a palette token now (pale
            // lavender in Light, deep violet in the dark looks) — still violet.
            val o = OshiTheme.bubbleOutgoing
            assertTrue("$p outgoing $o", o.blue > o.red && o.red > o.green)
            // The ink violet is still violet: blue-dominant with red above green.
            val b = OshiTheme.brand
            assertTrue("$p brand $b", b.blue > b.red && b.red > b.green)
        }
    }

    // ------------------------------------------------------------------ WCAG AA

    @Test fun `every text token clears 4_5 to 1 on every ground in every palette`() {
        val failures = mutableListOf<String>()
        for (p in OshiPalette.EVERY) {
            val grounds = mapOf(
                // A translucent `surface` (OLED, Light glass) is painted over the window's ground.
                "background" to p.background, "surface" to p.surface.compositeOver(p.background), "elevated" to p.surfaceElevated,
                "bubbleIncoming" to p.bubbleIncoming, "selectedFill" to p.selectedFill,
                "paneGround" to p.paneGround,
            ) + (p.glass?.let { g ->
                // The brightest glass a text can sit on: raised fill + sheen + a chip — and,
                // __GLASS_ALL_THEMES__, every other composite (Light's risk is the darkest).
                mapOf("glassBrightest" to g.brightestGround(p.background, p.surface)) +
                    g.textGrounds(p.background, p.surface) +
                    g.textGrounds(p.paneGround, p.surface).mapKeys { "pane." + it.key }
            } ?: emptyMap())
            val inks = mapOf(
                "textPrimary" to p.textPrimary,
                "textSecondary" to p.textSecondary,
                "Ink.soft" to p.textPrimary.copy(alpha = 0.60f),
                "brand" to p.brand,
            )
            for ((gn, g) in grounds) for ((inkName, ink) in inks) {
                // The selected segment only ever carries the strong label (`Ink.strong`).
                if (gn == "selectedFill" && inkName != "textPrimary") continue
                val ratio = OshiPalette.contrast(ink, g)
                if (ratio < 4.5) failures += "${p.name}: $inkName on $gn = ${"%.2f".format(ratio)}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `dark status colours read on the dark grounds`() {
        for (p in OshiPalette.EVERY.filter { it.isDark }) {
            // + the incoming bubble: the ticker chip's up/down colours are these tokens in dark.
            for (g in listOfNotNull(p.background, p.surface, p.bubbleIncoming, p.glass?.brightestGround(p.background, p.surface)) +
                (p.glass?.textGrounds(p.background, p.surface)?.values ?: emptyList())) {
                assertTrue("${p.name} danger", OshiPalette.contrast(p.danger, g) >= 4.5)
                assertTrue("${p.name} success", OshiPalette.contrast(p.success, g) >= 4.5)
            }
        }
    }

    @Test fun `white on the outgoing bubble violet stays above AA`() {
        assertTrue(OshiPalette.contrast(Color.White, OshiTheme.brandFill) >= 4.5)
    }

    @Test fun `the contrast helper matches known WCAG values`() {
        assertEquals(21.0, OshiPalette.contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, OshiPalette.contrast(Color.Black, Color.Black), 0.001)
        // 60% black over white composites to #666666: 5.74:1.
        assertEquals(5.74, OshiPalette.contrast(Color(0x99000000), Color.White), 0.02)
    }

    // ------------------------------------------------------------------ glass (__OLED_GLASS_2026_11_16__)

    @Test fun `the flat palettes have no glass and the glass ones do`() {
        assertEquals(null, OshiPalette.LIGHT.glass)
        assertEquals(null, OshiPalette.DARK.glass)
        assertEquals(null, OshiPalette.OLED_FLAT.glass)
        assertSame(GlassTokens.OLED, OshiPalette.OLED.glass)
        assertSame(GlassTokens.DARK, OshiPalette.DARK_GLASS.glass)
        assertSame(GlassTokens.LIGHT, OshiPalette.LIGHT_GLASS.glass)
    }

    @Test fun `OLED panes keep the black ground the glass sits on`() {
        assertEquals(Color.Black, OshiPalette.OLED.paneGround)
        // Light and dark panes are exactly what they were (`surface`).
        assertEquals(OshiPalette.LIGHT.surface, OshiPalette.LIGHT.paneGround)
        assertEquals(OshiPalette.DARK.surface, OshiPalette.DARK.paneGround)
    }

    @Test fun `the glass is translucent white, lit from the top`() {
        val g = GlassTokens.OLED
        for (c in listOf(g.fillTop, g.fillBottom, g.rimTop, g.rimBottom, g.sheen)) {
            assertEquals("$c is white", 1f, c.red); assertEquals(1f, c.green); assertEquals(1f, c.blue)
            assertTrue("$c is translucent", c.alpha in 0.01f..0.25f)
        }
        assertTrue("fill brighter at the top", g.fillTop.alpha > g.fillBottom.alpha)
        // The brief: a rim of ~18% at the top fading to ~4% at the bottom.
        assertEquals(0.18f, g.rimTop.alpha, 0.01f)
        assertEquals(0.04f, g.rimBottom.alpha, 0.01f)
        val (rt, rb) = g.fill(raised = true)
        assertTrue("raised glass is denser", rt.alpha > g.fillTop.alpha && rb.alpha > g.fillBottom.alpha)
        // Glass on black stays near-black: OLED's whole point is pixels mostly off.
        val brightest = g.brightestGround(Color.Black, OshiPalette.OLED.surface)
        assertTrue("$brightest is near-black", brightest.red < 0.25f)
    }

    @Test fun `floating glass is opaque and the glow is the brand violet`() {
        val g = GlassTokens.OLED
        assertEquals(1f, g.floating.alpha)
        assertTrue(g.floating.red < 0.08f)
        assertTrue("violet glow", g.glow.blue > g.glow.red && g.glow.red > g.glow.green)
        assertTrue("a glow, not a fill", g.glow.alpha < 1f)
    }

    @Test fun `white reads on the minimized call bar in every palette`() {
        for (p in OshiPalette.EVERY) {
            val bar = p.glass?.let { it.stripTint.compositeOver(p.callBar) } ?: p.callBar
            val ratio = OshiPalette.contrast(Color.White, bar)
            assertTrue("${p.name}: white on call bar = $ratio", ratio >= 4.5)
        }
    }

    // ------------------------------------------------------------------ glass in every theme (__GLASS_ALL_THEMES__)

    @Test fun `glass defaults - on for OLED, per-theme for light and dark, a stored choice wins everywhere`() {
        assertEquals(null, Appearance.DEFAULT.glass)
        assertTrue(GlassDefaults.OLED)
        assertSame(OshiPalette.OLED, OshiPalette.resolve(Appearance(ThemeMode.DARK, oledBlack = true), false))
        assertSame(if (GlassDefaults.DARK) OshiPalette.DARK_GLASS else OshiPalette.DARK,
            OshiPalette.resolve(Appearance(ThemeMode.DARK), false))
        assertSame(if (GlassDefaults.LIGHT) OshiPalette.LIGHT_GLASS else OshiPalette.LIGHT,
            OshiPalette.resolve(Appearance(ThemeMode.LIGHT), false))
        // On, everywhere.
        assertSame(OshiPalette.LIGHT_GLASS, OshiPalette.resolve(Appearance(ThemeMode.LIGHT, glass = true), false))
        assertSame(OshiPalette.DARK_GLASS, OshiPalette.resolve(Appearance(ThemeMode.DARK, glass = true), false))
        assertSame(OshiPalette.OLED, OshiPalette.resolve(Appearance(ThemeMode.DARK, oledBlack = true, glass = true), false))
        assertSame(OshiPalette.DARK_GLASS, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM, glass = true), true))
        // Off, everywhere — including Extra dark.
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance(ThemeMode.LIGHT, glass = false), false))
        assertSame(OshiPalette.DARK, OshiPalette.resolve(Appearance(ThemeMode.DARK, glass = false), false))
        assertSame(OshiPalette.OLED_FLAT, OshiPalette.resolve(Appearance(ThemeMode.DARK, oledBlack = true, glass = false), false))
    }

    @Test fun `OLED with glass on is exactly the shipped OLED tokens`() {
        val g = GlassTokens.OLED
        assertEquals(Color(0x14FFFFFF), g.fillTop); assertEquals(Color(0x08FFFFFF), g.fillBottom)
        assertEquals(1.6f, g.raisedBoost)
        assertEquals(Color(0x2EFFFFFF), g.rimTop); assertEquals(Color(0x0AFFFFFF), g.rimBottom)
        assertEquals(Color(0x0DFFFFFF), g.sheen); assertEquals(Color(0xFF0E0E11), g.floating)
        assertEquals(Color(0xA66A3BFF), g.glow)
        assertEquals(Color.White.copy(alpha = 0.06f), g.wellRim)
        assertEquals(g.fillTop, g.stripTint)
        // ...and switching glass off in OLED changes nothing else in the palette.
        val on = OshiPalette.OLED; val off = OshiPalette.OLED_FLAT
        assertEquals(on.background, off.background); assertEquals(on.surface, off.surface)
        assertEquals(on.paneGround, off.paneGround); assertEquals(on.cardOutline, off.cardOutline)
        assertEquals(on.scheme.surfaceContainer, off.scheme.surfaceContainer)
    }

    @Test fun `light glass is frosted white on a cool pale ground`() {
        val p = OshiPalette.LIGHT_GLASS
        val g = GlassTokens.LIGHT
        for (c in listOf(g.fillTop, g.fillBottom, g.sheen)) {
            assertEquals("$c is white", 1f, c.red); assertEquals(1f, c.green); assertEquals(1f, c.blue)
        }
        assertTrue("fill lighter at the top", g.fillTop.alpha > g.fillBottom.alpha)
        assertTrue("frosted, not clear", g.fillBottom.alpha >= 0.5f)
        // The ground is NOT white (white glass on white is invisible) but near it, and cool.
        val bg = p.background
        assertTrue("$bg near white", bg.red > 0.9f && bg.blue > 0.9f)
        assertTrue("$bg not white", bg != Color.White)
        assertTrue("$bg cool", bg.blue >= bg.red && bg.blue > bg.green)
        // The glass is visibly lighter than its ground, and its rim has a dark lower edge.
        val card = g.fillBottom.compositeOver(bg)
        assertTrue("glass reads against the ground", OshiPalette.contrast(card, bg) > 1.04)
        assertTrue("rim ends dark", g.rimBottom.red < 0.3f && g.rimBottom.alpha > 0f)
        // Same violet, softer glow.
        assertTrue("violet glow", g.glow.blue > g.glow.red && g.glow.red > g.glow.green)
        assertTrue(g.glow.alpha < GlassTokens.OLED.glow.alpha)
    }

    @Test fun `dark glass is smoked white on the classic grey, not on black`() {
        val p = OshiPalette.DARK_GLASS
        val g = GlassTokens.DARK
        assertEquals(OshiPalette.DARK.background, p.background)
        assertTrue(p.background != Color.Black)
        for (c in listOf(g.fillTop, g.fillBottom, g.rimTop, g.rimBottom, g.sheen)) {
            assertEquals("$c is white", 1f, c.red); assertTrue("$c translucent", c.alpha in 0.01f..0.3f)
        }
        assertTrue(g.fillTop.alpha > g.fillBottom.alpha && g.rimTop.alpha > g.rimBottom.alpha)
        // On grey the rim carries the glass: brighter than OLED's.
        assertTrue(g.rimTop.alpha > GlassTokens.OLED.rimTop.alpha)
        assertEquals(1f, g.floating.alpha)
    }

    // ------------------------------------------------------------------ live

    @Test fun `tokens are live - changing the appearance changes what call sites read`() {
        OshiTheme.appearance = Appearance(ThemeMode.LIGHT, glass = false)
        assertEquals(Color.White, OshiTheme.background)
        assertEquals(Color.Black, Ink.strong)

        OshiTheme.appearance = Appearance(ThemeMode.DARK, oledBlack = true)
        assertEquals(Color.Black, OshiTheme.background)
        assertEquals(Color.White, Ink.strong)
        assertEquals(Color.White, OshiTheme.bubbleIncomingText)
        assertSame(OshiPalette.OLED.scheme, OshiTheme.colors)

        OshiTheme.appearance = Appearance(ThemeMode.SYSTEM, oledBlack = true, glass = false)
        assertEquals(Color.White, OshiTheme.background)       // system is light here
        OshiTheme.appearance = Appearance(ThemeMode.SYSTEM, oledBlack = true, glass = true)
        assertEquals(OshiPalette.LIGHT_GLASS.background, OshiTheme.background)   // glass is opt-in in Light
        OshiTheme.systemDark = true
        assertEquals(Color.Black, OshiTheme.background)       // and now it is not
    }
}
