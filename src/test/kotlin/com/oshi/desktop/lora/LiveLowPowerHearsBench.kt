package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_ROUNDTRIP_2026_10_29__ The one question left: at LOW power, does B hear A
 * live — and does it record a position?
 *
 * `LiveLowPowerBothEndsBench` saw A emit twelve positions in twelve minutes at `tx_power 1`
 * and B record none. It could not say whether B heard A, because it tested
 * `lastHeard != 0`, which is true of a stamp CACHED in the node database when the link
 * attaches. That flag could not tell hearing A live from remembering A.
 *
 * This compares a BASELINE against a later sample, which is the only form of the question
 * that answers it, and reports the two facts separately:
 *
 *   stamp advances + position arrives → the lane works; earlier runs simply missed it
 *   stamp advances + no position      → B hears A and does something else with its
 *                                       POSITIONS. Ours, and a real one.
 *   stamp frozen                      → B does not hear A even at low power, so the
 *                                       near-field result was narrower than it looked
 */
class LiveLowPowerHearsBench {

    @Test
    fun atLowPowerDoesBHearAAndRecordIt() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set both radios", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val lowPower = System.getenv("OSHI_TX_POWER")?.toLongOrNull() ?: 1L

        var aNode = 0u
        var original: ByteArray? = null
        fun admin(payload: ByteArray) = LoRaProto.toRadioPacket(
            aNode, LoRaProto.PORT_ADMIN, payload, wantAck = true, packetId = LoRaProto.randomPacketId())

        try {
            val probe = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = { f ->
                LoRaProto.field(f, 3, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }?.let { aNode = it.toUInt() }
                LoRaProto.field(f, 5, wire = 2)?.payload
                    ?.let { LoRaProto.field(it, 6, wire = 2)?.payload }?.let { original = it }
            }, log = {})
            probe.start(); Thread.sleep(12_000); runCatching { probe.stop() }; Thread.sleep(2_000)
            val orig = original ?: run { println("LPH ❌ no Config.lora"); return }
            val quiet = LoRaAdmin.withVarintField(orig, field = 10, value = lowPower)
                ?: run { println("LPH ❌ not rewritable"); return }

            val setup = LoRaLink(LoRaChannel.Serial(devA), onFromRadio = {}, log = {})
            setup.start(); Thread.sleep(6_000)
            println("LPH tx_power -> $lowPower : ${setup.send(admin(ProtoWriter().bytes(34, ProtoWriter().bytes(6, quiet).data).data))}")
            Thread.sleep(3_000)
            println("LPH reboot: ${setup.send(admin(LoRaAdmin.rebootFrame(5)))}")
            runCatching { setup.stop() }
            Thread.sleep(25_000)

            val roster = LoRaNodeRoster()
            val b = LoRaLink(LoRaChannel.Serial(devB!!), onFromRadio = {
                roster.observeFromRadio(it, System.currentTimeMillis())
            }, log = {})
            b.start()
            Thread.sleep(10_000)
            fun view() = roster.onlineNodes(System.currentTimeMillis()).firstOrNull { it.num == aNode }
            val baseline = view()?.lastHeardMs ?: 0L
            println("LPH baseline lastHeard = $baseline")
            Thread.sleep(180_000)   // three minutes; A emits about once a minute
            val after = view()
            println("LPH after 3 min: lastHeard=${after?.lastHeardMs} lat=${after?.latitude}")
            val advanced = (after?.lastHeardMs ?: 0L) > baseline
            runCatching { b.stop() }
            println("LPH RESULT advanced=$advanced position=${after?.latitude != null}")
            println("LPH VERDICT: " + when {
                advanced && after?.latitude != null ->
                    "THE LANE WORKS — B heard A live and recorded its position"
                advanced ->
                    "B HEARS A LIVE and records NO position — this is ours, on the receive side"
                else ->
                    "B does not hear A even at low power; the near-field result was narrower than it looked"
            })
        } finally {
            original?.let { o ->
                runCatching {
                    val back = LoRaLink(LoRaChannel.Serial(devA!!), onFromRadio = {}, log = {})
                    back.start(); Thread.sleep(6_000)
                    println("LPH restore: ${back.send(admin(ProtoWriter().bytes(34, ProtoWriter().bytes(6, o).data).data))}")
                    Thread.sleep(3_000); runCatching { back.stop() }
                }.onFailure { println("LPH ⚠️ RESTORE FAILED: ${it.message}") }
            }
        }
    }
}
