package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_ANDROID_PIN_BENCH_2026_10_29__ A 1:1 PIN from the SHIPPED Android app, applied
 * by this client.
 *
 * Everything about the pin shipped earlier today was proved desktop-to-desktop, and that is
 * the weaker claim this repository keeps warning about: two copies of one client agree on a
 * wrong format as readily as a right one. The bytes were pinned against hand-written vectors,
 * which is better, and still not a second implementation answering.
 *
 * This is the second implementation answering. It needs the Android app open on a device or
 * emulator, with a conversation with this bench's throwaway identity.
 *
 * Gate: `OSHI_ANDROID_PEER=<the app's Identity Address>`, copied from
 * Settings -> Account -> Identity Details.
 *
 * ## What the assertion means
 *
 * `pinnedMessages.isPinned` is set by ONE thing on this client: a
 * `🔧ACTION🔧{"actionType":"pinMessage",...}` arriving over V2 and naming a message in that
 * conversation. There is no other writer, and before today's fix there was no reader at all —
 * the payload reached `ControlEvent.Foreign`, fell to step 7 of `handleV2Inbound` and was
 * logged and dropped. So a pass means the shipped Android emitter and this client's new
 * receiver agree, on the wire, in production.
 *
 * ## Run it like this
 *
 *   1. start the bench; it prints its own address and the message id it sent
 *   2. in the Android app, open that conversation, long-press the message, choose Pin
 *   3. the bench polls for 120 s and asserts the pin landed
 */
class LiveAndroidPinBench {

    @Test
    fun androidPinsAMessageAndThisClientRecordsIt() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-pin").toFile()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = dir,
            secretStore = InMemorySecretStore(),
            displayName = "desk-pin-bench",
            log = { logs += it },
        )
        try {
            // PATIENT ON PURPOSE. Measured against production on 2026-10-29: two of three
            // `GET /v2/config` calls timed out at 30 s and the third answered in 19 s. A
            // five-attempt loop with 2 s gaps gave up long before the relay answered, and
            // reported it as "v2 gate closed" — which reads as a rollout decision rather
            // than a slow server, and is exactly the wrong thing to tell whoever runs this.
            var open = false
            for (attempt in 0 until 12) {
                runCatching { c.router.refreshConfig() }
                if (c.config.isEnabledCached()) { open = true; break }
                println("PIN-BENCH gate attempt ${attempt + 1}: relay has not answered yet")
                Thread.sleep(10_000)
            }
            assertTrue("v2 gate still closed after 12 attempts — the relay is unreachable or very slow", open)
            assertTrue("bundle publish", c.router.publishBundleIfNeeded())

            println("PIN-BENCH desktop address : ${c.address}")
            println("PIN-BENCH android peer    : $peer")

            val outcome = c.send(peer!!, "pin me — desktop pin bench")
            println("PIN-BENCH send outcome    : $outcome")
            assertTrue("the message did not leave", outcome == OshiClient.SendOutcome.SENT)
            val sent = c.messages.messages(peer).last()
            println("PIN-BENCH message id      : ${sent.id}")
            println("PIN-BENCH >>> now pin that message in the Android app <<<")

            // Poll for the pin. The relay was measured at 7-20 s per call earlier today, so
            // the window is generous; it exits as soon as the pin lands.
            var pinned = false
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                runCatching { c.router.poll() }
                if (c.pinnedMessages.get(peer) != null) { pinned = true; break }
                Thread.sleep(2_000)
            }
            val got = c.pinnedMessages.get(peer)
            println("PIN-BENCH pinned id       : $got")
            println("PIN-BENCH matches sent    : ${got?.equals(sent.id, ignoreCase = true)}")
            assertTrue(
                "no pinMessage arrived from the Android app in 120 s — " +
                    "logs:\n" + logs.takeLast(25).joinToString("\n"),
                pinned,
            )
            assertTrue(
                "a pin arrived but named a different message: got=$got sent=${sent.id}",
                got!!.equals(sent.id, ignoreCase = true),
            )
        } finally {
            runCatching { c.close() }
            dir.deleteRecursively()
        }
    }
}
