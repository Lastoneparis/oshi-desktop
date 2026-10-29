package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_ANDROID_PIN_OUT_BENCH_2026_10_29__ This client PINS, and the SHIPPED Android app
 * is the one that has to understand it.
 *
 * The companion bench ([LiveAndroidPinBench]) drives the other direction and needs somebody
 * to long-press a bubble on the device. This one needs nothing touched on the phone: it
 * sends a message and then pins it, and the assertion is made by READING the Android app's
 * own screen afterwards — its pinned banner is drawn from `MessageRepository`'s pinned map,
 * which is written only by `addPinCallback` when a `🔧ACTION🔧{"actionType":"pinMessage"}`
 * arrives and decodes.
 *
 * So a pinned banner on the phone means the bytes this client emits are the bytes the
 * shipped Android receiver parses — the claim that no amount of desktop-to-desktop testing
 * can make.
 *
 * Gate: `OSHI_ANDROID_PEER=<the app's Identity Address>`.
 */
class LiveAndroidPinOutBench {

    @Test
    fun thisClientPinsAndTheShippedAndroidAppIsToldAboutIt() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-pin-out").toFile()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = dir, secretStore = InMemorySecretStore(),
            displayName = "desk-pin-out", log = { logs += it },
        )
        try {
            var open = false
            for (attempt in 0 until 12) {
                runCatching { c.router.refreshConfig() }
                if (c.config.isEnabledCached()) { open = true; break }
                println("PINOUT gate attempt ${attempt + 1}")
                Thread.sleep(10_000)
            }
            assertTrue("v2 gate closed", open)
            assertTrue("bundle publish", c.router.publishBundleIfNeeded())

            val marker = "PINNED-BY-DESKTOP-" + System.currentTimeMillis().toString().takeLast(5)
            println("PINOUT desktop address : ${c.address}")
            println("PINOUT marker          : $marker")
            assertEquals(OshiClient.SendOutcome.SENT, c.send(peer!!, marker))
            val sent = c.messages.messages(peer).last()
            println("PINOUT message id      : ${sent.id}")

            // Give the message time to land before pinning it: a pin naming a message the
            // peer has not stored yet is dropped by BOTH phones, which would make this
            // bench fail for a reason that has nothing to do with the format.
            Thread.sleep(20_000)

            val outcome = c.pinDirectMessage(peer, sent.id)
            println("PINOUT pin outcome     : $outcome")
            assertEquals(OshiClient.SendOutcome.SENT, outcome)
            assertEquals("our own state must hold the pin", sent.id, c.pinnedMessages.get(peer))
            println("PINOUT >>> now read the Android screen for a pinned banner <<<")
            // Keep the client alive briefly so the relay delivers before the process exits.
            Thread.sleep(25_000)
        } finally {
            runCatching { c.close() }
            dir.deleteRecursively()
        }
    }
}
