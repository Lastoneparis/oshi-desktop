package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_ROUNDTRIP_2026_10_29__ Every packet B receives FROM A, by portnum.
 *
 * `LiveTextVsPositionBench` settled the link: seven texts out of seven arrived from A and
 * B's `last_heard` advanced, while not one position did. So the A→B radio path works and
 * the failure is specific to portnum 3. Two possibilities remain and they live in different
 * places:
 *
 *   B receives portnum 3 from A → our parser drops it, and it is findable in this repo
 *   B receives only portnum 1   → A's firmware generates a position for its client but
 *                                 does not put it on the air
 *
 * A's own echo already proved the firmware GENERATES them. This counts what crosses.
 */
class LiveWhatBSeesFromABench {

    @Test
    fun countEveryPacketBGetsFromA() {
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
        println("WBS A=!%08x".format(aNode.toLong()))

        val byPort = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val linkB = LoRaLink(LoRaChannel.Serial(devB!!), onFromRadio = { f ->
            val pkt = LoRaProto.field(f, 2, wire = 2)?.payload ?: return@LoRaLink
            if (LoRaProto.field(pkt, 1, wire = 5)?.asUInt32() != aNode) return@LoRaLink
            val dec = LoRaProto.field(pkt, 4, wire = 2)?.payload
            val key = when {
                dec != null -> "decoded:" + LoRaTrafficLog.portName(
                    LoRaProto.field(dec, 1, wire = 0)?.varintValue)
                // field 5 is `encrypted` — a packet the radio handed up without decrypting
                LoRaProto.field(pkt, 5, wire = 2) != null -> "ENCRYPTED (not decoded for us)"
                else -> "neither decoded nor encrypted"
            }
            byPort.merge(key, 1, Int::plus)
            println("WBS ← from A: $key")
        }, log = {})

        try {
            linkB.start()
            val until = System.currentTimeMillis() + minutes * 60_000
            var sent = 0
            var nextText = 0L
            var lastMin = -1L
            while (System.currentTimeMillis() < until) {
                if (System.currentTimeMillis() >= nextText) {
                    sent++
                    // A control in the same run: text is KNOWN to cross, so its arrival
                    // proves the link was alive during this very window.
                    probe.send(LoRaProto.toRadioPacket(
                        LoRaProto.BROADCAST_ADDR, LoRaProto.PORT_TEXT,
                        "WBS-$sent".toByteArray(), wantAck = false,
                        packetId = LoRaProto.randomPacketId()))
                    nextText = System.currentTimeMillis() + 60_000
                }
                Thread.sleep(2_000)
                val m = (until - System.currentTimeMillis()) / 60_000
                if (m != lastMin) { lastMin = m; println("WBS ${m}min left — $byPort") }
            }
            println("WBS RESULT (packets B received FROM A, by kind): $byPort")
            val sawPosition = byPort.keys.any { it.contains("POSITION") }
            val sawText = byPort.keys.any { it.contains("TEXT") }
            println("WBS VERDICT: " + when {
                sawPosition -> "B DOES receive portnum 3 from A — our parser is dropping it, and it is findable here"
                sawText -> "B receives A's TEXT and never a POSITION — A's firmware generates positions for its client but does not transmit them"
                else -> "B received nothing from A in this window; the control failed, so nothing is measured"
            })
        } finally {
            runCatching { probe.stop() }; runCatching { linkB.stop() }
        }
    }
}
