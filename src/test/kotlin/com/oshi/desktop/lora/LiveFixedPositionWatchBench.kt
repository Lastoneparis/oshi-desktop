package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * __DESKTOP_LORA_BEACON_FIXED_2026_10_28__ Watch radio B for radio A's position, patiently.
 *
 * `LiveBeaconDiagnosisBench` writes the admin frame and then looks for 4 minutes. That is
 * the wrong window, and the reason is the whole point of the admin mechanism: the client
 * does not announce, the NODE does, on `position_broadcast_secs` — which defaults to **900
 * seconds**. A 4-minute observation can only ever catch it by luck, and reading that as
 * "the admin path does not work" would repeat the mistake this sequence started with: a
 * conclusion drawn from a window too small for the thing being measured.
 *
 * So this attaches ONLY the receiver and waits a full interval and a half, reporting what it
 * sees. It transmits nothing at all — no duty cycle spent, nothing that could disturb the
 * mesh it is observing.
 *
 * It presumes `OSHI_WATCH_NODE` was already given a fixed position by an earlier run.
 */
class LiveFixedPositionWatchBench {

    @Test
    fun watchForTheNodesScheduledAnnouncement() {
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        val target = System.getenv("OSHI_WATCH_NODE")   // e.g. 766eb396
        Assume.assumeTrue("set OSHI_LIVE_RADIO_B and OSHI_WATCH_NODE", !devB.isNullOrBlank() && !target.isNullOrBlank())
        val wanted = target!!.toLong(16).toUInt()

        val dir = Files.createTempDirectory("oshi-watch").toFile()
        val b = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = "watch", log = {})
        try {
            check(b.loraAttachSerial(devB!!)) { "radio B refused to attach" }
            Thread.sleep(6_000)
            println("WATCH listening on $devB for !%08x".format(wanted.toLong()))

            val started = System.currentTimeMillis()
            // 22 minutes: one default announcement interval (15 min) plus margin, so a miss
            // is a real miss rather than an impatient one.
            val deadline = started + (System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 22L) * 60_000
            var seen = false
            var lastReport = 0L
            while (System.currentTimeMillis() < deadline && !seen) {
                Thread.sleep(2_000)
                val node = b.loraOnlineNodeList().firstOrNull { it.num == wanted }
                if (node?.latitude != null) {
                    seen = true
                    println("WATCH ✅ after %ds — !%08x lat=%.5f lon=%.5f".format(
                        (System.currentTimeMillis() - started) / 1000,
                        wanted.toLong(), node.latitude, node.longitude))
                }
                val mins = (System.currentTimeMillis() - started) / 60_000
                if (mins != lastReport) {
                    lastReport = mins
                    println("WATCH ${mins}min — still no position from !%08x (B sees %d nodes with one)"
                        .format(wanted.toLong(), b.loraOnlineNodeList().count { it.latitude != null }))
                }
            }
            val watchedMin = (System.currentTimeMillis() - started) / 60_000
            println("WATCH RESULT: " + if (seen) "the node announced its fixed position"
                else "NO position in ${watchedMin}min — and this window is only meaningful " +
                    "against position_broadcast_secs (default 900s); say how long it watched, " +
                    "never a figure from the source")
        } finally {
            runCatching { b.loraDetach() }
            dir.deleteRecursively()
        }
    }
}
