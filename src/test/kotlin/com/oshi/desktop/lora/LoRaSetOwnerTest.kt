package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LORA_SET_OWNER_2026_10_29__ Naming the radio — the LoRa capability both phones
 * had and this client did not.
 *
 * The expected hex was computed in Python from Meshtastic's `AdminMessage`/`User`
 * definitions, not by calling anything here, for the reason this module keeps relearning:
 * a round trip through our own writer and our own parser passes when both share a mistake.
 */
class LoRaSetOwnerTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `set_owner is field 32 wrapping a User`() {
        // 8202 = field 32, wire 2. Inside: id (1) "!766eb396", long_name (2), short_name (3).
        assertEquals(
            "8202210a09213736366562333936120e4f534849204d65736820423339361a044f534849",
            hex(LoRaAdmin.setOwnerFrame(0x766eb396u, "OSHI Mesh B396", "OSHI")),
        )
    }

    @Test
    fun `the node id is the spelling every client uses`() {
        assertEquals("!766eb396", LoRaAdmin.nodeIdString(0x766eb396u))
        assertEquals("!0000000a", LoRaAdmin.nodeIdString(10u))
        assertEquals("!ffffffff", LoRaAdmin.nodeIdString(0xFFFFFFFFu))
    }

    // ---------------------------------------------------------------- the four BYTES

    @Test
    fun `short name is clamped to four BYTES, not four characters`() {
        // Android's __SHORT_NAME_IS_BYTES_2026_10_21__: `take(4)` counted UTF-16 chars, so
        // two emoji went out as EIGHT bytes and a surrogate pair could be cut in half.
        assertEquals("OSHI", LoRaAdmin.clampShortName("OSHI"))
        assertEquals("ABCD", LoRaAdmin.clampShortName("ABCDE"))
        assertEquals("🦊", LoRaAdmin.clampShortName("🦊🦊"))
        assertEquals("éé", LoRaAdmin.clampShortName("ééééé"))
        for (s in listOf("OSHI", "ABCDE", "🦊🦊", "ééééé", "日本語です")) {
            val out = LoRaAdmin.clampShortName(s)
            assertTrue(
                "'$s' clamped to '$out' = ${out.toByteArray(Charsets.UTF_8).size} bytes",
                out.toByteArray(Charsets.UTF_8).size <= LoRaAdmin.SHORT_NAME_MAX_BYTES,
            )
        }
    }

    @Test
    fun `a character is never cut in half`() {
        // Clamping BYTES by slicing the ByteArray would put invalid UTF-8 on the radio and
        // a replacement glyph on every client in range — worse than a name one byte short.
        val out = LoRaAdmin.clampShortName("🦊🦊")
        val round = String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals("the clamped name must survive a UTF-8 round trip", out, round)
        assertEquals(1, out.codePointCount(0, out.length))
    }

    @Test
    fun `whitespace is trimmed before clamping`() {
        assertEquals("AB", LoRaAdmin.clampShortName("  AB  "))
        assertEquals("", LoRaAdmin.clampShortName("   "))
    }

    // ---------------------------------------------------------------- the adopted name

    @Test
    fun `the adopted name matches iOS, hex suffix and all`() {
        // iOS: "OSHI Mesh \(String(format: "%04X", nodeNum & 0xFFFF))". The suffix is not
        // decoration — a mesh map with a dozen identical "OSHI Mesh" pins is useless.
        assertEquals("OSHI Mesh B396", LoRaAdmin.adoptedName(0x766eb396u))
        assertEquals("OSHI Mesh A86D", LoRaAdmin.adoptedName(0x4b88a86du))
        assertEquals("OSHI Mesh 000A", LoRaAdmin.adoptedName(10u))
        // Upper case, because iOS uses %04X and a map showing both spellings looks like
        // two different naming schemes.
        assertTrue(LoRaAdmin.adoptedName(0x0000abcdu).endsWith("ABCD"))
    }

    @Test
    fun `an adopted name and its short form fit the wire`() {
        val frame = LoRaAdmin.setOwnerFrame(0x766eb396u, LoRaAdmin.adoptedName(0x766eb396u), "OSHI")
        assertTrue("a set_owner frame must fit one LoRa packet", frame.size < 200)
    }
}
