package com.oshi.desktop.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two admin writes, checked at the BYTE level.
 *
 * Every assertion here describes a way to leave a working radio silent, which is why this
 * file does not settle for "it produced some bytes":
 *
 *  - a Config sent under field 33 is parsed as a Channel and thrown away, so "Set region"
 *    does nothing on the one screen that exists to rescue a node from `region=UNSET`;
 *  - `use_preset` omitted arrives as false, which puts the radio on a manual bandwidth and
 *    spread factor of 0 — configured, on the air, and unable to talk to anyone;
 *  - a `psk` omitted is "no encryption at all", not "keep what you had".
 *
 * None of these fails to compile, and none of them throws at runtime. They are only visible
 * in the bytes, so the bytes are what is asserted.
 */
class LoRaAdminTest {

    /** Decode one length-delimited field, or fail. */
    private fun sub(bytes: ByteArray, field: Int): ByteArray {
        val f = LoRaProto.field(bytes, field, wire = 2)
        assertNotNull("field $field absent", f)
        return f!!.payload
    }

    private fun varint(bytes: ByteArray, field: Int): Long? =
        LoRaProto.field(bytes, field, wire = 0)?.varintValue

    // ================================================================ set_config

    /**
     * 34, NOT 33. iOS hit this and recorded it: a Config under 33 is parsed as a Channel
     * and discarded, so the write silently does nothing.
     */
    @Test
    fun `a config goes under AdminMessage field 34`() {
        val admin = LoRaAdmin.setConfigFrame(LoRaAdmin.Region.EU868)!!
        assertNotNull("set_config must be field 34", LoRaProto.field(admin, 34, wire = 2))
        assertNull("field 33 is set_channel and must be absent", LoRaProto.field(admin, 33, wire = 2))
    }

    @Test
    fun `the LoRaConfig sits in Config field 6 and carries every field explicitly`() {
        val admin = LoRaAdmin.setConfigFrame(LoRaAdmin.Region.EU868, LoRaAdmin.ModemPreset.LONG_FAST, 3)!!
        val lora = sub(sub(admin, 34), 6)

        // use_preset: omitted would arrive as FALSE and strand the radio on a manual
        // bandwidth and spread factor of 0.
        assertEquals("use_preset must be written explicitly", 1L, varint(lora, 1))
        assertEquals("modem_preset", 0L, varint(lora, 2))
        assertEquals("region EU868", 3L, varint(lora, 7))
        assertEquals("hop_limit", 3L, varint(lora, 8))
        // tx_enabled: omitted arrives as false — a node that is configured and still silent.
        assertEquals("tx_enabled must be written explicitly", 1L, varint(lora, 9))
    }

    @Test
    fun `the slower preset and a different hop limit come through`() {
        val lora = sub(sub(LoRaAdmin.setConfigFrame(LoRaAdmin.Region.US, LoRaAdmin.ModemPreset.LONG_SLOW, 5)!!, 34), 6)
        assertEquals(1L, varint(lora, 2))
        assertEquals(1L, varint(lora, 7))
        assertEquals(5L, varint(lora, 8))
    }

    /** UNSET is the state this rescues a radio FROM. Writing it deliberately silences it. */
    @Test
    fun `writing region UNSET is refused`() {
        assertNull(LoRaAdmin.setConfigFrame(LoRaAdmin.Region.UNSET))
    }

    @Test
    fun `every region keeps the firmware's own number`() {
        // A renumbering here would put a radio on the wrong band — legal in one country and
        // not in another — so the mapping is pinned rather than trusted to ordering.
        assertEquals(1L, LoRaAdmin.Region.US.wire)
        assertEquals(2L, LoRaAdmin.Region.EU433.wire)
        assertEquals(3L, LoRaAdmin.Region.EU868.wire)
        assertEquals(13L, LoRaAdmin.Region.LORA24.wire)
        assertEquals(26L, LoRaAdmin.Region.BR902.wire)
        assertEquals(LoRaAdmin.Region.EU868, LoRaAdmin.Region.fromWire(3))
        assertNull(LoRaAdmin.Region.fromWire(99))
    }

    // ================================================================ set_channel

    @Test
    fun `a channel goes under AdminMessage field 33`() {
        val admin = LoRaAdmin.setChannelFrame("")
        assertNotNull("set_channel must be field 33", LoRaProto.field(admin, 33, wire = 2))
        assertNull("field 34 is set_config and must be absent", LoRaProto.field(admin, 34, wire = 2))
    }

    @Test
    fun `the channel is written into the primary slot with the PRIMARY role`() {
        val channel = sub(LoRaAdmin.setChannelFrame("oshi"), 33)
        assertEquals("index must be the primary slot", 0L, varint(channel, 1))
        assertEquals("role must be PRIMARY", 1L, varint(channel, 3))
    }

    @Test
    fun `the psk and the name are both written, psk first`() {
        val settings = sub(sub(LoRaAdmin.setChannelFrame("oshi"), 33), 2)
        assertArrayEquals("psk", byteArrayOf(1), sub(settings, 2))
        assertArrayEquals("name", "oshi".toByteArray(), sub(settings, 3))
    }

    /**
     * An EMPTY name is written, not omitted. Blank with the default key IS the stock
     * LongFast channel, and that is how "put me back on the normal mesh" stays reachable.
     */
    @Test
    fun `an empty name is written rather than omitted`() {
        val settings = sub(sub(LoRaAdmin.setChannelFrame(""), 33), 2)
        assertNotNull("the name field must be present even when empty", LoRaProto.field(settings, 3, wire = 2))
        assertEquals(0, sub(settings, 3).size)
    }

    @Test
    fun `the default psk is the one-byte public key index`() {
        assertArrayEquals(byteArrayOf(1), LoRaAdmin.Channel.DEFAULT_PSK)
    }

    /**
     * "LongFast" is a DISPLAY name. Writing it as the channel name produces a different
     * channel hash from every stock node out there — the opposite of what it means.
     */
    @Test
    fun `the default name is never written as the channel name`() {
        val settings = sub(sub(LoRaAdmin.setChannelFrame(""), 33), 2)
        assertEquals(0, sub(settings, 3).size)
        assertEquals("LongFast", LoRaAdmin.Channel.DEFAULT_NAME)
    }

    // ================================================================ names and keys

    @Test
    fun `a name is clamped to eleven BYTES, never cutting a code point`() {
        assertEquals("exactly11ch", LoRaAdmin.Channel.clampName("exactly11ch"))
        assertEquals("abcdefghijk", LoRaAdmin.Channel.clampName("abcdefghijklmnop"))
        // Four-byte emoji: two fit in eleven bytes, the third does not, and none is split.
        val clamped = LoRaAdmin.Channel.clampName("😀😀😀")
        assertTrue("a code point was cut in half", clamped.toByteArray(Charsets.UTF_8).size <= 11)
        assertEquals("😀😀", clamped)
    }

    /**
     * The case code points get wrong and grapheme clusters get right.
     *
     * 👨‍👩‍👦 is 18 bytes — one cluster, three emoji joined by two zero-width joiners. It does
     * not fit in eleven, so the whole cluster is dropped. Clamping by CODE POINT would emit
     * 👨 + ZWJ + 👩 = exactly 11 bytes and leave a dangling joiner on the wire; clamping by
     * UTF-16 unit would emit half a surrogate pair. Both are written to the radio without
     * complaint, and neither is what iOS writes.
     */
    @Test
    fun `a joined emoji sequence is dropped whole rather than split at a joiner`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66"
        assertEquals(18, family.toByteArray(Charsets.UTF_8).size)
        val clamped = LoRaAdmin.Channel.clampName(family)
        assertEquals("the cluster does not fit, so nothing of it may be written", "", clamped)
        assertTrue("no dangling zero-width joiner", !clamped.contains('\u200D'))
    }

    /**
     * The clustering must be the same on every JDK, because the one CI runs is not the
     * one this Mac runs.
     *
     * `BreakIterator`'s Unicode data is the JDK's, and UAX #29 GB11 — the emoji ZWJ rule
     * — only reached `java.text` in JDK 20. On JDK 17, which `desktop-ci.yml` uses on all
     * three platforms, it returned 👨, ZWJ, 👩, ZWJ, 👦 as five clusters and `clampName`
     * wrote a dangling joiner to the radio. These assert the joins directly, so the next
     * JDK bump cannot quietly restore it either way.
     */
    @Test
    fun `grapheme clustering does not depend on the JDK's Unicode version`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66"
        assertEquals("a ZWJ sequence is ONE character", listOf(family), LoRaAdmin.Channel.graphemes(family))

        // A skin-tone modifier, a variation selector and a keycap all belong to what precedes them.
        assertEquals(listOf("\uD83D\uDC4D\uD83C\uDFFD"), LoRaAdmin.Channel.graphemes("\uD83D\uDC4D\uD83C\uDFFD"))
        assertEquals(listOf("\u2764\uFE0F"), LoRaAdmin.Channel.graphemes("\u2764\uFE0F"))
        assertEquals(listOf("1\uFE0F\u20E3"), LoRaAdmin.Channel.graphemes("1\uFE0F\u20E3"))

        // And ordinary text is left exactly as BreakIterator had it.
        assertEquals(listOf("a", "b", "c"), LoRaAdmin.Channel.graphemes("abc"))
        assertEquals(listOf("e\u0301"), LoRaAdmin.Channel.graphemes("e\u0301"))
    }

    /** A name that fits exactly at the boundary is kept whole. */
    @Test
    fun `a name of exactly eleven bytes survives untouched`() {
        val eleven = "abcdefghijk"
        assertEquals(11, eleven.toByteArray(Charsets.UTF_8).size)
        assertEquals(eleven, LoRaAdmin.Channel.clampName(eleven))
    }

    @Test
    fun `a blank name stays blank`() {
        assertEquals("", LoRaAdmin.Channel.clampName("   "))
    }

    @Test
    fun `a blank key means the public default`() {
        assertArrayEquals(byteArrayOf(1), LoRaAdmin.Channel.pskOrNull(""))
    }

    @Test
    fun `both base64 alphabets are accepted for a key`() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val standard = java.util.Base64.getEncoder().encodeToString(key)
        val urlSafe = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        assertArrayEquals(key, LoRaAdmin.Channel.pskOrNull(standard))
        assertArrayEquals("a key copied from another app must not read as wrong", key, LoRaAdmin.Channel.pskOrNull(urlSafe))
    }

    /**
     * A key the firmware would not accept is REFUSED, never silently replaced with the
     * default — replacing it would move the radio onto a different mesh from the one asked
     * for, and say nothing.
     */
    @Test
    fun `a key of the wrong size is refused rather than replaced`() {
        val twenty = java.util.Base64.getEncoder().encodeToString(ByteArray(20))
        assertNull(LoRaAdmin.Channel.pskOrNull(twenty))
        assertNull(LoRaAdmin.Channel.pskOrNull("not base64 at all !!"))
    }

    @Test
    fun `the three key sizes the firmware accepts all pass`() {
        for (n in listOf(1, 16, 32)) {
            val encoded = java.util.Base64.getEncoder().encodeToString(ByteArray(n) { 9 })
            assertEquals("size $n must be accepted", n, LoRaAdmin.Channel.pskOrNull(encoded)?.size)
        }
    }
}
