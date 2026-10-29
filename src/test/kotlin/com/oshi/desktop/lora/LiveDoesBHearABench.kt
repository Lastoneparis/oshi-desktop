package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_BOTH_ENDS_2026_10_29__ Does radio B hear radio A AT ALL?
 *
 * `LiveBothEndsBench` watched both ends in one run and found A emitting nine positions
 * (`lat=46.205`, the coordinates the admin frame set) while B recorded none of them, with
 * thirty-nine other nodes' positions in its roster throughout. Two very different faults
 * produce that, and they belong to different people:
 *
 *   B hears A's other traffic but not its positions
 *       → something port-specific, and ours to chase
 *   B hears nothing from A at all
 *       → the radio link between two units sitting centimetres apart, which is a known
 *         LoRa near-field effect and NOT a software bug
 *
 * `last_heard` for A answers it, because the roster takes that stamp from the radio's own
 * node database. Sampled twice: a stamp that ADVANCES means B is receiving A's
 * transmissions right now, whatever it does with their contents.
 *
 * Read-only. It attaches one radio and listens.
 */
class LiveDoesBHearABench {

    @Test
    fun isBHearingAAtAll() {
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        val target = System.getenv("OSHI_WATCH_NODE")
        Assume.assumeTrue(
            "set OSHI_LIVE_RADIO_B and OSHI_WATCH_NODE",
            !devB.isNullOrBlank() && !target.isNullOrBlank(),
        )
        val a = target!!.toLong(16).toUInt()

        val roster = LoRaNodeRoster()
        val link = LoRaLink(
            channel = LoRaChannel.Serial(devB!!),
            onFromRadio = { roster.observeFromRadio(it, System.currentTimeMillis()) },
            log = {},
        )
        try {
            link.start()
            Thread.sleep(10_000)

            fun view() = roster.onlineNodes(System.currentTimeMillis()).firstOrNull { it.num == a }

            val first = view()
            println("HEARS first: name='${first?.name}' lastHeard=${first?.lastHeardMs} lat=${first?.latitude}")
            Thread.sleep(120_000)
            val second = view()
            println("HEARS later: name='${second?.name}' lastHeard=${second?.lastHeardMs} lat=${second?.latitude}")
            println("HEARS positioned nodes B holds: " +
                roster.onlineNodes(System.currentTimeMillis()).count { it.latitude != null })

            val f = first?.lastHeardMs ?: 0L
            val s = second?.lastHeardMs ?: 0L
            println("HEARS VERDICT: " + when {
                first == null && second == null ->
                    "B does not have A in its roster at all"
                s > f ->
                    "B IS hearing A right now — the stamp advanced, so B receives A's traffic " +
                        "and does something else with its POSITIONS. Ours to chase."
                else ->
                    "B's stamp for A did NOT advance in two minutes — B is not receiving A's " +
                        "transmissions at all. Two units centimetres apart is a known LoRa " +
                        "near-field problem, and that is a bench setup issue rather than a defect."
            })
        } finally {
            runCatching { link.stop() }
        }
    }
}
