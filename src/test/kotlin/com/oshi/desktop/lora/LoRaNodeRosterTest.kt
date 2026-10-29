package com.oshi.desktop.lora

import org.junit.Test
import org.junit.Assert.assertEquals

class LoRaNodeRosterTest {

    private val now = 1_790_000_000_000L // 2026-09

    private fun myInfo(num: Long) =
        ProtoWriter().bytes(3, ProtoWriter().varint(1, num).data).data

    /**
     * A `FromRadio.node_info` (top-level field 4) carrying a `NodeInfo`.
     *
     * __LORA_NODEINFO_FIELDS_2026_10_28__ `last_heard` is NodeInfo field 5 and it is a
     * `uint32`, so it is a VARINT on the wire. These fixtures wrote it as a fixed32 and the
     * roster read it as a fixed32, so the two agreed with each other and with nothing a
     * radio sends. The real layout was read off a ThinkNode M1.
     */
    private fun nodeInfo(num: Long, lastHeardS: Long?) =
        ProtoWriter().bytes(
            4,
            ProtoWriter().varint(1, num).let { w -> if (lastHeardS != null) w.varint(5, lastHeardS) else w }.data,
        ).data

    private fun packetFrom(num: Long) =
        ProtoWriter().bytes(2, ProtoWriter().fixed32(1, num.toInt()).data).data

    /**
     * A `NodeInfo` that also carries `user` — **field 2**, not 4: long_name (2),
     * short_name (3).
     *
     * __LORA_NODEINFO_FIELDS_2026_10_28__ This wrote the User at field 4 and the roster
     * read it from field 4, so every test here passed while every real radio produced a
     * nameless list: in a real `NodeInfo`, field 4 is `snr`, a float. The bug and its test
     * were built from one misreading and confirmed each other. What broke the tie was
     * pointing the roster at a device — PARITY row 1.2-a's own "NOT verified: a real
     * radio" — and watching 45 nodes arrive unnamed.
     */
    private fun nodeInfoNamed(num: Long, lastHeardS: Long?, longName: String, shortName: String = "") =
        ProtoWriter().bytes(
            4,
            ProtoWriter().varint(1, num)
                .let { w -> if (lastHeardS != null) w.varint(5, lastHeardS) else w }
                .bytes(
                    2,
                    ProtoWriter()
                        .let { u -> if (longName.isEmpty()) u else u.bytes(2, longName.toByteArray()) }
                        .let { u -> if (shortName.isEmpty()) u else u.bytes(3, shortName.toByteArray()) }
                        .data,
                )
                .data,
        ).data

    @Test
    fun theNodeDbDumpDoesNotMakeOldNodesOnline() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(nodeInfo(0x11, (now - 30 * 24 * 3600_000L) / 1000), now) // a month ago
        r.observeFromRadio(nodeInfo(0x22, (now - 10 * 60_000L) / 1000), now)        // 10 min ago
        assertEquals(1, r.onlineCount(now))
    }

    @Test
    fun ownRadioIsNeverCounted() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(myInfo(0x33), now)
        r.observeFromRadio(nodeInfo(0x33, now / 1000), now)
        r.observeFromRadio(nodeInfo(0x44, now / 1000), now)
        assertEquals(1, r.onlineCount(now))
    }

    @Test
    fun anUnsetRadioClockIsNotTrustedButAPacketIs() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(nodeInfo(0x55, 0), now)
        r.observeFromRadio(nodeInfo(0x66, null), now)
        assertEquals(0, r.onlineCount(now))
        r.observeFromRadio(packetFrom(0x55), now)
        assertEquals(1, r.onlineCount(now))
    }

    @Test
    fun aNodeAgesOutAfterTwoHoursAndAnOlderDumpDoesNotRewindIt() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(packetFrom(0x77), now)
        r.observeFromRadio(nodeInfo(0x77, (now - 5 * 3600_000L) / 1000), now) // stale re-dump
        assertEquals(1, r.onlineCount(now + LoRaNodeRoster.ONLINE_WINDOW_MS - 1))
        assertEquals(0, r.onlineCount(now + LoRaNodeRoster.ONLINE_WINDOW_MS))
    }

    @Test
    fun clearForgetsTheRadio() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(packetFrom(0x88), now)
        r.clear()
        assertEquals(0, r.onlineCount(now))
        assertEquals(emptyList<LoRaNodeRoster.Node>(), r.onlineNodes(now))
    }

    // ------------------------------------------------- the named list behind the reach badge

    @Test
    fun theLongNameLabelsTheNodeAndTheShortNameIsTheFallback() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(nodeInfoNamed(0x91, now / 1000, "OSHI ThinkNode M1 B", "M1B"), now)
        r.observeFromRadio(nodeInfoNamed(0x92, now / 1000, "", "TEA"), now)
        assertEquals(
            listOf("OSHI ThinkNode M1 B", "TEA"),
            r.onlineNodes(now).sortedBy { it.num }.map { it.name },
        )
    }

    @Test
    fun aNodeHeardButNeverAnnouncedIsListedWithNoName() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(packetFrom(0x93), now)
        assertEquals(listOf("" to 0x93u), r.onlineNodes(now).map { it.name to it.num })
    }

    @Test
    fun aLaterDumpWithoutTheUserFieldDoesNotBlankTheName() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(nodeInfoNamed(0x94, now / 1000, "Named once"), now)
        r.observeFromRadio(nodeInfo(0x94, now / 1000), now) // same node, no user field
        assertEquals(listOf("Named once"), r.onlineNodes(now).map { it.name })
    }

    @Test
    fun theListIsTheCountAndNewestFirst() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(nodeInfoNamed(0x95, (now - 30 * 24 * 3600_000L) / 1000, "a month ago"), now)
        r.observeFromRadio(nodeInfoNamed(0x96, (now - 90 * 60_000L) / 1000, "an hour and a half ago"), now)
        r.observeFromRadio(nodeInfoNamed(0x97, (now - 60_000L) / 1000, "a minute ago"), now)
        val nodes = r.onlineNodes(now)
        // Same predicate as the count: the aged-out node is in NEITHER.
        assertEquals(r.onlineCount(now), nodes.size)
        assertEquals(listOf("a minute ago", "an hour and a half ago"), nodes.map { it.name })
    }

    @Test
    fun ourOwnRadioIsNotInTheListEither() {
        val r = LoRaNodeRoster()
        r.observeFromRadio(myInfo(0x98), now)
        r.observeFromRadio(nodeInfoNamed(0x98, now / 1000, "this very radio"), now)
        r.observeFromRadio(nodeInfoNamed(0x99, now / 1000, "someone else"), now)
        assertEquals(listOf("someone else"), r.onlineNodes(now).map { it.name })
    }
}
