package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Two OSHI clients, two radios, one message THROUGH THE AIR — row 0.27's deepest claim.
 *
 * What already existed: a desktop sealed an envelope and a THIRD radio watched 8 frames go
 * out (PARITY row 0.27), and `ClientWiringTest` runs a desktop→desktop loop by pulling the
 * frames back out of what the sender wrote to its socket and re-emitting them to a second
 * client. Neither is two clients talking over the air. The first proves transmission with
 * nobody listening; the second proves the codec with no radio in the middle.
 *
 * Gate: `OSHI_LIVE_RADIO_A` and `OSHI_LIVE_RADIO_B`, two serial devices on two radios in
 * range of each other.
 *
 * **The receiver NEVER POLLS THE RELAY.** `router.poll()` is not called on B, not once. So
 * if the text appears in B's store, the only path it can have taken is the radio — which is
 * the whole claim, and the thing a test that polls both lanes cannot make.
 *
 * The session is established over the relay first, deliberately: X3DH needs a prekey bundle
 * and the bundle lives on the relay, so pretending otherwise would be testing a thing this
 * client does not do. What is under test is the MESSAGE lane, not key distribution.
 */
class LiveTwoRadioBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun devices(): Pair<String, String> {
        val a = System.getenv("OSHI_LIVE_RADIO_A")
        val b = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A and OSHI_LIVE_RADIO_B", !a.isNullOrBlank() && !b.isNullOrBlank())
        Assume.assumeTrue("missing device", File(a!!).exists() && File(b!!).exists())
        return a to b
    }

    private fun client(name: String, dir: File): OshiClient {
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name,
            log = { logs += "[$name] $it" })
        var open = false
        for (attempt in 0 until 3) {
            runCatching { c.router.refreshConfig() }
            if (c.config.isEnabledCached()) { open = true; break }
            Thread.sleep(1_000)
        }
        assertTrue("$name: v2 gate closed", open)
        assertTrue("$name: bundle publish", c.router.publishBundleIfNeeded())
        return c
    }

    @Test
    fun oneMessageBetweenTwoClientsOverTheAir() {
        val (devA, devB) = devices()
        val aDir = Files.createTempDirectory("oshi-air-a").toFile()
        val bDir = Files.createTempDirectory("oshi-air-b").toFile()
        val a = client("a", aDir)
        val b = client("b", bDir)
        try {
            assertTrue("radio A refused to attach on $devA", a.loraAttachSerial(devA))
            assertTrue("radio B refused to attach on $devB", b.loraAttachSerial(devB))
            Thread.sleep(6_000)   // let both handshakes finish and the node DBs settle
            println("LIVE_AIR A node=!%08x  B node=!%08x".format(
                a.loraMyNodeNumber().toLong(), b.loraMyNodeNumber().toLong()))

            // Establish the ratchet over the relay: X3DH needs a bundle, and the bundle
            // lives there. The MESSAGE lane is what is under test, not key distribution.
            val warmup = "AIR-WARMUP-${System.currentTimeMillis()}"
            assertTrue(a.router.sendText(b.address, warmup.toByteArray(), java.util.UUID.randomUUID().toString()))
            val warmDeadline = System.currentTimeMillis() + 60_000
            var warm = false
            while (System.currentTimeMillis() < warmDeadline && !warm) {
                runCatching { b.router.poll() }
                warm = b.messages.messages(a.address).any { it.content == warmup }
                Thread.sleep(700)
            }
            assertTrue("the relay warm-up never arrived; no session to test the radio with", warm)

            // ---- FROM HERE ON, B NEVER POLLS THE RELAY. ----
            val canary = "AIR-${java.util.UUID.randomUUID().toString().take(8)}"
            val sent = a.loraSendOshiMessage(b.address, canary)
            println("LIVE_AIR loraSendOshiMessage -> $sent")
            assertTrue("A refused to put the envelope on the air", sent)

            var arrived = false
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline && !arrived) {
                Thread.sleep(1_000)
                arrived = b.messages.messages(a.address).any { it.content == canary }
            }
            if (!arrived) synchronized(logs) { logs.takeLast(60).forEach { println("LIVE_AIR_LOG $it") } }
            assertTrue(
                "the message never arrived over the air (B never polled the relay, so the radio " +
                    "was the only path it could have taken)",
                arrived,
            )

            // ---- BITCOIN OVER THE AIR. A Cashu token is ordinary message text, so if a
            // radio can carry text it can carry bearer ecash — subject to the 500-byte
            // lane, which `CashuOverLoRaTest` measures per token form. This sends a real
            // single-proof NUT-00 token and requires the RECEIVER to decode it as money,
            // not merely to receive a string that looks like one.
            val token = "cashuA" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((
                    """{"token":[{"mint":"https://8333.space:3338","proofs":[""" +
                    """{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",""" +
                    """"C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"}""" +
                    """]}],"unit":"sat"}""").toByteArray())
            val payText = "/pay ${'$'}token"
            println("LIVE_AIR cashu bytes=${payText.toByteArray(Charsets.UTF_8).size} (lane ${LoRaInbound.LORA_SAFE_UTF8_BYTES})")
            assertTrue("A refused to put the payment on the air",
                a.loraSendOshiMessage(b.address, payText))
            var paid = false
            val payDeadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < payDeadline && !paid) {
                Thread.sleep(1_000)
                paid = b.messages.messages(a.address).any { it.content == payText }
            }
            assertTrue("the payment never crossed the air", paid)
            val decoded = com.oshi.messenger.util.CashuTokens.decode(token)
            assertNotNull("the receiver must read it as MONEY, not just as text", decoded)
            println("LIVE_AIR BITCOIN OK — token crossed $devA → $devB and decoded")

            val row = b.messages.messages(a.address).first { it.content == canary }
            println("LIVE_AIR ARRIVED transport=${row.transport} from=${row.senderAddress.take(12)}…")
            assertFalse("a message that came over the air must not be labelled relay-v2",
                row.transport == "relay-v2")
            println("LIVE_AIR OK — $canary crossed from $devA to $devB over LoRa, decrypted by B")
        } finally {
            runCatching { a.loraDetach() }; runCatching { b.loraDetach() }
            aDir.deleteRecursively(); bDir.deleteRecursively()
        }
    }
}
