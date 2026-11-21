package com.oshi.desktop.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __WINDOW_TALLER_THAN_THE_LAPTOP_2026_11_19__ the composer must not open under the taskbar. */
class ScreenFitTest {
    private val main = DpSize(1180.dp, 780.dp)

    @Test
    fun `1920x1080 at 150 percent fits above the taskbar`() {
        val s = ScreenFit.fit(main, 1280f, 688f)
        assertTrue(s.height.value <= 688f - 1f)
        assertTrue(s.width.value <= 1280f)
    }

    @Test
    fun `1366x768 at 100 percent fits`() {
        val s = ScreenFit.fit(main, 1366f, 728f)
        assertTrue(s.height.value <= 728f)
        assertEquals(1180f, s.width.value, 0.01f)
    }

    @Test
    fun `a big screen keeps the designed size, and no screen information changes nothing`() {
        assertEquals(main, ScreenFit.fit(main, 2560f, 1400f))
        assertEquals(main, ScreenFit.fit(main, null, null))
    }
}
