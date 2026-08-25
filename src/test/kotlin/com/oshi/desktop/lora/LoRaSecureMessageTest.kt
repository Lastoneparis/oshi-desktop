package com.oshi.desktop.lora

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The JSON inside the `"OM"` frames, against the shipped `SecureMessage` shape.
 *
 * Fixtures are written out literally in iOS's `CodingKeys` order
 * (`OSHI/MessageManager.swift:8513-8519`), never produced by this encoder and read back.
 */
class LoRaSecureMessageTest {

    /** 2025-08-25T04:00:00Z. Apple-epoch: 1756089600 − 978307200 = 777782400. */
    private val t0 = 1_756_089_600_000L
    private val appleT0 = 777_782_400L

    private val myKey = "AAAAbbbbCCCCddddEEEEffffGGGGhhhhIIIIjjjjKKK="
    private val theirKey = "ZZZZyyyyXXXXwwwwVVVVuuuuTTTTssssRRRRqqqqPPP="

    private fun enc() = LoRaEncryptedContent(
        ciphertext = "Y2lwaGVydGV4dA==",
        signature = "",
        senderPublicKey = theirKey,
        unixMillis = t0,
    )

    private fun msg(
        plaintext: String? = null,
        media: ByteArray? = null,
        method: String? = null,
    ) = LoRaSecureMessage(
        id = "11111111-2222-3333-4444-555555555555",
        senderAddress = theirKey,
        recipientAddress = myKey,
        encryptedContent = enc(),
        unixMillis = t0,
        isRead = false,
        deliveryStatus = LoRaSecureMessage.STATUS_SENT,
        senderPublicKey = theirKey,
        recipientPublicKey = myKey,
        plaintextContent = plaintext,
        mediaAttachment = media,
        deliveryMethod = method,
    )

    // ============================================================ THE PLAINTEXT STRIP

    /**
     * **The single most important assertion in this file.**
     *
     * `SecureMessage` has no custom `encode(to:)` and `plaintextContent` IS in its
     * `CodingKeys`, so encoding it whole puts the user's text on the air in the clear beside
     * the ciphertext — on a channel whose key is public and documented as public
     * (`MeshtasticManager.swift:486-491`). The LoRa path shipped once without the strip.
     */
    @Test
    fun `the user's plaintext never reaches the wire`() {
        val secret = "MEET ME AT THE BRIDGE AT MIDNIGHT"
        val json = String(msg(plaintext = secret).toWireJson(), Charsets.UTF_8)

        assertFalse("the text itself must not be on the air", json.contains(secret))
        assertFalse(json.contains("plaintextContent"))
        assertTrue("but the ciphertext still is", json.contains("Y2lwaGVydGV4dA=="))
    }

    /** …and the same for media and for the local-only sidecar fields. */
    @Test
    fun `media and the local-only fields never reach the wire`() {
        val json = String(
            msg(media = ByteArray(64) { 7 }, method = LoRaSecureMessage.METHOD_LORA).toWireJson(),
            Charsets.UTF_8,
        )
        assertFalse(json.contains("mediaAttachment"))
        assertFalse(json.contains("originalMediaData"))
        assertFalse(json.contains("editedContent"))
        assertFalse("deliveryMethod carries an enum raw value and must not travel",
            json.contains("deliveryMethod"))
    }

    /**
     * `deliveryMethod` is stripped for a reason distinct from privacy: it is the only field
     * carrying an enum RAW VALUE across the wire, and a case an older peer has never heard
     * of makes its `Decodable` **throw**, which drops the whole message rather than the
     * field (`MessageManager.swift:8036-8046`).
     *
     * The decoder therefore keeps it as a raw String rather than as a Kotlin enum — the
     * mirror-image mistake.
     */
    @Test
    fun `an unknown deliveryMethod on receive does not drop the message`() {
        val json = wireJson(extra = ""","deliveryMethod":"Starlink"""")
        val m = LoRaSecureMessage.fromWireJson(json.toByteArray(Charsets.UTF_8))
        assertNotNull("an unknown transport label must not cost the message", m)
        assertEquals("Starlink", m!!.deliveryMethod)
    }

    /** [LoRaSecureMessage.wireSafe] is idempotent and observable on the OBJECT too. */
    @Test
    fun `wireSafe clears all five local-only fields`() {
        val safe = msg(plaintext = "x", media = ByteArray(4), method = "LoRa").wireSafe()
        assertNull(safe.plaintextContent)
        assertNull(safe.mediaAttachment)
        assertNull(safe.originalMediaData)
        assertNull(safe.editedContent)
        assertNull(safe.deliveryMethod)
    }

    /** Media is refused by the transport before any of this runs. */
    @Test
    fun `carriesMedia sees either media field`() {
        assertFalse(msg().carriesMedia)
        assertTrue(msg(media = ByteArray(1)).carriesMedia)
        assertTrue(msg().copy(originalMediaData = ByteArray(1)).carriesMedia)
    }

    // ============================================================ THE EPOCH

    /**
     * Every date on this wire is Apple-reference seconds, and it prints without an exponent.
     *
     * Unix millis in this field decodes on an iPhone as roughly the year 55 000. The
     * conversion goes through `WireClock` and the number is formatted by
     * `WireClock.jsonNumber`, so `777782400.0` prints as `777782400` and not `7.777824E8`.
     */
    @Test
    fun `timestamps are apple-epoch seconds with no exponent`() {
        val json = String(msg().toWireJson(), Charsets.UTF_8)
        assertTrue("outer: $json", json.contains("\"timestamp\":$appleT0,"))
        val number = json.substringAfter("\"timestamp\":").substringBefore(",")
        assertFalse("no scientific notation in <$number>", number.contains("E") || number.contains("e"))
    }

    @Test
    fun `the nested encryptedContent timestamp is apple-epoch too`() {
        val json = String(msg().toWireJson(), Charsets.UTF_8)
        val nested = json.substringAfter("\"encryptedContent\":{").substringBefore("}")
        assertTrue("nested: $nested", nested.contains("\"timestamp\":$appleT0"))
    }

    /** A round trip lands back on the same millisecond. */
    @Test
    fun `a timestamp round-trips to the millisecond`() {
        val back = LoRaSecureMessage.fromWireJson(msg().toWireJson())!!
        assertEquals(t0, back.unixMillis)
        assertEquals(t0, back.encryptedContent.unixMillis)
    }

    /**
     * A Unix-MILLIS value in the Apple-epoch field yields a null timestamp and **keeps the
     * message** — stricter than either phone, which would render the year 55 000.
     */
    @Test
    fun `unix millis in the apple-epoch field yields null and keeps the message`() {
        val m = LoRaSecureMessage.fromWireJson(
            wireJson(timestamp = "1756089600000").toByteArray(Charsets.UTF_8)
        )
        assertNotNull(m)
        assertNull(m!!.unixMillis)
        assertEquals("11111111-2222-3333-4444-555555555555", m.id)
    }

    /** And the emitter refuses to put an unknown timestamp on the wire at all. */
    @Test
    fun `emitting with an unknown timestamp is refused`() {
        try {
            msg().copy(unixMillis = null).toWireJson()
            fail("iOS requires the key and drops a frame without it — we must not emit one")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestamp"))
        }
    }

    // ============================================================ THE REQUIRED KEYS

    /**
     * iOS decodes exactly seven keys with plain `decode`
     * (`MessageManager.swift:8521-8528`); a frame missing any one is dropped whole by an
     * iPhone, silently. Being more tolerant here would mean accepting frames a peer would
     * never send and could never read back.
     */
    @Test
    fun `each of the seven required keys is required`() {
        assertEquals(7, LoRaSecureMessage.REQUIRED_KEYS.size)
        for (key in LoRaSecureMessage.REQUIRED_KEYS) {
            val json = org.json.JSONObject(wireJson()).also { it.remove(key) }.toString()
            assertNull(
                "removing \"$key\" must drop the message — MessageManager.swift:8521-8528",
                LoRaSecureMessage.fromWireJson(json.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    /** A payload that is not JSON at all is null, not an exception: a corrupt frame is
     *  an ordinary event on a radio. */
    @Test
    fun `a non-JSON payload decodes to null rather than throwing`() {
        assertNull(LoRaSecureMessage.fromWireJson("not json".toByteArray()))
        assertNull(LoRaSecureMessage.fromWireJson(ByteArray(0)))
    }

    /** An absent optional is an ABSENT KEY, never JSON null — Swift `nil` encodes that way,
     *  and a null would spend wire bytes out of a 2160-byte budget for nothing. */
    @Test
    fun `absent optionals are absent keys and not nulls`() {
        val json = String(msg().toWireJson(), Charsets.UTF_8)
        assertFalse(json.contains("null"))
        assertFalse(json.contains("\"replyToId\""))
        assertFalse(json.contains("\"editedAt\""))
    }

    // ============================================================ THE RECIPIENT FILTER

    /**
     * OSHI BROADCASTS its ciphertext, so the only addressing is this comparison — and it
     * folds base64url and padding first (`MeshtasticManager.swift:469-474`).
     *
     * Comparing the two spellings literally would drop a legitimate message whenever one
     * side happened to hold the base64url form, with no error anywhere.
     */
    @Test
    fun `the recipient filter folds base64url and padding`() {
        val m = msg()
        assertTrue(LoRaSecureMessage.isAddressedToMe(m, myKey))
        val urlSpelling = myKey.replace("+", "-").replace("/", "_").trimEnd('=')
        assertTrue("the same key spelled base64url is the same key",
            LoRaSecureMessage.isAddressedToMe(m, urlSpelling))
        assertFalse(LoRaSecureMessage.isAddressedToMe(m, theirKey))
    }

    /**
     * **An empty identity matches nothing.**
     *
     * Both phones guard `!myKey.isNullOrEmpty()` before comparing
     * (`MeshtasticManager.kt:1115-1118`). Without the guard, a client with no identity
     * loaded would surface every nearby conversation's ciphertext to its own pipeline.
     */
    @Test
    fun `an empty identity is addressed by nothing`() {
        assertFalse(LoRaSecureMessage.isAddressedToMe(msg(), ""))
        // …including an envelope whose recipient field is itself empty.
        assertFalse(LoRaSecureMessage.isAddressedToMe(msg().copy(recipientPublicKey = ""), ""))
    }

    // ============================================================ FULL ROUND TRIP

    @Test
    fun `a message survives encode, framing, reassembly and decode`() {
        val m = msg(plaintext = "this must not travel")
        val frames = LoRaFrame.frames(0xCAFEBABEu, m.toWireJson())!!
        val r = LoRaReassembler()
        var whole: ByteArray? = null
        for (f in frames) whole = r.accept(f, 0) ?: whole
        assertNotNull(whole)

        val back = LoRaSecureMessage.fromWireJson(whole!!)!!
        assertEquals(m.id, back.id)
        assertEquals(t0, back.unixMillis)
        assertEquals("Y2lwaGVydGV4dA==", back.encryptedContent.ciphertext)
        assertNull("the strip survives the round trip", back.plaintextContent)
    }

    /** The base64 in a media field is STANDARD and padded, as Swift's `Data` encodes. */
    @Test
    fun `media base64 on receive is standard and padded`() {
        val raw = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte(), 1, 2, 3)
        val b64 = Base64.getEncoder().encodeToString(raw)
        assertTrue("fixture must exercise the alphabet", b64.contains('+') || b64.contains('/'))
        val m = LoRaSecureMessage.fromWireJson(
            wireJson(extra = ""","mediaAttachment":"$b64"""").toByteArray(Charsets.UTF_8)
        )!!
        assertArrayEquals(raw, m.mediaAttachment)
    }

    // ==================================================================== HELPERS

    /** A wire object written out literally, in iOS's `CodingKeys` order. */
    private fun wireJson(timestamp: String = "$appleT0", extra: String = ""): String =
        """{"id":"11111111-2222-3333-4444-555555555555",""" +
            """"senderAddress":"$theirKey","recipientAddress":"$myKey",""" +
            """"encryptedContent":{"ciphertext":"Y2lwaGVydGV4dA==","signature":"",""" +
            """"senderPublicKey":"$theirKey","timestamp":$appleT0},""" +
            """"timestamp":$timestamp,"isRead":false,"deliveryStatus":"sent",""" +
            """"senderPublicKey":"$theirKey","recipientPublicKey":"$myKey"$extra,""" +
            """"isViewOnce":false,"hasBeenViewed":false,"isDeletedForEveryone":false}"""
}
