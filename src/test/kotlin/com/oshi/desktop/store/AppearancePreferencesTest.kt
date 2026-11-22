package com.oshi.desktop.store

import com.oshi.desktop.ui.Appearance
import com.oshi.desktop.ui.AppearanceSettings
import com.oshi.desktop.ui.OshiPalette
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.ThemeMode
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** __OLED_BLACK_2026_11_16__ The appearance file: round-trips, defaults, never throws. */
class AppearancePreferencesTest {

    @After fun reset() {
        OshiTheme.appearance = Appearance.DEFAULT
        OshiTheme.systemDark = false
    }

    private fun tempFile() =
        Files.createTempDirectory("oshi-appearance").resolve(AppearancePreferences.FILE_NAME).toFile()

    @Test fun `OLED black and the theme survive a reopen`() {
        val file = tempFile()
        AppearancePreferences(file).save(Appearance(ThemeMode.DARK, oledBlack = true))
        assertEquals(Appearance(ThemeMode.DARK, oledBlack = true), AppearancePreferences(file).load())
        AppearancePreferences(file).save(Appearance(ThemeMode.SYSTEM, oledBlack = false))
        assertEquals(Appearance(ThemeMode.SYSTEM, oledBlack = false), AppearancePreferences(file).load())
    }

    @Test fun `the glass switch round-trips and an old file means the per-theme default`() {
        // __GLASS_ALL_THEMES__
        val file = tempFile()
        file.writeText("""{"theme":"dark","oledBlack":true}""")
        assertEquals(null, AppearancePreferences(file).load().glass)
        for (v in listOf(true, false)) {
            AppearancePreferences(file).save(Appearance(ThemeMode.LIGHT, glass = v))
            assertEquals(Appearance(ThemeMode.LIGHT, glass = v), AppearancePreferences(file).load())
        }
        // Untouched stays untouched: no key written, so a later default change still applies.
        AppearancePreferences(file).save(Appearance(ThemeMode.DARK))
        assertTrue(!JSONObject(file.readText()).has(AppearancePreferences.KEY_GLASS))
        file.writeText("""{"theme":"dark","glass":"yes"}""")
        assertEquals(null, AppearancePreferences(file).load().glass)
    }

    @Test fun `a missing file is the default - light, OLED off`() {
        assertEquals(Appearance.DEFAULT, AppearancePreferences(tempFile()).load())
    }

    @Test fun `a corrupt or future-shaped file falls back instead of throwing`() {
        val file = tempFile()
        file.writeText("{ not json")
        assertEquals(Appearance.DEFAULT, AppearancePreferences(file).load())
        file.writeText("""{"theme":"sepia","oledBlack":"maybe"}""")
        assertEquals(Appearance(ThemeMode.LIGHT, oledBlack = false), AppearancePreferences(file).load())
    }

    @Test fun `the file holds only the two documented keys`() {
        val file = tempFile()
        AppearancePreferences(file).save(Appearance(ThemeMode.DARK, oledBlack = true))
        val json = JSONObject(file.readText())
        // __GLASS_ALL_THEMES__ "glass" is written only once the switch has been touched.
        assertEquals(setOf("theme", "oledBlack"), json.keySet())
        assertEquals("dark", json.getString("theme"))
        assertTrue(json.getBoolean("oledBlack"))
    }

    @Test fun `settings apply live and persist together`() {
        val home = Files.createTempDirectory("oshi-appearance-home").toFile()
        AppearanceSettings.attach(home)
        assertEquals(Appearance.DEFAULT, OshiTheme.appearance)

        AppearanceSettings.setMode(ThemeMode.DARK)
        assertSame(OshiPalette.DARK, OshiTheme.palette)   // __GLASS_ALL_THEMES__ glass is opt-in in Dark
        AppearanceSettings.setOledBlack(true)
        assertSame(OshiPalette.OLED, OshiTheme.palette)
        AppearanceSettings.setGlass(false)
        assertSame(OshiPalette.OLED_FLAT, OshiTheme.palette)

        // A fresh launch on the same home reads the choice back.
        OshiTheme.appearance = Appearance.DEFAULT
        AppearanceSettings.attach(home)
        assertEquals(Appearance(ThemeMode.DARK, oledBlack = true, glass = false), OshiTheme.appearance)
        assertSame(OshiPalette.OLED_FLAT, OshiTheme.palette)
        AppearanceSettings.setMode(ThemeMode.LIGHT)
        assertSame(OshiPalette.LIGHT, OshiTheme.palette)   // a stored "off" holds in every theme
    }
}
