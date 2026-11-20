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

    @Test fun `the default is the window that shipped - light, OLED off`() {
        assertEquals(ThemeMode.LIGHT, Appearance.DEFAULT.mode)
        assertFalse(Appearance.DEFAULT.oledBlack)
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance.DEFAULT, systemDark = false))
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance.DEFAULT, systemDark = true))
    }

    @Test fun `OLED black does nothing while the theme is light`() {
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance(ThemeMode.LIGHT, oledBlack = true), systemDark = true))
        assertSame(OshiPalette.LIGHT, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM, oledBlack = true), systemDark = false))
    }

    @Test fun `OLED black applies to forced dark and to the system's dark`() {
        assertSame(OshiPalette.OLED, OshiPalette.resolve(Appearance(ThemeMode.DARK, oledBlack = true), systemDark = false))
        assertSame(OshiPalette.OLED, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM, oledBlack = true), systemDark = true))
    }

    @Test fun `dark without OLED black is the grey dark palette`() {
        assertSame(OshiPalette.DARK, OshiPalette.resolve(Appearance(ThemeMode.DARK), systemDark = false))
        assertSame(OshiPalette.DARK, OshiPalette.resolve(Appearance(ThemeMode.SYSTEM), systemDark = true))
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
            assertEquals(OshiTheme.brandFill, OshiTheme.bubbleOutgoing)
            // The ink violet is still violet: blue-dominant with red above green.
            val b = OshiTheme.brand
            assertTrue("$p brand $b", b.blue > b.red && b.red > b.green)
        }
    }

    // ------------------------------------------------------------------ WCAG AA

    @Test fun `every text token clears 4_5 to 1 on every ground in every palette`() {
        val failures = mutableListOf<String>()
        for (p in OshiPalette.ALL) {
            val grounds = mapOf(
                "background" to p.background, "surface" to p.surface, "elevated" to p.surfaceElevated,
                "bubbleIncoming" to p.bubbleIncoming, "selectedFill" to p.selectedFill,
                "paneGround" to p.paneGround,
            ) + (p.glass?.let { g ->
                // The brightest glass a text can sit on: raised fill + sheen + a chip.
                mapOf("glassBrightest" to g.brightestGround(p.background, p.surface),
                      "glassFloating" to g.floating)
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
        for (p in listOf(OshiPalette.DARK, OshiPalette.OLED)) {
            // + the incoming bubble: the ticker chip's up/down colours are these tokens in dark.
            for (g in listOfNotNull(p.background, p.surface, p.bubbleIncoming, p.glass?.brightestGround(p.background, p.surface))) {
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

    @Test fun `only OLED is glass - light and dark stay flat`() {
        assertEquals(null, OshiPalette.LIGHT.glass)
        assertEquals(null, OshiPalette.DARK.glass)
        assertSame(GlassTokens.OLED, OshiPalette.OLED.glass)
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
        for (p in OshiPalette.ALL) {
            val bar = p.glass?.let { it.fillTop.compositeOver(p.callBar) } ?: p.callBar
            val ratio = OshiPalette.contrast(Color.White, bar)
            assertTrue("${p.name}: white on call bar = $ratio", ratio >= 4.5)
        }
    }

    // ------------------------------------------------------------------ live

    @Test fun `tokens are live - changing the appearance changes what call sites read`() {
        assertEquals(Color.White, OshiTheme.background)
        assertEquals(Color.Black, Ink.strong)

        OshiTheme.appearance = Appearance(ThemeMode.DARK, oledBlack = true)
        assertEquals(Color.Black, OshiTheme.background)
        assertEquals(Color.White, Ink.strong)
        assertEquals(Color.White, OshiTheme.bubbleIncomingText)
        assertSame(OshiPalette.OLED.scheme, OshiTheme.colors)

        OshiTheme.appearance = Appearance(ThemeMode.SYSTEM, oledBlack = true)
        assertEquals(Color.White, OshiTheme.background)       // system is light here
        OshiTheme.systemDark = true
        assertEquals(Color.Black, OshiTheme.background)       // and now it is not
    }
}
