package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __LINUX_UI_FONT_2026_11_21__ installed families only, closest to SF/Segoe first, none = unchanged. */
class LinuxFontsTest {
    @Test
    fun `the first installed preferred family wins, none leaves the default`() {
        assertEquals("Noto Sans", LinuxFonts.pick { it in setOf("Noto Sans", "Ubuntu", "DejaVu Sans") })
        assertEquals("Inter", LinuxFonts.pick { it in setOf("Inter", "Noto Sans") })
        assertEquals("Cantarell", LinuxFonts.pick { it == "Cantarell" })
        assertNull(LinuxFonts.pick { it == "DejaVu Sans" })
    }

    @Test
    fun `nothing changes off Linux`() {
        assertNull(LinuxFonts.family(linux = false))
    }
}
