package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_RADIO_STATE_2026_10_29__ Read the region and tx flag off a REAL node.
 *
 * `LoRaRadioState` makes two opposite readings of protobuf's "absent means default":
 * region absent is UNSET (bad), tx_enabled absent is ENABLED (fine). Those are a judgement
 * call about what firmware actually puts on the wire, and a unit test built from my own
 * fixtures would agree with whichever reading I chose. A radio does not.
 */
class LiveRadioStateBench {

    @Test
    fun whatTheRadioSaysAboutItself() {
        val dev = System.getenv("OSHI_LIVE_RADIO_A")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A", !dev.isNullOrBlank())

        val state = LoRaRadioState()
        var sawLoraConfig = false
        var rawLora: ByteArray? = null
        val link = LoRaLink(
            channel = LoRaChannel.Serial(dev!!),
            onFromRadio = { f ->
                if (state.observeFromRadio(f)) {
                    sawLoraConfig = true
                    rawLora = LoRaProto.field(f, 5, wire = 2)?.payload
                        ?.let { LoRaProto.field(it, 6, wire = 2)?.payload }
                }
            },
            log = {},
        )
        try {
            link.start()
            Thread.sleep(12_000)
            println("RADIOSTATE sawLoraConfig=$sawLoraConfig known=${state.known}")
            rawLora?.let { println("RADIOSTATE Config.lora = ${it.joinToString("") { b -> "%02x".format(b) }}") }
            println("RADIOSTATE region=${state.regionCode} txEnabled=${state.transmitEnabled}")
            println("RADIOSTATE willNotTransmit=${state.willNotTransmit}")
            println("RADIOSTATE blockedReason=${state.blockedReason ?: "(none — the radio is fine)"}")
            rawLora?.let { lora ->
                val present = LoRaProto.parseFields(lora).map { it.number }.sorted()
                println("RADIOSTATE fields present in Config.lora: $present")
                println("RADIOSTATE   field 9 (tx_enabled) " +
                    if (present.contains(9)) "IS on the wire" else "is ABSENT — the default reading decides")
            }
        } finally {
            runCatching { link.stop() }
        }
    }
}
