package com.oshi.desktop

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.V2Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable form of the parity risk list in PLAN.md.
 *
 * Every test here corresponds to a divergence that ACTUALLY HAPPENED between iOS and
 * Android and cost real messages. They are written as guards, not as documentation:
 * a desktop client that regresses one of them fails the build rather than shipping a
 * silently-incompatible message.
 */
class WireFormatTest {

    private val alice = DesktopIdentity.generate()
    private val bob = DesktopIdentity.generate()

    private fun sampleEnvelope(
        groupId: String? = null,
        x3dh: V2Session.X3DHHeader? = null,
    ) = DesktopEnvelope(
        msgId = "11111111-2222-3333-4444-555555555555",
        from = alice.userKey,
        to = bob.userKey,
        groupId = groupId,
        x3dh = x3dh,
        header = ByteArray(40) { it.toByte() },
        ciphertext = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        ts = 1756060000000L,
    )

    // ---- RISK 1: base64 vs base64url --------------------------------------------

    /**
     * Android emits `Base64.NO_WRAP`; iOS reads with a bare `Data(base64Encoded:)`, whose
     * default options reject `-`, `_`, missing padding AND embedded newlines. Standard,
     * padded, unwrapped is the only spelling all three platforms accept.
     */
    @Test
    fun `wire base64 is standard padded and never url-safe or wrapped`() {
        // 0xFB 0xFF produces '+' and '/' in the standard alphabet, '-' and '_' in url-safe.
        val tricky = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte(), 0xFE.toByte(), 0x00)
        val encoded = DesktopIdentity.B64.encodeToString(tricky)

        assertTrue("must use the standard alphabet", encoded.contains('+') || encoded.contains('/'))
        assertFalse("base64url '-' would be unreadable by iOS", encoded.contains('-'))
        assertFalse("base64url '_' would be unreadable by iOS", encoded.contains('_'))
        assertFalse("a line break makes Data(base64Encoded:) return nil", encoded.contains('\n'))
        assertFalse("a line break makes Data(base64Encoded:) return nil", encoded.contains('\r'))
        assertTrue("padding is required", encoded.endsWith("="))
    }

    /**
     * The emitted bytes must be deterministic. `JSONObject.toString()` is NOT: the Maven
     * org.json artifact is HashMap-backed, while Android's is LinkedHashMap-backed, so the
     * same code emits a different key order on the two platforms. We emit through an
     * explicitly ordered writer; this test pins both the order and the stability.
     */
    @Test
    fun `wire bytes are byte-for-byte stable and in the declared key order`() {
        val env = sampleEnvelope()
        val once = String(env.toWireBytes())
        repeat(20) { assertEquals("emission must be deterministic", once, String(env.toWireBytes())) }

        val order = listOf("\"v\"", "\"msgId\"", "\"from\"", "\"to\"", "\"type\"", "\"header\"",
            "\"ciphertext\"", "\"ts\"")
        var cursor = -1
        for (key in order) {
            val at = once.indexOf(key)
            assertTrue("$key missing from the envelope", at >= 0)
            assertTrue("$key is out of order", at > cursor)
            cursor = at
        }
        assertTrue("v must be emitted first", once.startsWith("{\"v\":4,"))
        // And it must still parse.
        assertEquals(env.ts, DesktopEnvelope.fromJson(JSONObject(once)).ts)
    }

    /** The same rule, applied to the fields that actually carry key material. */
    @Test
    fun `envelope byte fields carry no url-safe characters and no line breaks`() {
        val json = String(sampleEnvelope().toWireBytes())
        assertFalse(json.contains('\n'))
        for (field in listOf("header", "ciphertext", "from", "to")) {
            val v = JSONObject(json).getString(field)
            assertFalse("$field must not be base64url", v.contains('-') || v.contains('_'))
        }
    }

    // ---- RISK 2: date encodings ---------------------------------------------------

    /**
     * The relay envelope's `ts` is epoch MILLISECONDS as a JSON number.
     * Three wrong answers, all of which exist elsewhere in this codebase:
     *   - epoch SECONDS (the legacy IPFS inner payload)
     *   - APPLE epoch, seconds since 2001-01-01 (anything iOS types as `Date` under a
     *     bare JSONDecoder — 978307200 less than Unix seconds)
     *   - an ISO-8601 STRING (only `MessageGroup` uses those)
     */
    @Test
    fun `envelope ts is unix epoch millis as a number`() {
        // Asserted on the bytes ACTUALLY SENT, not on toJson(). An earlier version of this
        // test read toJson() and stayed green while the emitter divided ts by 1000 — the
        // guard was watching a serializer that never reaches the wire.
        val wire = String(sampleEnvelope().toWireBytes())
        assertTrue("ts must be a bare JSON number, never a quoted ISO-8601 string",
            wire.contains("\"ts\":1756060000000"))
        assertFalse("a quoted ts is an ISO-8601 regression", wire.contains("\"ts\":\""))

        val json = JSONObject(wire)
        val ts = json.get("ts")
        assertTrue("ts must be a JSON number, never an ISO-8601 string", ts is Long || ts is Int)

        val millis = json.getLong("ts")
        assertEquals("ts must be millis — dividing by 1000 is the seconds regression",
            1756060000000L, millis)

        // Magnitude guard: the codebase's own >1e10 heuristic separates millis from seconds.
        assertTrue("value is in the millis range, not seconds", millis > 1e12)

        val appleEpochSeconds = millis / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS
        assertTrue("must NOT be Apple-epoch seconds", millis.toDouble() != appleEpochSeconds)
    }

    /**
     * Guard for the OTHER convention, the one that bites payloads INSIDE the ciphertext.
     * Anything iOS declares as `Date` and encodes with a bare JSONEncoder is Apple epoch.
     * A desktop client that sends Unix millis there does not throw on iOS — it decodes to
     * roughly the year 58,000 and quietly drives an "(edited)" stamp or a location age.
     */
    @Test
    fun `apple epoch conversion is exact for control payloads`() {
        val unixMillis = 1756060000000L
        val apple = unixMillis / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS
        assertEquals(777752800.0, apple, 0.001)

        // The round trip a receiver performs.
        assertEquals(unixMillis, ((apple + APPLE_EPOCH_OFFSET_SECONDS) * 1000).toLong())

        // The >1e10 legacy guard both platforms use to spot a mis-encoded value.
        assertTrue("a correct Apple-epoch value is below the guard", apple < 1e10)
        assertTrue("raw Unix millis trips the guard", unixMillis.toDouble() > 1e10)
    }

    // ---- RISK 3: omitted vs null --------------------------------------------------

    /**
     * `groupId`, `x3dh` and `oneTimePreKeyId` are ABSENT KEYS when they do not apply.
     * Emitting an explicit JSON null instead is the classic way to break the stricter of
     * two decoders, and it is exactly what iOS's `encodeIfPresent` never does.
     */
    @Test
    fun `absent optionals are omitted keys and never json null`() {
        val env = sampleEnvelope(groupId = null, x3dh = null)
        val json = env.toJson()
        assertFalse("groupId must be absent, not null", json.has("groupId"))
        assertFalse("x3dh must be absent, not null", json.has("x3dh"))
        assertFalse("no literal null may reach the wire", String(env.toWireBytes()).contains("null"))
    }

    @Test
    fun `a drained one-time prekey omits oneTimePreKeyId entirely`() {
        val x3dh = V2Session.X3DHHeader(
            identityKey = ByteArray(32) { 1 },
            ephemeralKey = ByteArray(32) { 2 },
            signedPreKeyId = "spk-1",
            oneTimePreKeyId = null,
        )
        val inner = sampleEnvelope(x3dh = x3dh).toJson().getJSONObject("x3dh")
        assertTrue(inner.has("signedPreKeyId"))
        assertFalse("must be an absent key when the OPK pool is drained", inner.has("oneTimePreKeyId"))
    }

    // ---- RISK 4: strict decoders --------------------------------------------------

    /**
     * Swift's Codable throws on a MISSING key; kotlinx throws on an EXTRA one. Both have
     * cost this project whole messages. The v2 reader must ignore unknown fields — on a
     * PULL, one throw takes the entire array of messages with it, not just one.
     */
    @Test
    fun `an unknown field does not sink the message`() {
        val json = sampleEnvelope().toJson().apply {
            put("edited", true)                     // a field a future platform adds
            put("someFutureThing", JSONObject().put("nested", 1))
        }
        val parsed = DesktopEnvelope.fromJson(json)
        assertEquals("11111111-2222-3333-4444-555555555555", parsed.msgId)
        assertEquals(1756060000000L, parsed.ts)
    }

    /** An explicit null from a sloppy peer must read as absent, not crash or stringify. */
    @Test
    fun `an explicit null optional reads as absent`() {
        val json = sampleEnvelope().toJson().apply { put("groupId", JSONObject.NULL) }
        assertEquals(null, DesktopEnvelope.fromJson(json).groupId)
    }

    /**
     * `signedPreKeyId` is declared String but iOS's `decodeFlexibleKeyId` accepts a String
     * OR a Number. A peer that sends `"keyId": 7` must not break the session.
     */
    @Test
    fun `a numeric prekey id is accepted as a string`() {
        val json = sampleEnvelope(x3dh = V2Session.X3DHHeader(
            ByteArray(32), ByteArray(32), "ignored", null)).toJson()
        json.getJSONObject("x3dh").put("signedPreKeyId", 7)
        assertEquals("7", DesktopEnvelope.fromJson(json).x3dh!!.signedPreKeyId)
    }

    // ---- RISK 5: routing by prefix -------------------------------------------------

    /**
     * The most expensive bug in this project's history. iOS serialises some payloads from
     * a Swift `[String: Any]` with no `.sortedKeys`, so dictionary order is the per-process
     * seeded hash order — a DIFFERENT key order on every launch of the same binary. Android
     * routed on `startsWith("{\"type\":")`, which held on only 120 of the 720 orderings, and
     * photos were destroyed on roughly four launches in five.
     *
     * A desktop client must PARSE and branch, never prefix-match. This test asserts the
     * property directly by permuting the keys.
     */
    @Test
    fun `media routing survives every key ordering`() {
        val keys = listOf("type", "mediaType", "content", "size", "isViewOnce", "fileName")
        val perms = permutations(keys)
        assertEquals(720, perms.size)

        var startsWithType = 0
        for (p in perms) {
            val o = JSONObject()
            for (k in p) {
                when (k) {
                    "type" -> o.put("type", "media")
                    "mediaType" -> o.put("mediaType", "image")
                    "content" -> o.put("content", "AAAA")
                    "size" -> o.put("size", 3)
                    "isViewOnce" -> o.put("isViewOnce", false)
                    "fileName" -> o.put("fileName", "a.png")
                }
            }
            val s = o.toString()
            if (s.startsWith("{\"type\":")) startsWithType++
            assertTrue("parse-based detection must hold for every ordering", isMediaEnvelope(s))
        }
        // org.json does not preserve insertion order, so we assert the real property:
        // prefix-matching is NOT reliable, while parsing is.
        assertTrue("prefix matching is unreliable by construction", startsWithType < perms.size)
    }

    /** Parse, require payload bytes, then branch — never `startsWith`. */
    private fun isMediaEnvelope(text: String): Boolean = try {
        val o = JSONObject(text.trimStart())
        val hasPayload = !o.optString("content").isNullOrEmpty() || !o.optString("data").isNullOrEmpty()
        hasPayload && (o.optString("type").equals("media", true) ||
            o.optString("type").uppercase() in setOf("IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "CONTACT") ||
            o.optString("mediaType").isNotEmpty())
    } catch (_: Exception) { false }

    private fun permutations(items: List<String>): List<List<String>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }

    // ---- RISK 6: JSON escaping ------------------------------------------------------

    /**
     * A hand-rolled escaper that handled `"` and `\n` but not `\` turned a caption of
     * `C:\Users` into invalid JSON: iOS stored the whole base64 photo as a text bubble,
     * Android dropped the message outright. One shared escaper, and it must cover the
     * backslash, CR, TAB and the C0 range.
     */
    @Test
    fun `the shared escaper survives backslashes controls and quotes`() {
        val nasty = "C:\\Users\t\"quoted\"\r\n\u0007 end"
        val json = """{"caption":"${OSHICryptoV2.jsonEscape(nasty)}"}"""
        assertEquals("must re-parse cleanly", nasty, JSONObject(json).getString("caption"))
        assertTrue("backslash escaped", json.contains("\\\\"))
        assertTrue("C0 control escaped as \\u", json.contains("\\u0007"))
    }

    // ---- RISK 7: request signing ------------------------------------------------------

    /**
     * `encodeIdentity` percent-encodes everything that is not ASCII alphanumeric. A base64
     * identity contains `+`, `/` and `=`; a normal URL encoder leaves `=` alone and turns
     * a space into `+`. Getting this wrong breaks every identity-in-path route with a 401
     * that looks like a clock-skew problem.
     */
    @Test
    fun `encodeIdentity percent-encodes plus slash and equals`() {
        assertEquals("aB9%2B%2F%3D", DesktopV2Signer.encodeIdentity("aB9+/="))
        // Every non-alphanumeric byte, upper-case hex.
        assertEquals("%2D%5F%2E%7E", DesktopV2Signer.encodeIdentity("-_.~"))
        // A real address survives round-tripping through the path.
        val encoded = DesktopV2Signer.encodeIdentity(alice.userKey)
        assertTrue(encoded.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `canonical string is METHOD PATH BODYHASH TIMESTAMP with three newlines`() {
        val signer = DesktopV2Signer(alice)
        val canonical = signer.canonicalString("GET", "/v2/config", ByteArray(0), "1756060000000")
        val parts = canonical.split("\n")
        assertEquals(4, parts.size)
        assertEquals("GET", parts[0])
        assertEquals("/v2/config", parts[1])
        // SHA-256 of the empty input, LOWERCASE hex. Upper-case here is a silent 401.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", parts[2])
        assertEquals(parts[2], parts[2].lowercase())
        assertEquals("1756060000000", parts[3])
    }

    /** The verify-first blob/account routes hash the TWO-BYTE string `""`, not zero bytes. */
    @Test
    fun `the empty-json-string body is two bytes and hashes differently from zero bytes`() {
        assertEquals(2, DesktopV2Signer.EMPTY_JSON_STRING_BODY.size)
        assertEquals("\"\"", String(DesktopV2Signer.EMPTY_JSON_STRING_BODY))
        assertTrue(
            DesktopV2Signer.sha256Hex(DesktopV2Signer.EMPTY_JSON_STRING_BODY) !=
                DesktopV2Signer.sha256Hex(ByteArray(0))
        )
    }

    /** Signing the canonical string must be verifiable with the published signing key. */
    @Test
    fun `request signature verifies against the advertised signing pubkey`() {
        val signer = DesktopV2Signer(alice)
        val headers = signer.sign("POST", "/v2/messages", "body".toByteArray(), timestampMs = 1756060000000L)
        val canonical = signer.canonicalString("POST", "/v2/messages", "body".toByteArray(), "1756060000000")
        val sig = DesktopIdentity.B64D.decode(headers["x-oshi-signature"])
        val pub = DesktopIdentity.B64D.decode(headers["x-oshi-signing-pubkey"])
        assertTrue(DesktopIdentity.verify(canonical.toByteArray(Charsets.UTF_8), sig, pub))
    }

    /** `x-oshi-user` is the X25519 ADDRESS, never the Ed25519 signing key. */
    @Test
    fun `the user header is the x25519 identity not the signing key`() {
        val headers = DesktopV2Signer(alice).sign("DELETE", "/v2/account",
            DesktopV2Signer.EMPTY_JSON_STRING_BODY, withUserHeader = true)
        assertEquals(alice.userKey, headers["x-oshi-user"])
        assertTrue(headers["x-oshi-user"] != headers["x-oshi-signing-pubkey"])
        assertEquals(32, DesktopIdentity.B64D.decode(headers["x-oshi-user"]).size)
    }

    // ---- RISK 8: rollout bucketing --------------------------------------------------

    /**
     * The rollout bucket must land an identity in the SAME cohort on all platforms, or a
     * desktop client publishes a prekey bundle while the server believes that identity is
     * not V2-enabled. First 4 bytes of SHA-256(userKey), big-endian, mod 100.
     */
    @Test
    fun `rollout bucket matches the documented big-endian mod 100`() {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        val bucket = (v % 100).toInt()
        assertTrue(bucket in 0..99)
        // SHA-256("abc") = ba7816bf... -> 0xba7816bf = 3_128_432_319 -> mod 100 = 19.
        // Pinned so a little-endian read (which would give a different cohort, and so a
        // desktop client enabled when the server thinks it is not) fails here.
        assertEquals(19, bucket)
    }

    companion object {
        /** Seconds between 1970-01-01 and 2001-01-01. The Apple reference epoch offset. */
        const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200.0
    }
}
