package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_BOTH_ENDS_2026_10_29__ Watch BOTH radios in the SAME run.
 *
 * Every experiment before this watched one end. The run that proved radio A emits its
 * position only listened to A; the runs that waited at radio B never confirmed A had
 * spoken. Two half-observations of different runs cannot be joined — if A emits in one run
 * and B hears nothing in another, the honest reading is that nothing was measured about
 * the link at all.
 *
 * So: reboot A, then hold BOTH serial links open and watch for the one frame on both
 * sides. A's own `FromRadio` echo says it emitted; B's roster says it arrived. Four
 * outcomes, and only one of them is a mystery:
 *
 *   emitted + arrived  → the whole lane works, end to end
 *   emitted + not      → a genuine reception failure between two radios in one room
 *   not emitted        → the emission is not reliable, and the earlier captures were luck
 *   neither            → the reboot did not take; nothing measured
 */
class LiveBothEndsBench {

    @Test
    fun oneFrameWatchedFromBothSides() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set both radios", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 10L

        var aNode = 0u
        // AtomicInteger rather than a @Volatile local: the callback runs on the link's
        // read thread and the loop below reads from the test thread.
        val emittedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val emittedLatRef = java.util.concurrent.atomic.AtomicReference<Double?>(null)

        // ---- reboot A, so PositionModule sends with lastGpsSend == 0 -------------------
        val probe = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = { f ->
            LoRaProto.field(f, 3, wire = 2)?.payload
                ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                ?.let { aNode = it.toUInt() }
        }, log = {})
        probe.start()
        Thread.sleep(8_000)
        println("BOTH A=!%08x".format(aNode.toLong()))
        probe.send(LoRaProto.toRadioPacket(aNode, LoRaProto.PORT_ADMIN,
            LoRaAdmin.rebootFrame(5), wantAck = true, packetId = LoRaProto.randomPacketId()))
        println("BOTH rebooted A")
        runCatching { probe.stop() }
        Thread.sleep(20_000)

        // ---- both ends, at once -------------------------------------------------------
        val rosterB = LoRaNodeRoster()
        val linkA = LoRaLink(LoRaChannel.Serial(devA), onFromRadio = { f ->
            val pkt = LoRaProto.field(f, 2, wire = 2)?.payload ?: return@LoRaLink
            val port = LoRaProto.field(pkt, 4, wire = 2)?.payload
                ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
            if (port != LoRaProto.PORT_POSITION) return@LoRaLink
            if (LoRaProto.field(pkt, 1, wire = 5)?.asUInt32() != aNode) return@LoRaLink
            emittedCount.incrementAndGet()
            LoRaProto.field(pkt, 4, wire = 2)?.payload
                ?.let { LoRaProto.field(it, 2, wire = 2)?.payload }
                ?.let { pl ->
                    for (pf in LoRaProto.parseFields(pl)) {
                        if (pf.wire == 5 && pf.number == 1) emittedLatRef.set(pf.asInt32() * 1e-7)
                    }
                }
            println("BOTH ✅ A EMITTED a position (lat=${emittedLatRef.get()})")
        }, log = {})
        val linkB = LoRaLink(LoRaChannel.Serial(devB!!), onFromRadio = { f ->
            rosterB.observeFromRadio(f, System.currentTimeMillis())
        }, log = {})

        try {
            linkA.start(); linkB.start()
            Thread.sleep(5_000)
            val until = System.currentTimeMillis() + minutes * 60_000
            var arrived = false
            var lastMin = -1L
            while (System.currentTimeMillis() < until && !(emittedCount.get() > 0 && arrived)) {
                Thread.sleep(2_000)
                val seen = rosterB.onlineNodes(System.currentTimeMillis())
                    .firstOrNull { it.num == aNode && it.latitude != null }
                if (seen != null && !arrived) {
                    arrived = true
                    println("BOTH ✅ B RECEIVED it: lat=%.5f lon=%.5f".format(seen.latitude, seen.longitude))
                }
                val m = (until - System.currentTimeMillis()) / 60_000
                if (m != lastMin) {
                    lastMin = m
                    println("BOTH ${m}min left — emitted=${emittedCount.get()} arrived=$arrived " +
                        "(B has ${rosterB.onlineNodes(System.currentTimeMillis()).count { it.latitude != null }} positioned)")
                }
            }
            println("BOTH RESULT emitted=${emittedCount.get()} arrived=$arrived")
            println("BOTH VERDICT: " + when {
                emittedCount.get() > 0 && arrived -> "THE WHOLE LANE WORKS — A emitted and B received the same position"
                emittedCount.get() > 0 -> "A emitted and B did NOT hear it — a real reception failure between two radios in one room"
                arrived -> "B has a position for A without A emitting in this window — it came from somewhere else"
                else -> "A never emitted in this window either; the reboot may not have taken"
            })
        } finally {
            runCatching { linkA.stop() }; runCatching { linkB.stop() }
        }
    }
}
