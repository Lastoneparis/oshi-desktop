package com.oshi.desktop.ui.components

import androidx.compose.ui.graphics.Color
import com.oshi.desktop.ui.OshiPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __GLASS_HOVER_ALPHA_2026_11_21__ a hover/border derived from a token never gets MORE opaque than it. */
class AtMostAlphaTest {
    @Test
    fun `an opaque token behaves exactly like copy(alpha)`() {
        val c = Color(0xFFF2F2F7)
        assertEquals(c.copy(alpha = 0.6f), c.atMostAlpha(0.6f))
    }

    @Test
    fun `a translucent token is never made more opaque`() {
        val c = Color(0x1223204A)
        assertEquals(c, c.atMostAlpha(0.6f))
        assertEquals(0.05f, c.atMostAlpha(0.05f).alpha, 0.001f)
    }

    @Test
    fun `the settings-row hover in every palette is at most as opaque as its surface`() {
        // The bug: Light glass surface (7 %) became a 60 % slate slab on hover, OLED a 60 % white one.
        OshiPalette.EVERY.forEach { p ->
            val hover = p.surface.atMostAlpha(0.6f)
            assertTrue("${p.name}: hover alpha ${hover.alpha} > surface ${p.surface.alpha}", hover.alpha <= p.surface.alpha + 1e-4f)
            assertTrue("${p.name}: hover alpha ${hover.alpha} > 0.6", hover.alpha <= 0.6f + 1e-4f)
        }
    }
}
