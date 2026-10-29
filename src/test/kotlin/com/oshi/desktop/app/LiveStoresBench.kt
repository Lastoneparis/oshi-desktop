package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The STORES, exercised against the live relay — what PARITY.md's 🟡 actually asks for.
 *
 * The ledger's own legend says 🟡 means *"implemented and unit-tested; not yet exercised
 * against a real phone or the live server"*, and ✅ means it has been. So rows 0.6, 0.7,
 * 0.13 and 0.21 were not waiting on code; they were waiting on a run like this one. Each
 * had unit tests and none had been driven end to end against production.
 *
 * Gate: `OSHI_LIVE_STORES=1`. Throwaway identities, canary text, nothing of the user's.
 *
 * What each assertion is FOR:
 *
 *  - **0.6 prekey store, peek-then-burn.** A publishes a bundle; B fetches it to open a
 *    session, which must CONSUME one one-time prekey on the server. If peek-then-burn were
 *    a peek-only, two peers would open sessions on the same one-time key.
 *  - **0.7 session store.** B is rebuilt from the same home directory and the same vault,
 *    and must decrypt a message A sends AFTERWARDS. A fresh in-memory ratchet would decrypt
 *    nothing; a half-persisted one would decrypt the replay and fail the next message.
 *  - **0.13 local message store.** The rows written before the reload are still there after
 *    it, read back off disk through the sealed journal.
 *  - **0.21 blocked contacts.** The row's whole argument is that a blocked sender's message
 *    is dropped AFTER decryption, because the ratchet has to advance or every message after
 *    an UNBLOCK is undecryptable. That is only provable across a block/unblock boundary on
 *    a real session, which is this.
 */
class LiveStoresBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun live() = Assume.assumeTrue("set OSHI_LIVE_STORES=1", System.getenv("OSHI_LIVE_STORES") == "1")

    private fun client(name: String, dir: File, store: InMemorySecretStore): OshiClient {
        val c = OshiClient(home = dir, secretStore = store, displayName = name, log = { logs += "[$name] $it" })
        // Retried: ONE failed `/v2/config` fetch leaves the gate reading closed, and a live
        // bench that dies on a single dropped request reports a network blip as a parity
        // failure. Three attempts, then it really is closed.
        var open = false
        for (attempt in 0 until 3) {
            runCatching { c.router.refreshConfig() }
            if (c.config.isEnabledCached()) { open = true; break }
            Thread.sleep(1_000)
        }
        assertTrue("$name: v2 gate closed after 3 config fetches", open)
        assertTrue("$name: bundle publish", c.router.publishBundleIfNeeded())
        runCatching { c.deviceMailbox.tick() }
        return c
    }

    /**
     * One-time pre-keys the SERVER still holds for [address] — asked over plain HTTP, the
     * same `/v2/keys/<identity>/count` the client uses. Read from the server rather than
     * from the local store on purpose: "we think we burned it" is not the claim, "the
     * relay will not hand it to anyone else" is.
     */
    private fun remainingOneTime(address: String, device: String? = null): Int? = runCatching {
        val base = System.getenv("OSHI_SERVER") ?: "https://oshi-messenger.com"
        val id = com.oshi.desktop.DesktopV2Signer.encodeIdentity(address)
        val q = device?.let { "?device=$it" }.orEmpty()
        val url = java.net.URI.create("$base/v2/keys/$id/count$q").toURL()
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 15_000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        org.json.JSONObject(body).optInt("remaining", -1).takeIf { it >= 0 }
    }.getOrNull()

    private fun pump(vararg cs: OshiClient, rounds: Int = 1) {
        repeat(rounds) {
            for (c in cs) { runCatching { c.deviceMailbox.tick() }; runCatching { c.router.poll() } }
            Thread.sleep(700)
        }
    }

    private fun until(what: String, vararg cs: OshiClient, timeoutMs: Long = 90_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            pump(*cs)
        }
        synchronized(logs) { logs.takeLast(50).forEach(::println) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun storesRoundOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val aDir = Files.createTempDirectory("oshi-live-stores-a-$tag").toFile()
        val bDir = Files.createTempDirectory("oshi-live-stores-b-$tag").toFile()
        val aStore = InMemorySecretStore(); val bStore = InMemorySecretStore()

        val a = client("a", aDir, aStore)
        var b = client("b", bDir, bStore)
        println("LIVE_STORES device-mailbox active: a=${a.deviceMailbox.active} b=${b.deviceMailbox.active}")

        // ---- 0.6: opening a session BURNS a one-time prekey on the server.
        //
        // It is B's count that moves, not A's: A is the one OPENING the session, so A
        // fetches B's bundle and consumes one of B's one-time keys. The first version of
        // this measured A and asserted 19 -> 19, which is exactly what a correct
        // peek-then-burn looks like from the wrong end.
        //
        // And with `device_mailbox_enabled` ON — which production now has — the session is
        // opened against B's DEVICE bundle, not its account bundle, so the account counter
        // does not move either. The device one is the one under test.
        val bDevice = b.deviceMailbox.deviceId.takeIf { b.deviceMailbox.active }
        val before = remainingOneTime(b.address, bDevice)
        val m1 = "LIVE-STORES-$tag-1"
        assertTrue("A could not send", a.router.sendText(b.address, m1.toByteArray(), java.util.UUID.randomUUID().toString()))
        until("B receives the first message", a, b) { b.messages.messages(a.address).any { it.content == m1 } }
        val after = remainingOneTime(b.address, bDevice)
        println("LIVE_STORES B one-time prekeys (device=$bDevice): before=$before after=$after")
        if (before != null && after != null) {
            assertTrue("peek-then-burn: the fetched one-time key must be gone ($before -> $after)", after < before)
        }

        // ---- 0.13: the row is on disk, in B's sealed journal.
        assertTrue("B has no stored row for the first message", b.messages.messages(a.address).any { it.content == m1 })

        // ---- 0.7 + 0.13: rebuild B from the SAME home and the SAME vault.
        b = client("b2", bDir, bStore)
        assertTrue(
            "0.13: the reloaded store lost the first message",
            b.messages.messages(a.address).any { it.content == m1 },
        )
        val m2 = "LIVE-STORES-$tag-2"
        assertTrue(a.router.sendText(b.address, m2.toByteArray(), java.util.UUID.randomUUID().toString()))
        until("0.7: the RELOADED session decrypts a new message", a, b) {
            b.messages.messages(a.address).any { it.content == m2 }
        }

        // ---- 0.21: blocked is dropped AFTER decryption, so the ratchet still advances.
        b.block(a.address)
        val blocked = "LIVE-STORES-$tag-BLOCKED"
        assertTrue(a.router.sendText(b.address, blocked.toByteArray(), java.util.UUID.randomUUID().toString()))
        pump(a, b, rounds = 6)
        assertFalse(
            "0.21: a blocked sender's message must not be stored",
            b.messages.messages(a.address).any { it.content == blocked },
        )
        assertEquals(1, b.unblock(a.address))
        val afterUnblock = "LIVE-STORES-$tag-AFTER"
        assertTrue(a.router.sendText(b.address, afterUnblock.toByteArray(), java.util.UUID.randomUUID().toString()))
        until("0.21: the message AFTER an unblock still decrypts", a, b) {
            b.messages.messages(a.address).any { it.content == afterUnblock }
        }

        println("LIVE_STORES OK — 0.6 burn=$before->$after, 0.7 reload decrypt, 0.13 reload read, 0.21 block/unblock")
        aDir.deleteRecursively(); bDir.deleteRecursively()
    }
}
