package com.oshi.desktop.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ATTACHMENT_TYPE_CONFUSION_2026_11_02__ The attack this policy exists to
 * stop, written as the message a contact would actually send.
 */
class AttachmentOpenPolicyTest {

    @Test
    fun `a photo that is really an executable is refused`() {
        // THE ATTACK: `kind` says image, so the bubble draws a photo row; the
        // sender-chosen filename ends in .exe, and java.awt.Desktop.open is the
        // Windows shell. One click would run it.
        val d = AttachmentOpenPolicy.decide("holiday.jpg.exe", "image")
        assertEquals(AttachmentOpenPolicy.Verdict.REFUSE_EXECUTABLE, d.verdict)
        assertFalse(d.allowed)
        assertTrue(d.reason!!.contains("holiday.jpg.exe"))
        // The sentence must say what it will do instead, not just refuse.
        assertTrue(d.reason.contains("Save a copy"))
    }

    @Test
    fun `the three platforms OSHI ships on are all covered`() {
        for (name in listOf("invoice.scr", "setup.msi", "notes.hta", "link.lnk", "x.vbs", "a.ps1")) {
            assertEquals("win: $name", AttachmentOpenPolicy.Verdict.REFUSE_EXECUTABLE,
                AttachmentOpenPolicy.decide(name, "document").verdict)
        }
        for (name in listOf("Photos.app", "open.command", "install.pkg")) {
            assertEquals("mac: $name", AttachmentOpenPolicy.Verdict.REFUSE_EXECUTABLE,
                AttachmentOpenPolicy.decide(name, "document").verdict)
        }
        for (name in listOf("run.desktop", "install.sh", "tool.AppImage", "pkg.deb")) {
            assertEquals("linux: $name", AttachmentOpenPolicy.Verdict.REFUSE_EXECUTABLE,
                AttachmentOpenPolicy.decide(name, "document").verdict)
        }
    }

    @Test
    fun `case and double extensions do not get past it`() {
        // The shell looks at the LAST extension, so that is what is checked.
        assertEquals(AttachmentOpenPolicy.Verdict.REFUSE_EXECUTABLE,
            AttachmentOpenPolicy.decide("report.PDF.EXE", "document").verdict)
        assertEquals(AttachmentOpenPolicy.Verdict.REFUSE_EXECUTABLE,
            AttachmentOpenPolicy.decide("cat.png.BaT", "image").verdict)
        // ...and an extension-looking tail is not an extension.
        assertEquals("exe", AttachmentOpenPolicy.extensionOf("a.b.c.exe"))
        assertEquals("", AttachmentOpenPolicy.extensionOf("no-extension-at-all"))
    }

    @Test
    fun `a kind that contradicts the extension is refused as unknowable`() {
        val d = AttachmentOpenPolicy.decide("statement.pdf", "image")
        assertEquals(AttachmentOpenPolicy.Verdict.REFUSE_MISMATCH, d.verdict)
        assertTrue(d.reason!!.contains("shown as image"))
        assertTrue(d.reason.contains("document"))
    }

    @Test
    fun `ordinary attachments still open`() {
        for ((name, kind) in listOf(
            "holiday.jpg" to "image", "clip.mp4" to "video", "note.m4a" to "audio",
            "statement.pdf" to "document", "sheet.xlsx" to "document",
            "photo.HEIC" to "image", "archive.zip" to "document",
        )) {
            val d = AttachmentOpenPolicy.decide(name, kind)
            assertTrue("$name/$kind should open, got ${d.verdict}", d.allowed)
            assertNull(d.reason)
        }
    }

    @Test
    fun `an unknown extension or an unknown kind contradicts nothing`() {
        // Over-strictness has a cost too: a file OSHI has no opinion about must
        // not be blocked, or people stop trusting the warning that matters.
        assertTrue(AttachmentOpenPolicy.decide("design.sketch", "document").allowed)
        assertTrue(AttachmentOpenPolicy.decide("holiday.jpg", "unknown").allowed)
        assertTrue(AttachmentOpenPolicy.decide("holiday.jpg", null).allowed)
        assertTrue(AttachmentOpenPolicy.decide("whatever", "image").allowed)
    }

    @Test
    fun `a file with no extension is left to the system to ask about`() {
        val d = AttachmentOpenPolicy.decide("document", "document")
        assertTrue(d.allowed)
    }
}
