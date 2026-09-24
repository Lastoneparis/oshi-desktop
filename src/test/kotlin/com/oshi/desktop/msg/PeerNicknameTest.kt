package com.oshi.desktop.msg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __SHARED_NICKNAME_2026_09_22__ The peer's shared nickname: sanitizer, the tri-state wire
 * read (including a payload from a build that predates the key), and the display rule.
 */
class PeerNicknameTest {

    // ------------------------------------------------------------------------ sanitize

    @Test
    fun `plain names survive, surrounding whitespace does not`() {
        assertEquals("Hugo", PeerNickname.sanitize("  Hugo \t"))
    }

    @Test
    fun `empty, blank and null all read as no nickname`() {
        assertNull(PeerNickname.sanitize(null))
        assertNull(PeerNickname.sanitize(""))
        assertNull(PeerNickname.sanitize("   \n  "))
    }

    @Test
    fun `newlines, controls and bidi overrides are stripped`() {
        assertEquals("AliceBob", PeerNickname.sanitize("Alice\nBob"))
        assertEquals("evil.exe", PeerNickname.sanitize("evil‮.exe"))
        assertEquals("abc", PeerNickname.sanitize("⁦a⁩b‏c\u0000"))
        assertEquals("ab", PeerNickname.sanitize("a b‪"))
        assertNull("a name made only of bidi controls is no name", PeerNickname.sanitize("‮‭"))
    }

    @Test
    fun `the cap is 48 code points and never splits a surrogate pair`() {
        assertEquals(48, PeerNickname.sanitize("x".repeat(100))!!.length)
        val emoji = "😀" // one code point, two UTF-16 units
        val capped = PeerNickname.sanitize(emoji.repeat(60))!!
        assertEquals(48, capped.codePointCount(0, capped.length))
        assertEquals(96, capped.length)
    }

    // ------------------------------------------------------------------------ wire read

    private fun update(body: String) = ControlPrefix.PROFILE_UPDATE + body

    @Test
    fun `an old payload without the key is Absent, never Clear`() {
        // Byte-for-byte the shape iOS <= 1.0.x and Android emit before this key existed.
        val old = """{"type":"profile_update","profileImageData":null,"lastSeen":1.7E9,"version":1,"groupEnvelopeV3":true,"ratchetV3":true}"""
        assertEquals(PeerNickname.Read.Absent, PeerNickname.readProfileUpdate(update(old)))
    }

    @Test
    fun `a present nickname is Set, sanitized`() {
        assertEquals(
            PeerNickname.Read.Set("Hugo"),
            PeerNickname.readProfileUpdate(update("""{"type":"profile_update","displayName":" Hugo\n"}""")),
        )
    }

    @Test
    fun `a present but empty or null nickname is Clear`() {
        assertEquals(PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":""}""")))
        assertEquals(PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":null}""")))
        assertEquals(PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":"‮ "}""")))
        assertEquals("a non-string is not a name", PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":42}""")))
    }

    @Test
    fun `unreadable bodies and other prefixes are Absent`() {
        assertEquals(PeerNickname.Read.Absent, PeerNickname.readProfileUpdate(update("not json")))
        assertEquals(PeerNickname.Read.Absent, PeerNickname.readProfileUpdate("""{"displayName":"x"}"""))
        assertEquals(
            PeerNickname.Read.Absent,
            PeerNickname.readProfileUpdate(ControlPrefix.PROFILE_REQUEST + """{"displayName":"x"}"""),
        )
    }

    @Test
    fun `a profile update never fires a push from any send path`() {
        assert(ControlPrefix.suppressesPush(update("""{"displayName":"Hugo"}""")))
    }

    // ------------------------------------------------------------------------ resolve

    @Test
    fun `alias beats nickname beats fallback`() {
        assertEquals("Mum", PeerNickname.resolve("Mum", "Hugo", "abcd…"))
        assertEquals("Hugo", PeerNickname.resolve(null, "Hugo", "abcd…"))
        assertEquals("Hugo", PeerNickname.resolve("  ", "Hugo", "abcd…"))
        assertEquals("abcd…", PeerNickname.resolve(null, null, "abcd…"))
        assertEquals("abcd…", PeerNickname.resolve("", " ", "abcd…"))
    }
}
