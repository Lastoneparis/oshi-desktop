package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __LINUX_HIDPI_2026_11_21__ Xft.dpi -> an integer AWT scale, only on X11 and only when unset. */
class LinuxUiScaleTest {

    @Test
    fun `dpi maps to an integer scale, 1 is left alone`() {
        assertNull(LinuxUiScale.scaleFor(96.0))
        assertNull(LinuxUiScale.scaleFor(120.0))
        assertNull(LinuxUiScale.scaleFor(144.0))   // 150 %: X11 AWT would truncate 1.5 to 1 anyway
        assertEquals(2, LinuxUiScale.scaleFor(168.0))
        assertEquals(2, LinuxUiScale.scaleFor(192.0))
        assertEquals(3, LinuxUiScale.scaleFor(288.0))
        assertEquals(4, LinuxUiScale.scaleFor(960.0))
        assertNull(LinuxUiScale.scaleFor(null))
    }

    @Test
    fun `xrdb output is parsed`() {
        assertEquals(192.0, LinuxUiScale.parseXrdb("Xcursor.size:\t48\nXft.dpi:\t192\nXft.antialias:\t1\n")!!, 0.0)
        assertNull(LinuxUiScale.parseXrdb("Xcursor.size:\t24\n"))
    }

    private fun apply(env: Map<String, String>, props: Map<String, String> = emptyMap(), xrdb: String? = "Xft.dpi:\t192\n"): Pair<Int?, Map<String, String>> {
        val set = HashMap<String, String>()
        val r = LinuxUiScale.applyIfNeeded(linux = true, env = { env[it] }, prop = { props[it] }, readXrdb = { xrdb }, set = { k, v -> set[k] = v })
        return r to set
    }

    @Test
    fun `X11 with 192 dpi asks for scale 2`() {
        val (r, set) = apply(mapOf("DISPLAY" to ":0", "XDG_SESSION_TYPE" to "x11"))
        assertEquals(2, r)
        assertEquals("2", set["sun.java2d.uiScale"])
    }

    @Test
    fun `never under Wayland, never over an explicit choice, never without a display`() {
        assertNull(apply(mapOf("DISPLAY" to ":0", "XDG_SESSION_TYPE" to "wayland")).first)
        assertNull(apply(mapOf("DISPLAY" to ":0", "WAYLAND_DISPLAY" to "wayland-0")).first)
        assertNull(apply(mapOf("DISPLAY" to ":0", "GDK_SCALE" to "2")).first)
        assertNull(apply(mapOf("DISPLAY" to ":0", "J2D_UISCALE" to "1")).first)
        assertNull(apply(mapOf("DISPLAY" to ":0"), props = mapOf("sun.java2d.uiScale" to "1")).first)
        assertNull(apply(emptyMap()).first)
        assertNull(apply(mapOf("DISPLAY" to ":0"), xrdb = null).first)
    }
}
