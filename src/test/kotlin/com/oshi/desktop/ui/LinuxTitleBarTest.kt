package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __LINUX_DARK_TITLE_BAR_2026_11_21__ which _GTK_THEME_VARIANT, and where nothing is touched. */
class LinuxTitleBarTest {
    private fun env(vararg kv: Pair<String, String>): (String) -> String? = mapOf(*kv)::get

    @Test
    fun `dark and light follow the theme, system removes the hint`() {
        val gnome = env("DISPLAY" to ":0", "XDG_CURRENT_DESKTOP" to "ubuntu:GNOME")
        assertEquals("dark", LinuxTitleBar.variant(true, gnome, systemMode = false, dark = true))
        assertEquals("light", LinuxTitleBar.variant(true, gnome, systemMode = false, dark = false))
        assertEquals("", LinuxTitleBar.variant(true, gnome, systemMode = true, dark = true))
        assertEquals("dark", LinuxTitleBar.variant(true, env("DISPLAY" to ":1", "XDG_CURRENT_DESKTOP" to "XFCE"), false, true))
    }

    @Test
    fun `nothing off Linux, without a display, or on KDE and LXQt`() {
        assertNull(LinuxTitleBar.variant(false, env("DISPLAY" to ":0"), false, true))
        assertNull(LinuxTitleBar.variant(true, env(), false, true))
        assertNull(LinuxTitleBar.variant(true, env("DISPLAY" to ":0", "XDG_CURRENT_DESKTOP" to "KDE"), false, true))
        assertNull(LinuxTitleBar.variant(true, env("DISPLAY" to ":0", "DESKTOP_SESSION" to "plasmawayland"), false, true))
        assertNull(LinuxTitleBar.variant(true, env("DISPLAY" to ":0", "XDG_CURRENT_DESKTOP" to "LXQt"), false, true))
    }
}
