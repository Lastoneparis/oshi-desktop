package com.oshi.desktop.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __GROUP_PARITY_2026_09_23__ the reply / forward envelopes, against the shipped emitters' shapes. */
class ReplyEnvelopeTest {

    private val quote = ReplyEnvelope.Quote(
        originalMessageId = "5A0E1C2B-0000-4000-8000-000000000001",
        originalSenderKey = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=",
        originalText = "on my way",
        originalTimestampMs = 1_758_620_000_000L,
    )

    @Test
    fun `wrap emits the iOS MessageWithReply shape with an Apple-epoch Double timestamp`() {
        val wire = ReplyEnvelope.wrap("see you", quote)
        assertTrue(wire.startsWith("💬REPLY💬"))
        val o = JSONObject(wire.removePrefix("💬REPLY💬"))
        assertEquals("see you", o.getString("content"))
        val r = o.getJSONObject("replyTo")
        assertEquals(quote.originalMessageId, r.getString("originalMessageId"))
        assertEquals(quote.originalSenderKey, r.getString("originalSenderKey"))
        assertEquals("on my way", r.getString("originalText"))
        // iOS decodes a NON-optional Date from a default JSONDecoder: Apple-epoch seconds.
        assertEquals(1_758_620_000.0 - 978_307_200.0, r.getDouble("originalTimestamp"), 0.001)
        // iOS omits a nil optional; emitting null would be Android's group dialect, not iOS's.
        assertFalse(r.has("originalMediaType"))
    }

    @Test
    fun `the quote is truncated at iOS's 180 characters`() {
        val wire = ReplyEnvelope.wrap("x", quote.copy(originalText = "a".repeat(400)))
        val r = JSONObject(wire.removePrefix("💬REPLY💬")).getJSONObject("replyTo")
        assertEquals(180, r.getString("originalText").length)
    }

    @Test
    fun `round trip keeps content, quote and timestamp`() {
        val u = ReplyEnvelope.unwrap(ReplyEnvelope.wrap("see you", quote.copy(originalMediaType = "photo")))!!
        assertEquals("see you", u.content)
        assertEquals(quote.copy(originalMediaType = "photo"), u.quote)
    }

    @Test
    fun `an iOS-emitted reply decodes (fixture in the Swift encoder's shape)`() {
        // What `JSONEncoder().encode(MessageWithReply)` produces on an iPhone.
        val ios = "💬REPLY💬{\"content\":\"ok\",\"replyTo\":{\"originalMessageId\":\"ABC\"," +
            "\"originalSenderKey\":\"KEY\",\"originalText\":\"hi\",\"originalTimestamp\":780000000.5}}"
        val u = ReplyEnvelope.unwrap(ios)!!
        assertEquals("ok", u.content)
        assertEquals("ABC", u.quote!!.originalMessageId)
        assertEquals("KEY", u.quote!!.originalSenderKey)
        assertEquals(((780000000.5 + 978307200.0) * 1000).toLong(), u.quote!!.originalTimestampMs)
        assertNull(u.quote!!.originalMediaType)
    }

    @Test
    fun `Android's group envelope with a null media type decodes`() {
        // GroupManager.buildReplyEnvelope puts JSONObject.NULL for a text quote.
        val android = "💬REPLY💬{\"content\":\"yes\",\"replyTo\":{\"originalMessageId\":\"M1\"," +
            "\"originalSenderKey\":\"K\",\"originalText\":\"q\",\"originalTimestamp\":780000000," +
            "\"originalMediaType\":null}}"
        val u = ReplyEnvelope.unwrap(android)!!
        assertEquals("yes", u.content)
        assertNull(u.quote!!.originalMediaType)
    }

    @Test
    fun `Android's legacy reply schema still decodes`() {
        val legacy = "↩️REPLY↩️{\"originalId\":\"M2\",\"originalContent\":\"hello\"," +
            "\"originalSender\":\"K2\",\"replyContent\":\"hey\"}"
        val u = ReplyEnvelope.unwrap(legacy)!!
        assertEquals("hey", u.content)
        assertEquals("M2", u.quote!!.originalMessageId)
        assertEquals("hello", u.quote!!.originalText)
    }

    @Test
    fun `a forwarded message unwraps with its sender name`() {
        val fwd = "➡️FORWARDED➡️{\"originalSenderName\":\"Ana\",\"content\":\"look\",\"isForwarded\":true,\"forwardCount\":1}"
        val u = ReplyEnvelope.unwrap(fwd)!!
        assertEquals("look", u.content)
        assertEquals("Ana", u.forwardedFrom)
        assertNull(u.quote)
    }

    @Test
    fun `a broken envelope is null so the raw text stays visible`() {
        assertNull(ReplyEnvelope.unwrap("💬REPLY💬{not json"))
        assertNull(ReplyEnvelope.unwrap("💬REPLY💬{\"replyTo\":{}}")) // no content
        assertNull(ReplyEnvelope.unwrap("plain text"))
        assertNull(ReplyEnvelope.unwrap(null))
    }

    @Test
    fun `a quote of a reply carries its text, and a quote of a sentinel carries no JSON`() {
        val nested = ReplyEnvelope.wrap("inner", quote)
        assertEquals("inner", ReplyEnvelope.quotableText(nested))
        val control = "📬DELIVERY_RECEIPT📬ABC"
        assertFalse(ReplyEnvelope.quotableText(control).contains("DELIVERY"))
        assertEquals("photo", ReplyEnvelope.quotableText("", "photo"))
    }

    @Test
    fun `the envelope is a control payload, so the plain send path still refuses a typed one`() {
        assertTrue(ControlPrefix.isControl(ReplyEnvelope.wrap("x", quote)))
        assertNotNull(ControlPrefix.match(ReplyEnvelope.wrap("x", quote)))
    }

    @Test
    fun `a forward is iOS's ForwardedMessage, and a forward of a forward counts up and keeps the first sender`() {
        val once = ReplyEnvelope.forward("look at this", "Ana")
        val o = JSONObject(once.removePrefix("➡️FORWARDED➡️"))
        assertEquals("Ana", o.getString("originalSenderName"))
        assertEquals("look at this", o.getString("content"))
        assertTrue(o.getBoolean("isForwarded"))
        assertEquals(1, o.getInt("forwardCount"))
        val twice = JSONObject(ReplyEnvelope.forward(once, "Bob").removePrefix("➡️FORWARDED➡️"))
        assertEquals("Ana", twice.getString("originalSenderName"))
        assertEquals(2, twice.getInt("forwardCount"))
        // Unknown sender: the key is omitted, as Swift drops a nil optional.
        assertFalse(JSONObject(ReplyEnvelope.forward("x", null).removePrefix("➡️FORWARDED➡️")).has("originalSenderName"))
        // A reply is forwarded as its text, not its envelope.
        val reply = ReplyEnvelope.wrap("inner", quote)
        assertEquals("inner", JSONObject(ReplyEnvelope.forward(reply, "C").removePrefix("➡️FORWARDED➡️")).getString("content"))
    }
}
