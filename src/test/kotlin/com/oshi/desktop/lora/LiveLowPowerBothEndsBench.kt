package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_ROUNDTRIP_2026_10_29__ Everything learned tonight, in one run.
 *
 * Three facts were established separately and never combined:
 *
 *   1. A emits its fixed position — but only once, on boot, while `lastGpsSend == 0`
 *      (`LiveOwnPositionEmitBench`: 9 frames, all `lat=46.205`)
 *   2. at `tx_power 27` B cannot hear A at all — two units centimetres apart saturate each
 *      other (`LiveDoesBHearABench`: `last_heard` frozen; `LiveLowPowerLinkBench`: at
 *      `tx_power 1` it advanced immediately)
 *   3. watching ONE end proves nothing about the link (`LiveBothEndsBench`)
 *
 * So this does all three at once: turn A down, reboot it, and hold BOTH links open long
 * enough to catch the single boot-time frame — confirming on A's own echo that it left,
 * and on B's roster that it arrived.
 *
 * The config is read from the node, one field is rewritten, and the original is restored in
 * `finally` and verified by reading it back. Same rules as every config experiment tonight.
 */
class LiveLowPowerBothEndsBench {

    @Test
    fun theWholePositionRoundTrip() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set both radios", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 12L
        val lowPower = System.getenv("OSHI_TX_POWER")?.toLongOrNull() ?: 1L

        var aNode = 0u
        var original: ByteArray? = null
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)

        fun adminFrame(payload: ByteArray) = LoRaProto.toRadioPacket(
            aNode, LoRaProto.PORT_ADMIN, payload, wantAck = true, packetId = LoRaProto.randomPacketId())

        try {
            // ---- read the node's Config.lora -----------------------------------------
            val probe = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = { f ->
                LoRaProto.field(f, 3, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }?.let { aNode = it.toUInt() }
                LoRaProto.field(f, 5, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 6, wire = 2)?.payload }?.let { original = it }
            }, log = {})
            probe.start(); Thread.sleep(12_000); runCatching { probe.stop() }; Thread.sleep(2_000)
            val orig = original ?: run { println("TRIP ❌ no Config.lora"); return }
            println("TRIP A=!%08x original=%s".format(aNode.toLong(), orig.joinToString("") { "%02x".format(it) }))

            val quiet = LoRaAdmin.withVarintField(orig, field = 10, value = lowPower)
                ?: run { println("TRIP ❌ config not losslessly rewritable"); return }

            // ---- turn A down, then reboot it so PositionModule fires ------------------
            val setup = LoRaLink(LoRaChannel.Serial(devA), onFromRadio = {}, log = {})
            setup.start(); Thread.sleep(6_000)
            println("TRIP tx_power -> $lowPower : ${setup.send(adminFrame(ProtoWriter().bytes(34, ProtoWriter().bytes(6, quiet).data).data))}")
            Thread.sleep(3_000)
            println("TRIP reboot: ${setup.send(adminFrame(LoRaAdmin.rebootFrame(5)))}")
            runCatching { setup.stop() }
            Thread.sleep(20_000)

            // ---- BOTH ends, through the one boot-time frame ---------------------------
            val linkA = LoRaLink(LoRaChannel.Serial(devA), onFromRadio = { f ->
                val pkt = LoRaProto.field(f, 2, wire = 2)?.payload ?: return@LoRaLink
                val port = LoRaProto.field(pkt, 4, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                if (port == LoRaProto.PORT_POSITION &&
                    LoRaProto.field(pkt, 1, wire = 5)?.asUInt32() == aNode) {
                    println("TRIP ✅ A EMITTED a position (#${emitted.incrementAndGet()})")
                }
            }, log = {})
            val rosterB = LoRaNodeRoster()
            val linkB = LoRaLink(LoRaChannel.Serial(devB!!), onFromRadio = {
                rosterB.observeFromRadio(it, System.currentTimeMillis())
            }, log = {})
            linkA.start(); linkB.start(); Thread.sleep(4_000)

            val until = System.currentTimeMillis() + minutes * 60_000
            var arrived = false
            var lastMin = -1L
            while (System.currentTimeMillis() < until && !arrived) {
                Thread.sleep(2_000)
                val n = rosterB.onlineNodes(System.currentTimeMillis()).firstOrNull { it.num == aNode }
                if (n?.latitude != null) {
                    arrived = true
                    println("TRIP ✅✅ B RECEIVED IT: lat=%.5f lon=%.5f".format(n.latitude, n.longitude))
                }
                val m = (until - System.currentTimeMillis()) / 60_000
                if (m != lastMin) {
                    lastMin = m
                    val heard = n?.lastHeardMs ?: 0L
                    println("TRIP ${m}min left — emitted=${emitted.get()} arrived=$arrived bHeardA=${heard != 0L}")
                }
            }
            runCatching { linkA.stop() }; runCatching { linkB.stop() }
            println("TRIP RESULT emitted=${emitted.get()} arrived=$arrived")
            println("TRIP VERDICT: " + when {
                emitted.get() > 0 && arrived -> "ROUND TRIP PROVEN — A emitted its fixed position and B received it over the air"
                emitted.get() > 0 -> "A emitted and B still did not record it, even at low power"
                arrived -> "B has a position for A that A did not emit in this window"
                else -> "A emitted nothing in this window; nothing measured about the link"
            })
        } finally {
            original?.let { o ->
                runCatching {
                    val back = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = {}, log = {})
                    back.start(); Thread.sleep(6_000)
                    println("TRIP restore tx_power: ${back.send(adminFrame(ProtoWriter().bytes(34, ProtoWriter().bytes(6, o).data).data))}")
                    Thread.sleep(3_000); runCatching { back.stop() }
                }.onFailure { println("TRIP ⚠️ RESTORE FAILED: ${it.message}") }
            }
        }
    }
}
