package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_OWN_POSITION_2026_10_29__ Does the node EMIT a position at all?
 *
 * Every earlier attempt watched the SECOND radio and learned only that nothing arrived,
 * which cannot tell a sender that never spoke from a receiver that never heard. The
 * traffic log added tonight makes the other half observable: Meshtastic echoes a node's own
 * packets to its client, so a position the firmware originates should appear in radio A's
 * OWN `FromRadio` stream with `from` = A.
 *
 * `PositionModule::runOnce` sends immediately while `lastGpsSend == 0`, true only on a
 * fresh boot, so this reboots and then listens. Read-only afterwards: it transmits nothing
 * of its own.
 *
 * Three outcomes, all useful:
 *  - a portnum-3 frame FROM our own node  → the firmware emits; the earlier silence at the
 *    second radio is a reception or range question, not an emission one
 *  - portnum-3 frames from OTHER nodes but never ours → it is emission, and specific to us
 *  - no portnum-3 at all → the radio is not relaying position traffic to the client, and
 *    this instrument cannot answer the question either
 */
class LiveOwnPositionEmitBench {

    @Test
    fun doesOurOwnNodeEverEmitAPosition() {
        val dev = System.getenv("OSHI_LIVE_RADIO_A")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A", !dev.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 6L

        var myNode = 0u
        var ownPositions = 0
        var otherPositions = 0
        var frames = 0
        val link = LoRaLink(
            channel = LoRaChannel.Serial(dev!!),
            onFromRadio = { f ->
                frames++
                // my_info (3) names us.
                LoRaProto.field(f, 3, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                    ?.let { myNode = it.toUInt() }
                // packet (2) → decoded (4) → portnum (1)
                val pkt = LoRaProto.field(f, 2, wire = 2)?.payload
                if (pkt != null) {
                    val port = LoRaProto.field(pkt, 4, wire = 2)?.payload
                        ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                    if (port == LoRaProto.PORT_POSITION) {
                        val from = LoRaProto.field(pkt, 1, wire = 5)?.asUInt32()
                        if (from != null && from == myNode) {
                            ownPositions++
                            println("EMIT ✅ OUR OWN node emitted a POSITION (count=$ownPositions)")
                        } else {
                            otherPositions++
                        }
                    }
                }
            },
            log = {},
        )
        try {
            link.start()
            Thread.sleep(8_000)
            println("EMIT my node = !%08x".format(myNode.toLong()))

            // The one moment the firmware sends unprompted: lastGpsSend == 0, on boot.
            val reboot = LoRaAdmin.rebootFrame(5)
            val pkt = LoRaProto.toRadioPacket(
                to = myNode, portnum = LoRaProto.PORT_ADMIN,
                payload = reboot, wantAck = true, packetId = LoRaProto.randomPacketId(),
            )
            println("EMIT reboot -> ${link.send(pkt)}")
            runCatching { link.stop() }
            Thread.sleep(20_000)   // let it go down and come back

            val link2 = LoRaLink(
                channel = LoRaChannel.Serial(dev),
                onFromRadio = { f ->
                    frames++
                    LoRaProto.field(f, 3, wire = 2)?.payload
                        ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                        ?.let { myNode = it.toUInt() }
                    val pkt2 = LoRaProto.field(f, 2, wire = 2)?.payload
                    if (pkt2 != null) {
                        val port = LoRaProto.field(pkt2, 4, wire = 2)?.payload
                            ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                        if (port == LoRaProto.PORT_POSITION) {
                            val from = LoRaProto.field(pkt2, 1, wire = 5)?.asUInt32()
                            if (from != null && from == myNode) {
                                ownPositions++
                                // __DESKTOP_LORA_OWN_POSITION_2026_10_29__ DECODE it. "A
                                // position was emitted" is not the claim that matters —
                                // `observePosition` on every receiver DISCARDS 0/0,
                                // because that is what a node with no lock reports. If
                                // ours goes out empty, every receiver is right to drop it
                                // and the fixed position never reaches anyone.
                                val payload = LoRaProto.field(pkt2, 4, wire = 2)?.payload
                                    ?.let { LoRaProto.field(it, 2, wire = 2)?.payload }
                                var la: Double? = null
                                var lo: Double? = null
                                payload?.let { pl ->
                                    for (pf in LoRaProto.parseFields(pl)) {
                                        if (pf.wire != 5) continue
                                        when (pf.number) {
                                            1 -> la = pf.asInt32() * 1e-7
                                            2 -> lo = pf.asInt32() * 1e-7
                                        }
                                    }
                                }
                                println("EMIT ✅ OUR OWN POSITION went out: lat=$la lon=$lo " +
                                    "payload=${payload?.joinToString("") { b -> "%02x".format(b) }}")
                                if ((la == null || (la == 0.0 && lo == 0.0))) {
                                    println("EMIT ⚠️ it carries NO usable position — every receiver " +
                                        "discards 0/0, so this is why radio B never showed us")
                                }
                            } else otherPositions++
                        }
                    }
                },
                log = {},
            )
            link2.start()
            val until = System.currentTimeMillis() + minutes * 60_000
            var lastMin = -1L
            while (System.currentTimeMillis() < until && ownPositions == 0) {
                Thread.sleep(2_000)
                val m = (until - System.currentTimeMillis()) / 60_000
                if (m != lastMin) {
                    lastMin = m
                    println("EMIT ${m}min left — own=$ownPositions others=$otherPositions frames=$frames")
                }
            }
            runCatching { link2.stop() }
            println("EMIT RESULT own=$ownPositions others=$otherPositions frames=$frames")
            println("EMIT VERDICT: " + when {
                ownPositions > 0 -> "the firmware DOES emit our position — the silence at radio B is reception, not emission"
                otherPositions > 0 -> "position traffic reaches the client from OTHER nodes but NEVER ours — it is emission, and specific to this node"
                else -> "no position traffic reaches the client at all, from anyone — this instrument cannot answer it"
            })
        } finally {
            runCatching { link.stop() }
        }
    }
}
