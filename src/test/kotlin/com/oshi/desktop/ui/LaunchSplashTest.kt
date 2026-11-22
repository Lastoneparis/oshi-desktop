package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __O_BUBBLE_MASCOT_2026_11_16__ The splash timeline, without a window. */
class LaunchSplashTest {

    @Test
    fun `logo first, crossfade to the mascot, the mascot reached well inside 1_2 s`() {
        val f0 = LaunchSplash.frameAt(0, reducedMotion = false)
        assertEquals(1f, f0.logoAlpha); assertEquals(0f, f0.neutralAlpha); assertFalse(f0.waving)
        val mid = LaunchSplash.frameAt(LaunchSplash.LOGO_HOLD_MS + LaunchSplash.CROSSFADE_MS / 2, false)
        // The logo stays opaque beneath the mascot fading in over it (same canvas).
        assertEquals(1f, mid.logoAlpha)
        assertTrue(mid.neutralAlpha in 0.3f..0.7f)
        assertTrue(LaunchSplash.WAVE_START_MS <= 1_200)
        assertEquals(0, LaunchSplash.frameAt(LaunchSplash.WAVE_START_MS, false).waveIndex)
        assertEquals(39, LaunchSplash.frameAt(LaunchSplash.WAVE_START_MS + 1_999, false).waveIndex)
        // After the wave: the neutral pose it ends on, held.
        val after = LaunchSplash.frameAt(LaunchSplash.WAVE_START_MS + 2_000, false)
        assertNull(after.waveIndex); assertEquals(1f, after.neutralAlpha)
    }

    @Test
    fun `reduced motion is the fade alone`() {
        for (t in listOf(LaunchSplash.WAVE_START_MS, 600L, 1_000L, 5_000L)) {
            val f = LaunchSplash.frameAt(t, reducedMotion = true)
            assertFalse("t=$t", f.waving)
            assertEquals(1f, f.neutralAlpha)
        }
        assertTrue(LaunchSplash.isSettled(LaunchSplash.WAVE_START_MS, reducedMotion = true))
        assertFalse(LaunchSplash.isSettled(LaunchSplash.WAVE_START_MS, reducedMotion = false))
    }

    @Test
    fun `late frames hold the neutral pose instead of skipping the wave`() {
        val f = LaunchSplash.frameAt(700, reducedMotion = false, waveStartMs = 900)
        assertNull(f.waveIndex); assertEquals(1f, f.neutralAlpha)
        assertEquals(0, LaunchSplash.frameAt(900, false, waveStartMs = 900).waveIndex)
    }

    @Test
    fun `the launch frames render from the bundled pack`() {
        val img = LaunchSplash.renderFrame(LaunchSplash.WAVE_START_MS + 500, reducedMotion = false, px = 64)
        var opaque = 0
        for (y in 0 until 64) for (x in 0 until 64) if ((img.getRGB(x, y) ushr 24) > 200) opaque++
        assertTrue("expected a drawn mascot, got $opaque opaque px", opaque > 400)
    }

    /** __OLED_SPLASH_2026_11_16__ The card follows the chosen theme; black + glass rim in OLED. */
    @Test
    fun `splash ground follows the chosen theme`() {
        val light = LaunchSplash.Ground.of(Appearance(ThemeMode.LIGHT, glass = false), systemDark = true)
        assertEquals(java.awt.Color(255, 255, 255), light.fill)
        // __GLASS_ALL_THEMES__ Light glass (opt-in): its pale ground and its glass rim.
        val lightGlass = LaunchSplash.Ground.of(Appearance(ThemeMode.LIGHT, glass = true), systemDark = true)
        assertEquals(java.awt.Color(0xEF, 0xEE, 0xF5), lightGlass.fill)
        val dark = LaunchSplash.Ground.of(Appearance(ThemeMode.DARK, oledBlack = false), systemDark = false)
        assertEquals(java.awt.Color(0x1C, 0x1C, 0x1E), dark.fill)
        val oled = LaunchSplash.Ground.of(Appearance(ThemeMode.DARK, oledBlack = true), systemDark = false)
        assertEquals(java.awt.Color(0, 0, 0), oled.fill)
        // OLED's rim is the glass one: lit at the top, fading down.
        val (top, bottom) = oled.rim!!
        assertTrue(top.alpha > bottom.alpha)
        // System follows the OS; OLED only applies when that resolves to dark.
        assertEquals(java.awt.Color(0, 0, 0), LaunchSplash.Ground.of(Appearance(ThemeMode.SYSTEM, oledBlack = true), systemDark = true).fill)
        assertEquals(java.awt.Color(255, 255, 255), LaunchSplash.Ground.of(Appearance(ThemeMode.SYSTEM, oledBlack = true, glass = false), systemDark = false).fill)
    }

    /**
     * __SPLASH_GREY_FACE_2026_11_18__ Mid-crossfade on the Dark and OLED cards, the centre of
     * the O must already be the face colour — never the grey mix of a half-faded face and the
     * dark card that showed through the logo's transparent hole.
     */
    @Test
    fun `no grey face shows through the O during the crossfade on dark grounds`() {
        val px = 256
        for (oled in listOf(false, true)) {
            val ground = LaunchSplash.Ground.of(Appearance(ThemeMode.DARK, oledBlack = oled), systemDark = true)
            for (t in listOf(0L, LaunchSplash.LOGO_HOLD_MS + LaunchSplash.CROSSFADE_MS / 4, LaunchSplash.LOGO_HOLD_MS + LaunchSplash.CROSSFADE_MS / 2)) {
                val card = LaunchSplash.renderCard(t, reducedMotion = true, px = px, ground = ground)
                // Hole centre in pack coordinates (measured: the forehead, 0.33 of the side, clear of eyes and smile), plus the card padding.
                val c = java.awt.Color(card.getRGB(LaunchSplash.PAD + (0.50 * px).toInt(), LaunchSplash.PAD + (0.33 * px).toInt()), true)
                val lum = (c.red + c.green + c.blue) / 3
                assertTrue("oled=$oled t=$t centre=$c is not the light face", lum > 200)
            }
        }
    }

    @Test
    fun `the hole fill is a closed disk inside the logo`() {
        val (logo, _) = LaunchSplash.loadStills(256)
        val hole = LaunchSplash.holeFill(logo!!)!!
        var n = 0
        for (y in 0 until 256) for (x in 0 until 256) if ((hole.getRGB(x, y) ushr 24) > 0) n++
        // r ≈ 0.18 of the side ⇒ π·46² ≈ 6 650 px at 256.
        assertTrue("hole area $n", n in 5_000..9_000)
        assertEquals(0, hole.getRGB(2, 2) ushr 24)
    }
}
