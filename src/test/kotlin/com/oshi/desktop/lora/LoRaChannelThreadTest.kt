package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__ The desktop's half.
 *
 * The keys asserted here are the SAME STRINGS the phones produce
 * (`service/lora/LoRaChannelThread.kt`, `OSHI/LoRaChannelThread.swift`), and that
 * is the point of the file: a user with a phone and this client sees one
 * channel's history on both, so two spellings would be two rooms. They are
 * written out literally rather than built from the constants, because a test
 * that reads the constant cannot notice the constant changing.
 */
class LoRaChannelThreadTest {

    @Test
    fun `the keys are the exact strings the phones produce`() {
        assertEquals("lorachan!manif", LoRaChannelThread.keyFor(0, "manif"))
        assertEquals("lorachan#0", LoRaChannelThread.keyFor(0, null))
        assertEquals("lorachan#0", LoRaChannelThread.keyFor(0, "   "))
        assertEquals("lorachan#3", LoRaChannelThread.keyFor(3, ""))
    }

    @Test
    fun `a channel named like a slot is not read as one`() {
        // The bug Android's own test caught before it shipped: with ONE prefix, a
        // channel the user named "#3" collided with the unnamed slot 3 and the two
        // rooms merged silently. The spellings differ at character nine.
        assertEquals("lorachan!#3", LoRaChannelThread.keyFor(0, "#3"))
        assertEquals("lorachan#3", LoRaChannelThread.keyFor(3, null))
        assertFalse(LoRaChannelThread.keyFor(0, "#3") == LoRaChannelThread.keyFor(3, null))
    }

    @Test
    fun `capitalisation does not split a channel into two rooms`() {
        assertEquals(LoRaChannelThread.keyFor(0, "Manif"), LoRaChannelThread.keyFor(0, "manif"))
        assertEquals(LoRaChannelThread.keyFor(0, "MANIF"), LoRaChannelThread.keyFor(1, "manif"))
    }

    @Test
    fun `the name wins over the index, because the index is local to each node`() {
        // mesh.proto says it out loud: the channel index "has no meaning when sent
        // between nodes". Keying on it would move every message to a different room
        // the moment someone reordered their channels.
        assertEquals(LoRaChannelThread.keyFor(0, "manif"), LoRaChannelThread.keyFor(7, "manif"))
    }

    @Test
    fun `only a key this object made is a channel key`() {
        assertTrue(LoRaChannelThread.isChannelKey("lorachan!manif"))
        assertTrue(LoRaChannelThread.isChannelKey("lorachan#0"))
        assertFalse(LoRaChannelThread.isChannelKey("lora!1a2b3c4d"))
        assertFalse(LoRaChannelThread.isChannelKey("AAAAbbbbCCCC="))
        assertFalse(LoRaChannelThread.isChannelKey(""))
    }

    @Test
    fun `an unnamed primary channel is called what every stock node calls it`() {
        assertEquals("LongFast", LoRaChannelThread.displayName("lorachan#0"))
        // A secondary slot with no name keeps its number: there is nothing truer.
        assertEquals("#3", LoRaChannelThread.displayName("lorachan#3"))
        assertEquals("manif", LoRaChannelThread.displayName("lorachan!manif"))
        // Not a channel key at all: handed back untouched rather than guessed at.
        assertEquals("lora!1a2b3c4d", LoRaChannelThread.displayName("lora!1a2b3c4d"))
        assertEquals("lorachan#zz", LoRaChannelThread.displayName("lorachan#zz"))
    }

    // ── The radio's channel table ──

    private fun channelFrame(index: Int, name: String?, role: Long): ByteArray {
        val settings = ProtoWriter().apply { if (name != null) bytes(3, name.toByteArray()) }.data
        val channel = ProtoWriter().varint(1, index.toLong()).bytes(2, settings).varint(3, role).data
        return ProtoWriter().bytes(10, channel).data
    }

    @Test
    fun `a named slot is learned from the handshake burst`() {
        val table = LoRaChannelThread.Table()
        assertTrue(table.observeFromRadio(channelFrame(0, "manif", role = 1)))
        assertEquals("manif", table.name(0))
        assertEquals("lorachan!manif", LoRaChannelThread.keyFor(0, table.name(0)))
    }

    @Test
    fun `a disabled slot is not a channel anyone is on`() {
        val table = LoRaChannelThread.Table()
        table.observeFromRadio(channelFrame(2, "old", role = 1))
        assertEquals("old", table.name(2))
        // Most nodes report seven disabled slots. Keeping the name would let a room
        // survive the user turning the channel off.
        table.observeFromRadio(channelFrame(2, "old", role = 0))
        assertNull(table.name(2))
    }

    @Test
    fun `a blank name is absent, not empty`() {
        val table = LoRaChannelThread.Table()
        table.observeFromRadio(channelFrame(0, "", role = 1))
        // "" and "not reported yet" must not be distinguishable at the call site —
        // both mean "fall back to the slot form", and storing "" would make
        // `keyFor` produce `lorachan!` with nothing after it.
        assertNull(table.name(0))
        assertEquals("lorachan#0", LoRaChannelThread.keyFor(0, table.name(0)))
    }

    @Test
    fun `a frame carrying no channel is not one`() {
        val table = LoRaChannelThread.Table()
        assertFalse(table.observeFromRadio(ProtoWriter().varint(3, 1).data))
        assertTrue(table.named().isEmpty())
    }

    @Test
    fun `detaching forgets the old radio's slots`() {
        val table = LoRaChannelThread.Table()
        table.observeFromRadio(channelFrame(1, "manif", role = 2))
        assertEquals(mapOf(1 to "manif"), table.named())
        table.clear()
        assertTrue(table.named().isEmpty())
    }
}
