package com.oshi.desktop.app

import com.oshi.desktop.crypto.SafetyNumber
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * Contacts, safety numbers and blocking, against the live relay — PARITY row 1.3.
 *
 * Row 1.3 is the **More** pane's substance: every contact with its full sixty-digit safety
 * number, verification of a pasted `SAFETY:v1:` payload *by exact equality with the two
 * live identity keys*, and block/unblock with the blocked peer withheld from the
 * conversation list. All of that was unit-tested against a local client and none of it had
 * ever run against two real identities on the production relay.
 *
 * The verification is the part worth a live run. `SafetyNumber.verify` compares the SCANNED
 * payload to the one derived from the two keys — iOS `SafetyNumber.swift:41-43` — and the
 * row above it records that three mutually incompatible implementations have shipped. A
 * fixture can agree with itself; two independently generated identities on a live relay
 * cannot.
 *
 * Gate: `OSHI_LIVE_CONTACTS=1`. Throwaway identities.
 */
class LiveContactsBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun live() = Assume.assumeTrue("set OSHI_LIVE_CONTACTS=1", System.getenv("OSHI_LIVE_CONTACTS") == "1")

    private fun client(name: String): OshiClient {
        val dir = Files.createTempDirectory("oshi-live-contacts-$name").toFile()
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

    private fun pump(vararg cs: OshiClient) {
        for (c in cs) { runCatching { c.router.poll() } }
        Thread.sleep(700)
    }

    @Test
    fun contactsAndSafetyNumbersOnProduction() {
        live()
        val a = client("a"); val b = client("b")
        val tag = java.util.UUID.randomUUID().toString().take(8)

        // A real exchange, so each side records the other the way a user's would.
        val hello = "CONTACTS-$tag"
        assertTrue(a.router.sendText(b.address, hello.toByteArray(), java.util.UUID.randomUUID().toString()))
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline &&
            b.messages.messages(a.address).none { it.content == hello }) pump(a, b)
        assertTrue("the greeting never arrived", b.messages.messages(a.address).any { it.content == hello })

        // ...and a reply, because a contact is recorded on RECEIVE (TOFU), not on send.
        // The first version asserted that A knew B straight after A had sent to B, and
        // failed against correct behaviour: adding someone you have written to, before
        // they answer, is a decision this client leaves to the user (row 1.3: "Adding is
        // local: no message, nothing published, the peer is not told").
        val back = "CONTACTS-BACK-$tag"
        assertTrue(b.router.sendText(a.address, back.toByteArray(), java.util.UUID.randomUUID().toString()))
        val d2 = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < d2 &&
            a.messages.messages(b.address).none { it.content == back }) pump(a, b)
        assertTrue("the reply never arrived", a.messages.messages(b.address).any { it.content == back })

        // ---- the sixty digits, from two live identities, identical from both ends.
        val sa = a.safetyNumber(b.address)
        assertEquals("a safety number is sixty digits in twelve groups of five",
            12, sa.trim().split(Regex("\\s+")).size)
        assertEquals("both ends must derive the same number", sa, b.safetyNumber(a.address))

        // ---- the SAFETY:v1: payload verifies by EXACT EQUALITY with the two live keys.
        val payload = SafetyNumber.qrPayload(a.address, b.address)
        assertTrue("A must accept the payload for B", a.verifySafetyNumber(b.address, payload))
        assertTrue("B must accept the same payload for A", b.verifySafetyNumber(a.address, payload))

        // ...and must refuse one built for a different pair. This is the assertion that
        // separates "verified" from "always says yes" — the failure mode the row's three
        // shipped implementations are a warning about.
        val c = client("c")
        val wrong = SafetyNumber.qrPayload(a.address, c.address)
        assertFalse("A must REFUSE a payload built for a different peer",
            a.verifySafetyNumber(b.address, wrong))
        assertFalse("a truncated payload must not verify",
            a.verifySafetyNumber(b.address, payload.dropLast(4)))

        // ---- block withholds the peer from the conversation list; unblock restores it.
        assertTrue("B should be known to A after a real exchange",
            a.contacts.all().any { it.address == b.address })
        val visibleBefore = a.conversations().size
        a.block(b.address)
        assertTrue("a blocked peer must be hidden from the visible contacts",
            a.contacts.visible().none { it.address == b.address })
        assertTrue("...but never deleted — it must still be in all()",
            a.contacts.all().any { it.address == b.address })
        assertTrue("a blocked peer's conversation must be withheld from the list",
            a.conversations().size < visibleBefore || a.conversations().none { it.conversationId == b.address })
        assertEquals(1, a.unblock(b.address))
        assertTrue("unblocking must bring the contact back",
            a.contacts.visible().any { it.address == b.address })

        println("LIVE_CONTACTS OK — $sa")
    }
}
