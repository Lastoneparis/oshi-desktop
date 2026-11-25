package com.oshi.desktop.app

import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.scheduled.ScheduledMessage
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Identity, pairing and scheduling, against the live relay — the other half of the ledger's
 * 🟡→✅ bar (rows 0.14, 0.20, 0.22, 0.25).
 *
 * Gate: `OSHI_LIVE_IDENTITY=1`. Throwaway identities on production, canary text only.
 *
 * Each row, and what would be missed without a live run:
 *
 *  - **0.20 safety numbers.** The property that matters is SYMMETRY: both ends must compute
 *    the same string from opposite arguments. The row notes three mutually incompatible
 *    implementations have shipped, which is what an asymmetric derivation looks like after
 *    it reaches users. A unit test with one pair of fixed keys can be symmetric by accident;
 *    two independently generated real identities cannot.
 *  - **0.22 QR pairing.** A payload that parses is not a payload that WORKS. This scans A's
 *    real code on B and then makes B send to A over the relay, so the address that came out
 *    of the QR is the one the ratchet addresses.
 *  - **0.14 contacts.** The peer is recorded on first contact and survives a rebuild from
 *    disk through the sealed store.
 *  - **0.25 scheduled messages.** Due-time delivery on the real wire, with the lateness
 *    REPORTED rather than hidden — the row's stated contract.
 */
class LiveIdentityBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun live() = Assume.assumeTrue("set OSHI_LIVE_IDENTITY=1", System.getenv("OSHI_LIVE_IDENTITY") == "1")

    private fun client(name: String, dir: File, store: InMemorySecretStore): OshiClient {
        val c = OshiClient(home = dir, secretStore = store, displayName = name, log = { logs += "[$name] $it" })
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

    private fun pump(vararg cs: OshiClient) {
        for (c in cs) { runCatching { c.deviceMailbox.tick() }; runCatching { c.router.poll() } }
        Thread.sleep(700)
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
    fun identityRoundOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val aDir = Files.createTempDirectory("oshi-live-id-a-$tag").toFile()
        val bDir = Files.createTempDirectory("oshi-live-id-b-$tag").toFile()
        val aStore = InMemorySecretStore(); val bStore = InMemorySecretStore()
        val a = client("a", aDir, aStore)
        var b = client("b", bDir, bStore)

        // ---- 0.20: the same number from both ends, derived from two real identities.
        val sa = a.safetyNumber(b.address)
        val sb = b.safetyNumber(a.address)
        println("LIVE_ID safety A->B=$sa  B->A=$sb")
        assertTrue("a safety number must not be empty", sa.isNotBlank())
        assertEquals("0.20: both ends must compute the SAME safety number", sa, sb)
        // And it must actually depend on the pair, or symmetry is trivially satisfied.
        assertNotEquals("0.20: the number must depend on the peer", sa, a.safetyNumber(a.address))

        // ---- 0.22: B scans A's real QR payload and gets A's address back.
        val scan = b.scanContact(a.qrPayload())
        assertTrue("0.22: scanning A's code was refused: $scan", scan is ContactQr.Scan.Contact)
        assertEquals("0.22: the scanned address is not A", a.address, (scan as ContactQr.Scan.Contact).address)

        // ...and the scanned address is the one the ratchet can actually reach.
        val m1 = "LIVE-ID-$tag-from-b"
        assertTrue("B could not send to the scanned address",
            b.router.sendText(scan.address, m1.toByteArray(), java.util.UUID.randomUUID().toString()))
        until("0.22: the message to the SCANNED address arrives", a, b) {
            a.messages.messages(b.address).any { it.content == m1 }
        }

        // ---- 0.14: the peer is recorded, and survives a rebuild from disk.
        assertTrue("0.14: A did not record B as a contact", a.contacts.all().any { it.address == b.address })
        b = client("b2", bDir, bStore)
        assertTrue("0.14: the rebuilt client lost its contacts", b.contacts.all().any { it.address == a.address })

        // ---- 0.25: a scheduled message goes out on the real wire, lateness reported.
        val m2 = "LIVE-ID-$tag-scheduled"
        // Due well past the 5-minute warn threshold, on purpose. A message one second late
        // reports lateByMs = 0 — correctly: the runner only counts lateness ABOVE
        // `latenessWarnMs`, so it does not cry "late" over a second. Asserting on a
        // one-second delay would therefore have asserted nothing. This is 30 minutes late,
        // which is the case the row exists for: the laptop was shut when the time came.
        val lateBy = 30 * 60 * 1000L
        val dueAt = System.currentTimeMillis() - lateBy
        a.scheduled.put(
            ScheduledMessage(
                id = java.util.UUID.randomUUID().toString(), recipient = b.address, content = m2,
                scheduledAtMs = dueAt, createdAtMs = System.currentTimeMillis() - lateBy - 1_000,
            )
        )
        val run = a.scheduler.runDue()
        println("LIVE_ID scheduled: sent=${run.sent} lateByMs=${run.lateByMs} wasLate=${run.wasLate}")
        assertTrue("0.25: the due message was not sent", run.sent > 0)
        assertTrue(
            "0.25: a message 30 minutes overdue must be REPORTED late, not delivered as if on time",
            run.wasLate && run.lateByMs >= lateBy - 5_000,
        )
        until("0.25: the scheduled message reaches B", a, b) {
            b.messages.messages(a.address).any { it.content == m2 }
        }

        println("LIVE_ID OK — 0.20 symmetric, 0.22 scan→send→arrive, 0.14 contacts reload, 0.25 due delivery")
        aDir.deleteRecursively(); bDir.deleteRecursively()
    }
}
