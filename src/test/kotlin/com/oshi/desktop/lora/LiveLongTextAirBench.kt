package com.oshi.desktop.lora

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __LORA_LONG_TEXT_OMP_2026_11_18__ on REAL radios: a 3,000-character text, sealed by one desktop
 * client, crosses the air through two OSHI Mesh radios and is decrypted by a second client — in
 * both directions. Same discipline as [LiveTwoRadioBench]: the session is set up over the relay
 * (X3DH needs the bundle), then NEITHER client polls the relay again, so the radio is the only
 * path the long text can take.
 *
 * Gate: `OSHI_LIVE_RADIO_A`, `OSHI_LIVE_RADIO_B` (serial devices of two OSHI Mesh radios in range).
 * Optional `OSHI_LONGTEXT_CHARS` (default 3000). Airtime: the firmware enforces the region's duty
 * cycle (EU_868 10 %); a held message shows up as `queued` in the log, it is not an error.
 */
class LiveLongTextAirBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun client(name: String, dir: File): OshiClient {
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name,
            log = { line -> logs += "[$name] $line"; if (!line.contains("poll")) println("LIVE_LT [$name] $line") })
        c.onLoRa = { line -> logs += "[$name] $line"; println("LIVE_LT_RADIO [$name] $line") }
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

    private fun longText(chars: Int, tag: String): String {
        val words = ("Lorem ipsum dolor sit amet, café déjà vu — OSHI Mesh long message test. " +
            "Le message entier doit arriver d'un seul bloc, sans être coupé. ").split(" ")
        val sb = StringBuilder("$tag ")
        var i = 0
        while (sb.length < chars) { sb.append(words[i % words.size]).append(if (i % 37 == 36) "\n" else " "); i++ }
        return sb.substring(0, chars)
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) { if (cond()) return true; Thread.sleep(1_000) }
        return cond()
    }

    private fun oneWay(from: OshiClient, to: OshiClient, label: String, chars: Int) {
        val text = longText(chars, "LT-$label-${java.util.UUID.randomUUID().toString().take(8)}")
        val bytes = text.toByteArray(Charsets.UTF_8).size
        println("LIVE_LT $label sending ${text.length} chars / $bytes UTF-8 bytes")
        val t0 = System.currentTimeMillis()
        assertTrue("$label: sender refused to put the long text on the air", from.loraSendOshiMessage(to.address, text))
        val arrived = waitFor(900_000) { to.messages.messages(from.address).any { it.content == text } }
        val dt = (System.currentTimeMillis() - t0) / 1000.0
        if (!arrived) synchronized(logs) { logs.takeLast(80).forEach { println("LIVE_LT_LOG $it") } }
        assertTrue("$label: the long text never arrived over the air (relay never polled)", arrived)
        val row = to.messages.messages(from.address).first { it.content == text }
        assertEquals("$label: arrived as ONE message, byte for byte", text, row.content)
        println("LIVE_LT $label ARRIVED in ${"%.1f".format(dt)} s, ${text.length} chars, transport=${row.transport}")
        // Give the end-to-end receipt a chance to come back before the next transfer.
        waitFor(120_000) { synchronized(logs) { logs.any { it.contains("delivered") && it.startsWith("[${if (label.startsWith("A")) "a" else "b"}]") } } }
    }

    @Test
    fun threeThousandCharactersBothWaysOverTheAir() {
        val devA = System.getenv("OSHI_LIVE_RADIO_A")
        val devB = System.getenv("OSHI_LIVE_RADIO_B")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A and OSHI_LIVE_RADIO_B", !devA.isNullOrBlank() && !devB.isNullOrBlank())
        Assume.assumeTrue("missing device", File(devA!!).exists() && File(devB!!).exists())
        val chars = System.getenv("OSHI_LONGTEXT_CHARS")?.toIntOrNull() ?: 3000

        val aDir = Files.createTempDirectory("oshi-lt-a").toFile()
        val bDir = Files.createTempDirectory("oshi-lt-b").toFile()
        val a = client("a", aDir)
        val b = client("b", bDir)
        try {
            assertTrue("radio A refused to attach on $devA", a.loraAttachSerial(devA))
            assertTrue("radio B refused to attach on $devB", b.loraAttachSerial(devB))
            assertTrue("A: OSHI Mesh probe never answered", waitFor(45_000) { a.loraOshiFirmware })
            assertTrue("B: OSHI Mesh probe never answered", waitFor(45_000) { b.loraOshiFirmware })
            println("LIVE_LT A node=!%08x  B node=!%08x".format(a.loraMyNodeNumber().toLong(), b.loraMyNodeNumber().toLong()))

            val warmup = "LT-WARMUP-${System.currentTimeMillis()}"
            assertTrue(a.router.sendText(b.address, warmup.toByteArray(), java.util.UUID.randomUUID().toString()))
            assertTrue("relay warm-up A->B", waitFor(60_000) { runCatching { b.router.poll() }; b.messages.messages(a.address).any { it.content == warmup } })
            val warmBack = "LT-WARMBACK-${System.currentTimeMillis()}"
            assertTrue(b.router.sendText(a.address, warmBack.toByteArray(), java.util.UUID.randomUUID().toString()))
            assertTrue("relay warm-up B->A", waitFor(60_000) { runCatching { a.router.poll() }; a.messages.messages(b.address).any { it.content == warmBack } })

            // ---- FROM HERE ON, NOBODY POLLS THE RELAY. ----
            oneWay(a, b, "A->B", chars)
            oneWay(b, a, "B->A", chars)
            println("LIVE_LT OK — $chars characters crossed $devA <-> $devB over LoRa, both ways")
        } finally {
            runCatching { a.loraDetach() }; runCatching { b.loraDetach() }
            aDir.deleteRecursively(); bDir.deleteRecursively()
        }
    }
}
