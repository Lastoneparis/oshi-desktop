package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_ANDROID_OFFLINE_ONLY_BENCH_2026_10_29__ The SHIPPED Android app asks this
 * conversation to leave the relay, and this client has to honour it.
 *
 * This is the privacy feature closed earlier today, and the direction that matters: a peer
 * ASKS and we comply. Before the fix the request reached `ControlEvent.Foreign`, fell to step
 * 7 of `handleV2Inbound` and was logged and dropped, while the asking phone waited for a
 * confirmation that never came.
 *
 * Unlike a poll BALLOT, this one can actually travel from Android: its own comment says it is
 * *"Sent through `MessageRepository.sendTextMessage`, like a poll and like every other
 * sentinel the catalog marks `.rendered` — NOT through `dispatchControlPayload`"*, and
 * `sendTextMessage` is the path that has the `ControlLane.preferV2` branch. That contrast is
 * the whole reason `LiveAndroidPollVoteBench` fails and this one can pass.
 *
 * Send a message from here first so the phone has a conversation, then turn on
 * "offline only" in its chat menu while this waits.
 *
 * Gate: `OSHI_ANDROID_PEER=<the app's Identity Address>`.
 */
class LiveAndroidOfflineOnlyBench {

    @Test
    fun theShippedAndroidAppTakesTheConversationOffTheRelayAndThisClientComplies() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-offline").toFile()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = dir, secretStore = InMemorySecretStore(),
            displayName = "desk-offline", log = { logs += it },
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

            val tag = System.currentTimeMillis().toString().takeLast(5)
            println("OFFLINE desktop address : ${c.address}")
            assertEquals(OshiClient.SendOutcome.SENT, c.send(peer!!, "offline-only bench $tag"))
            println("OFFLINE >>> in the Android chat menu, turn ON 'offline only' <<<")

            assertTrue("we must start unrestricted", !c.offlineOnly.isOfflineOnly(peer))
            var restricted = false
            val deadline = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < deadline) {
                runCatching { c.router.poll() }
                if (c.offlineOnly.isOfflineOnly(peer)) { restricted = true; break }
                Thread.sleep(3_000)
            }
            println("OFFLINE restricted      : $restricted")
            println("OFFLINE state           : ${c.offlineOnly.state(peer)}")
            assertTrue(
                "no OFFLINE_ONLY request arrived from the Android app in 240 s — logs:\n" +
                    logs.takeLast(20).joinToString("\n"),
                restricted,
            )
            // THE PROMISE: with it on, this client refuses the relay for that conversation.
            assertEquals(
                "the restriction was recorded but not enforced",
                OshiClient.SendOutcome.NO_V2_PATH, c.send(peer, "this must not travel"),
            )
            // And the request rendered as a readable row, never the payload.
            val row = c.history(peer).lastOrNull { it.content?.contains("offline only") == true }
            println("OFFLINE rendered row    : ${row?.content}")
            assertTrue("the request must render as a sentence", row != null)
        } finally {
            runCatching { c.close() }
            dir.deleteRecursively()
        }
    }
}
