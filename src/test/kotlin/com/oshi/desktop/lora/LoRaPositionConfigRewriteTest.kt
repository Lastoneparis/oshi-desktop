package com.oshi.desktop.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __DESKTOP_LORA_SMART_BROADCAST_2026_10_28__ Rewriting ONE field of a radio's config
 * without destroying the rest.
 *
 * The payload below is REAL: it is what a ThinkNode M1 returned for `Config.position` on
 * 2026-10-28, read out of its handshake burst. Using the captured bytes rather than an
 * invented example is the point — the function's output goes into a radio's flash, and the
 * failure mode it exists to prevent (a field silently dropped because this parser did not
 * understand it) can only be demonstrated against bytes a device actually produced.
 */
class LoRaPositionConfigRewriteTest {

    /** `Config.position` as captured from the radio. */
    private val captured = byteArrayOf(
        0x08, 0x84.toByte(), 0x07,  // position_broadcast_secs = 900
        0x10, 0x01,                 // position_broadcast_smart_enabled = true
        0x18, 0x01,                 // fixed_position = true
        0x28, 0x78,                 // gps_update_interval = 120
        0x38, 0xab.toByte(), 0x06,  // position_flags = 811
        0x50, 0x64,                 // broadcast_smart_minimum_distance = 100
        0x58, 0x1e,                 // broadcast_smart_minimum_interval_secs = 30
        0x68, 0x01,                 // gps_mode = 1
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `the captured payload is what the radio sent`() {
        assertEquals("08840710011801287838ab065064581e6801", hex(captured))
    }

    @Test
    fun `turning smart broadcast off changes exactly two bytes`() {
        val off = LoRaAdmin.withVarintField(captured, field = 2, value = 0)!!
        assertEquals("08840710001801287838ab065064581e6801", hex(off))
        assertEquals("the length must not change for a 1 to 0 flip", captured.size, off.size)
        // Every other field survives, read back through the parser rather than by eye.
        val fields = LoRaProto.parseFields(off).associate { it.number to it.varintValue }
        assertEquals(900L, fields[1])
        assertEquals(0L, fields[2])     // the one that changed
        assertEquals(1L, fields[3])     // fixed_position still true
        assertEquals(120L, fields[5])
        assertEquals(811L, fields[7])
        assertEquals(100L, fields[10])
        assertEquals(30L, fields[11])
        assertEquals(1L, fields[13])
    }

    @Test
    fun `rewriting a field to its own value reproduces the bytes exactly`() {
        // The identity case, and the one that proves nothing is lost in transit. If this
        // ever fails, the parser has stopped understanding some field and the function
        // must refuse rather than write a lossy config.
        val same = LoRaAdmin.withVarintField(captured, field = 2, value = 1)!!
        assertArrayEquals(captured, same)
    }

    @Test
    fun `a payload this parser cannot reproduce is REFUSED, not guessed`() {
        // Wire type 1 (fixed64) is not handled. The answer must be null — writing a config
        // with that field silently dropped would be worse than not writing one at all.
        val withFixed64 = captured + byteArrayOf(0x71, 1, 2, 3, 4, 5, 6, 7, 8)
        assertNull(LoRaAdmin.withVarintField(withFixed64, field = 2, value = 0))
    }

    @Test
    fun `the admin wrapper nests position inside Config inside AdminMessage`() {
        val frame = LoRaAdmin.setPositionConfigFrame(captured)
        // 92 02 = AdminMessage.set_config (34, wire 2); then Config.position (2, wire 2) = 12.
        assertEquals(true, hex(frame).startsWith("9202"))
        assertEquals(true, hex(frame).contains("12" + "%02x".format(captured.size) + hex(captured)))
    }
}
