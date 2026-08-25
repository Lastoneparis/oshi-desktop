package com.oshi.desktop.bot

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bot envelope, against the SERVER's emitter — PARITY.md row 0.26.
 *
 * Every fixture here is assembled the way `ServerVPS/api/message_queue_server.js:584-609`
 * assembles one, in the same key order, and none of it is produced by [BotEnvelope]: there
 * is no encoder in that object to round-trip against, because a desktop client is a
 * RECEIVER on this lane and writing an emitter to test the parser with would prove nothing
 * about the server.
 */
class BotEnvelopeTest {

    private val messageId = "0e0e0e0e-1111-4222-8333-444444444444"
    private val token8 = "a1b2c3d4"

    /**
     * The canonical payload, in the server's literal key order.
     *
     * Written as a raw string rather than built with `JSONObject`, so the ORDER is part of
     * the fixture and the `type`/`timestamp` spellings are exactly what Node writes:
     * `new Date().toISOString()` is always `…Z` with three fractional digits.
     */
    private fun payload(
        type: String = "bot_message",
        content: String = "hello from the bridge",
        timestamp: String = "2026-08-25T14:03:11.123Z",
        extra: String = "",
    ): String =
        """{"type":"$type","messageId":"$messageId","botToken":"$token8...",""" +
            """"botName":"Telegram Bridge","groupId":"A4B2C1D3-0000-0000-0000-000000000001",""" +
            """"groupName":"Ops","content":"$content","timestamp":"$timestamp"$extra}"""

    private fun envelope(payload: String, id: String = messageId): String =
        "bot:$id:" + Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))

    // ============================================================ THE HAPPY PATH

    @Test
    fun `parses the server's envelope field for field`() {
        val m = BotEnvelope.parse(envelope(payload()))

        assertEquals(messageId, m.envelopeMessageId)
        assertEquals(messageId, m.payloadMessageId)
        assertFalse(m.idsDisagree)
        assertEquals("$token8...", m.botToken)
        assertEquals("Telegram Bridge", m.botName)
        assertEquals("A4B2C1D3-0000-0000-0000-000000000001", m.groupId)
        assertEquals("Ops", m.groupName)
        assertEquals("hello from the bridge", m.content)
        assertNull(m.mediaType)
        assertNull(m.mediaData)
    }

    /**
     * The timestamp is an ISO-8601 STRING with milliseconds — a different epoch encoding
     * from the Unix-seconds Double that the legacy IPFS payload beside it in the same queue
     * carries.
     *
     * `2026-08-25T14:03:11.123Z` is 1 787 666 591 123 ms. Both the millisecond and the
     * second-precision spellings must parse, because iOS tries both formatters and Android
     * has both patterns.
     */
    @Test
    fun `timestamp is ISO-8601 and both precisions parse`() {
        assertEquals(
            1_787_666_591_123L,
            BotEnvelope.parse(envelope(payload(timestamp = "2026-08-25T14:03:11.123Z"))).unixMillis,
        )
        assertEquals(
            1_787_666_591_000L,
            BotEnvelope.parse(envelope(payload(timestamp = "2026-08-25T14:03:11Z"))).unixMillis,
        )
    }

    /**
     * An unparseable timestamp yields null and keeps the message — it is NOT silently
     * replaced with the receive time.
     *
     * Both phones substitute `Date()` / `System.currentTimeMillis()` on a parse failure
     * (`MessageManager.swift:2720`, `VPSClient.kt:1084`), which makes a bot with a broken
     * clock indistinguishable from one with a correct one. Null lets the caller stamp a
     * receive time AND know it did.
     */
    @Test
    fun `an unparseable timestamp is null rather than now`() {
        val m = BotEnvelope.parse(envelope(payload(timestamp = "yesterday afternoon")))
        assertNull(m.unixMillis)
        assertEquals("the message itself survives", "hello from the bridge", m.content)
    }

    /**
     * The synthetic sender address strips the ellipsis BY CONTENT, not by length.
     *
     * `bot:` + `botToken` minus the literal `"..."`. Android's `botToken.take(8)` gives the
     * same answer today only because the server's token field is exactly `<8 hex>...`; if
     * the ellipsis ever changed, a by-length strip would truncate a real token and a
     * by-content strip would not.
     */
    @Test
    fun `sender address is bot colon plus the token prefix with the ellipsis removed`() {
        assertEquals("bot:$token8", BotEnvelope.parse(envelope(payload())).senderAddress)

        // A token field with no ellipsis (a producer other than this server) must not be
        // truncated — by-length would cut a 32-char token down to 8.
        val full = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val p = payload().replace("\"botToken\":\"$token8...\"", "\"botToken\":\"$full\"")
        assertEquals("bot:$full", BotEnvelope.parse(envelope(p)).senderAddress)
    }

    /** `messageIdOf` works without decoding a payload that may be hundreds of KB. */
    @Test
    fun `the message id is readable without decoding the payload`() {
        val huge = "bot:$messageId:" + Base64.getEncoder().encodeToString(ByteArray(400_000))
        assertEquals(messageId, BotEnvelope.messageIdOf(huge))
        assertNull(BotEnvelope.messageIdOf("QmNotABot"))
        assertNull("an empty id is not an ack key", BotEnvelope.messageIdOf("bot::e30="))
    }

    // ============================================================ MEDIA

    @Test
    fun `inline media decodes and the iOS image alias maps to photo`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val b64 = Base64.getEncoder().encodeToString(bytes)

        val photo = BotEnvelope.parse(envelope(payload(
            extra = ""","mediaType":"photo","mediaData":"$b64","mediaFileName":"cat.jpg""""
        )))
        assertEquals("photo", photo.mediaType)
        assertEquals("cat.jpg", photo.mediaFileName)
        assertTrue(bytes.contentEquals(photo.mediaData!!))

        val aliased = BotEnvelope.parse(envelope(payload(
            extra = ""","mediaType":"image","mediaData":"$b64","mediaFileName":"cat.jpg""""
        )))
        assertEquals("iOS maps \"image\" to photo (MessageManager.swift:2731)", "photo", aliased.mediaType)
    }

    /** An unknown media type maps to null rather than passing a value nothing handles. */
    @Test
    fun `an unknown media type becomes null`() {
        assertNull(BotEnvelope.normaliseMediaType("hologram"))
        assertNull(BotEnvelope.normaliseMediaType(""))
        assertEquals("document", BotEnvelope.normaliseMediaType("document"))
    }

    /**
     * Undecodable media loses the ATTACHMENT, not the message.
     *
     * iOS's `Data(base64Encoded:)` returns nil and it carries on
     * (`MessageManager.swift:2740`); dropping the whole message would lose text a phone
     * shows.
     */
    @Test
    fun `undecodable media keeps the text`() {
        val m = BotEnvelope.parse(envelope(payload(
            extra = ""","mediaType":"photo","mediaData":"!!!not base64!!!","mediaFileName":"x.jpg""""
        )))
        assertNull(m.mediaData)
        assertEquals("hello from the bridge", m.content)
    }

    // ============================================================ THE GUARDS

    @Test
    fun `an entry without the bot prefix is refused`() {
        expectMalformed("QmSomeContentIdentifier", "prefix")
    }

    /**
     * Exactly three colon-delimited parts.
     *
     * Both shipped parsers bound the split and both require 3 (`MessageManager.swift:2677`,
     * `VPSClient.kt:1035`).
     */
    @Test
    fun `an envelope with the wrong number of parts is refused`() {
        expectMalformed("bot:only-two-parts", "3 colon-delimited parts")
    }

    @Test
    fun `an empty message id is refused because it is the ack key`() {
        expectMalformed("bot::" + Base64.getEncoder().encodeToString("{}".toByteArray()), "messageId")
    }

    @Test
    fun `a payload that is not standard base64 is refused`() {
        expectMalformed("bot:$messageId:@@@not-base64@@@", "STANDARD base64")
    }

    @Test
    fun `a payload that is not a JSON object is refused`() {
        val b64 = Base64.getEncoder().encodeToString("not json at all".toByteArray())
        expectMalformed("bot:$messageId:$b64", "not a JSON object")
    }

    /**
     * The `type` field must be the literal `bot_message`.
     *
     * Stricter than either phone — iOS never reads the field, Android logs it. It is
     * enforced because the `bot:` prefix is the ONLY thing separating this from a CID in a
     * shared queue slot, and without the check a future entry type would decode as a bot
     * message with every field defaulted to empty and render as a message from `bot:` with
     * no text.
     */
    @Test
    fun `a payload whose type is not bot_message is refused`() {
        expectMalformed(envelope(payload(type = "system_notice")), "bot_message")
    }

    /**
     * The two message ids are reported when they disagree rather than one being silently
     * preferred.
     *
     * The ack keys on the ENVELOPE id and a UI would key on the payload id, so a caller that
     * ignored a mismatch could ack one message and display another.
     */
    @Test
    fun `disagreeing message ids are reported and not resolved silently`() {
        val m = BotEnvelope.parse(envelope(payload(), id = "ffffffff-0000-0000-0000-000000000000"))
        assertTrue(m.idsDisagree)
        assertEquals("ffffffff-0000-0000-0000-000000000000", m.envelopeMessageId)
        assertEquals(messageId, m.payloadMessageId)
    }

    private fun expectMalformed(entry: String, expectInMessage: String) {
        try {
            BotEnvelope.parse(entry)
            fail("must be refused; expected a message mentioning \"$expectInMessage\"")
        } catch (e: BotEnvelope.MalformedBotEnvelopeException) {
            assertTrue("message should name the problem, got: ${e.message}",
                e.message!!.contains(expectInMessage))
        }
    }
}
