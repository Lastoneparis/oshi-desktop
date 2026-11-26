package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.MediaType
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * A LOCATION SHARE from an Android client reaching this one — PARITY row 0.19.
 *
 * Row 0.19: *"RECEIVE-FROM-ANDROID IS DEAD TOO, measured 2026-08-27: a handset shared its
 * position at a real desktop and the desktop got nothing … the send path has NO
 * `v2MessageRouter` branch at all"*, with this client's codecs *"still right and still
 * byte-tested"* and nothing to feed them. Same root cause as receipts, same fix
 * (`ControlLane.preferV2`), different call site — and "same mechanism" is exactly the kind
 * of reasoning this repository keeps disproving, so it gets its own run.
 *
 * Two phases, because the Android side has to be driven by hand:
 *
 *   `OSHI_LOCATION_PHASE=greet`  — create/load a desktop identity at `OSHI_LOCATION_HOME`,
 *                                  print its address, and send a greeting so the Android
 *                                  app creates the contact by TOFU (no key pasting).
 *   `OSHI_LOCATION_PHASE=await`  — reload that SAME identity and wait for a LOCATION row.
 *
 * The identity lives in a fixed directory for exactly that reason: a throwaway home would
 * take the ratchet with it between the two phases.
 */
class LiveAndroidLocationBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    /**
     * A file-backed secret store, because this bench runs in TWO JVMs.
     *
     * `InMemorySecretStore` holds the vault's master key in a HashMap, so the second phase
     * opened a vault whose key had died with the first process:
     * *"the master key is missing from in-memory (NOT PERSISTENT), but the vault exists"* —
     * the store saying exactly what it is, while I used it as if it were not. The real
     * client uses the macOS Keychain here; a test has no business writing there, so this
     * keeps the key beside the vault it belongs to, under the scratch directory that holds
     * the throwaway identity anyway.
     */
    private class FileSecretStore(private val file: File) : com.oshi.desktop.store.SecretStore {
        override val id = "file (TEST ONLY): ${file.name}"
        private fun load(): MutableMap<String, String> =
            if (!file.isFile) mutableMapOf()
            else file.readLines().filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
        private fun save(m: Map<String, String>) {
            file.parentFile?.mkdirs()
            file.writeText(m.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
        override fun get(account: String): ByteArray? =
            load()[account]?.let { java.util.Base64.getDecoder().decode(it) }
        override fun put(account: String, secret: ByteArray) {
            val m = load(); m[account] = java.util.Base64.getEncoder().encodeToString(secret); save(m)
        }
        override fun delete(account: String) { val m = load(); m.remove(account); save(m) }
    }

    private val store by lazy { FileSecretStore(File(home(), "test-secrets.txt")) }

    private fun home(): File {
        val h = System.getenv("OSHI_LOCATION_HOME")
        Assume.assumeTrue("set OSHI_LOCATION_HOME", !h.isNullOrBlank())
        return File(h!!).apply { mkdirs() }
    }

    private fun client(): OshiClient {
        val c = OshiClient(home = home(), secretStore = store, displayName = "desk-loc",
            log = { logs += it })
        var open = false
        for (attempt in 0 until 3) {
            runCatching { c.router.refreshConfig() }
            if (c.config.isEnabledCached()) { open = true; break }
            Thread.sleep(1_000)
        }
        assertTrue("v2 gate closed", open)
        assertTrue("bundle publish", c.router.publishBundleIfNeeded())
        return c
    }

    @Test
    fun androidLocationReachesTheDesktop() {
        val phase = System.getenv("OSHI_LOCATION_PHASE")
        Assume.assumeTrue("set OSHI_LOCATION_PHASE=greet|await", phase in setOf("greet", "await"))
        val peerEnv = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER", !peerEnv.isNullOrBlank())
        val peer: String = peerEnv!!

        val c = client()
        println("LIVE_LOC desktop address: ${c.address}")

        if (phase == "greet") {
            val outcome = c.send(peer, "LOC-GREET ${System.currentTimeMillis()}")
            println("LIVE_LOC greeting -> $outcome")
            assertTrue("the greeting did not leave", outcome == OshiClient.SendOutcome.SENT)
            return
        }

        // await: the Android side is being driven to share a location right now.
        val deadline = System.currentTimeMillis() + 240_000
        var row: com.oshi.desktop.store.Message? = null
        while (System.currentTimeMillis() < deadline && row == null) {
            runCatching { c.router.poll() }
            // NOT `mediaType == LOCATION`. The desktop stores an incoming location as a
            // TEXT row whose content is the RENDERED summary — `LocationPayload` turns
            // `📍LOCATION📍{json}` into "📍 Location: <place>" (LocationPayload.kt:190) —
            // because row 0.19 says location "rides row 0.18's dispatch: no second
            // catalog, no second router". My first version asserted on the media type and
            // failed against a row that had arrived, parsed and resolved correctly.
            row = c.messages.messages(peer).firstOrNull {
                !it.fromMe && it.content.orEmpty().startsWith("📍 Location")
            }
            Thread.sleep(1_500)
        }
        if (row == null) {
            println("LIVE_LOC rows seen: " + c.messages.messages(peer).map { "${it.mediaType}:${it.content?.take(40)}" })
            synchronized(logs) { logs.takeLast(30).forEach { println("LIVE_LOC_LOG $it") } }
        }
        assertTrue(
            "no LOCATION row arrived from Android in 4 minutes. Before ControlLane.preferV2 " +
                "reached the location send this was structurally impossible (row 0.19).",
            row != null,
        )
        // Tied to the coordinates actually injected with `adb emu geo fix 6.1432 46.2044`,
        // so this cannot pass on some other location that happened to be lying around.
        assertTrue(
            "the row arrived but is not the position that was shared: ${row!!.content}",
            row!!.content.orEmpty().contains("Genè") || row!!.content.orEmpty().contains("Gene"),
        )
        println("LIVE_LOC OK — location from Android: ${row!!.content?.take(80)}")
    }
}
