package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __DESKTOP_LORA_BEACON_2026_10_28__ A DIAGNOSTIC, not a claim.
 *
 * `LiveBeaconAirBench` failed in a way that matters: ten position frames were written to
 * radio A, every write returned true, and radio B — which parses positions from thirty-odd
 * other nodes on this mesh perfectly well — never recorded one from A. That is not a flaky
 * test. Either our send path produces something the air does not carry, or a
 * client-originated POSITION_APP packet is not relayed at all.
 *
 * It matters beyond this client: Android and iOS shipped the same mechanism today
 * (`enqueuePacket(BROADCAST_ADDR, PORT_POSITION, payload)`), so if the mechanism is wrong,
 * their beacons are wrong too and today's commits oversold them.
 *
 * This bench changes ONE variable. The same radio sends, in the same run:
 *
 *   1. an interop TEXT broadcast — the path `LiveTwoRadioBench` already proved crosses
 *      these two radios, using the SAME packet builder;
 *   2. a POSITION broadcast.
 *
 * and reports what B saw of each. Text arriving while position does not isolates the
 * portnum, which is the only thing that differs. Neither arriving means the air was not
 * working at that moment and the earlier failure proves nothing either way — which is worth
 * knowing before anybody rewrites a send path.
 *
 * It asserts almost nothing on purpose. Its output is the finding.
 */
class LiveBeaconDiagnosisBench {

    @Test
    fun whatCrossesAndWhatDoesNot() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A and OSHI_LIVE_RADIO_B", !devA.isNullOrBlank() && !devB.isNullOrBlank())

        val aDir = Files.createTempDirectory("oshi-diag-a").toFile()
        val bDir = Files.createTempDirectory("oshi-diag-b").toFile()
        val a = OshiClient(home = aDir, secretStore = InMemorySecretStore(), displayName = "a", log = {})
        val b = OshiClient(home = bDir, secretStore = InMemorySecretStore(), displayName = "b", log = {})
        // Every FromRadio B's link hands up, counted by portnum, so "did anything from A
        // arrive at all" is answerable rather than inferred from the roster.
        val fromA = java.util.Collections.synchronizedList(ArrayList<String>())

        try {
            check(a.loraAttachSerial(devA!!)) { "radio A refused to attach" }
            check(b.loraAttachSerial(devB!!)) { "radio B refused to attach" }
            Thread.sleep(6_000)
            val aNode = a.loraMyNodeNumber()
            println("DIAG A=!%08x B=!%08x".format(aNode.toLong(), b.loraMyNodeNumber().toLong()))

            fun bSeesPositionFromA(): Boolean =
                b.loraOnlineNodeList().any { it.num == aNode && it.latitude != null }

            val nonce = (System.currentTimeMillis() % 100_000).toString()
            println("DIAG position already known to B before we send anything: ${bSeesPositionFromA()}")

            // ---- 1. TEXT, the proven path, same builder --------------------------------
            val text = "DIAG-$nonce"
            val textWrote = a.loraSendInteropText(text)
            println("DIAG text write -> $textWrote ($text)")

            var textSeen = false
            var posSeen = false
            // ---- 2. POSITION, the path under suspicion ---------------------------------
            val lat = 46.20500
            val lon = 6.10500
            val posWrote = a.loraBroadcastPosition(lat, lon)
            println("DIAG position write -> $posWrote (%.5f, %.5f)".format(lat, lon))

            // The CONTROL. `LiveTwoRadioBench` cannot serve as one today — it needs the
            // production v2 gate to establish a ratchet and that gate is closed right now
            // ("a: v2 gate closed"), which says nothing about the air. Plain interop text
            // needs no relay, no identity and no ratchet: it lands in B's `lora!<nodehex>`
            // thread. If the text crosses and the position does not, the only difference
            // left is the portnum. If NEITHER crosses, radio A is not transmitting and the
            // position result proves nothing — which is the outcome worth not mistaking
            // for a defect.
            val loraThread = "lora!%08x".format(aNode.toLong())
            fun bHasText(): Boolean =
                runCatching { b.messages.messages(loraThread).any { it.content?.contains(text) == true } }
                    .getOrDefault(false)

            // ---- 3. THE ADMIN PATH, the one the firmware actually honours -------------
            // `AdminMessage{ set_fixed_position }` tells the NODE where it is; its own
            // PositionModule then announces on its own schedule. If this crosses and the
            // broadcast above does not, the mechanism — not the frame — was the defect.
            val adminWrote = a.loraSetFixedPosition(lat, lon)
            println("DIAG admin set_fixed_position write -> $adminWrote")

            val deadline = System.currentTimeMillis() + 240_000
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() < deadline && !(textSeen && posSeen)) {
                Thread.sleep(1_000)
                if (!posSeen && bSeesPositionFromA()) {
                    posSeen = true
                    println("DIAG ✅ POSITION arrived at B after ${(System.currentTimeMillis() - startedAt) / 1000}s")
                }
                if (!textSeen && bHasText()) {
                    textSeen = true
                    println("DIAG ✅ TEXT arrived at B after ${(System.currentTimeMillis() - startedAt) / 1000}s")
                }
            }

            val view = b.loraOnlineNodeList().firstOrNull { it.num == aNode }
            println("DIAG FINAL  textWrote=$textWrote posWrote=$posWrote adminWrote=$adminWrote")
            println("DIAG FINAL  B's view of A: name='${view?.name}' lat=${view?.latitude} lon=${view?.longitude}")
            println("DIAG FINAL  TEXT      from A seen by B: $textSeen   (thread $loraThread)")
            println("DIAG FINAL  POSITION  from A seen by B: $posSeen")
            println("DIAG FINAL  VERDICT: " + when {
                textSeen && !posSeen -> "the AIR WORKS and POSITION does not — the portnum is the only difference"
                textSeen && posSeen -> "both crossed"
                !textSeen && !posSeen -> "NOTHING crossed — radio A is not transmitting; the position result proves nothing"
                else -> "position crossed but text did not — unexpected, look again"
            })
            println("DIAG FINAL  total nodes B can see with a position: " +
                b.loraOnlineNodeList().count { it.latitude != null })
        } finally {
            runCatching { a.loraDetach() }; runCatching { b.loraDetach() }
            aDir.deleteRecursively(); bDir.deleteRecursively()
        }
    }
}
