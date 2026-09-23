package com.oshi.desktop.bot

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __BOT_E2E_2026_09_23__ `bot-seal-v1` on the recipient side, against the cross-platform
 * vectors (`ServerPatches/bot_e2e/test/vectors.json`, BOT_SEAL_SPEC.md: "every client MUST
 * pass the vectors") and against a Kotlin mirror of `bot_seal.js`'s sealer for the cases the
 * vectors do not carry.
 */
class BotSealedEnvelopeTest {

    private val v = BotSealFixtures.vectors()
    private val messageId = v.getString("messageId")
    private val envelope = v.getString("envelope")

    private fun recipient(i: Int) = v.getJSONArray("recipients").getJSONObject(i)
    private fun priv(i: Int) = b64d(recipient(i).getString("privateKeyB64"))
    private fun pub(i: Int) = b64d(recipient(i).getString("publicKeyB64"))

    @Test
    fun `kid is the first 16 hex chars of sha256(pub) for both vector recipients`() {
        for (i in 0..1) assertEquals(recipient(i).getString("kid"), BotSealedEnvelope.kidFor(pub(i)))
    }

    @Test
    fun `both vector recipients open the envelope to the vector's inner JSON`() {
        val expected = JSONObject(v.getString("innerJSON"))
        for (i in 0..1) {
            val d = BotEnvelope.decode(envelope, priv(i), pub(i))
            assertTrue("recipient $i: $d", d is BotEnvelope.Decoded.Message)
            val m = (d as BotEnvelope.Decoded.Message).message
            assertEquals(messageId, m.envelopeMessageId)
            assertEquals(messageId, m.payloadMessageId)
            assertEquals(expected.getString("content"), m.content)
            assertEquals("héllo 🔐 vectors", m.content)
            assertEquals("Vector Bot", m.botName)
            assertEquals("Vectors", m.groupName)
            assertEquals(expected.getString("groupId"), m.groupId)
            assertEquals("bot:abcd1234", m.senderAddress)
            assertEquals(BotEnvelope.Sealing.SERVER, m.sealing)
            assertEquals(java.time.Instant.parse("2026-09-23T12:00:00Z").toEpochMilli(), m.unixMillis)
            assertFalse("the outer placeholder must never surface", m.content.contains("Update OSHI"))
        }
        // And the low-level opener returns exactly the vector's inner object.
        val outer = BotSealFixtures.outerOf(envelope)
        assertEquals(expected.similar(BotSealedEnvelope.open(outer, priv(0), pub(0))), true)
    }

    @Test
    fun `a tampered body is dropped with a content-free reason`() {
        val outer = BotSealFixtures.outerOf(envelope)
        val sealed = outer.getJSONObject("sealed")
        val body = b64d(sealed.getString("body"))
        body[20] = (body[20].toInt() xor 1).toByte()
        sealed.put("body", b64e(body))
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertTrue(d is BotEnvelope.Decoded.Dropped)
        d as BotEnvelope.Decoded.Dropped
        assertEquals(messageId, d.envelopeMessageId)
        assertTrue(d.reason, d.reason.contains("body does not authenticate"))
        assertFalse(d.reason.contains("héllo"))
    }

    @Test
    fun `a tampered wrap is dropped`() {
        val outer = BotSealFixtures.outerOf(envelope)
        val wraps = outer.getJSONObject("sealed").getJSONObject("wraps")
        val kid = recipient(0).getString("kid")
        val w = b64d(wraps.getString(kid)); w[60] = (w[60].toInt() xor 0x80).toByte()
        wraps.put(kid, b64e(w))
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertTrue(d is BotEnvelope.Decoded.Dropped && d.reason.contains("wrap does not authenticate"))
    }

    @Test
    fun `a different outer messageId breaks the AAD and is dropped`() {
        val other = "6f1c1b0e-3d2a-4c55-9a3e-1b2c3d4e5f61"
        val outer = BotSealFixtures.outerOf(envelope).put("messageId", other)
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(other, outer), priv(0), pub(0))
        assertTrue("$d", d is BotEnvelope.Decoded.Dropped)
        assertEquals(other, d.envelopeMessageId)
    }

    @Test
    fun `an envelope id that differs from the sealed messageId is dropped`() {
        val moved = "11111111-1111-1111-1111-111111111111"
        val d = BotEnvelope.decode(envelope.replace(messageId, moved), priv(0), pub(0))
        assertTrue("$d", d is BotEnvelope.Decoded.Dropped)
        assertEquals(moved, d.envelopeMessageId)
    }

    @Test
    fun `an inner messageId that does not match the outer one is dropped`() {
        val inner = JSONObject(v.getString("innerJSON")).put("messageId", "99999999-9999-9999-9999-999999999999")
        val outer = BotSealFixtures.sealedOuter(inner, messageId, inner.getString("groupId"), listOf(pub(0)))
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertTrue(d is BotEnvelope.Decoded.Dropped && d.reason.contains("inner messageId"))
    }

    @Test
    fun `an inner groupId that does not match the outer one is dropped`() {
        val inner = JSONObject(v.getString("innerJSON"))
        val outer = BotSealFixtures.sealedOuter(inner, messageId, "00000000-0000-0000-0000-000000000000", listOf(pub(0)))
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertTrue(d is BotEnvelope.Decoded.Dropped && d.reason.contains("inner groupId"))
    }

    @Test
    fun `groupId and messageId comparisons are case-insensitive`() {
        val inner = JSONObject(v.getString("innerJSON")).put("messageId", messageId.uppercase())
        val outer = BotSealFixtures.sealedOuter(inner, messageId, inner.getString("groupId").lowercase(), listOf(pub(0)))
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertTrue("$d", d is BotEnvelope.Decoded.Message)
    }

    @Test
    fun `a key with no wrap is dropped and nothing is shown`() {
        val stranger = X25519PrivateKeyParameters(SecureRandom())
        val d = BotEnvelope.decode(envelope, stranger.encoded, stranger.generatePublicKey().encoded)
        assertTrue(d is BotEnvelope.Decoded.Dropped)
        d as BotEnvelope.Decoded.Dropped
        assertEquals(messageId, d.envelopeMessageId)
        assertTrue(d.reason, d.reason.contains("no wrap for this key"))
    }

    @Test
    fun `a missing wrap is dropped even if another member's wrap is present`() {
        val outer = BotSealFixtures.outerOf(envelope)
        outer.getJSONObject("sealed").getJSONObject("wraps").remove(recipient(1).getString("kid"))
        val env = BotSealFixtures.envelopeOf(messageId, outer)
        assertTrue(BotEnvelope.decode(env, priv(0), pub(0)) is BotEnvelope.Decoded.Message)
        val d = BotEnvelope.decode(env, priv(1), pub(1))
        assertTrue(d is BotEnvelope.Decoded.Dropped && d.reason.contains("no wrap"))
    }

    @Test
    fun `a wrap of the wrong length is dropped`() {
        val outer = BotSealFixtures.outerOf(envelope)
        outer.getJSONObject("sealed").getJSONObject("wraps").put(recipient(0).getString("kid"), b64e(ByteArray(91)))
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertTrue(d is BotEnvelope.Decoded.Dropped && d.reason.contains("bad wrap length"))
    }

    @Test
    fun `sealedBy bot is labelled end-to-end by the bot`() {
        val inner = JSONObject(v.getString("innerJSON")).put("sealedBy", "bot")
        val outer = BotSealFixtures.sealedOuter(inner, messageId, inner.getString("groupId"), listOf(pub(0)), e2e = true)
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(messageId, outer), priv(0), pub(0))
        assertEquals(BotEnvelope.Sealing.BOT, (d as BotEnvelope.Decoded.Message).message.sealing)
    }

    @Test
    fun `a sealed callback answer opens to its text`() {
        val id = "7a7a7a7a-0000-4000-8000-000000000001"
        val inner = JSONObject().put("sealedBy", "server").put("messageId", id)
            .put("type", "bot_callback_answer").put("callbackQueryId", "cq1").put("text", "Done ✓").put("showAlert", true)
        val sealed = BotSealFixtures.seal(inner.toString(), id, listOf(pub(1)))
        val outer = JSONObject().put("type", "bot_callback_answer").put("v", "bot-seal-v1").put("messageId", id)
            .put("timestamp", "2026-09-23T12:00:00.000Z").put("botName", "Bot").put("callbackQueryId", "cq1")
            .put("text", BotSealFixtures.PLACEHOLDER).put("showAlert", false).put("e2e", false).put("sealed", sealed)
        val d = BotEnvelope.decode(BotSealFixtures.envelopeOf(id, outer), priv(1), pub(1))
        d as BotEnvelope.Decoded.CallbackAnswer
        assertEquals("Done ✓", d.text)
        assertEquals("cq1", d.callbackQueryId)
        assertTrue(d.showAlert)
        assertEquals(BotEnvelope.Sealing.SERVER, d.sealing)
    }

    @Test
    fun `a legacy plaintext envelope still reads, labelled not end-to-end`() {
        val id = "11111111-2222-3333-4444-555555555555"
        val payload = """{"type":"bot_message","messageId":"$id","botToken":"a1b2c3d4...","botName":"B",""" +
            """"groupId":"G","groupName":"N","content":"hi","timestamp":"2026-08-25T14:03:11.123Z"}"""
        val d = BotEnvelope.decode("bot:$id:" + b64e(payload.toByteArray()), priv(0), pub(0))
        val m = (d as BotEnvelope.Decoded.Message).message
        assertEquals("hi", m.content)
        assertEquals(BotEnvelope.Sealing.NONE, m.sealing)
        assertEquals(m, BotEnvelope.parse("bot:$id:" + b64e(payload.toByteArray())))
    }

    @Test
    fun `garbage is dropped, and an envelope without an id cannot be acked`() {
        assertNull(BotEnvelope.decode("bot::xyz", priv(0), pub(0)).envelopeMessageId)
        val d = BotEnvelope.decode("bot:abc:!!!", priv(0), pub(0))
        assertTrue(d is BotEnvelope.Decoded.Dropped)
        assertEquals("abc", d.envelopeMessageId)
    }

    private fun b64d(s: String) = Base64.getDecoder().decode(s)
    private fun b64e(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/** Test-side mirror of `bot_seal.js` (sealForMembers / buildOuter / envelopeString). */
internal object BotSealFixtures {
    const val PLACEHOLDER = "🔒 Encrypted bot message. Update OSHI to read it."
    private val rng = SecureRandom()

    fun vectors(): JSONObject {
        val f = listOf(
            File(System.getProperty("oshi.repo.root") ?: "..", "ServerPatches/bot_e2e/test/vectors.json"),
            File("ServerPatches/bot_e2e/test/vectors.json"),
        ).firstOrNull { it.isFile } ?: error("vectors.json not found (ServerPatches/bot_e2e/test/vectors.json)")
        return JSONObject(f.readText())
    }

    fun outerOf(envelope: String): JSONObject =
        JSONObject(String(Base64.getDecoder().decode(envelope.split(":", limit = 3)[2]), Charsets.UTF_8))

    fun envelopeOf(messageId: String, outer: JSONObject): String =
        "bot:$messageId:" + Base64.getEncoder().encodeToString(outer.toString().toByteArray(Charsets.UTF_8))

    fun sealedOuter(inner: JSONObject, messageId: String, groupId: String, pubs: List<ByteArray>, e2e: Boolean = false): JSONObject =
        JSONObject().put("type", "bot_message").put("v", "bot-seal-v1").put("messageId", messageId)
            .put("timestamp", "2026-09-23T12:00:00.000Z").put("botName", "Bot").put("e2e", e2e)
            .put("sealed", seal(inner.toString(), messageId, pubs)).put("botId", "feedfacecafebeef")
            .put("groupId", groupId).put("content", PLACEHOLDER).put("botToken", "abcd1234...")

    fun seal(innerJson: String, messageId: String, pubs: List<ByteArray>): JSONObject {
        val k = ByteArray(32).also(rng::nextBytes)
        val body = gcm(k, innerJson.toByteArray(Charsets.UTF_8), "bot-seal-v1|body|$messageId")
        val wraps = JSONObject()
        for (pub in pubs) {
            val eph = X25519PrivateKeyParameters(rng)
            val epk = eph.generatePublicKey().encoded
            val shared = ByteArray(32)
            X25519Agreement().apply { init(eph); calculateAgreement(X25519PublicKeyParameters(pub, 0), shared, 0) }
            val wk = ByteArray(32)
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(shared, epk + pub, "bot-seal-v1".toByteArray()))
                generateBytes(wk, 0, 32)
            }
            val kid = BotSealedEnvelope.kidFor(pub)
            wraps.put(kid, Base64.getEncoder().encodeToString(epk + gcm(wk, k, "bot-seal-v1|wrap|$messageId|$kid")))
        }
        return JSONObject().put("v", "bot-seal-v1").put("alg", "X25519-HKDF-SHA256/AES-256-GCM")
            .put("body", Base64.getEncoder().encodeToString(body)).put("wraps", wraps)
    }

    private fun gcm(key: ByteArray, pt: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + c.doFinal(pt)
    }
}
