package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * __DESKTOP_LORA_BEACON_2026_10_28__ A position beacon from THIS client, received by a
 * SECOND radio, over the air — the one claim the beacon's commit refused to make.
 *
 * That commit said plainly: *"NOT verified: a second radio actually receiving one of these
 * beacons over the air. The encoder matches an independent implementation of the spec and
 * the policy matches both phones; what is unproven is the round trip."* This closes that,
 * and nothing more — it proves the frame is built, transmitted, and parsed back into
 * coordinates by a receiver that never saw the sender's memory.
 *
 * **The receiver is a different process-level client with its own roster**, and the only
 * path between them is the radio: there is no relay in this test, no shared object, and the
 * sender's `LoRaFixedPosition` is not readable from the receiving client's roster. If
 * B reports A's coordinates, they crossed the air.
 *
 * Gate: `OSHI_LIVE_RADIO_A` and `OSHI_LIVE_RADIO_B`, two radios in range of each other —
 * the same pair `LiveTwoRadioBench` uses.
 *
 * ## Why it asserts the COORDINATES and not just "a node appeared"
 *
 * Both radios are permanently in each other's node database, so "B has heard A" is true
 * before this test runs and would pass against a beacon that never left. The assertion is
 * therefore on the latitude and longitude B parsed, against a position this run picked and
 * no previous run used — a nonce in the only field that carries one.
 */
class LiveBeaconAirBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun devices(): Pair<String, String> {
        val a = System.getenv("OSHI_LIVE_RADIO_A")
        val b = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A and OSHI_LIVE_RADIO_B", !a.isNullOrBlank() && !b.isNullOrBlank())
        Assume.assumeTrue("missing device", File(a!!).exists() && File(b!!).exists())
        return a to b
    }

    private fun client(name: String, dir: File): OshiClient =
        OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name,
            log = { logs += "[$name] $it" })

    @Test
    fun aPositionBeaconCrossesToASecondRadio() {
        val (devA, devB) = devices()
        val aDir = Files.createTempDirectory("oshi-beacon-a").toFile()
        val bDir = Files.createTempDirectory("oshi-beacon-b").toFile()
        val a = client("a", aDir)
        val b = client("b", bDir)

        // Remembered so the machine is left as it was found: this is a real preference
        // store shared with the running app, not a fixture.
        val savedPos = LoRaFixedPosition.position
        val savedOn = LoRaFixedPosition.enabled

        try {
            assertTrue("radio A refused to attach on $devA", a.loraAttachSerial(devA))
            assertTrue("radio B refused to attach on $devB", b.loraAttachSerial(devB))
            Thread.sleep(6_000)   // both handshakes, and the node DBs settle
            val aNode = a.loraMyNodeNumber()
            val bNode = b.loraMyNodeNumber()
            println("LIVE_BEACON A=!%08x  B=!%08x".format(aNode.toLong(), bNode.toLong()))
            assertTrue("A never learned its own node number", aNode != 0u)

            // A position no earlier run used, so a stale entry in B's node database cannot
            // pass this test. Jittered in the 5th decimal — ~1 m, far above the 1e-7 wire
            // resolution and far below anything that would leave Geneva.
            val jitter = (System.currentTimeMillis() % 900).toInt()
            val lat = 46.20000 + jitter * 1e-5
            val lon = 6.10000 + jitter * 1e-5
            println("LIVE_BEACON sending %.5f, %.5f".format(lat, lon))

            assertTrue("the position was refused by the store", LoRaFixedPosition.set(lat, lon))
            assertTrue("the beacon switch refused to turn on", LoRaFixedPosition.setEnabled(true))
            LoRaPositionBeacon.reset()

            // Drive the SHIPPED path, not `loraBroadcastPosition` directly: what is under
            // test is the thing the pane's tick calls, policy included.
            val decision = a.loraPositionBeaconTick()
            println("LIVE_BEACON decision=$decision")
            assertEquals(
                "the beacon refused to send with a radio attached, a position set and the switch on",
                LoRaPositionBeacon.Decision.SEND, decision,
            )

            // ---- FROM HERE, ONLY THE AIR. B polls its own roster. ----
            //
            // Re-sent on a slow loop rather than once, and that is not flakiness insurance:
            // a Meshtastic BROADCAST is never acknowledged, this channel carries 58 other
            // nodes, and one frame can simply collide. The first run of this bench asserted
            // on the policy's decision alone and learned nothing when B stayed empty —
            // `onLoRa` defaults to a no-op sink, so the absence of a log line was not the
            // absence of a send. Every write is now checked, and at least one must succeed.
            var writes = 0
            var heard: LoRaNodeRoster.Node? = null
            val deadline = System.currentTimeMillis() + 240_000
            var nextSendAt = 0L
            while (System.currentTimeMillis() < deadline && heard == null) {
                if (System.currentTimeMillis() >= nextSendAt) {
                    val wrote = a.loraBroadcastPosition(lat, lon)
                    if (wrote) writes++
                    println("LIVE_BEACON write #$writes -> $wrote")
                    // 25 s: well above a LongFast frame's airtime, and polite on a channel
                    // this busy. Nothing here should make the mesh worse to prove a point.
                    nextSendAt = System.currentTimeMillis() + 25_000
                }
                Thread.sleep(1_000)
                heard = b.loraOnlineNodeList().firstOrNull {
                    it.num == aNode && it.latitude != null && it.longitude != null
                }
            }
            assertTrue("not one position frame was written to radio A", writes > 0)
            if (heard == null) {
                println("LIVE_BEACON B heard A as: " + b.loraOnlineNodeList()
                    .filter { it.num == aNode }
                    .joinToString { "!%08x(%s lat=%s)".format(it.num.toLong(), it.name, it.latitude) })
                synchronized(logs) { logs.takeLast(40).forEach { println("LIVE_BEACON_LOG $it") } }
            }
            assertNotNull(
                "no position from A arrived at B in 2 minutes. B never polled a relay and " +
                    "shares nothing with A but the air, so the radio was the only path.",
                heard,
            )

            // The coordinates, not merely the arrival. 1e-5 of a degree is ~1 m, and the
            // wire carries 1e-7, so this tolerance allows the rounding and nothing else.
            val gotLat = heard!!.latitude!!
            val gotLon = heard!!.longitude!!
            println("LIVE_BEACON ARRIVED lat=%.5f lon=%.5f (sent %.5f, %.5f)".format(gotLat, gotLon, lat, lon))
            assertTrue(
                "B received a position, but not the one A sent: got $gotLat,$gotLon want $lat,$lon",
                abs(gotLat - lat) < 1e-5 && abs(gotLon - lon) < 1e-5,
            )
            println("LIVE_BEACON OK — %.5f, %.5f crossed $devA → $devB over LoRa".format(lat, lon))
        } finally {
            runCatching { a.loraDetach() }; runCatching { b.loraDetach() }
            // Put the machine back: this bench writes the REAL preference node.
            runCatching {
                LoRaFixedPosition.clear()
                savedPos?.let { LoRaFixedPosition.set(it.first, it.second) }
                LoRaFixedPosition.setEnabled(savedOn)
            }
            aDir.deleteRecursively(); bDir.deleteRecursively()
        }
    }
}
