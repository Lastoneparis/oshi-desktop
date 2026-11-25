package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.store.InMemorySecretStore
import com.oshi.desktop.store.IdentityStore
import com.oshi.desktop.store.KeyVault
import com.oshi.desktop.store.PrekeyStore
import com.oshi.desktop.store.SessionStore
import com.oshi.messenger.network.v2.OSHICryptoV2
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two independent clients, one relay, a whole conversation — PARITY.md row 0.12.
 *
 * Every test below drives TWO routers that share nothing but the server: separate vaults,
 * separate identities, separate ratchets. That is what makes the assertions meaningful.
 * A single-router test can only prove that this code agrees with itself; the failures
 * that matter in this protocol — a first message that establishes but cannot be opened, a
 * message acked before it was delivered, a prekey handed to two people — only appear
 * between two parties.
 */
class V2RouterTest {

    private val dir: File = Files.createTempDirectory("oshi-router-test").toFile()
    private val relay = RelayServer()

    @After
    fun tearDown() {
        relay.close()
        dir.deleteRecursively()
    }

    /**
     * The OS key store survives a restart — that is its entire purpose — so a test that
     * simulates one has to keep the same store per client. Handing [Client] a fresh
     * `InMemorySecretStore` on restart models a machine whose keychain was wiped, and the
     * vault correctly refuses to open in that case. Keyed by client name so `restart()`
     * gets the same master key the first run wrote.
     */
    private val secretStores = HashMap<String, InMemorySecretStore>()

    /** One complete client: vault, identity, stores, clients, router. */
    private inner class Client(name: String) {
        val home = File(dir, name).apply { mkdirs() }
        val vault = KeyVault.open(
            File(home, KeyVault.FILE_NAME),
            secretStores.getOrPut(name) { InMemorySecretStore() },
        )
        val identity: DesktopIdentity = IdentityStore.loadOrCreate(vault)
        val prekeys = PrekeyStore(vault)
        val sessions = SessionStore(vault)
        val http = V2Http(DesktopV2Signer(identity), relay.baseUrl)
        val keys = V2KeysClient(http, identity, prekeys)
        val messages = V2MessagesClient(http)
        val config = V2ConfigGate(baseUrl = relay.baseUrl, buildNumber = 1)
        val state = RouterState(File(home, RouterState.FILE_NAME))
        /** Captured so a failing heal test can say WHY, instead of only that it failed. */
        val logs = mutableListOf<String>()
        val router = V2Router(identity, config, keys, messages, sessions, prekeys, state, log = { logs.add(it) })
        val inbox = mutableListOf<V2Inbound>()

        init {
            router.onMessage = { inbox.add(it) }
            config.refresh(identity.userKey)
        }

        val address: String get() = identity.userKey

        /** Reopen every store from disk — a restart, with nothing carried over in memory. */
        fun restart(): Client = Client(home.name)
    }

    // ------------------------------------------------------------------ the happy path

    // ================================================================ ratchet healing

    /**
     * __DESKTOP_RATCHET_HEAL_2026_10_29__ A DEAD SESSION REPAIRS ITSELF, end to end.
     *
     * The bug this answers was reported twice by real users of the phones — *"I get the
     * notification but not the message"* — and before this commit the desktop had no cure at
     * all: the retry budget condemned the envelope, the session stayed dead, and every later
     * message from that peer died the same way, for ever.
     *
     * The break is made the way it actually happens rather than by deleting a session: Bob's
     * session record is REPLACED with a valid session belonging to a third party, so Bob
     * holds a session that exists (no X3DH path to rescue him) and cannot open anything Alice
     * sends. Deleting it instead would exercise the unrelated "first contact, no header" case
     * that this client already acks and drops.
     */
    @Test
    fun `a session that can no longer decrypt is repaired without either user acting`() {
        val alice = Client("alice")
        val bob = Client("bob")
        val carol = Client("carol")
        bob.router.publishBundleIfNeeded()
        alice.router.publishBundleIfNeeded()

        // A working conversation first, so the repair is measured against a real session.
        assertTrue(alice.router.sendText(bob.address, "before".toByteArray()))
        assertEquals(1, bob.router.poll())
        assertEquals("before", bob.inbox.single().text)

        // Now break Bob: his record for Alice becomes a session with Carol's keys. Alice's
        // own session is untouched, which is the whole premise — she keeps writing with it
        // and attaches no X3DH header, because as far as she knows nothing is wrong.
        carol.router.publishBundleIfNeeded()
        assertTrue(carol.router.sendText(bob.address, "establish carol".toByteArray()))
        bob.router.poll()
        val carolRecord = bob.sessions.load(carol.address, null)!!
        bob.sessions.save(carolRecord.copy(peerUserKey = alice.address), null)
        assertNotEquals(
            "the break must leave a session in place, not remove one",
            null, bob.sessions.load(alice.address, null),
        )

        // THE TIE-BREAK MADE DETERMINISTIC, and this is not a workaround — it is the rule.
        // `RatchetHealSignal.Policy.isHealInitiator(me, peer)` is `me < peer`, so whether Bob
        // may act at once depends on how two random keys happen to sort: on half the runs he
        // is the non-initiator and must wait a full cooldown. Rather than seed identities
        // until they sort conveniently, the FIRST-EVIDENCE stamp is backdated past the
        // cooldown, which is precisely the state a non-initiator reaches when the breakage
        // persists — the case in which the rule says he SHOULD act. Without this the test
        // passes or fails on a coin toss, which is worse than not having it.
        bob.state.healEvidenceAt(
            alice.address,
            System.currentTimeMillis() - com.oshi.messenger.network.v2.RatchetHealSignal.Policy.COOLDOWN_MS - 1,
        )

        // Alice writes into the broken session. Every attempt fails, and the budget holds the
        // envelope until it condemns it — which is the moment the heal is owed.
        assertTrue(alice.router.sendText(bob.address, "lost in the dark".toByteArray()))
        val before = bob.inbox.size
        repeat(com.oshi.messenger.network.v2.V2RetryBudget.MAX_RETRIES + 2) { bob.router.poll() }
        assertEquals("the broken message must NOT have been delivered", before, bob.inbox.size)

        // THE REPAIR. Bob deleted his bad session and sent an acknowledgement whose envelope
        // carries a fresh X3DH header; Alice adopts it in the act of decrypting.
        //
        // The session is REPLACED, not absent — asserted that way because asserting `null`
        // here failed and the code was right: deleting is only the first half, and `sendText`
        // immediately re-runs X3DH (which is the only reason the header gets attached), so a
        // fresh INITIATOR session exists by the time this line runs. "Absent" would have been
        // a weaker claim anyway; "replaced, as initiator" is the state that actually heals.
        val repaired = bob.sessions.load(alice.address, null)
        assertNotEquals("no session at all; log=\n" + bob.logs.joinToString("\n"), null, repaired)
        assertEquals(
            "the broken session must be GONE, not re-used; log=\n" + bob.logs.joinToString("\n"),
            SessionStore.Role.INITIATOR, repaired!!.role,
        )
        assertTrue(
            "the heal must have been reported once",
            bob.logs.count { it.startsWith("heal: session with") } == 1,
        )
        val aliceInboxBefore = alice.inbox.size
        alice.router.poll()
        assertEquals(
            "a heal acknowledgement must never become a message for the user",
            aliceInboxBefore, alice.inbox.size,
        )

        // And the conversation works again, in both directions, with nobody having touched
        // anything. This is the assertion the whole feature exists for.
        assertTrue(alice.router.sendText(bob.address, "after the repair".toByteArray()))
        bob.router.poll()
        assertEquals("after the repair", bob.inbox.last().text)
        assertTrue(bob.router.sendText(alice.address, "and back".toByteArray()))
        alice.router.poll()
        assertEquals("and back", alice.inbox.last().text)
    }

    /**
     * A FORGERY MUST NOT DESTROY A WORKING SESSION.
     *
     * `establishResponder` throws `SecurityException` when an envelope's X3DH identity does
     * not match its `from`. If that condemnation healed, anyone able to POST a relay envelope
     * could delete a verified session at will — a denial of service handed to a stranger, and
     * worse, one that makes the victim re-run X3DH against an attacker-chosen moment.
     */
    @Test
    fun `a condemned forgery leaves the session alone`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        alice.router.sendText(bob.address, "legitimate".toByteArray())
        bob.router.poll()
        val good = bob.sessions.load(alice.address, null)
        assertNotEquals(null, good)

        // An envelope claiming to be from Alice, bootstrapped with a DIFFERENT identity's
        // X3DH header. Carol publishes and sends to Bob, and the envelope is relabelled as
        // Alice's on the relay — which is exactly the shape the responder-side TOFU refuses.
        val carol = Client("carol")
        carol.router.publishBundleIfNeeded()
        assertTrue(carol.router.sendText(bob.address, "forged".toByteArray()))
        relay.relabelSender(carol.address, alice.address, inMailboxOf = bob.address)

        // Backdated as above, so this test cannot pass merely because Bob was yielding: the
        // refusal has to come from the forgery branch, not from the cooldown.
        bob.state.healEvidenceAt(
            alice.address,
            System.currentTimeMillis() - com.oshi.messenger.network.v2.RatchetHealSignal.Policy.COOLDOWN_MS - 1,
        )
        repeat(com.oshi.messenger.network.v2.V2RetryBudget.MAX_RETRIES + 2) { bob.router.poll() }
        assertNotEquals(
            "a forgery deleted a verified session — a stranger can now break any conversation",
            null, bob.sessions.load(alice.address, null),
        )
        assertEquals("and it must never be stored as a message", 1, bob.inbox.size)
    }

    @Test
    fun `a first message establishes a session and arrives readable`() {
        val alice = Client("alice")
        val bob = Client("bob")
        assertTrue(bob.router.publishBundleIfNeeded())
        assertTrue(relay.hasBundle(bob.address))

        assertTrue("send reported failure", alice.router.sendText(bob.address, "hello bob".toByteArray()))
        assertEquals(1, bob.router.poll())
        assertEquals("hello bob", bob.inbox.single().text)
        assertEquals(alice.address, bob.inbox.single().from)
    }

    @Test
    fun `a reply comes back and both directions keep ratcheting`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        alice.router.publishBundleIfNeeded()

        alice.router.sendText(bob.address, "one".toByteArray())
        bob.router.poll()
        bob.router.sendText(alice.address, "two".toByteArray())
        alice.router.poll()
        alice.router.sendText(bob.address, "three".toByteArray())
        bob.router.poll()

        assertEquals(listOf("one", "three"), bob.inbox.map { it.text })
        assertEquals(listOf("two"), alice.inbox.map { it.text })
    }

    /**
     * The X3DH header rides message #1 and nothing else. Re-attaching it names an
     * ephemeral and a one-time prekey the responder already burned; the recomputed secret
     * differs and the conversation cannot recover.
     */
    @Test
    fun `the X3DH header is attached to the first message only`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()

        alice.router.sendText(bob.address, "first".toByteArray())
        alice.router.sendText(bob.address, "second".toByteArray())

        val pulled = bob.messages.pull(bob.address, 0)!!
        assertEquals(2, pulled.messages.size)
        assertTrue("message #1 must carry the X3DH header", pulled.messages[0].x3dh != null)
        assertTrue("message #2 must NOT carry it", pulled.messages[1].x3dh == null)

        assertEquals(2, bob.router.poll())
        assertEquals(listOf("first", "second"), bob.inbox.map { it.text })
    }

    @Test
    fun `messages sent while the peer was offline all arrive on the next poll`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()

        repeat(5) { alice.router.sendText(bob.address, "msg-$it".toByteArray()) }
        assertEquals(5, bob.router.poll())
        assertEquals((0..4).map { "msg-$it" }, bob.inbox.map { it.text })
    }

    // ------------------------------------------------------------------ the relay contract

    /** Acking is what deletes. Deliver first, then ack — and only up to what was delivered. */
    @Test
    fun `delivered messages are acked and the mailbox drains`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        alice.router.sendText(bob.address, "drain me".toByteArray())
        assertEquals(1, relay.mailboxSize(bob.address))

        bob.router.poll()
        assertEquals("the relay should have dropped the acked envelope", 0, relay.mailboxSize(bob.address))
    }

    /**
     * An envelope that cannot be decrypted must NOT be acked — the relay has to keep it —
     * and the hold must be BOUNDED, or one poisoned envelope pins the cursor forever and
     * every message behind it is re-pulled and never delivered.
     */
    @Test
    fun `an undecryptable envelope holds the ack, but only for the retry budget`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()

        // A forged envelope: right shape, no session, no X3DH header to establish from.
        relay.inject(bob.address, forged(from = alice.address, to = bob.address, msgId = "poison"))
        alice.router.sendText(bob.address, "behind the poison".toByteArray())

        // The forged one has no x3dh, so it is undecryptable-FOREVER: it is acked away and
        // the real message behind it still arrives.
        assertEquals(1, bob.router.poll())
        assertEquals("behind the poison", bob.inbox.single().text)

        // Now a genuinely transient failure: an x3dh header naming a prekey we do not
        // hold. That one must be held, and the budget must eventually let it go.
        val bogus = forged(from = alice.address, to = bob.address, msgId = "transient").put(
            "x3dh", JSONObject()
                .put("identityKey", alice.address)
                .put("ephemeralKey", b64(OSHICryptoV2.generateX25519().pub))
                .put("signedPreKeyId", "a-prekey-id-we-never-published")
        )
        relay.inject(bob.address, bogus)
        var polls = 0
        while (relay.mailboxSize(bob.address) > 0 && polls < 20) { bob.router.poll(); polls++ }
        assertTrue("the mailbox must eventually drain — an unbounded hold wedges it forever",
            relay.mailboxSize(bob.address) == 0)
        assertTrue("it must have been held for several polls, not acked on the first",
            polls >= 8)
    }

    @Test
    fun `a cursor that cannot be acked does not advance`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        alice.router.sendText(bob.address, "one".toByteArray())
        bob.router.poll()
        val after = bob.state.lastSeq()
        assertTrue("the cursor must advance on a successful ack", after > 0)

        // A second poll with nothing new must not move it.
        bob.router.poll()
        assertEquals(after, bob.state.lastSeq())
    }

    // ------------------------------------------------------------------ identity & security

    /**
     * Responder-side TOFU. The initiator's X3DH identity key must equal the envelope's
     * `from`, because the address IS the X25519 identity. Without the check, anyone who
     * can POST a relay envelope bootstraps a session under a spoofed sender and their
     * message is stored as coming from the impersonated contact.
     */
    @Test
    fun `an X3DH header claiming someone else's identity is refused`() {
        val alice = Client("alice")
        val bob = Client("bob")
        val mallory = Client("mallory")
        bob.router.publishBundleIfNeeded()

        // Mallory sends a perfectly valid first message… with `from` set to Alice.
        val bundle = mallory.keys.fetchBundle(bob.address)!!
        val init = com.oshi.messenger.network.v2.V2Session.initiator(mallory.identity.identity, bundle)
        val (header, ct) = com.oshi.messenger.network.v2.OSHIRatchetV2.encrypt(
            init.state, "I am Alice".toByteArray(), ByteArray(0))
        val envelope = JSONObject()
            .put("v", 4).put("msgId", "spoofed")
            .put("from", alice.address)                 // ← the lie
            .put("to", bob.address).put("type", "1to1")
            .put("x3dh", JSONObject()
                .put("identityKey", b64(init.x3dh.identityKey))   // …Mallory's real key
                .put("ephemeralKey", b64(init.x3dh.ephemeralKey))
                .put("signedPreKeyId", init.x3dh.signedPreKeyId)
                .apply { init.x3dh.oneTimePreKeyId?.let { put("oneTimePreKeyId", it) } })
            .put("header", b64(OSHICryptoV2.encodeHeader(header)))
            .put("ciphertext", b64(ct))
            .put("ts", System.currentTimeMillis())
        relay.inject(bob.address, envelope)

        repeat(10) { bob.router.poll() }
        assertTrue("a spoofed sender was delivered: ${bob.inbox.map { it.text }}", bob.inbox.isEmpty())
        assertFalse("no session may be created for the impersonated address", bob.sessions.has(alice.address))
    }

    /** A peer with no published bundle cannot be reached over V2 — the caller must fall back. */
    @Test
    fun `sending to a peer with no bundle reports failure rather than pretending`() {
        val alice = Client("alice")
        val bob = Client("bob")     // never publishes
        assertFalse(alice.router.ensureCapable(bob.address))
        assertFalse(alice.router.sendText(bob.address, "anyone there".toByteArray()))
    }

    /**
     * Every `GET /v2/keys/:peer` pops a one-time prekey server-side. Probing capability
     * and then sending must share ONE fetch, or each new conversation burns two of the
     * peer's pool.
     */
    @Test
    fun `starting a conversation costs exactly one bundle fetch`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        val before = relay.remainingOpks(bob.address)

        assertTrue(alice.router.ensureCapable(bob.address))
        assertTrue(alice.router.sendText(bob.address, "hi".toByteArray()))

        assertEquals("the capability probe and the send must share one bundle",
            1, relay.bundleFetches[bob.address])
        assertEquals("exactly one one-time prekey may be consumed", before - 1, relay.remainingOpks(bob.address))
    }

    @Test
    fun `a one-time prekey is never handed to two peers`() {
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        val alice = Client("alice")
        val carol = Client("carol")

        val a = alice.keys.fetchBundle(bob.address)!!
        val c = carol.keys.fetchBundle(bob.address)!!
        assertNotEquals("the same one-time prekey was served twice", a.oneTimePreKeyId, c.oneTimePreKeyId)
    }

    /** A drained pool is normal: X3DH proceeds without a one-time prekey. */
    @Test
    fun `a conversation still starts when the peer's one-time prekeys are exhausted`() {
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        val alice = Client("alice")
        // Drain the pool.
        repeat(V2Router.OPK_TARGET + 2) { alice.keys.fetchBundle(bob.address) }
        assertEquals(0, relay.remainingOpks(bob.address))

        assertTrue(alice.router.sendText(bob.address, "no opk left".toByteArray()))
        assertEquals(1, bob.router.poll())
        assertEquals("no opk left", bob.inbox.single().text)
    }

    // ------------------------------------------------------------------ persistence

    /**
     * The whole point of PARITY.md row 0.5: a restart keeps the address AND the session,
     * so a conversation continues rather than starting over.
     */
    @Test
    fun `a restarted client keeps its address and its sessions`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        alice.router.sendText(bob.address, "before the restart".toByteArray())
        bob.router.poll()

        val bobAgain = bob.restart()
        assertEquals("the address changed across a restart", bob.address, bobAgain.address)
        assertTrue("the session was lost", bobAgain.sessions.has(alice.address))

        alice.router.sendText(bob.address, "after the restart".toByteArray())
        assertEquals(1, bobAgain.router.poll())
        assertEquals("after the restart", bobAgain.inbox.single().text)
    }

    /**
     * `seen` is persisted, so a crash between delivering and acking does not resurface a
     * message as a duplicate bubble.
     */
    @Test
    fun `a message delivered but not acked is not delivered twice after a restart`() {
        val alice = Client("alice")
        val bob = Client("bob")
        bob.router.publishBundleIfNeeded()
        alice.router.sendText(bob.address, "exactly once".toByteArray())

        bob.router.poll()
        assertEquals(1, bob.inbox.size)
        // Simulate the crash: the envelope is put back in the mailbox as if the ack never
        // landed, and the client restarts with only what it persisted.
        relay.inject(bob.address, forgedCopyOf(bob, alice))

        val bobAgain = bob.restart()
        bobAgain.router.poll()
        assertTrue("a duplicate was delivered after the restart", bobAgain.inbox.isEmpty())
    }

    @Test
    fun `publishing tops up only the shortfall instead of minting a fresh batch`() {
        val bob = Client("bob")
        assertTrue(bob.router.publishBundleIfNeeded())
        assertEquals(V2Router.OPK_TARGET, relay.remainingOpks(bob.address))

        // A healthy pool: nothing new is minted.
        assertTrue(bob.router.publishBundleIfNeeded())
        assertEquals(V2Router.OPK_TARGET, relay.remainingOpks(bob.address))

        // Drain below the low-water mark, then republish: exactly the shortfall.
        val alice = Client("alice")
        repeat(V2Router.OPK_TARGET - V2Router.OPK_LOW_WATER + 1) { alice.keys.fetchBundle(bob.address) }
        val low = relay.remainingOpks(bob.address)
        assertTrue("the pool should be under the low-water mark", low < V2Router.OPK_LOW_WATER)
        bob.router.publishBundleIfNeeded()
        assertEquals(V2Router.OPK_TARGET, relay.remainingOpks(bob.address))
    }

    @Test
    fun `nothing is sent or polled while the rollout gate is closed`() {
        relay.configJson = """{"v2_enabled":false,"rollout_percent":0,"min_build":0}"""
        val alice = Client("alice")
        val bob = Client("bob")
        assertFalse("a closed gate must not publish a bundle — it would route V2 traffic here",
            bob.router.publishBundleIfNeeded())
        assertFalse(relay.hasBundle(bob.address))
        assertFalse(alice.router.sendText(bob.address, "nope".toByteArray()))
        assertEquals(0, bob.router.poll())
    }

    // ------------------------------------------------------------------ helpers

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** A structurally valid envelope whose ciphertext is noise. */
    private fun forged(from: String, to: String, msgId: String): JSONObject = JSONObject()
        .put("v", 4).put("msgId", msgId).put("from", from).put("to", to).put("type", "1to1")
        .put("header", b64(OSHICryptoV2.encodeHeader(
            OSHICryptoV2.Header(dh = ByteArray(32) { 1 }, pn = 0, n = 0))))
        .put("ciphertext", b64(ByteArray(48) { 2 }))
        .put("ts", System.currentTimeMillis())

    /** Re-inject the message Bob already received, as if his ack had been lost. */
    private fun forgedCopyOf(bob: Client, alice: Client): JSONObject {
        val msgId = bob.inbox.single().msgId
        return forged(alice.address, bob.address, msgId)
    }
}
