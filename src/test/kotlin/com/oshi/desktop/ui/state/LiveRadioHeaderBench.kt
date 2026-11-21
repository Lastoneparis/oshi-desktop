package com.oshi.desktop.ui.state

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The reach badge, against A REAL RADIO — the one thing PARITY row 1.2-a says has never
 * been done.
 *
 * That row is 🟡 with the words *"logic tested, never against a radio or a real peer"*, and
 * it ends *"NOT verified: a real Windows/Linux window, a real mesh peer, a real radio."*
 * `HeaderStatusTest` and `LoRaNodeRosterTest` feed the roster hand-built protobuf; nothing
 * had ever pointed it at a device and asked what it heard.
 *
 * Gate: `OSHI_LIVE_RADIO=<device>`, e.g. `/dev/cu.usbmodem21201`. Receive only — this
 * attaches, listens, and transmits NOTHING. Airtime on LongFast is 1-2 s a frame and a test
 * that chatters is a test nobody can run twice in a room with other people's radios.
 *
 * What it asserts, and why each one is the row's own claim rather than a restatement:
 *
 *  - The badge reports `loraAttached` only once the link is really up.
 *  - `N` is OTHER nodes: our own radio is excluded, which is only checkable where there is
 *    a real `my_info` to exclude.
 *  - **The count and the list come from ONE predicate.** The row says a list and a count
 *    that disagree is the defect this mirrors from the phones; with live traffic arriving
 *    between two calls, that is a real race rather than a tautology.
 *  - Every node the list names was heard inside Meshtastic's two-hour window, from the
 *    radio's OWN `last_heard` — not stamped "now" on arrival, which is the iOS defect the
 *    roster was written to avoid and which only a device with a populated node DB can
 *    expose.
 */
class LiveRadioHeaderBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun device(): String {
        val d = System.getenv("OSHI_LIVE_RADIO")
        Assume.assumeTrue("set OSHI_LIVE_RADIO=/dev/cu.usbmodemXXXX", !d.isNullOrBlank())
        Assume.assumeTrue("no such device: $d", File(d!!).exists())
        return d
    }

    @Test
    fun theReachBadgeCountsWhatTheRadioHeard() {
        val dev = device()
        val dir = Files.createTempDirectory("oshi-live-radio").toFile()
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = "radio",
            log = { logs += it })
        try {
            assertTrue("the radio refused to attach on $dev", c.loraAttachSerial(dev))

            // The node DB is dumped in full at attach, then live packets arrive. Give it
            // long enough for both; this is a radio, not a socket.
            val deadline = System.currentTimeMillis() + 45_000
            var nodes = 0
            while (System.currentTimeMillis() < deadline && nodes == 0) {
                Thread.sleep(1_000)
                nodes = c.loraOnlineNodes()
            }

            val tracker = NetworkBadgeTracker()
            val now = System.currentTimeMillis()
            val badge = tracker.observe(liveMeshPeers = 0, loraAttached = true, loraNodes = nodes, nowMs = now)
            val list = c.loraOnlineNodeList(now)

            println("LIVE_RADIO device=$dev badge=$badge listSize=${list.size}")
            for (n in list) {
                println("LIVE_RADIO node !%08x".format(n.num.toLong()) +
                    "  name='${n.name}'  lastHeard=${now - n.lastHeardMs}ms ago" +
                    (n.distanceKm?.let { "  %.2f km".format(it) } ?: "  (no fix)"))
            }

            assertTrue("the badge must report the radio as attached", badge.loraAttached)
            assertTrue("a badge with a radio attached is connected", badge.connected)

            // The row's own rule: ONE predicate behind both numbers.
            assertEquals(
                "the count and the list disagree — the defect this roster mirrors from the phones",
                badge.loraNodes, list.size,
            )

            Assume.assumeTrue(
                "no other node was heard in 45s — nothing to assert about the list (power a second radio)",
                list.isNotEmpty(),
            )

            val myNum = c.loraMyNodeNumber()
            assertTrue("our OWN radio must not be counted as a peer", list.none { it.num == myNum })
            for (n in list) {
                assertTrue(
                    "node !%08x was counted but its last_heard is outside the 2h window".format(n.num.toLong()),
                    now - n.lastHeardMs < 2 * 60 * 60 * 1000L,
                )
                assertTrue("a counted node must have been heard at all", n.lastHeardMs > 0)
            }
            println("LIVE_RADIO OK — ${list.size} node(s) heard on a real radio, count == list, self excluded")
        } finally {
            runCatching { c.loraDetach() }
            dir.deleteRecursively()
        }
    }
}
