package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_BOOTLOG_2026_10_28__ Read the radio's OWN log instead of inferring.
 *
 * Six hypotheses have been measured about why a node with a fixed position never appears on
 * another radio's map, and the firmware source says it should: `NodeDB.cpp:648` restores a
 * persisted fixed position into `localPosition` at boot, `hasValidPosition` then passes on
 * `localPosition.latitude_i != 0`, and `PositionModule::runOnce` sends immediately while
 * `lastGpsSend == 0`. Channel utilisation is 0.0%, so the 25% gate is not it; the build on
 * the radio (2.8.1.392d747) is in this checkout and contains both that restore and the
 * 6-hour floor.
 *
 * When the source and the device disagree and the source has been checked, stop reasoning
 * and ask the device. The firmware streams its debug log over the same serial link as
 * everything else (`FromRadio.log_record`, field 6; `LogRecord.message`, field 1), and
 * `NodeDB.cpp:654` logs the restore by name: "Restored fixed position to localPosition".
 * Either that line appears at boot or it does not, and either answer is worth more than a
 * seventh guess.
 *
 * Read-only: attaches, listens, prints. It transmits nothing.
 */
class LiveBootLogBench {

    @Test
    fun whatTheRadioSaysAboutItsOwnPositionAtBoot() {
        val dev = System.getenv("OSHI_LIVE_RADIO_A")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A", !dev.isNullOrBlank())
        val seconds = System.getenv("OSHI_LOG_SECONDS")?.toLongOrNull() ?: 120L

        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val link = LoRaLink(
            channel = LoRaChannel.Serial(dev!!),
            onFromRadio = { f ->
                LoRaProto.field(f, 6, wire = 2)?.payload?.let { rec ->
                    LoRaProto.field(rec, 1, wire = 2)?.payload?.decodeToString()?.let { lines += it }
                }
            },
            log = {},
        )
        try {
            link.start()
            println("BOOTLOG listening on $dev for ${seconds}s")
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) Thread.sleep(1_000)

            val all = lines.toList()
            println("BOOTLOG captured ${all.size} log records")
            val interesting = all.filter { l ->
                listOf("position", "Position", "POSITION", "fixed", "Fixed", "GPS", "gps")
                    .any { l.contains(it) }
            }
            println("BOOTLOG ${interesting.size} mention position/gps:")
            interesting.take(40).forEach { println("BOOTLOG   $it") }
            if (all.isEmpty()) {
                println("BOOTLOG (no log records at all — this build may not stream them, " +
                    "which is an answer about the INSTRUMENT, not about the position)")
            }
        } finally {
            runCatching { link.stop() }
        }
    }
}
