package com.oshi.desktop.mail

import com.oshi.desktop.mail.MailHtml.Block
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __MAIL_READER_2026_11_16__ / __MAIL_LIST_PREVIEW_2026_11_16__ desktop parity with the phones' reader. */
class MailReaderTest {

    @Test
    fun `html becomes paragraphs with bold and safe links, scripts and styles dropped`() {
        val r = MailHtml.render(
            """<html><head><style>p{color:red}</style><title>x</title></head><body>
               <h1>Hello &amp; welcome</h1><p>Read <b>this</b> <a href="https://example.com/a?b=1&amp;c=2">now</a>.</p>
               <script>alert(1)</script><p><a href="javascript:alert(1)">evil</a></p><ul><li>one</li><li>two</li></ul></body></html>""",
        )
        val paras = r.blocks.filterIsInstance<Block.Paragraph>()
        assertEquals("Hello & welcome", paras[0].spans.joinToString("") { it.text })
        assertTrue(paras[0].heading)
        val second = paras[1].spans
        assertEquals("Read this now.", second.joinToString("") { it.text })
        assertTrue(second.first { it.text == "this" }.bold)
        assertEquals("https://example.com/a?b=1&c=2", second.first { it.text == "now" }.href)
        assertFalse(r.plainText.contains("alert"))
        assertFalse(r.plainText.contains("color:red"))
        assertNull(paras[2].spans.single().href)          // javascript: is not a link
        assertEquals(listOf("• one", "• two"), paras.drop(3).map { p -> p.spans.joinToString("") { it.text } })
    }

    @Test
    fun `cid images are inline, remote images are counted and never followed`() {
        val r = MailHtml.render("""<p>Hi</p><img src="cid:logo@x"><img src="https://t.example/pixel.gif" width=1><img src="data:image/png;base64,AAAA">""")
        assertEquals(Block.InlineImage("logo@x", ""), r.blocks[1])
        assertEquals(1, r.remoteImageCount)
        assertEquals(3, r.blocks.size)
    }

    @Test
    fun `mime keeps cid parts as inline images instead of dropping them`() {
        val raw = (
            "Content-Type: multipart/related; boundary=\"B\"\r\nSubject: s\r\n\r\n" +
                "--B\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>x<img src=\"cid:pic1\"></p>\r\n" +
                "--B\r\nContent-Type: image/png\r\nContent-ID: <pic1>\r\nContent-Transfer-Encoding: base64\r\n\r\niVBORw0KGgo=\r\n" +
                "--B\r\nContent-Type: application/pdf; name=\"a.pdf\"\r\nContent-Disposition: attachment; filename=\"a.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERg==\r\n" +
                "--B--\r\n"
            ).toByteArray()
        val mail = MailMime.parse(raw)
        val inline = mail.attachments.single { it.inline }
        assertEquals("pic1", inline.contentId)
        assertTrue(inline.isImage)
        assertEquals(listOf("a.pdf"), mail.attachments.filterNot { it.inline }.map { it.filename })
        assertTrue(mail.html!!.contains("cid:pic1"))
    }

    @Test
    fun `sealed list preview fields are optional and type-checked`() {
        val full = MailClient.EnvelopeJson.envelopeOf(JSONObject(
            """{"from":"A <a@x>","subject":"S","snippet":"  hello\n  world ","hasAttachments":true,"attachmentCount":2,"hasImages":true}""",
        ))
        assertEquals("hello world", full.snippet)
        assertEquals(2, full.attachmentCount)
        assertTrue(full.hasImages)
        val wrongTypes = MailClient.EnvelopeJson.envelopeOf(JSONObject("""{"subject":"S","snippet":5,"hasImages":"yes","attachmentCount":"2"}"""))
        assertNull(wrongTypes.snippet); assertFalse(wrongTypes.hasImages); assertEquals(0, wrongTypes.attachmentCount)
        val legacySent = MailClient.EnvelopeJson.envelopeOf(JSONObject("""{"subject":"S","attachments":["photo.JPG","b.pdf"]}"""))
        assertEquals(2, legacySent.attachmentCount); assertTrue(legacySent.hasImages)
        assertEquals("2 attachments, contains images", MailHtml.attachmentDescription(2, true))
        assertNull(MailHtml.attachmentDescription(0, false))
    }
}
