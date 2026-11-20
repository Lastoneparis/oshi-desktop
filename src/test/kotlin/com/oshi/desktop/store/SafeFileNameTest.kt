package com.oshi.desktop.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __RECEIVED_NAMES_MANGLED_2026_11_19__ */
class SafeFileNameTest {
    @Test fun `accents, spaces and other scripts survive`() {
        assertEquals("Facture été.pdf", SafeFileName.sanitize("Facture été.pdf"))
        assertEquals("Отчёт 2026.docx", SafeFileName.sanitize("Отчёт 2026.docx"))
        assertEquals("写真.jpg", SafeFileName.sanitize("写真.jpg"))
    }

    @Test fun `what Windows forbids is replaced`() {
        assertEquals("a_b_c_d_e_f_g_h_i.txt", SafeFileName.sanitize("a\\b/c:d*e?f\"g<h>i.txt"))
        assertEquals("invoice.exe", SafeFileName.sanitize("invoice.exe. . "))
        assertEquals("_CON.txt", SafeFileName.sanitize("CON.txt"))
        assertEquals("_nul", SafeFileName.sanitize("nul"))
        assertEquals("file", SafeFileName.sanitize(".."))
        assertEquals("file", SafeFileName.sanitize(null))
        assertFalse(SafeFileName.sanitize("cod‮fdp.exe").contains('‮'))
        assertFalse(SafeFileName.sanitize("a\u0000b\nc").any { it.isISOControl() })
    }

    @Test fun `long names keep their extension`() {
        val s = SafeFileName.sanitize("é".repeat(300) + ".pdf")
        assertTrue(s.endsWith(".pdf"))
        assertEquals(120, s.codePointCount(0, s.length))
    }

    @Test fun `the display name drops our message-id prefix only`() {
        assertEquals("Facture été.pdf", SafeFileName.displayName("1a2b3c4d-Facture été.pdf"))
        assertEquals("photo.jpg", SafeFileName.displayName("1A2B3C4D-import-photo.jpg"))
        assertEquals("notes.txt", SafeFileName.displayName("notes.txt"))
    }
}
