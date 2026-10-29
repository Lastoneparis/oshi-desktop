package com.oshi.desktop.call

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.SecretStore
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * __LIVE_CALL_TO_A_PHONE_2026_10_28__ This client DIALLING an Android peer — PARITY row
 * 2.1's standing gap, and the counterfactual Anatole's N12 fix still needs.
 *
 * Row 2.1 has read "desktop↔desktop measured live; NEVER against a phone" all along. This
 * is that call. It also answers a second question at the same time: the N12 fix gates
 * `setShowWhenLocked`/`setTurnScreenOn` on an `incoming_call` extra, and launching
 * MainActivity with `am start --ez incoming_call true` is NOT the path a real call takes —
 * a real one arrives through a notification's full-screen intent. Only an actual incoming
 * call exercises it, so only an actual incoming call can show that calls still break
 * through a locked screen.
 *
 * The identity lives at `OSHI_CALL_HOME`, a fixed directory, for the reason
 * `LiveAndroidLocationBench` gives: a throwaway home takes the ratchet with it, and the
 * peer would have to re-TOFU on every run.
 *
 * It RINGS and holds. It does not assert that the phone answered — nobody is there to
 * answer it. What it asserts is that the offer left this client and the lane called it
 * ringing; what happens on the handset's screen is read off the handset, with a camera or
 * `adb exec-out screencap`, because that is where the claim lives.
 */
class LiveCallToPhoneBench {

    /** File-backed, because the identity has to outlive the JVM between runs. */
    private class FileSecretStore(private val file: File) : SecretStore {
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

    @Test
    fun ringAPhoneFromThisClient() {
        val homePath = System.getenv("OSHI_CALL_HOME")
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_CALL_HOME and OSHI_ANDROID_PEER",
            !homePath.isNullOrBlank() && !peer.isNullOrBlank())
        val ringSeconds = System.getenv("OSHI_RING_SECONDS")?.toLongOrNull() ?: 60L

        val home = File(homePath!!).apply { mkdirs() }
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = home,
            secretStore = FileSecretStore(File(home, "test-secrets.txt")),
            displayName = "desk-caller",
            // Printed, not just collected: the FIRST version of this bench failed on
            // "v2 gate open=false" and the gate's own reason — it logs one, precisely —
            // went into a list that is only dumped on a different code path. A diagnostic
            // you cannot read is not a diagnostic.
            log = { logs += it; if (it.contains("v2 gate")) println("CALL_GATE $it") },
        )

        var open = false
        for (attempt in 0 until 5) {
            runCatching { c.router.refreshConfig() }
            if (c.config.isEnabledCached()) { open = true; break }
            Thread.sleep(2_000)
        }
        println("CALL v2 gate open=$open   my address=${c.address}")
        assertTrue("the v2 gate is closed — /v2/config said no", open)
        assertTrue("bundle publish", c.router.publishBundleIfNeeded())

        // A greeting first, so the handset creates the contact by TOFU and the call is not
        // the first thing it has ever heard from this address.
        val greet = "CALL-GREET ${System.currentTimeMillis()}"
        println("CALL greeting -> ${c.send(peer!!, greet)}")
        Thread.sleep(4_000)

        println("CALL dialling ${peer.take(12)}…")
        when (val d = c.calls.call(peer)) {
            is CallLane.Dialled.Ringing -> {
                println("CALL ✅ RINGING callId=${d.callId} delivery=${d.delivery}")
                println("CALL holding the ring for ${ringSeconds}s — look at the handset NOW")
                val until = System.currentTimeMillis() + ringSeconds * 1000
                var lastSec = -1L
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(1_000)
                    val s = (until - System.currentTimeMillis()) / 1000
                    if (s != lastSec && s % 10 == 0L) { lastSec = s; println("CALL ringing, ${s}s left") }
                }
                println("CALL hanging up")
                runCatching { c.calls.hangUp() }
            }
            is CallLane.Dialled.Refused -> {
                synchronized(logs) { logs.takeLast(25).forEach { println("CALL_LOG $it") } }
                throw AssertionError("the client refused to dial: ${d.why}")
            }
        }
    }
}
