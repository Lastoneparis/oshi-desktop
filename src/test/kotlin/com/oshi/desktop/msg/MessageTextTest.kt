package com.oshi.desktop.msg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * __DESKTOP_LINE_BREAKS_2026_10_29__ Checked against the phones' set, character for
 * character, because a normaliser that differs between two clients is worse than none.
 *
 * Both phones have done this on every outgoing message since before this client existed
 * (`MessageTextSplitter.normalizeLineBreaks` on iOS, `MessageSendPolicy` on Android) and
 * the desktop did not — nothing in the tree even spelled ` `. Pasted Word or
 * classic-Mac text therefore reached an Android phone as one run-on block, while the
 * sender's own window showed the paragraphs.
 */
class MessageTextTest {

    @Test
    fun `every separator the phones list becomes one LF`() {
        assertEquals("a\nb", MessageText.normalizeLineBreaks("a\rb"))            // CR, classic Mac / Word
        assertEquals("a\nb", MessageText.normalizeLineBreaks("a\u000Bb"))        // VT, Word manual break
        assertEquals("a\nb", MessageText.normalizeLineBreaks("a\u0085b"))        // NEL
        assertEquals("a\nb", MessageText.normalizeLineBreaks("a b"))        // LINE SEPARATOR
        assertEquals("a\nb", MessageText.normalizeLineBreaks("a b"))        // PARAGRAPH SEPARATOR
    }

    @Test
    fun `CRLF is ONE break, not two`() {
        assertEquals("a\nb", MessageText.normalizeLineBreaks("a\r\nb"))
        assertEquals("a\n\nb", MessageText.normalizeLineBreaks("a\r\n\r\nb"))
    }

    @Test
    fun `text already in LF is returned untouched`() {
        val s = "one\ntwo\n\nthree"
        assertSame("no copy when there is nothing to fix", s, MessageText.normalizeLineBreaks(s))
    }

    @Test
    fun `nothing is trimmed and blank lines are kept`() {
        // A deliberately empty line is content, and leading/trailing space is the user's.
        assertEquals("  a\n\n  b  ", MessageText.normalizeLineBreaks("  a\r\r  b  "))
        assertEquals("\n", MessageText.normalizeLineBreaks("\r"))
    }

    @Test
    fun `a lone CR at the very end is still a break`() {
        assertEquals("a\n", MessageText.normalizeLineBreaks("a\r"))
    }

    /**
     * The guard: rewriting raw characters inside a control payload's JSON is how one stops
     * decoding. `OshiClient.send` refuses control payloads before this is reached, and this
     * is the belt to that brace.
     */
    @Test
    fun `a control payload passes through untouched`() {
        // Taken from the catalog rather than typed, so this cannot drift from the real list.
        val control = ControlPrefix.catalog.first().prefix + """{"id":"x","note":"a\rb"}"""
        assertTrue("fixture must actually be a control payload", ControlPrefix.isControl(control))
        assertEquals("a control payload is never rewritten", control, MessageText.normalizedForSend(control))
    }

    @Test
    fun `ordinary prose is normalised for sending`() {
        assertEquals("para one\npara two", MessageText.normalizedForSend("para one para two"))
    }
}
