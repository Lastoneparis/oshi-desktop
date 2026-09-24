package com.oshi.desktop.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * __SHARED_NICKNAME_2026_09_22__ The `📸PROFILE_UPDATE📸` payload as the THREE clients
 * actually write it, read by every other one.
 *
 * The iOS fixtures are shaped like `JSONEncoder` output of `ProfileUpdatePayload`
 * (arbitrary key order, `/` escaped as `\/`, raw UTF-8, `nil` keys omitted). The Android
 * fixtures are `MessageRepository.sendProfileUpdate` output (`"profileImageData":null`,
 * org.json number formatting). [IosDecoderRules] replays what the iOS synthesized
 * `Codable` accepts — REQUIRED `type`/`lastSeen`/`version`, optional keys that must still
 * have the right JSON type — plus `SharedNickname.sanitize`.
 *
 * The same fixtures are asserted on Android in `ProfileUpdateCrossPlatformTest.kt`.
 */
class ProfileUpdateCrossPlatformTest {

    companion object {
        const val P = "📸PROFILE_UPDATE📸"

        /** iOS 1.0.47-style payload with a nickname, an avatar, capabilities. */
        const val IOS = P + """{"ratchetV3":true,"lastSeen":1790000000.25,"appPlatform":"ios","displayName":"Zoé 🌸","groupEnvelopeV3":true,"type":"profile_update","version":1,"appVersion":"1.0.47","profileImageData":"iVBORw0KGgo\/8AAA=="}"""

        /** A hostile iOS-shaped nickname: RLO, a newline, a zero-width LRM. */
        const val IOS_HOSTILE = P + """{"type":"profile_update","lastSeen":1790000000,"version":1,"displayName":"‮Hugo\nX‎"}"""

        /** An iOS build from before the nickname existed. */
        const val IOS_OLD = P + """{"type":"profile_update","lastSeen":1790000000,"version":1,"groupEnvelopeV3":true,"ratchetV3":true}"""

        /** Android after this change: Last Seen OFF now writes 0 instead of dropping the key. */
        const val ANDROID = P + """{"type":"profile_update","profileImageData":null,"lastSeen":0,"version":1,"groupEnvelopeV3":true,"ratchetV3":true,"appVersion":"1.6.20","appPlatform":"android","displayName":"Hugo"}"""

        /** Android BEFORE this change with Last Seen off — no `lastSeen` key at all. */
        const val ANDROID_PRE_FIX_LASTSEEN_OFF = P + """{"type":"profile_update","profileImageData":null,"version":1,"groupEnvelopeV3":true,"ratchetV3":true,"displayName":"Hugo"}"""

        /** What THIS client emits for "Hugo desk" (JSON-equal; org.json key order varies). */
        const val DESKTOP = P + """{"type":"profile_update","lastSeen":0,"version":1,"displayName":"Hugo desk"}"""
    }

    // ------------------------------------------------------------------ iOS decoder rules

    /**
     * What `JSONDecoder().decode(ProfileUpdatePayload.self, …)` accepts, then what
     * `processIncomingProfileUpdate` does with `displayName`. Returns the nickname iOS would
     * store: `Absent` (key missing), `Clear`, or `Set(name)`. Throws where iOS throws.
     */
    object IosDecoderRules {
        class DecodingError(msg: String) : Exception(msg)

        fun decode(text: String): PeerNickname.Read {
            if (!text.startsWith(P)) throw DecodingError("not a profile update")
            val o = JSONObject(text.removePrefix(P))
            requireString(o, "type")
            requireNumber(o, "lastSeen")
            requireInt(o, "version")
            for (k in listOf("profileImageData", "pqPublicKey", "appVersion", "appPlatform", "displayName")) optionalString(o, k)
            for (k in listOf("groupEnvelopeV3", "ratchetV3")) optionalBool(o, k)
            // `nicknamePresent` is a JSONSerialization lookup: NSNull counts as present.
            if (!o.has("displayName")) return PeerNickname.Read.Absent
            val raw = if (o.isNull("displayName")) null else o.getString("displayName")
            return sanitize(raw)?.let { PeerNickname.Read.Set(it) } ?: PeerNickname.Read.Clear
        }

        private fun requireString(o: JSONObject, k: String) {
            if (!o.has(k) || o.isNull(k)) throw DecodingError("keyNotFound/valueNotFound $k")
            if (o.get(k) !is String) throw DecodingError("typeMismatch $k")
        }
        private fun requireNumber(o: JSONObject, k: String) {
            if (!o.has(k) || o.isNull(k)) throw DecodingError("keyNotFound/valueNotFound $k")
            if (o.get(k) !is Number) throw DecodingError("typeMismatch $k")
        }
        private fun requireInt(o: JSONObject, k: String) {
            requireNumber(o, k)
            val d = (o.get(k) as Number).toDouble()
            if (d != Math.floor(d)) throw DecodingError("typeMismatch $k (not integral)")
        }
        private fun optionalString(o: JSONObject, k: String) {
            if (o.has(k) && !o.isNull(k) && o.get(k) !is String) throw DecodingError("typeMismatch $k")
        }
        private fun optionalBool(o: JSONObject, k: String) {
            if (o.has(k) && !o.isNull(k) && o.get(k) !is Boolean) throw DecodingError("typeMismatch $k")
        }

        /**
         * `SharedNickname.sanitize` (OSHI/ContactPresenceManager.swift): drop Unicode Cc, the
         * line/paragraph separators and the bidi controls; trim; keep 48 CHARACTERS
         * (grapheme clusters — approximated by code points here, identical for the inputs
         * below); trim again; empty ⇒ nil.
         */
        fun sanitize(raw: String?): String? {
            if (raw == null) return null
            val sb = StringBuilder()
            raw.codePoints().forEach { cp ->
                val drop = Character.getType(cp) == Character.CONTROL.toInt() ||
                    cp == 0x2028 || cp == 0x2029 || cp == 0x200E || cp == 0x200F ||
                    cp in 0x202A..0x202E || cp in 0x2066..0x2069
                if (!drop) sb.appendCodePoint(cp)
            }
            var s = sb.toString().trim()
            if (s.codePointCount(0, s.length) > 48) s = s.substring(0, s.offsetByCodePoints(0, 48)).trim()
            return s.ifEmpty { null }
        }
    }

    private fun jsonEquals(a: String, b: String) =
        JSONObject(a.removePrefix(P)).similar(JSONObject(b.removePrefix(P)))

    // ------------------------------------------------------------------ inbound on Desktop

    @Test
    fun `an iOS payload parses on Desktop`() {
        assertEquals(PeerNickname.Read.Set("Zoé 🌸"), PeerNickname.readProfileUpdate(IOS))
        assertEquals(PeerNickname.Read.Set("HugoX"), PeerNickname.readProfileUpdate(IOS_HOSTILE))
        assertEquals(PeerNickname.Read.Absent, PeerNickname.readProfileUpdate(IOS_OLD))
    }

    @Test
    fun `an Android payload parses on Desktop, before and after the lastSeen fix`() {
        assertEquals(PeerNickname.Read.Set("Hugo"), PeerNickname.readProfileUpdate(ANDROID))
        assertEquals(PeerNickname.Read.Set("Hugo"), PeerNickname.readProfileUpdate(ANDROID_PRE_FIX_LASTSEEN_OFF))
    }

    @Test
    fun `the iOS rules agree with the Kotlin reader on every well-formed fixture`() {
        for (f in listOf(IOS, IOS_HOSTILE, IOS_OLD, ANDROID, DESKTOP)) {
            assertEquals(f, PeerNickname.readProfileUpdate(f), IosDecoderRules.decode(f))
        }
    }

    @Test
    fun `iOS REJECTS the pre-fix Android payload - the bug the lastSeen fix closes`() {
        try {
            IosDecoderRules.decode(ANDROID_PRE_FIX_LASTSEEN_OFF)
            fail("iOS decodes lastSeen as a required Double; a payload without it must not decode")
        } catch (e: IosDecoderRules.DecodingError) {
            assertTrue(e.message!!.contains("lastSeen"))
        }
    }

    // ------------------------------------------------------------------ outbound from Desktop

    @Test
    fun `Desktop output is the documented fixture and decodes under the iOS rules`() {
        val out = ProfileUpdateWire.encode("Hugo desk")
        assertTrue(out, jsonEquals(out, DESKTOP))
        assertEquals(PeerNickname.Read.Set("Hugo desk"), IosDecoderRules.decode(out))
        assertEquals(PeerNickname.Read.Set("Hugo desk"), PeerNickname.readProfileUpdate(out))
        assertTrue("silent on every lane", ControlPrefix.suppressesPush(out))
    }

    @Test
    fun `Desktop never claims an avatar or a capability it does not have`() {
        val o = JSONObject(ProfileUpdateWire.encode("Hugo").removePrefix(P))
        for (k in listOf("profileImageData", "groupEnvelopeV3", "ratchetV3", "pqPublicKey", "appVersion", "appPlatform")) {
            assertFalse("unexpected key $k", o.has(k))
        }
        assertEquals(0, o.getInt("lastSeen"))
    }

    @Test
    fun `no nickname omits the key, an explicit clear sends an empty one`() {
        val none = ProfileUpdateWire.encode(null)
        assertFalse(JSONObject(none.removePrefix(P)).has("displayName"))
        assertEquals(PeerNickname.Read.Absent, IosDecoderRules.decode(none))

        val clear = ProfileUpdateWire.encode(null, clear = true)
        assertEquals(PeerNickname.Read.Clear, IosDecoderRules.decode(clear))
        assertEquals(PeerNickname.Read.Clear, PeerNickname.readProfileUpdate(clear))
    }

    @Test
    fun `what Desktop sends is already a fixed point of the iOS sanitizer`() {
        val nasty = "  ‮" + "🌸".repeat(60) + "\n"
        val sent = (IosDecoderRules.decode(ProfileUpdateWire.encode(nasty)) as PeerNickname.Read.Set).name
        assertEquals(sent, IosDecoderRules.sanitize(sent))
        assertEquals(48, sent.codePointCount(0, sent.length))
        assertFalse(sent.contains('�'))
    }

    // ------------------------------------------------------------------ own-device archive

    @Test
    fun `writing our nickname into the phones' profile blob keeps their avatar and links`() {
        val phone = """{"userName":"Old","profileImageData":"AAAA","profileLinks":"eyJ9"}""".toByteArray()
        val merged = JSONObject(String(ProfileUpdateWire.mergeLegacyProfileBlob(phone, "Hugo"), Charsets.UTF_8))
        assertEquals("Hugo", merged.getString("userName"))
        assertEquals("AAAA", merged.getString("profileImageData"))
        assertEquals("eyJ9", merged.getString("profileLinks"))

        val cleared = JSONObject(String(ProfileUpdateWire.mergeLegacyProfileBlob(phone, null), Charsets.UTF_8))
        assertFalse(cleared.has("userName"))
        assertEquals("AAAA", cleared.getString("profileImageData"))

        assertEquals("Hugo", ProfileUpdateWire.readLegacyProfileBlob("""{"userName":" Hugo‮ "}""".toByteArray()))
        assertNull(ProfileUpdateWire.readLegacyProfileBlob("""{"profileImageData":"AAAA"}""".toByteArray()))
        assertNull(ProfileUpdateWire.readLegacyProfileBlob("not json".toByteArray()))
    }
}
