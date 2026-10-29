package com.oshi.desktop.app

import com.oshi.desktop.store.DeliveryStatus
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * A desktop message reaches an ANDROID client and comes back DELIVERED — PARITY rows
 * 0.18/0.19, from the side that was actually broken.
 *
 * Those rows read "RECEIVE-FROM-ANDROID IS STRUCTURALLY DEAD" and noted that the desktop's
 * receipt codecs were right and byte-tested with nothing to feed them. The cause was on the
 * Android side: `sendDeliveryReceipt`, `sendReadReceipt` and `TypingIndicatorManager` went
 * straight to `VPSClient` with no `v2MessageRouter` branch, so a peer that speaks V2 and
 * not the legacy lane — this client, deliberately (row 0.23) — got every message and never
 * a tick. `ControlLane.preferV2` fixed that; this is the run that shows it.
 *
 * Gate: `OSHI_ANDROID_PEER=<the handset's identity address>`, and an OSHI Android build
 * carrying the fix, open on screen. The address is pasted from the app's own
 * Settings → Account → Identity Details.
 *
 * What makes the assertion mean something: `DeliveryStatus.DELIVERED` on the desktop is set
 * by ONE thing, a `📬DELIVERY_RECEIPT📬<id>` arriving over V2 and matching the id we sent.
 * The desktop implements no legacy lane at all, so there is no other way for this row to
 * change — and before the fix there was no way for that receipt to arrive.
 */
class LiveAndroidReceiptBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    @Test
    fun androidAcksADesktopMessageOverV2() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-receipt").toFile()
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = "desk",
            log = { logs += it })
        try {
            var open = false
            for (attempt in 0 until 3) {
                runCatching { c.router.refreshConfig() }
                if (c.config.isEnabledCached()) { open = true; break }
                Thread.sleep(1_000)
            }
            assertTrue("v2 gate closed", open)
            assertTrue("bundle publish", c.router.publishBundleIfNeeded())

            val text = "ANDROID-ACK-${java.util.UUID.randomUUID().toString().take(8)}"
            val outcome = c.send(peer!!, text)
            println("LIVE_ANDROID sendText -> $outcome   peer=${peer.take(12)}…")

            // The row we are watching. DELIVERED can only come from a receipt over V2.
            val deadline = System.currentTimeMillis() + 180_000
            var delivered = false
            var lastStatus: DeliveryStatus? = null
            while (System.currentTimeMillis() < deadline && !delivered) {
                runCatching { c.router.poll() }
                val row = c.messages.messages(peer).firstOrNull { it.content == text && it.fromMe }
                lastStatus = row?.deliveryStatus
                delivered = row != null && row.deliveryStatus.rank >= DeliveryStatus.DELIVERED.rank
                Thread.sleep(1_500)
            }
            println("LIVE_ANDROID final status=$lastStatus")
            if (!delivered) synchronized(logs) { logs.takeLast(40).forEach { println("LIVE_ANDROID_LOG $it") } }
            assertTrue(
                "the Android client never acked over V2 — status stayed $lastStatus. Before " +
                    "ControlLane.preferV2 this was structurally impossible; if it is still " +
                    "failing, the receipt is not taking the V2 lane.",
                delivered,
            )
            println("LIVE_ANDROID OK — Android acked a desktop message over V2; status=$lastStatus")
        } finally {
            dir.deleteRecursively()
        }
    }
}
