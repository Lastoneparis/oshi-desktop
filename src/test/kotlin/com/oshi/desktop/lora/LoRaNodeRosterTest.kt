package com.oshi.desktop.lora

import org.junit.Test
import org.junit.Assert.assertEquals

class LoRaNodeRosterTest {

    private val now = 1_790_000_000_000L // 2026-09

    private fun myInfo(num: Long) =
        ProtoWriter().bytes(3, ProtoWriter().varint(1, num).data).data

    private fun nodeInfo(num: Long, lastHeardS: Long?) =
        ProtoWriter().bytes(
            4,
            ProtoWriter().varint(1, num).let { w -> if (lastHeardS != null) w.fixed32(5, lastHeardS.toInt()) else w }.data,
        ).data

    private fun packetFrom(num: Long) =
        ProtoWriter().bytes(2, ProtoWriter().fixed32(1, num.toInt()).data).data

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
    }
}
