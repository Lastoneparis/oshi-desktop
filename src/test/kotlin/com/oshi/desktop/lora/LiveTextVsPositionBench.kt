package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_ROUNDTRIP_2026_10_29__ Text and position from the SAME radio, in the SAME
 * run, to the SAME receiver.
 *
 * Two measurements of tonight contradict each other and both were taken honestly:
 *
 *   - interop TEXT from A reached B in 3 s and 7 s
 *   - B's `last_heard` for A never advances, at either transmit power, while A emits a
 *     position about once a minute
 *
 * Those cannot both describe a link that is simply down. What separates them is the
 * CONTROL: the text runs were a different hour, a different power, and never alongside a
 * position. So this sends text on a timer from A while A's firmware emits its own
 * positions, and watches B for both — the control and the subject under identical
 * conditions, which is the one discipline that has worked tonight and the one whose
 * absence cost the most.
 *
 *   text arrives, position does not → portnum-specific, and OURS to fix
 *   neither arrives                 → the A→B link is down right now; the earlier text
 *                                     successes were a different link state (or relayed)
 *   both arrive                     → the lane works and every earlier miss was timing
 */
class LiveTextVsPositionBench {

    @Test
    fun textAndPositionUnderIdenticalConditions() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set both radios", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 6L

        var aNode = 0u
        val probe = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = { f ->
            LoRaProto.field(f, 3, wire = 2)?.payload
                ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }?.let { aNode = it.toUInt() }
        }, log = {})
        probe.start(); Thread.sleep(10_000)
        println("TVP A=!%08x".format(aNode.toLong()))

        val rosterB = LoRaNodeRoster()
        val texts = java.util.Collections.synchronizedList(ArrayList<String>())
        val linkB = LoRaLink(LoRaChannel.Serial(devB!!), onFromRadio = { f ->
            rosterB.observeFromRadio(f, System.currentTimeMillis())
            // Plain interop text from A: packet(2) → decoded(4) → portnum(1)==TEXT, payload(2)
            val pkt = LoRaProto.field(f, 2, wire = 2)?.payload ?: return@LoRaLink
            if (LoRaProto.field(pkt, 1, wire = 5)?.asUInt32() != aNode) return@LoRaLink
            val dec = LoRaProto.field(pkt, 4, wire = 2)?.payload ?: return@LoRaLink
            if (LoRaProto.field(dec, 1, wire = 0)?.varintValue != LoRaProto.PORT_TEXT) return@LoRaLink
            LoRaProto.field(dec, 2, wire = 2)?.payload?.decodeToString()?.let {
                texts += it
                println("TVP ✅ TEXT arrived at B: '$it'")
            }
        }, log = {})

        try {
            linkB.start(); Thread.sleep(4_000)
            val baseline = rosterB.onlineNodes(System.currentTimeMillis())
                .firstOrNull { it.num == aNode }?.lastHeardMs ?: 0L
            println("TVP baseline lastHeard at B = $baseline")

            val until = System.currentTimeMillis() + minutes * 60_000
            var nextText = 0L
            var sent = 0
            var lastMin = -1L
            var positioned = false
            while (System.currentTimeMillis() < until) {
                if (System.currentTimeMillis() >= nextText) {
                    sent++
                    val msg = "TVP-$sent-${System.currentTimeMillis() % 100000}"
                    val pkt = LoRaProto.toRadioPacket(
                        LoRaProto.BROADCAST_ADDR, LoRaProto.PORT_TEXT,
                        msg.toByteArray(Charsets.UTF_8), wantAck = false,
                        packetId = LoRaProto.randomPacketId())
                    println("TVP → sent text #$sent ('$msg') -> ${probe.send(pkt)}")
                    nextText = System.currentTimeMillis() + 45_000
                }
                Thread.sleep(2_000)
                val n = rosterB.onlineNodes(System.currentTimeMillis()).firstOrNull { it.num == aNode }
                if (!positioned && n?.latitude != null) {
                    positioned = true
                    println("TVP ✅ POSITION arrived at B: lat=%.5f".format(n.latitude))
                }
                val m = (until - System.currentTimeMillis()) / 60_000
                if (m != lastMin) {
                    lastMin = m
                    println("TVP ${m}min left — textsSent=$sent textsArrived=${texts.size} " +
                        "position=$positioned stampAdvanced=${(n?.lastHeardMs ?: 0L) > baseline}")
                }
            }
            val advanced = (rosterB.onlineNodes(System.currentTimeMillis())
                .firstOrNull { it.num == aNode }?.lastHeardMs ?: 0L) > baseline
            println("TVP RESULT sent=$sent arrived=${texts.size} position=$positioned advanced=$advanced")
            println("TVP VERDICT: " + when {
                texts.isNotEmpty() && positioned -> "both lanes work — every earlier miss was timing"
                texts.isNotEmpty() -> "TEXT arrives and POSITION does not, same radio same run — portnum-specific, and OURS"
                positioned -> "position arrives and text does not — unexpected, look again"
                else -> "NEITHER arrives: the A→B link is down right now, so the earlier text successes were a different link state or relayed"
            })
        } finally {
            runCatching { probe.stop() }; runCatching { linkB.stop() }
        }
    }
}
