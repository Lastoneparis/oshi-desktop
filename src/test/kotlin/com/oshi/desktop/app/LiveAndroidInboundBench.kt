package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_ANDROID_INBOUND_BENCH_2026_10_29__ The control for every other Android bench:
 * does a PLAIN TEXT from the shipped app reach this client at all?
 *
 * Written because two inbound benches failed in a row (`LiveAndroidPollVoteBench`,
 * `LiveAndroidOfflineOnlyBench`) and the diagnosis for each depends on something neither of
 * them establishes. A ballot provably cannot arrive — it goes out over IPFS. The offline-only
 * request should be able to: `ChatViewModel.setOfflineOnly` calls
 * `messageRepository.sendTextMessage`, which has the `ControlLane.preferV2` branch.
 *
 * So the question is whether ANY Android→desktop delivery works right now, and no bench in
 * this suite answered it: every passing cross-client measurement so far has been
 * desktop→Android. Without this control, "the offline-only payload did not arrive" could
 * equally mean "nothing from Android arrives" or "this payload takes the wrong lane", and
 * those have completely different fixes.
 *
 * Send a plain message from the app while this waits.
 *
 * Gate: `OSHI_ANDROID_PEER=<the app's Identity Address>`.
 */
class LiveAndroidInboundBench {

    @Test
    fun aPlainTextFromTheShippedAndroidAppArrivesHere() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-inbound").toFile()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = dir, secretStore = InMemorySecretStore(),
            displayName = "desk-inbound", log = { logs += it },
        )
        try {
            var open = false
            for (attempt in 0 until 12) {
                runCatching { c.router.refreshConfig() }
                if (c.config.isEnabledCached()) { open = true; break }
                Thread.sleep(10_000)
            }
            assertTrue("v2 gate closed", open)
            assertTrue("bundle publish", c.router.publishBundleIfNeeded())

            // Write first so the phone has a conversation to answer in.
            assertEquals(OshiClient.SendOutcome.SENT, c.send(peer!!, "inbound control — reply to me"))
            println("INBOUND desktop address : ${c.address}")
            println("INBOUND >>> reply with ANY plain text from the Android app <<<")

            val before = c.history(peer).size
            var got = false
            val deadline = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < deadline) {
                runCatching { c.router.poll() }
                if (c.history(peer).any { !it.fromMe }) { got = true; break }
                Thread.sleep(3_000)
            }
            val inbound = c.history(peer).filter { !it.fromMe }
            println("INBOUND received        : ${inbound.map { it.content }}")
            assertTrue(
                "NOTHING from the Android app arrived in 240 s. Every passing cross-client " +
                    "measurement so far has been desktop->Android; this is the control that " +
                    "says whether the reverse works at all. logs:\n" + logs.takeLast(20).joinToString("\n"),
                got,
            )
        } finally {
            runCatching { c.close() }
            dir.deleteRecursively()
        }
    }
}
