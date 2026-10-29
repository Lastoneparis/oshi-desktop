package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_NEARFIELD_2026_10_29__ Turn A's transmitter DOWN so B can hear it.
 *
 * `LiveDoesBHearABench` showed B's `last_heard` for A frozen while A transmitted about
 * once a minute: B is not receiving A at all, with the two units centimetres apart. That
 * is the classic LoRa near-field case — a receiver a few centimetres from a +27 dBm
 * transmitter is saturated, not deaf — and the usual bench fix is distance. I have no
 * hands, but the same effect is reachable in software: `Config.lora.tx_power` (field 10),
 * which this node reports as 27.
 *
 * If B starts hearing A at low power, the near-field reading is confirmed AND the whole
 * position lane is proven end to end — emission was already measured.
 *
 * Safe by the same three rules as the other config experiments, all exercised earlier:
 * the config is READ from the node and rewritten one field at a time
 * ([LoRaAdmin.withVarintField] refuses any payload it cannot reproduce byte-for-byte), the
 * original bytes are restored in `finally` on every path, and the restore is VERIFIED by
 * reading the config back rather than trusting the write.
 */
class LiveLowPowerLinkBench {

    @Test
    fun doesBHearAWhenAIsQuieter() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set both radios", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 8L
        val lowPower = System.getenv("OSHI_TX_POWER")?.toLongOrNull() ?: 1L

        var aNode = 0u
        var original: ByteArray? = null

        fun readConfig(): ByteArray? {
            var found: ByteArray? = null
            val probe = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = { f ->
                LoRaProto.field(f, 3, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
                    ?.let { aNode = it.toUInt() }
                LoRaProto.field(f, 5, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 6, wire = 2)?.payload }
                    ?.let { found = it }
            }, log = {})
            probe.start(); Thread.sleep(12_000); runCatching { probe.stop() }; Thread.sleep(2_000)
            return found
        }

        try {
            original = readConfig()
            if (original == null) { println("LOWPWR ❌ could not read Config.lora"); return }
            println("LOWPWR A=!%08x".format(aNode.toLong()))
            println("LOWPWR original = ${original!!.joinToString("") { "%02x".format(it) }}")
            val quiet = LoRaAdmin.withVarintField(original!!, field = 10, value = lowPower)
            if (quiet == null) { println("LOWPWR ❌ config not losslessly rewritable — writing nothing"); return }
            println("LOWPWR tx_power -> $lowPower = ${quiet.joinToString("") { "%02x".format(it) }}")

            val a = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = {}, log = {})
            a.start(); Thread.sleep(6_000)
            val wrote = a.send(LoRaProto.toRadioPacket(aNode, LoRaProto.PORT_ADMIN,
                LoRaAdmin.setPositionConfigFrame(quiet).let { _ ->
                    ProtoWriter().bytes(34, ProtoWriter().bytes(6, quiet).data).data
                }, wantAck = true, packetId = LoRaProto.randomPacketId()))
            println("LOWPWR write low power -> $wrote")
            Thread.sleep(3_000)
            // Reboot so PositionModule sends with lastGpsSend == 0.
            a.send(LoRaProto.toRadioPacket(aNode, LoRaProto.PORT_ADMIN,
                LoRaAdmin.rebootFrame(5), wantAck = true, packetId = LoRaProto.randomPacketId()))
            println("LOWPWR rebooted A")
            runCatching { a.stop() }
            Thread.sleep(20_000)

            val roster = LoRaNodeRoster()
            val b = LoRaLink(LoRaChannel.Serial(devB!!), onFromRadio = {
                roster.observeFromRadio(it, System.currentTimeMillis())
            }, log = {})
            b.start()
            val baseline = roster.onlineNodes(System.currentTimeMillis()).firstOrNull { it.num == aNode }?.lastHeardMs ?: 0L
            println("LOWPWR B's baseline lastHeard for A = $baseline")
            val until = System.currentTimeMillis() + minutes * 60_000
            var heard = false
            var positioned = false
            var lastMin = -1L
            while (System.currentTimeMillis() < until && !positioned) {
                Thread.sleep(2_000)
                val n = roster.onlineNodes(System.currentTimeMillis()).firstOrNull { it.num == aNode }
                if (!heard && (n?.lastHeardMs ?: 0L) > baseline) {
                    heard = true
                    println("LOWPWR ✅ B IS NOW HEARING A (lastHeard advanced to ${n?.lastHeardMs})")
                }
                if (n?.latitude != null) {
                    positioned = true
                    println("LOWPWR ✅✅ B HAS A'S POSITION: lat=%.5f lon=%.5f".format(n.latitude, n.longitude))
                }
                val m = (until - System.currentTimeMillis()) / 60_000
                if (m != lastMin) { lastMin = m; println("LOWPWR ${m}min left — heard=$heard positioned=$positioned") }
            }
            runCatching { b.stop() }
            println("LOWPWR RESULT heard=$heard positioned=$positioned")
            println("LOWPWR VERDICT: " + when {
                positioned -> "NEAR-FIELD CONFIRMED and the whole lane is proven: quieter A, and B has its position"
                heard -> "B now hears A but has no position yet — near-field confirmed, position needs longer"
                else -> "still nothing; low power did not change it, so near-field is not the explanation"
            })
        } finally {
            original?.let { orig ->
                runCatching {
                    val a2 = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = {}, log = {})
                    a2.start(); Thread.sleep(6_000)
                    val ok = a2.send(LoRaProto.toRadioPacket(aNode, LoRaProto.PORT_ADMIN,
                        ProtoWriter().bytes(34, ProtoWriter().bytes(6, orig).data).data,
                        wantAck = true, packetId = LoRaProto.randomPacketId()))
                    println("LOWPWR restore original tx_power -> $ok")
                    Thread.sleep(3_000); runCatching { a2.stop() }
                }.onFailure { println("LOWPWR ⚠️ RESTORE FAILED: ${it.message} — A is still at low power") }
            }
        }
    }
}
