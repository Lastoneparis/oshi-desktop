package com.oshi.desktop.ui.state

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The WINDOW's own state layer, driven against the live relay — PARITY row 1.2.
 *
 * Row 1.2 is 🟡, and by this ledger's legend that means "implemented and unit-tested; not
 * yet exercised against a real phone or the live server". Its 23 unit tests all drive the
 * model against a local client. Nothing had ever run the shell end to end on production.
 *
 * This does exactly what a person does with a mouse, through the same entry points the
 * composables call and no others:
 *
 *     startConversation(<the other side's code>)   ← paste/scan, then "Open conversation"
 *     select(id)                                    ← click the row
 *     draft("…"); send()                            ← type, press enter
 *
 * and then asserts the message appears in the RECEIVER's `ThreadView.messages` — the list
 * the bubbles are actually drawn from — not in the client's store underneath it. The gap
 * between those two is exactly where a shell can be wrong while the protocol is right.
 *
 * Gate: `OSHI_LIVE_SHELL=1`. Throwaway identities, canary text.
 */
class LiveShellBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private fun live() = Assume.assumeTrue("set OSHI_LIVE_SHELL=1", System.getenv("OSHI_LIVE_SHELL") == "1")

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
    fun shellRoundOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val aDir = Files.createTempDirectory("oshi-live-shell-a-$tag").toFile()
        val bDir = Files.createTempDirectory("oshi-live-shell-b-$tag").toFile()
        val a = client("a", aDir, InMemorySecretStore())
        val b = client("b", bDir, InMemorySecretStore())

        ChatShellModel(a).use { am ->
            ChatShellModel(b).use { bm ->
                // `ChatShellModel` starts on `ShellState.empty`, whose `gateOpen` is false,
                // and fills in on the first publish — which the window triggers before it
                // draws. Reading `state` straight after the constructor therefore reads the
                // placeholder, not the client; the first version of this test asserted on it
                // and "failed" against a perfectly healthy gate.
                am.refresh(); bm.refresh()

                // The window is driven by the REAL client: its own state must agree with it.
                assertEquals(a.address, am.state.selfAddress)
                assertTrue("the shell reports the v2 gate closed", am.state.gateOpen)

                // 1. Paste B's code and open the conversation — the "New" screen's two controls.
                am.startConversation(b.qrShareText())
                am.refresh()

                // Pasting a code SELECTS the new conversation and opens its thread. It does
                // NOT put a row in the conversation list, and that is correct rather than a
                // miss: the list is built from conversations that have messages, so a
                // contact you have just added and not written to has nothing to list yet.
                // The first version of this test asserted the row and timed out against a
                // shell that had done exactly the right thing — `notice` said "Added …
                // Nothing was sent and the relay was not told", `selectedId` was already B.
                assertEquals("pasting a code must select that conversation", b.address, am.state.selectedId)
                assertNotNull("no thread opened for the pasted code", am.state.thread)

                // 2. Type and send.
                val text = "LIVE-SHELL-$tag"
                am.draft(text)
                assertEquals("the draft is not in the state the composer draws from", text, am.state.draft)
                am.send()

                // 3. The sender's OWN thread shows it, and the draft is cleared.
                until("the sent message appears in A's thread", a, b, timeoutMs = 30_000) {
                    am.refresh(); am.state.thread?.messages?.any { it.body == text } == true
                }
                assertEquals("sending must clear the composer", "", am.state.draft)
                // ...and NOW it is in the list, because the conversation has something in it.
                assertTrue(
                    "a conversation with a message must appear in the list",
                    am.state.conversations.any { it.id == b.address },
                )

                // 4. The RECEIVER's thread — the list the bubbles are drawn from.
                until("the message reaches B's SHELL, not just B's store", a, b) {
                    bm.refresh()
                    bm.select(a.address)
                    bm.state.thread?.messages?.any { it.body == text } == true
                }

                // 5. And B can answer through the same path.
                val reply = "LIVE-SHELL-$tag-reply"
                bm.draft(reply); bm.send()
                until("B's reply reaches A's shell", a, b) {
                    am.refresh(); am.state.thread?.messages?.any { it.body == reply } == true
                }
                println("LIVE_SHELL OK — paste, open, type, send, receive and reply, all through ChatShellModel")
            }
        }
        aDir.deleteRecursively(); bDir.deleteRecursively()
    }
}
