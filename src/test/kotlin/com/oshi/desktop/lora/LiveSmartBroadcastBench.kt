package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * __DESKTOP_LORA_SMART_BROADCAST_2026_10_28__ The last standing hypothesis, measured.
 *
 * Established so far: a client POSITION_APP broadcast reaches no node; the admin frame
 * `set_fixed_position` DOES land (the node reports `fixed_position = 1` and holds the right
 * coordinates); our receiver no longer drops `NodeInfo.position`; and across a 20-minute
 * watch — longer than `position_broadcast_secs`' 900 s — the second radio still never saw
 * the first one's position, while holding positions for 37 other nodes throughout.
 *
 * The remaining candidate is the firmware's own smart broadcast. The node reports
 * `position_broadcast_smart_enabled = 1` with `broadcast_smart_minimum_distance = 100`, and
 * a FIXED position by definition never moves 100 metres. That is the same gate that makes
 * the one-hour ceiling this client's entire clock — and if the firmware applies it without
 * a ceiling of its own, a fixed node announces once and then never again.
 *
 * This turns it off, watches, and PUTS IT BACK. Three things make that safe rather than
 * reckless:
 *
 *  - the config is READ from the node and rewritten one field at a time, so every other
 *    setting survives byte-for-byte ([LoRaAdmin.withVarintField] refuses anything it cannot
 *    reproduce exactly);
 *  - the original bytes are captured before the write and restored in `finally`, on every
 *    path including a failed assertion;
 *  - it writes only to the radio named by `OSHI_LIVE_RADIO_A`, a development node.
 *
 * A negative here does NOT mean smart broadcast is innocent — the window is still finite.
 * A positive is decisive.
 */
class LiveSmartBroadcastBench {

    @Test
    fun doesTurningSmartBroadcastOffPutUsOnTheMap() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A and OSHI_LIVE_RADIO_B", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        val minutes = System.getenv("OSHI_WATCH_MINUTES")?.toLongOrNull() ?: 18L
        // Which PositionConfig field to flip, so the next hypothesis does not need a new
        // bench. 2 = position_broadcast_smart_enabled (tested 2026-10-28: not the cause).
        // 13 = gps_mode, the current candidate: the node reports ENABLED, and a
        // ThinkNode M1 with no GPS lock may be waiting for one instead of falling back to
        // the fixed position it was given.
        val flipField = System.getenv("OSHI_FLIP_FIELD")?.toIntOrNull() ?: 2
        val flipValue = System.getenv("OSHI_FLIP_VALUE")?.toLongOrNull() ?: 0L

        val aDir = Files.createTempDirectory("oshi-smart-a").toFile()
        val bDir = Files.createTempDirectory("oshi-smart-b").toFile()
        val a = OshiClient(home = aDir, secretStore = InMemorySecretStore(), displayName = "a", log = {})
        val b = OshiClient(home = bDir, secretStore = InMemorySecretStore(), displayName = "b", log = {})

        var original: ByteArray? = null
        try {
            // --- capture the node's current Config.position from its handshake burst ----
            val captured = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
            val probe = LoRaLink(channel = LoRaChannel.Serial(devA!!), onFromRadio = { captured += it }, log = {})
            probe.start()
            Thread.sleep(12_000)
            original = captured.toList().firstNotNullOfOrNull { f ->
                LoRaProto.field(f, 5, wire = 2)?.payload?.let { LoRaProto.field(it, 2, wire = 2)?.payload }
            }
            runCatching { probe.stop() }
            Thread.sleep(2_000)

            if (original == null) {
                println("SMART ❌ could not read Config.position — writing nothing")
                return
            }
            println("SMART original Config.position = ${original!!.joinToString("") { "%02x".format(it) }}")
            val smartOff = LoRaAdmin.withVarintField(original!!, field = flipField, value = flipValue)
            if (smartOff == null) {
                println("SMART ❌ the config could not be rewritten losslessly — writing nothing")
                return
            }
            println("SMART field $flipField -> $flipValue  = ${smartOff.joinToString("") { "%02x".format(it) }}")

            check(a.loraAttachSerial(devA)) { "radio A refused to attach" }
            Thread.sleep(6_000)
            val aNode = a.loraMyNodeNumber()
            println("SMART A=!%08x".format(aNode.toLong()))
            println("SMART write field $flipField=$flipValue -> ${a.loraSetPositionConfig(smartOff)}")

            check(b.loraAttachSerial(devB!!)) { "radio B refused to attach" }
            Thread.sleep(4_000)

            val started = System.currentTimeMillis()
            val deadline = started + minutes * 60_000
            var seen = false
            var lastMin = -1L
            while (System.currentTimeMillis() < deadline && !seen) {
                Thread.sleep(2_000)
                val node = b.loraOnlineNodeList().firstOrNull { it.num == aNode }
                if (node?.latitude != null) {
                    seen = true
                    println("SMART ✅ after %ds — lat=%.5f lon=%.5f".format(
                        (System.currentTimeMillis() - started) / 1000, node.latitude, node.longitude))
                }
                val m = (System.currentTimeMillis() - started) / 60_000
                if (m != lastMin) {
                    lastMin = m
                    println("SMART ${m}min — no position yet (B sees ${b.loraOnlineNodeList().count { it.latitude != null }} positioned nodes)")
                }
            }
            val watched = (System.currentTimeMillis() - started) / 60_000
            println("SMART RESULT after ${watched}min: " + if (seen)
                "SMART BROADCAST WAS THE CAUSE — turning it off put this node on the map"
            else
                "still nothing. Smart broadcast is NOT exonerated (the window is finite) but it is not the whole story either")
        } finally {
            // Always put the radio back the way it was found, on every path.
            original?.let {
                runCatching {
                    if (!a.loraAttached) a.loraAttachSerial(devA!!).also { Thread.sleep(5_000) }
                    println("SMART restore original config -> ${a.loraSetPositionConfig(it)}")
                    Thread.sleep(2_000)
                }.onFailure { e -> println("SMART ⚠️ RESTORE FAILED: ${e.message} — the radio still has smart broadcast OFF") }
            }
            runCatching { a.loraDetach() }; runCatching { b.loraDetach() }
            aDir.deleteRecursively(); bDir.deleteRecursively()
        }
    }
}
