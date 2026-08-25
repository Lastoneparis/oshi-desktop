package com.oshi.desktop.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-rolled protobuf codec and the stream framing.
 *
 * The protobuf half is checked against literal bytes derived from the wire format itself
 * (`MeshtasticManager.swift:25-84`), not against this writer. The stream half is the one
 * construct in this package with no line reference into the shipped trees — see
 * [LoRaAttach]'s class doc — and its tests say so.
 */
class LoRaProtoTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ============================================================ VARINT

    /**
     * **Zero encodes as a single `0x00` byte, not as nothing.**
     *
     * iOS uses a `repeat…while` for exactly this (`MeshtasticManager.swift:29-33`). A plain
     * `while` emits an empty byte string for 0, which shifts every following field and
     * corrupts the whole message — and it works in every test that never encodes a zero.
     */
    @Test
    fun `varint zero is one byte and not zero bytes`() {
        assertArrayEquals(bytes(0x00), ProtoWriter.vint(0))
    }

    @Test
    fun `varint is base-128 little-endian groups`() {
        assertArrayEquals(bytes(0x01), ProtoWriter.vint(1))
        assertArrayEquals(bytes(0x7F), ProtoWriter.vint(127))
        assertArrayEquals(bytes(0x80, 0x01), ProtoWriter.vint(128))
        // 256 = PRIVATE_APP, the portnum every OSHI frame carries.
        assertArrayEquals(bytes(0x80, 0x02), ProtoWriter.vint(256))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x0F), ProtoWriter.vint(0xFFFFFFFFL))
    }

    // ============================================================ FIELD ENCODING

    /** `fixed32` is LITTLE-endian — the opposite of the stream framing's length. */
    @Test
    fun `fixed32 is little-endian and tagged with wire type 5`() {
        // field 2, wire 5 → key = (2<<3)|5 = 0x15
        assertArrayEquals(
            bytes(0x15, 0x04, 0x03, 0x02, 0x01),
            ProtoWriter().fixed32(2, 0x01020304).data,
        )
    }

    @Test
    fun `bytes is tagged with wire type 2 and a length prefix`() {
        // field 2, wire 2 → key = (2<<3)|2 = 0x12
        assertArrayEquals(
            bytes(0x12, 0x03, 0xAA, 0xBB, 0xCC),
            ProtoWriter().bytes(2, bytes(0xAA, 0xBB, 0xCC)).data,
        )
    }

    // ============================================================ THE ToRadio PACKET

    /**
     * A whole `ToRadio` for a broadcast OSHI frame, byte for byte
     * (`MeshtasticManager.swift:766-782`).
     *
     * ```
     * Data       : 08 80 02        portnum = 256 (varint, field 1)
     *              12 02 4F 4D     payload = "OM" (bytes, field 2)
     * MeshPacket : 15 FF FF FF FF  to = 0xFFFFFFFF (fixed32, field 2)
     *              22 07 <Data>    decoded (bytes, field 4)
     *              35 EF BE AD DE  id (fixed32, field 6)
     * ToRadio    : 0A 12 <MeshPacket>
     * ```
     */
    @Test
    fun `a broadcast ToRadio matches the literal layout`() {
        val out = LoRaProto.toRadioPacket(
            to = LoRaProto.BROADCAST_ADDR,
            portnum = LoRaProto.PORT_OSHI,
            payload = bytes(0x4F, 0x4D),
            wantAck = false,
            packetId = 0xDEADBEEFu,
        )
        val expected = bytes(
            0x0A, 0x13,                                     // ToRadio.packet (key 0x0A), len 19
            0x15, 0xFF, 0xFF, 0xFF, 0xFF,                   // MeshPacket.to  = 0xFFFFFFFF   (5)
            0x22, 0x07,                                     // MeshPacket.decoded, len 7     (2)
            0x08, 0x80, 0x02,                               //   Data.portnum = 256          (3)
            0x12, 0x02, 0x4F, 0x4D,                         //   Data.payload = "OM"         (4)
            0x35, 0xEF, 0xBE, 0xAD, 0xDE,                   // MeshPacket.id  = 0xDEADBEEF   (5)
        )                                                   // 5+2+3+4+5 = 19 = 0x13
        assertArrayEquals(expected, out)
    }

    /**
     * **`want_ack` is written ONLY when true.**
     *
     * On the wire `want_ack: false` and "no field at all" are the same bytes — protobuf
     * omits defaults. Writing it explicitly would still decode, but it is one more byte out
     * of a budget that matters and it stops a byte diff against a phone frame from matching.
     */
    @Test
    fun `want_ack is absent when false and present when true`() {
        val off = LoRaProto.toRadioPacket(1u, LoRaProto.PORT_OSHI, bytes(0x4F), false, 1u)
        val on = LoRaProto.toRadioPacket(1u, LoRaProto.PORT_OSHI, bytes(0x4F), true, 1u)
        assertEquals("want_ack costs exactly two bytes", off.size + 2, on.size)
        // field 10, wire 0 → key = (10<<3)|0 = 0x50, value 1
        assertTrue(on.toList().windowed(2).any { it == listOf(0x50.toByte(), 0x01.toByte()) })
        assertTrue(off.toList().windowed(2).none { it == listOf(0x50.toByte(), 0x01.toByte()) })
    }

    // ============================================================ PARSING

    @Test
    fun `parseMeshPacket pulls from, portnum and payload back out`() {
        // Build a MeshPacket the way the RADIO would: `from` is field 1, which OSHI never
        // writes — so this fixture is assembled by hand rather than by toRadioPacket.
        val data = ProtoWriter().varint(1, LoRaProto.PORT_OSHI).bytes(2, bytes(0x4F, 0x4D, 0x01)).data
        val pkt = ProtoWriter()
            .fixed32(1, 0x1234ABCDu)     // MeshPacket.from — the radio fills this in
            .fixed32(2, 0xFFFFFFFFu)
            .bytes(4, data)
            .data

        val inc = LoRaProto.parseMeshPacket(pkt)!!
        assertEquals(0x1234ABCDu, inc.from)
        assertEquals(LoRaProto.PORT_OSHI, inc.portnum)
        assertArrayEquals(bytes(0x4F, 0x4D, 0x01), inc.payload)
    }

    /** A packet missing any layer is null: a partial packet is not a packet. */
    @Test
    fun `parseMeshPacket refuses a packet missing a layer`() {
        assertNull("no from", LoRaProto.parseMeshPacket(ProtoWriter().bytes(4, ByteArray(2)).data))
        assertNull("no decoded", LoRaProto.parseMeshPacket(ProtoWriter().fixed32(1, 1u).data))
        assertNull(LoRaProto.parseMeshPacket(ByteArray(0)))
    }

    /**
     * A TRUNCATED field ends the parse and returns what was read so far — not an exception,
     * not an empty list (`MeshtasticManager.swift:71-74`).
     *
     * A radio that hands over half a frame must not take the connection down with it, and
     * the fields that did arrive are still usable.
     */
    @Test
    fun `a truncated field ends the parse and keeps what was read`() {
        // A complete varint field, then a length-delimited field whose body is short.
        val d = bytes(0x08, 0x2A, 0x12, 0x10, 0xAA, 0xBB)
        val fields = LoRaProto.parseFields(d)
        assertEquals(1, fields.size)
        assertEquals(42L, fields[0].varintValue)
    }

    /** An unknown wire type stops the parse dead — 3 and 4 carry no length to skip. */
    @Test
    fun `an unknown wire type stops the parse`() {
        val d = bytes(0x08, 0x01, 0x0B, 0xFF, 0xFF)   // field 1 varint, then wire type 3
        assertEquals(1, LoRaProto.parseFields(d).size)
    }

    // ============================================================ STREAM FRAMING
    //
    // UNVERIFIED against a real radio — see [LoRaAttach]. These tests pin the codec's own
    // behaviour, which is all a test without hardware can honestly do.

    @Test
    fun `stream framing is 0x94 0xC3 then a BIG-endian length`() {
        val framed = LoRaAttach.StreamFraming.encode(ByteArray(300) { 1 })
        assertEquals(0x94.toByte(), framed[0])
        assertEquals(0xC3.toByte(), framed[1])
        assertEquals("length hi byte", 0x01.toByte(), framed[2])
        assertEquals("length lo byte", 0x2C.toByte(), framed[3])
        assertEquals(304, framed.size)
    }

    /**
     * The length is BIG-endian while the `"OM"` msgId and every protobuf `fixed32` are
     * LITTLE-endian. Three fields, two orders, one stack — worth an assertion so nobody
     * "fixes" one to match the others.
     */
    @Test
    fun `the stream length is big-endian unlike everything else in this package`() {
        val framed = LoRaAttach.StreamFraming.encode(ByteArray(256))
        assertArrayEquals(bytes(0x01, 0x00), framed.copyOfRange(2, 4))
        // …whereas the OM header's msgId is little-endian:
        assertArrayEquals(bytes(0x00, 0x01, 0x00, 0x00), LoRaFrame.frame(256u, 0, 1, bytes(1)).copyOfRange(2, 6))
    }

    @Test
    fun `a framed message round-trips`() {
        val body = ByteArray(64) { (it * 3).toByte() }
        val d = LoRaAttach.StreamFraming.decode(LoRaAttach.StreamFraming.encode(body))!!
        assertArrayEquals(body, d.protobuf)
        assertEquals(68, d.consumed)
    }

    /**
     * A Meshtastic node emits human-readable log lines on the same stream, so scanning
     * forward to the next magic is what makes a serial link usable at all.
     */
    @Test
    fun `decode re-synchronises past debug text`() {
        val body = bytes(0xAA, 0xBB)
        val noise = "INFO Booting…\n".toByteArray(Charsets.UTF_8)
        val buf = noise + LoRaAttach.StreamFraming.encode(body)
        val d = LoRaAttach.StreamFraming.decode(buf)!!
        assertArrayEquals(body, d.protobuf)
        assertEquals("consumed must include the skipped noise", buf.size, d.consumed)
    }

    /** An incomplete frame is null — the caller keeps the buffer and appends more. */
    @Test
    fun `an incomplete frame decodes to null rather than a short read`() {
        val full = LoRaAttach.StreamFraming.encode(ByteArray(32))
        assertNull(LoRaAttach.StreamFraming.decode(full.copyOfRange(0, full.size - 1)))
        assertNotNull(LoRaAttach.StreamFraming.decode(full))
    }

    /**
     * An implausible length is a DESYNC, not a big message: step past the magic and keep
     * scanning rather than waiting forever for bytes that will never come.
     */
    @Test
    fun `an oversized length is treated as a desync and skipped`() {
        val body = bytes(0x01, 0x02)
        val bogus = bytes(0x94, 0xC3, 0xFF, 0xFF)   // claims 65535 bytes
        val d = LoRaAttach.StreamFraming.decode(bogus + LoRaAttach.StreamFraming.encode(body))!!
        assertArrayEquals(body, d.protobuf)
    }
}
