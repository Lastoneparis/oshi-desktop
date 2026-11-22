package com.oshi.desktop.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two bugs this parser exists to avoid, both found the hard way on other platforms
 * (see [MailMime]'s class note):
 *
 *  1. a header folded across lines is ONE header and must be unfolded BEFORE matching;
 *  2. the CRLF immediately before a MIME boundary belongs to the delimiter, not the part.
 */
class MailMimeTest {

    @Test
    fun `folded header is unfolded before being read`() {
        val raw = (
            "Subject: this is a very long subject line that a real MTA\r\n" +
                " would fold across two physical lines per RFC 5322\r\n" +
                "From: a@x.com\r\n" +
                "\r\n" +
                "body"
            ).toByteArray(Charsets.ISO_8859_1)
        val mail = MailMime.parse(raw)
        assertEquals(
            "this is a very long subject line that a real MTA would fold across two physical lines per RFC 5322",
            mail.subject,
        )
        assertEquals("body", mail.text)
    }

    @Test
    fun `CRLF before a multipart boundary is not appended to the body`() {
        val boundary = "BOUNDARY123"
        val raw = (
            "Content-Type: multipart/mixed; boundary=$boundary\r\n" +
                "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val mail = MailMime.parse(raw)
        // A defect here appends a trailing blank line ("hello\n") instead of "hello".
        assertEquals("hello", mail.text)
    }

    @Test
    fun `RFC 2047 base64 encoded-word subject decodes to UTF-8`() {
        // "Café ✓" in UTF-8, base64
        val raw = "Subject: =?UTF-8?B?Q2Fmw6kg4pyT?=\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Café ✓", MailMime.parse(raw).subject)
    }

    @Test
    fun `RFC 2047 quoted-printable encoded-word decodes and underscores become spaces`() {
        val raw = "Subject: =?UTF-8?Q?Caf=C3=A9_test?=\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Café test", MailMime.parse(raw).subject)
    }

    @Test
    fun `multipart alternative prefers text over html for the plain field but keeps both`() {
        val boundary = "ALT1"
        val raw = (
            "Content-Type: multipart/alternative; boundary=$boundary\r\n" +
                "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "plain body\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/html\r\n\r\n" +
                "<p>html body</p>\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val mail = MailMime.parse(raw)
        assertEquals("plain body", mail.text)
        assertEquals("<p>html body</p>", mail.html)
    }

    @Test
    fun `multipart mixed extracts attachment bytes and base64 decodes them`() {
        val boundary = "MIX1"
        val fileBytes = "hello attachment".toByteArray(Charsets.UTF_8)
        val b64 = java.util.Base64.getEncoder().encodeToString(fileBytes)
        val raw = (
            "Content-Type: multipart/mixed; boundary=$boundary\r\n" +
                "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "see attached\r\n" +
                "--$boundary\r\n" +
                "Content-Type: application/octet-stream; name=\"note.txt\"\r\n" +
                "Content-Disposition: attachment; filename=\"note.txt\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n" +
                b64 + "\r\n" +
                "--$boundary--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val mail = MailMime.parse(raw)
        assertEquals("see attached", mail.text)
        assertEquals(1, mail.attachments.size)
        val att = mail.attachments[0]
        assertEquals("note.txt", att.filename)
        assertEquals("application/octet-stream", att.mimeType)
        assertArrayEquals(fileBytes, att.bytes)
    }

    @Test
    fun `quoted-printable body decodes soft line breaks`() {
        val raw = (
            "Content-Type: text/plain\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n\r\n" +
                "This is a long line that soft=\r\nwraps in the middle of a word."
            ).toByteArray(Charsets.ISO_8859_1)
        assertEquals("This is a long line that softwraps in the middle of a word.", MailMime.parse(raw).text)
    }

    @Test
    fun `nested multipart is walked recursively`() {
        val outer = "OUT1"
        val inner = "IN1"
        val raw = (
            "Content-Type: multipart/mixed; boundary=$outer\r\n\r\n" +
                "--$outer\r\n" +
                "Content-Type: multipart/alternative; boundary=$inner\r\n\r\n" +
                "--$inner\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "nested plain\r\n" +
                "--$inner--\r\n" +
                "--$outer--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertEquals("nested plain", MailMime.parse(raw).text)
    }

    @Test
    fun `html-only mail strips tags for the plain field`() {
        val raw = (
            "Content-Type: text/html\r\n\r\n" +
                "<p>Hello <b>world</b></p><br>Line two"
            ).toByteArray(Charsets.ISO_8859_1)
        val mail = MailMime.parse(raw)
        assertEquals("<p>Hello <b>world</b></p><br>Line two", mail.html)
        assertTrue(mail.text.contains("Hello world"))
        assertTrue(mail.text.contains("Line two"))
    }

    @Test
    fun `displayName and emailAddress split a name-addr`() {
        assertEquals("Jane Doe", MailMime.displayName("Jane Doe <jane@x.com>"))
        assertEquals("jane@x.com", MailMime.emailAddress("Jane Doe <jane@x.com>"))
        assertEquals("bare@x.com", MailMime.displayName("bare@x.com"))
        assertEquals("bare@x.com", MailMime.emailAddress("bare@x.com"))
    }

    @Test
    fun `no content-type defaults to text-plain`() {
        val raw = "From: a@x.com\r\n\r\njust text".toByteArray(Charsets.ISO_8859_1)
        val mail = MailMime.parse(raw)
        assertEquals("just text", mail.text)
        assertNull(mail.html)
    }

    // ---- real-mail bugs: raw UTF-8 headers and block-level HTML -------------------------

    @Test
    fun `a header with RAW UTF-8 and no RFC2047 encoded-word decodes, not mojibake`() {
        // A real sender's "From: Boutique Lumière <x@y.com>" with NO =?UTF-8?...?= wrapper.
        // Read one-char-per-byte as ISO-8859-1 (so attachment bytes survive byte-for-byte),
        // this used to render as "Boutique LumiÃ¨re" unless re-decoded.
        val raw = "From: Boutique Lumière <x@y.com>\r\nSubject: hi\r\n\r\nbody".toByteArray(Charsets.UTF_8)
        val mail = MailMime.parse(raw)
        assertEquals("Boutique Lumière <x@y.com>", mail.from)
    }

    @Test
    fun `a genuine Latin-1 header is left alone, not mangled by a UTF-8 upgrade attempt`() {
        // Byte 0xE9 alone ('é' in Latin-1) is not valid UTF-8 on its own (0xE9 is a
        // 3-byte-sequence lead byte with no continuation bytes here), so the upgrade must
        // fail closed and keep the Latin-1 reading rather than corrupt it.
        val raw = "Subject: café test\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café test", MailMime.parse(raw).subject)
    }

    @Test
    fun `a pure-ASCII header is untouched by the UTF-8 upgrade path`() {
        val raw = "Subject: plain ascii subject\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1)
        assertEquals("plain ascii subject", MailMime.parse(raw).subject)
    }

    @Test
    fun `stripHtml breaks on every block element, not only closing p`() {
        // "Merci !Votre commande…" was the defect: only </p> ended a line, so an <h1>
        // immediately followed by a <p> ran the two together.
        assertEquals("Merci !\n\nVotre commande…", MailMime.stripHtml("<h1>Merci !</h1><p>Votre commande…</p>"))
    }

    @Test
    fun `stripHtml breaks list items onto their own lines`() {
        assertEquals("a\n\nb", MailMime.stripHtml("<ul><li>a</li><li>b</li></ul>"))
    }

    @Test
    fun `stripHtml breaks table rows onto their own lines`() {
        assertEquals("1\n\n2", MailMime.stripHtml("<table><tr>1</tr><tr>2</tr></table>"))
    }

    @Test
    fun `html-only mail with block elements strips through parse end to end`() {
        // No charset param on the Content-Type, so decodeText's default applies (UTF-8) —
        // the body bytes must actually be UTF-8, not ISO-8859-1, to round-trip correctly.
        val raw = "Content-Type: text/html\r\n\r\n<h1>Merci !</h1><p>Votre commande est confirmée.</p>"
            .toByteArray(Charsets.UTF_8)
        val mail = MailMime.parse(raw)
        assertTrue(mail.text.contains("Merci !"))
        assertTrue(mail.text.contains("Votre commande est confirmée."))
        assertTrue("block elements must break, not run together", !mail.text.contains("Merci !Votre"))
    }

    // ---- legacy / ANSI charsets ------------------------------------------------

    private fun msg(charset: String, body: ByteArray, encoding: String = "8bit"): ByteArray =
        "From: a@b.com\r\nSubject: T\r\nContent-Type: text/plain; charset=$charset\r\nContent-Transfer-Encoding: $encoding\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1) + body

    /**
     * Windows "ANSI". 0x80 is the euro sign and 0x93/0x94 are curly quotes; in ISO-8859-1
     * those same bytes are control characters, so mapping 1252 onto Latin-1 silently turns
     * prices and quotes into invisible characters.
     */
    @Test
    fun `windows-1252 body decodes`() {
        val bytes = byteArrayOf(0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x30, 0x30, 0x80.toByte(), 0x20, 0x93.toByte(), 0x6F, 0x6B, 0x94.toByte())
        assertEquals("Prix 100€ “ok”", MailMime.parse(msg("windows-1252", bytes)).text)
    }

    /** iso-8859-15 differs from -1 where it matters: 0xA4 is the euro sign, not ¤. */
    @Test
    fun `iso-8859-15 euro is not the currency sign`() {
        assertEquals("Prix €", MailMime.parse(msg("iso-8859-15", byteArrayOf(0x50, 0x72, 0x69, 0x78, 0x20, 0xA4.toByte()))).text)
    }

    @Test
    fun `plain ascii and latin-1 still work`() {
        assertEquals("Plain ASCII", MailMime.parse(msg("us-ascii", "Plain ASCII".toByteArray())).text)
        assertEquals("Café", MailMime.parse(msg("iso-8859-1", byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()))).text)
    }

    @Test
    fun `non-latin legacy charsets`() {
        assertEquals("Привет",
            MailMime.parse(msg("koi8-r", byteArrayOf(0xF0.toByte(), 0xD2.toByte(), 0xC9.toByte(), 0xD7.toByte(), 0xC5.toByte(), 0xD4.toByte()))).text)
        assertEquals("Zelená",
            MailMime.parse(msg("iso-8859-2", byteArrayOf(0x5A, 0x65, 0x6C, 0x65, 0x6E, 0xE1.toByte()))).text)
    }

    /** A windows-1252 subject — Outlook's default — used to show as its raw source. */
    @Test
    fun `encoded-word in a legacy charset`() {
        assertEquals("Prix €100", MailMime.decodeEncodedWords("=?windows-1252?Q?Prix_=80100?="))
        assertEquals("Zelená", MailMime.decodeEncodedWords("=?ISO-8859-2?Q?Zelen=E1?="))
    }

    /** An unknown label must not lose the message. */
    @Test
    fun `unknown charset falls back instead of failing`() {
        assertEquals("hi", MailMime.parse(msg("x-nonsense", "hi".toByteArray())).text)
    }
}
