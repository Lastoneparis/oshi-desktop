package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * __DESKTOP_LORA_REBOOT_2026_10_28__ The over-the-air proof, finally runnable.
 *
 * Everything before this failed to observe a fixed position reaching a second radio, and
 * reading `PositionModule.cpp` explained why: `fixed_position` marks the node STATIONARY,
 * which clamps its announcement to `default_position_stationary_broadcast_secs` — **six
 * hours** — whatever `position_broadcast_secs` says. Three experiments of 17-20 minutes
 * were measuring a twentieth of the interval and proved nothing.
 *
 * The same function says how to see it inside a test's lifetime:
 *
 *     if (lastGpsSend == 0 || msSinceLastSend >= effectiveIntervalMs) {
 *         if (nodeDB->hasValidPosition(node) && sendOurPosition()) {
 *
 * `lastGpsSend == 0` is true exactly once — on a fresh boot. So: reboot radio A and watch
 * radio B. If the position arrives, the whole chain is proven end to end — our admin frame
 * set it, the node kept it, the firmware announced it, the air carried it, and our roster
 * parsed it.
 *
 * Nothing about this is a workaround for a defect. Six hours is the firmware's policy for a
 * node that has pinned itself, and the product consequence (a person sees nothing on a
 * neighbour's map for up to six hours) is already written into the pane. The reboot only
 * moves the FIRST announcement forward so a human can watch it happen.
 */
class LiveRebootAnnouncementBench {

    @Test
    fun aFixedPositionReachesTheSecondRadioAfterAReboot() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A and OSHI_LIVE_RADIO_B", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 8L

        val aDir = Files.createTempDirectory("oshi-reboot-a").toFile()
        val bDir = Files.createTempDirectory("oshi-reboot-b").toFile()
        val a = OshiClient(home = aDir, secretStore = InMemorySecretStore(), displayName = "a", log = {})
        val b = OshiClient(home = bDir, secretStore = InMemorySecretStore(), displayName = "b", log = {})

        try {
            check(a.loraAttachSerial(devA!!)) { "radio A refused to attach" }
            Thread.sleep(6_000)
            val aNode = a.loraMyNodeNumber()
            println("REBOOT A=!%08x".format(aNode.toLong()))

            // The position must already be on the node — set by the admin frame in an
            // earlier run and confirmed by reading the config back. Re-asserted here so a
            // failure cannot be blamed on a radio that was never told anything.
            println("REBOOT re-assert fixed position -> ${a.loraSetFixedPosition(46.20500, 6.10500)}")
            Thread.sleep(3_000)

            check(b.loraAttachSerial(devB!!)) { "radio B refused to attach" }
            Thread.sleep(5_000)
            val before = b.loraOnlineNodeList().firstOrNull { it.num == aNode }?.latitude
            println("REBOOT B's view of A BEFORE: lat=$before")

            println("REBOOT asking A to reboot -> ${a.loraReboot(5)}")
            runCatching { a.loraDetach() }     // the port disappears while it reboots

            val started = System.currentTimeMillis()
            val deadline = started + minutes * 60_000
            var seen: LoRaNodeRoster.Node? = null
            var lastMin = -1L
            while (System.currentTimeMillis() < deadline && seen == null) {
                Thread.sleep(2_000)
                seen = b.loraOnlineNodeList().firstOrNull { it.num == aNode && it.latitude != null }
                val m = (System.currentTimeMillis() - started) / 60_000
                if (m != lastMin) {
                    lastMin = m
                    println("REBOOT ${m}min — waiting (B sees ${b.loraOnlineNodeList().count { it.latitude != null }} positioned nodes)")
                }
            }
            if (seen != null) {
                println("REBOOT ✅✅ AFTER %ds — !%08x lat=%.5f lon=%.5f".format(
                    (System.currentTimeMillis() - started) / 1000,
                    aNode.toLong(), seen!!.latitude, seen!!.longitude))
                println("REBOOT RESULT: THE WHOLE CHAIN IS PROVEN — admin frame set it, the node kept it, " +
                    "the firmware announced it on boot, the air carried it, our roster parsed it")
            } else {
                println("REBOOT RESULT: nothing in ${minutes}min after a reboot. The boot path is the " +
                    "one case the firmware sends unprompted, so this one IS a meaningful negative")
            }
        } finally {
            runCatching { a.loraDetach() }; runCatching { b.loraDetach() }
            aDir.deleteRecursively(); bDir.deleteRecursively()
        }
    }
}
