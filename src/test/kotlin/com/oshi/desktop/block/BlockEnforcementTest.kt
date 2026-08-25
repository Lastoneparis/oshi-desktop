package com.oshi.desktop.block

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.mesh.MeshMessage
import com.oshi.desktop.net.V2Inbound
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * The block gates as they are actually WIRED — PARITY.md row 0.21.
 *
 * [BlockPolicyTest] proves the policy decides correctly. That is only half a guard: a
 * policy nothing calls is a policy that does nothing, and the failure mode this row
 * exists to prevent is exactly the one Android shipped — "`blockContact()` had ZERO
 * callers, so nothing was ever persisted, and no inbound handler checked `isBlocked` — a
 * blocked contact still delivered, still rang, still notified"
 * (`MessageRepository.kt:5122-5128`).
 *
 * So these drive the real client. `OshiClient.router.onMessage` and `mesh.onMessage` are
 * the exact lambdas the constructor installs, invoked here directly; nothing is
 * re-implemented and nothing is stubbed past the network, which is never reached because
 * the client is never started.
 *
 * Every peer key is a REAL 32-byte key, and every block is set under one spelling and
 * tested under another — a test that blocks and probes the same string would pass against
 * the exact map lookup this row replaced.
 */
class BlockEnforcementTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val PADDED = "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="
    private val UNPADDED = PADDED.trimEnd('=')
    private val STRANGER = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 1).toByte() })

    private fun client(): OshiClient = OshiClient(
        home = tmp.newFolder(),
        secretStore = InMemorySecretStore(),
        passphrase = null,
        // Never contacted: `start()` is not called, and every path exercised here stops
        // before the network.
        serverUrl = "http://127.0.0.1:1",
        displayName = "test",
        log = {},
    )

    /**
     * A real wall-clock millisecond. `Message` refuses anything outside
     * [2000-01-01, 2100-01-01] as an un-converted epoch (`MessageStore.kt:402`), which is
     * PARITY.md 0.18's "four different date epochs" guard doing its job on a lazy fixture.
     */
    private val TS = 1_700_000_000_000L

    private fun inbound(from: String, id: String, text: String) =
        V2Inbound(from = from, msgId = id, ts = TS, groupId = null, text = text)

    private fun mesh(from: String) = MeshMessage(
        id = "m1",
        type = "TEXT_MESSAGE",
        senderPublicKey = from,
        senderName = "them",
        recipientPublicKey = "",
        payload = "opaque",
        timestamp = TS.toDouble(),
        hopCount = 0,
        maxHops = 5,
        seenBy = emptyList(),
        platform = "android",
    )

    // ────────────────────────────────────────────── inbound, after decryption

    @Test
    fun `a blocked sender's decrypted message is never stored or surfaced`() {
        client().use { c ->
            c.contacts.seen(PADDED, 1_000L)
            c.contacts.block(PADDED)
            var surfaced = 0
            c.onMessage = { surfaced++ }

            // Blocked under the padded spelling, arriving under the unpadded one — which
            // is how a block gets walked past when the comparison is exact.
            c.router.onMessage(inbound(UNPADDED, "a1", "hello"))

            assertEquals("nothing surfaced", 0, surfaced)
            assertTrue("nothing stored under either spelling", c.history(UNPADDED).isEmpty())
            assertTrue(c.history(PADDED).isEmpty())
            assertTrue("no conversation created", c.conversationsIncludingBlocked().isEmpty())
        }
    }

    @Test
    fun `an unblocked sender is delivered normally`() {
        client().use { c ->
            var surfaced = 0
            c.onMessage = { surfaced++ }
            c.router.onMessage(inbound(STRANGER, "a2", "hello"))

            assertEquals(1, surfaced)
            assertEquals(1, c.history(STRANGER).size)
            assertEquals("hello", c.history(STRANGER).single().content)
        }
    }

    /**
     * Blocking is reversible, and reversing it must give back a working conversation —
     * which is only true because the block is applied AFTER decryption, so the ratchet
     * stayed in step while the messages were being discarded.
     */
    @Test
    fun `history from before a block survives it and the thread comes back on unblock`() {
        client().use { c ->
            c.router.onMessage(inbound(PADDED, "a1", "before"))
            assertEquals(1, c.history(PADDED).size)

            c.contacts.block(PADDED)
            c.router.onMessage(inbound(UNPADDED, "a2", "during"))
            assertEquals("the blocked message is not added", 1, c.history(PADDED).size)
            assertTrue("and the thread is not offered", c.conversations().isEmpty())

            BlockPolicy.unblockEverySpelling(c.contacts, UNPADDED)
            assertEquals("the earlier history is intact", 1, c.history(PADDED).size)
            assertEquals(listOf(PADDED), c.conversations().map { it.conversationId })

            c.router.onMessage(inbound(PADDED, "a3", "after"))
            assertEquals(2, c.history(PADDED).size)
        }
    }

    // ────────────────────────────────────────────── conversation list

    @Test
    fun `the conversation list withholds a blocked peer while the settings view still sees it`() {
        client().use { c ->
            c.router.onMessage(inbound(PADDED, "a1", "hi"))
            c.router.onMessage(inbound(STRANGER, "a2", "hi"))
            assertEquals(2, c.conversations().size)

            c.contacts.block(PADDED)
            assertEquals(listOf(STRANGER), c.conversations().map { it.conversationId })
            assertEquals(
                "a blocked-contacts view must still be able to show it",
                2, c.conversationsIncludingBlocked().size,
            )
        }
    }

    // ────────────────────────────────────────────── outbound

    @Test
    fun `sending to a blocked peer is refused under any spelling`() {
        client().use { c ->
            c.contacts.seen(PADDED, 1_000L)
            c.contacts.block(PADDED)
            assertEquals(OshiClient.SendOutcome.BLOCKED, c.send(UNPADDED, "hi"))
            assertTrue("a refused send writes no local row either", c.history(UNPADDED).isEmpty())
            assertTrue(c.history(PADDED).isEmpty())
        }
    }

    // ────────────────────────────────────────────── mesh, before anything else

    @Test
    fun `mesh traffic from a blocked peer never reaches the traffic callback`() {
        client().use { c ->
            c.contacts.seen(PADDED, 1_000L)
            c.contacts.block(PADDED)
            val seen = ArrayList<String>()
            c.onMeshTraffic = { seen.add(it) }

            c.mesh.onMessage(mesh(UNPADDED))
            assertTrue("blocked mesh traffic must be dropped", seen.isEmpty())

            c.mesh.onMessage(mesh(STRANGER))
            assertEquals(1, seen.size)
            assertFalse(seen.single().isEmpty())
        }
    }
}
