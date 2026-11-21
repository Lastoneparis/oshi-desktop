package com.oshi.desktop.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** __MARK_OF_THE_WEB_2026_11_20__ / __OPEN_POLICY_GAPS_2026_11_20__ / __FILENAME_FORMAT_CHARS_2026_11_20__ */
class DownloadMarkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the Windows zone stream says Internet`() {
        assertEquals("[ZoneTransfer]\r\nZoneId=3\r\n", DownloadMark.zoneIdentifier())
        assertTrue(DownloadMark.quarantineValue(0x10).startsWith("0081;10;OSHI"))
    }

    @Test
    fun `on macOS the quarantine attribute is really set`() {
        assumeTrue(DesktopPaths.isMac)
        val f = tmp.newFile("received.docx")
        assertTrue(DownloadMark.mark(f))
        val p = ProcessBuilder("/usr/bin/xattr", "-p", "com.apple.quarantine", f.absolutePath).start()
        assertTrue(p.inputStream.readBytes().toString(Charsets.UTF_8).startsWith("0081;"))
    }

    @Test
    fun `newly listed launchers are refused`() {
        for (ext in listOf("jnlp", "pyw", "accde", "webloc", "fileloc", "vsto", "xla")) {
            assertFalse(ext, AttachmentOpenPolicy.decide("x.$ext", "document").allowed)
        }
    }

    @Test
    fun `invisible format characters are removed from received names`() {
        for (c in listOf('؜', '​', '‌', '‍', ' ', ' ', '﻿')) {
            assertFalse(SafeFileName.sanitize("in${c}voice.pdf").contains(c))
        }
        assertEquals("Facture été.pdf", SafeFileName.sanitize("Facture été.pdf"))
    }
}
