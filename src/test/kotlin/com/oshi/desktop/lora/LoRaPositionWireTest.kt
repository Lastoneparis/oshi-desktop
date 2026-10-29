package com.oshi.desktop.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __DESKTOP_LORA_BEACON_2026_10_28__ The BYTES of a POSITION_APP beacon, against a
 * reference payload built outside this codebase.
 *
 * The expected hex below was computed in Python from Meshtastic's `Position` definition —
 * `latitude_i` sfixed32 field 1, `longitude_i` sfixed32 field 2, `time` varint field 4 —
 * and NOT by calling anything in this repository. That distinction is the whole point of
 * the test. A round trip through our own writer and our own parser passes when both sides
 * share a mistake, which is how `LoRaNodeRosterTest` once encoded the wrong field number in
 * its own fixtures and stayed green until the real packets were looked at: `NodeInfo.user`
 * is field 2, not 4, and all 45 nodes on the mesh were nameless while the tests agreed with
 * themselves.
 *
 * So: a hand-made payload, and three of them, chosen so a sign bug cannot hide.
 */
class LoRaPositionWireTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `Geneva encodes to the reference bytes`() {
        assertEquals(
            "0d603b8a1b15c060a90320809f88d706",
            hex(LoRaProto.positionPayload(46.2044, 6.1432, 1_793_200_000L)),
        )
    }

    @Test
    fun `a southern and eastern point keeps both signs`() {
        // Sydney. latitude_i is negative and longitude_i is large and positive, so a writer
        // that treated either as unsigned, or swapped the two fields, produces different
        // bytes rather than a plausible-looking frame somewhere else on Earth.
        assertEquals(
            "0d0008d0eb1548b5205a20809f88d706",
            hex(LoRaProto.positionPayload(-33.8688, 151.2093, 1_793_200_000L)),
        )
    }

    @Test
    fun `a southern and western point keeps both signs negative`() {
        // Santiago, and a time of 1 so the varint is a single byte — which also pins that
        // `time` is field 4 as a varint and not a fixed32.
        assertEquals(
            "0d581a10ec1578b8e0d52001",
            hex(LoRaProto.positionPayload(-33.4489, -70.6693, 1L)),
        )
    }

    @Test
    fun `the scale is 1e-7, not 1e-6 or 1e-8`() {
        // One ten-millionth of a degree is ~1.1 cm, so an order of magnitude either way is
        // a node 1.1 km or 110 km from where it said it was — both plausible on a map.
        val payload = LoRaProto.positionPayload(1.0, 1.0, 0L)
        // 1e7 as sfixed32 LE = 80 96 98 00
        assertEquals("0d8096980015809698002000", hex(payload))
    }

    @Test
    fun `the portnum is three`() {
        assertEquals(
            "portnum 3 is POSITION_APP in Meshtastic's portnums.proto — every client maps on it",
            3L, LoRaProto.PORT_POSITION,
        )
    }

    @Test
    fun `a beacon is addressed to the broadcast address and never asks for an ack`() {
        // Meshtastic does not acknowledge broadcasts, so `wantAck` on one raises a flag
        // nobody will ever answer — LoRaProto's own header records this and leaves it to
        // the caller, so the caller is what this asserts.
        val payload = LoRaProto.positionPayload(46.2044, 6.1432, 1L)
        val frame = LoRaProto.toRadioPacket(
            to = LoRaProto.BROADCAST_ADDR,
            portnum = LoRaProto.PORT_POSITION,
            payload = payload,
            wantAck = false,
            packetId = 0x11223344u,
        )
        val h = hex(frame)
        // MeshPacket.to = fixed32 field 2 (tag 0x15) carrying 0xFFFFFFFF.
        assertEquals("the beacon must go to ALL", true, h.contains("15ffffffff"))
        // want_ack is varint field 10 (tag 0x50) and must be ABSENT, not false-valued.
        assertEquals("want_ack must not appear at all", false, h.contains("5001"))
        // The payload must be carried intact.
        assertEquals(true, h.contains(hex(payload)))
    }

    @Test
    fun `two beacons of the same position differ only in their packet id`() {
        // The property `LoRaProto`'s header asks for: a captured phone frame and a desktop
        // frame should differ in their random ids and nowhere else.
        val a = LoRaProto.toRadioPacket(
            LoRaProto.BROADCAST_ADDR, LoRaProto.PORT_POSITION,
            LoRaProto.positionPayload(46.2044, 6.1432, 7L), false, 0x00000001u,
        )
        val b = LoRaProto.toRadioPacket(
            LoRaProto.BROADCAST_ADDR, LoRaProto.PORT_POSITION,
            LoRaProto.positionPayload(46.2044, 6.1432, 7L), false, 0x00000002u,
        )
        assertEquals("same length", a.size, b.size)
        assertNotEquals("the ids must actually differ", hex(a), hex(b))
        val differing = a.indices.count { a[it] != b[it] }
        assertEquals("exactly one byte of the id may differ", 1, differing)
    }
}

/**
 * __DESKTOP_LORA_BEACON_FIXED_2026_10_28__ The ADMIN frame — the one the firmware honours.
 *
 * Same discipline as the file above: the expected hex was computed in Python from
 * Meshtastic's `AdminMessage`/`Position` definitions, not by calling anything here. It
 * matters more for this frame than for the last one, because the whole reason it exists is
 * that the obvious send path was accepted by the radio and reached nobody — a mistake no
 * amount of agreeing with ourselves would have caught, and which ten green unit tests, a
 * three-platform digest and an independently-checked payload all failed to catch.
 *
 * The SAME literals are asserted on Android (`LoRaFixedPositionWireTest`) and iOS
 * (`LoRaFixedPositionWireTests`), so the three clients send identical bytes.
 */
class LoRaFixedPositionAdminWireTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `set_fixed_position is field 41 wrapping a Position`() {
        // ca02 = tag for field 41, wire type 2. 0a = the Position's length (10 bytes).
        assertEquals(
            "ca020a0d603b8a1b15c060a903",
            hex(LoRaAdmin.setFixedPositionFrame(46.2044, 6.1432)!!),
        )
    }

    @Test
    fun `a southern and eastern point keeps its signs through the admin wrapper`() {
        assertEquals(
            "ca020a0d0008d0eb1548b5205a",
            hex(LoRaAdmin.setFixedPositionFrame(-33.8688, 151.2093)!!),
        )
    }

    @Test
    fun `remove_fixed_position is field 42, a bool`() {
        assertEquals("d00201", hex(LoRaAdmin.removeFixedPositionFrame()))
    }

    @Test
    fun `a position that is not on Earth is refused rather than framed`() {
        // Null, not a frame: 0/0 is what a radio with no lock reports, and writing it into
        // the node's CONFIGURATION would park it in the Gulf of Guinea persistently — worse
        // than a bad broadcast, which at least expires.
        org.junit.Assert.assertNull(LoRaAdmin.setFixedPositionFrame(0.0, 0.0))
        org.junit.Assert.assertNull(LoRaAdmin.setFixedPositionFrame(91.0, 6.0))
        org.junit.Assert.assertNull(LoRaAdmin.setFixedPositionFrame(46.0, 181.0))
        org.junit.Assert.assertNotNull("Greenwich is a real place", LoRaAdmin.setFixedPositionFrame(51.4779, 0.0))
    }
}

/**
 * __LORA_NODEINFO_POSITION_2026_10_28__ A node's position announced inside its `NodeInfo`.
 *
 * `observeNodeInfo` read `num` (1), `user` (2) and `last_heard` (5) and nothing else, so a
 * position carried in field 3 was dropped: the node was heard, named, and placeless. The
 * roster's positions came only from portnum-3 traffic.
 *
 * Found while asking why a radio holding a fixed position never appeared on a second
 * radio's map — a question this does NOT settle, because the sender has a separate measured
 * problem. It is a defect on its own terms, and the same shape as the field-4-vs-2 bug that
 * left 45 nodes nameless: a field number nobody had checked against a real packet.
 *
 * The NodeInfo bytes here are assembled from the field numbers, not captured, so this
 * proves the roster reads the field it claims to — the live proof is a radio, and the
 * reason that is not enough on its own is written above.
 */
class LoRaNodeInfoPositionTest {

    private fun varint(field: Int, value: Long): ByteArray =
        ProtoWriter().varint(field, value).data

    private fun nodeInfoWithPosition(num: Long, lat: Double, lon: Double): ByteArray {
        val position = ProtoWriter()
            .fixed32(1, Math.round(lat * 1e7).toInt())
            .fixed32(2, Math.round(lon * 1e7).toInt())
            .data
        return ProtoWriter()
            .varint(1, num)              // NodeInfo.num
            .bytes(3, position)          // NodeInfo.position — the field that was ignored
            // last_heard (field 5, a varint) so the node counts as ONLINE. Without it the
            // roster records the position and `onlineNodes` filters the node out, which is
            // correct behaviour and made the first version of this test fail for a reason
            // that had nothing to do with field 3.
            .varint(5, 1_793_200_000L)
            .data
    }

    @Test
    fun `a position inside a NodeInfo reaches the roster`() {
        val roster = LoRaNodeRoster()
        // FromRadio.node_info is field 4.
        val frame = ProtoWriter()
            .bytes(4, nodeInfoWithPosition(0x766eb396L, 46.2044, 6.1432))
            .data
        roster.observeFromRadio(frame, nowMs = 1_793_200_000_000L)
        val node = roster.onlineNodes(1_793_200_000_000L).firstOrNull { it.num == 0x766eb396u }
        assertEquals(46.2044, node?.latitude ?: 0.0, 1e-6)
        assertEquals(6.1432, node?.longitude ?: 0.0, 1e-6)
    }

    @Test
    fun `a NodeInfo carrying 0 slash 0 is still refused`() {
        // The guards live in observePosition and must apply to BOTH paths — a NodeInfo
        // must not be able to write something a packet could not.
        val roster = LoRaNodeRoster()
        val frame = ProtoWriter()
            .bytes(4, nodeInfoWithPosition(0x4b88a86dL, 0.0, 0.0))
            .data
        roster.observeFromRadio(frame, nowMs = 1_793_200_000_000L)
        val node = roster.onlineNodes(1_793_200_000_000L).firstOrNull { it.num == 0x4b88a86du }
        assertNull("0/0 is what a radio with no lock reports", node?.latitude)
    }
}
