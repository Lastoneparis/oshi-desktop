package com.oshi.desktop.msg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1, docs/RESTRICTED_NETWORK_HINT.md §6 — the
 * vectors Android's RestrictedNetworkHintTest and iOS share, byte for byte.
 */
class RestrictedNetworkHintTest {
    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun utf8(h: String) = String(hex(h), Charsets.UTF_8)

    @Test fun `marker is the three spec code points, nine UTF-8 bytes`() {
        assertEquals("e2 81 a3 e2 81 a4 e2 81 a3", RestrictedNetworkHint.MARKER.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02x".format(it) })
    }

    @Test fun `spec vectors`() {
        assertEquals(RestrictedNetworkHint.Ingested("Hello", false), RestrictedNetworkHint.ingest(utf8("48 65 6c 6c 6f")))
        assertEquals(RestrictedNetworkHint.Ingested("Hello", true), RestrictedNetworkHint.ingest(utf8("48 65 6c 6c 6f e2 81 a3 e2 81 a4 e2 81 a3")))
        val middle = utf8("48 65 e2 81 a3 e2 81 a4 e2 81 a3 6c 6c 6f")
        assertEquals(RestrictedNetworkHint.Ingested(middle, false), RestrictedNetworkHint.ingest(middle))
        assertEquals(8, middle.length)
        val two = utf8("48 69 e2 81 a3 e2 81 a4")
        assertEquals(RestrictedNetworkHint.Ingested(two, false), RestrictedNetworkHint.ingest(two))
    }

    @Test fun `reply vector, escaped (Android org-json) and raw (iOS JSONEncoder)`() {
        val escaped = "💬REPLY💬{\"content\":\"On my way\\u2063\\u2064\\u2063\",\"replyTo\":{\"originalMessageId\":\"m-1\",\"originalSenderKey\":\"k\",\"originalText\":\"Where are you?\",\"originalTimestamp\":1763460000000}}"
        val raw = escaped.replace("\\u2063\\u2064\\u2063", RestrictedNetworkHint.MARKER)
        for (wire in listOf(escaped, raw)) {
            val ing = RestrictedNetworkHint.ingest(wire)
            assertTrue(ing.flagged)
            val un = ReplyEnvelope.unwrap(ing.text)!!
            assertEquals("On my way", un.content)
            assertEquals("m-1", un.quote!!.originalMessageId)
            assertFalse("marker gone from the stored envelope", ing.text.contains(RestrictedNetworkHint.MARKER))
        }
        // An unflagged reply is left byte-for-byte as it came.
        val plain = escaped.replace("\\u2063\\u2064\\u2063", "")
        assertEquals(RestrictedNetworkHint.Ingested(plain, false), RestrictedNetworkHint.ingest(plain))
    }

    @Test fun `mark never doubles and round-trips`() {
        val once = RestrictedNetworkHint.mark("Hi")
        assertEquals(once, RestrictedNetworkHint.mark(once))
        assertEquals(RestrictedNetworkHint.Ingested("Hi", true), RestrictedNetworkHint.ingest(once))
        assertFalse("not whitespace: an old client's isNotBlank/trim keep it", once.trim() != once)
    }

    /**
     * §5 "old clients": desktop 1.4.x stored and drew the decrypted text verbatim. Its text stack
     * (Skia paragraph shaping under Compose Desktop) must draw the marker with ZERO width — so an
     * old desktop shows "Hello", not "Hello□□□".
     */
    @Test fun `the marker draws with zero width in the desktop text stack`() {
        val fonts = org.jetbrains.skia.paragraph.FontCollection().apply { setDefaultFontManager(org.jetbrains.skia.FontMgr.default) }
        fun width(s: String): Float {
            val style = org.jetbrains.skia.paragraph.ParagraphStyle()
            val ts = org.jetbrains.skia.paragraph.TextStyle().setFontSize(16f).setColor(0xFF000000.toInt())
            val p = org.jetbrains.skia.paragraph.ParagraphBuilder(style, fonts).pushStyle(ts).addText(s).build()
            p.layout(10_000f)
            return p.maxIntrinsicWidth
        }
        assertEquals(width("Hello"), width("Hello" + RestrictedNetworkHint.MARKER), 0.01f)
        assertEquals(width("Hello"), width("Hello"), 0.01f)
    }
}
