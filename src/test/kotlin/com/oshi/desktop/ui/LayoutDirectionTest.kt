package com.oshi.desktop.ui

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** __RTL_2027__ the window mirrors for exactly the right-to-left catalogs. */
class LayoutDirectionTest {
    @Test
    fun `arabic persian and hebrew are right to left, the rest left to right`() {
        for (t in listOf("ar", "fa", "he")) assertEquals(t, LayoutDirection.Rtl, layoutDirectionFor(t))
        val ltr = java.io.File(javaClass.getResource("/i18n/locales.txt")!!.toURI()).readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it !in setOf("ar", "fa", "he") }
        for (t in ltr) assertEquals(t, LayoutDirection.Ltr, layoutDirectionFor(t))
    }
}
