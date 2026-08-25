package com.oshi.desktop.sync

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.store.ContactStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * The drain loop, driven end to end against a behaving sync store ([SyncServer]) — the same
 * posture `V2RouterTest` takes with `RelayServer`. Nothing here is asserted against a canned
 * response: the server assigns its own `seq`, dedupes its own `itemId`s and pages its own
 * log, so the four properties in [SyncEngine]'s doc comment are exercised rather than
 * asserted about.
 */
class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SyncServer
    private lateinit var identity: DesktopIdentity
    private lateinit var client: V2SyncClient
    private lateinit var cursor: SyncCursor
    private lateinit var key: ByteArray

    @Before
    fun setUp() {
        server = SyncServer()
        identity = DesktopIdentity.generate()
        client = V2SyncClient(V2Http(DesktopV2Signer(identity), server.baseUrl), identity.userKey)
        cursor = SyncCursor(File(tmp.newFolder(), "sync-cursor.json"))
        key = SyncCrypto.v2ArchiveKey(ByteArray(32) { 7 })
    }

    @After
    fun tearDown() = server.close()

    private fun engine(deviceId: String = "dev-1", pageLimit: Int = SyncEngine.DEFAULT_PAGE_LIMIT,
                       applyOwn: Boolean = true) =
        SyncEngine(client, key, cursor, deviceId, pageLimit, applyOwn)

    private fun collector(): Pair<MutableList<SyncProtocol.InboundRecord>, SyncEngine.Applier> {
        val seen = mutableListOf<SyncProtocol.InboundRecord>()
        return seen to SyncEngine.Applier { seen.add(it) }
    }

    // ────────────────────────────────────── round trip

    @Test
    fun `an archived record comes back decrypted through a drain`() {
        val e = engine()
        e.archive(SyncProtocol.SyncKind.CONTACT, "alice", """{"alias":"Alice"}""".toByteArray()).getOrThrow()

        val (seen, applier) = collector()
        val report = engine("dev-2").drain(applier).getOrThrow()

        assertEquals(1, report.applied)
        assertEquals(1, seen.size)
        assertEquals("contact:alice", seen[0].itemId)
        assertEquals(SyncProtocol.SyncKind.CONTACT, seen[0].kind)
        assertEquals("""{"alias":"Alice"}""", String(seen[0].payload))
        assertEquals("dev-1", seen[0].deviceId)
    }

    /** The server never sees plaintext — the whole point of an E2E archive. */
    @Test
    fun `the ciphertext on the server does not contain the plaintext`() {
        engine().archive(SyncProtocol.SyncKind.CONTACT, "alice", "SECRETALIAS".toByteArray()).getOrThrow()
        val page = client.pull().getOrThrow()
        val raw = Base64.getDecoder().decode(page.items[0].ciphertext)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("SECRETALIAS"))
    }

    /** `V2SyncModels.swift:37-42` — a re-push of the same itemId is a server no-op. */
    @Test
    fun `pushing the same itemId twice does not grow the log`() {
        val e = engine()
        val first = e.archive(SyncProtocol.SyncKind.CONTACT, "alice", "a".toByteArray()).getOrThrow()
        val second = e.archive(SyncProtocol.SyncKind.CONTACT, "alice", "b".toByteArray()).getOrThrow()
        assertEquals(first.accepted["contact:alice"], second.accepted["contact:alice"])
        assertEquals(1, server.logSize(identity.userKey))
    }

    // ────────────────────────────────────── property 1: monotonic cursor

    /**
     * GUARD — [SyncCursor.recordConsumed] never moves backwards
     * (`V2SyncManager.swift:342-343`). A rewind is not a duplicate delivery; it is an
     * unbounded replay of the whole archive on every launch.
     */
    @Test
    fun `the cursor never moves backwards`() {
        cursor.recordConsumed(50)
        assertEquals(50, cursor.lastSeq())
        cursor.recordConsumed(10)
        assertEquals("a lower seq must be ignored, not written", 50, cursor.lastSeq())
        cursor.recordConsumed(0)
        assertEquals(50, cursor.lastSeq())
        cursor.recordConsumed(51)
        assertEquals(51, cursor.lastSeq())
    }

    @Test
    fun `the cursor survives a reload and a second drain applies nothing`() {
        engine().archive(SyncProtocol.SyncKind.MESSAGE, "m1", "x".toByteArray()).getOrThrow()
        val file = File(tmp.newFolder(), "c.json")
        val c1 = SyncCursor(file)
        val e1 = SyncEngine(client, key, c1, "dev-2")
        assertEquals(1, e1.drain { }.getOrThrow().applied)

        val c2 = SyncCursor(file)   // fresh object, same file
        assertEquals(1, c2.lastSeq())
        val e2 = SyncEngine(client, key, c2, "dev-2")
        assertEquals("nothing new: a second drain is two head calls", 0, e2.drain { }.getOrThrow().applied)
    }

    @Test
    fun `the device id is stable across reloads`() {
        val file = File(tmp.newFolder(), "c.json")
        val id = SyncCursor(file).deviceId()
        assertTrue(id.isNotEmpty())
        assertEquals(id, SyncCursor(file).deviceId())
    }

    // ────────────────────────────────────── properties 2 and 3: item isolation

    /**
     * GUARD — one undecryptable item costs one item, and the cursor advances PAST it
     * (`V2SyncManager.swift:222,227-233`).
     *
     * Both halves matter and they fail differently: without the isolation the drain aborts
     * on the corrupt item; without the advance the drain never terminates, because `last`
     * never passes the item and the same page comes back forever.
     */
    @Test
    fun `a corrupt item costs one item and the drain continues past it`() {
        val e = engine()
        e.archive(SyncProtocol.SyncKind.MESSAGE, "before", "one".toByteArray()).getOrThrow()
        // A well-formed item sealed under a DIFFERENT key — what an item from an identity
        // whose key rotated, or a hostile write, actually looks like.
        server.seed(identity.userKey, "msg:corrupt", "msg",
            SyncCrypto.seal(SyncCrypto.v2ArchiveKey(ByteArray(32) { 99 }), "nope".toByteArray()))
        e.archive(SyncProtocol.SyncKind.MESSAGE, "after", "two".toByteArray()).getOrThrow()

        val (seen, applier) = collector()
        val report = SyncEngine(client, key, cursor, "dev-2").drain(applier).getOrThrow()

        assertEquals(2, report.applied)
        assertEquals(1, report.skippedUndecryptable)
        assertEquals(listOf("msg:before", "msg:after"), seen.map { it.itemId })
        assertEquals("the cursor must pass the corrupt item or the drain never terminates",
            3, cursor.lastSeq())

        // …and it stays past it: a second drain does not re-read the corrupt item.
        val second = SyncEngine(client, key, cursor, "dev-2").drain { }.getOrThrow()
        assertEquals(0, second.applied)
        assertEquals(0, second.skippedUndecryptable)
    }

    /** A truncated blob is isolated the same way a wrong-key blob is. */
    @Test
    fun `a malformed ciphertext is isolated too`() {
        server.seed(identity.userKey, "msg:short", "msg", "YWJj")   // 3 bytes
        engine().archive(SyncProtocol.SyncKind.MESSAGE, "ok", "x".toByteArray()).getOrThrow()

        val report = SyncEngine(client, key, cursor, "dev-2").drain { }.getOrThrow()
        assertEquals(1, report.applied)
        assertEquals(1, report.skippedUndecryptable)
    }

    // ────────────────────────────────────── property 4: paging

    @Test
    fun `a drain pages through more items than one page holds`() {
        val e = engine()
        repeat(7) { e.archive(SyncProtocol.SyncKind.MESSAGE, "m$it", "body$it".toByteArray()).getOrThrow() }

        val (seen, applier) = collector()
        val report = SyncEngine(client, key, cursor, "dev-2", pageLimit = 3).drain(applier).getOrThrow()

        assertEquals(7, report.applied)
        assertEquals(7, cursor.lastSeq())
        assertEquals((0..6).map { "msg:m$it" }, seen.map { it.itemId })
        assertEquals("seq order, ascending, no gaps", (1..7).toList(), seen.map { it.seq })
    }

    /**
     * GUARD (property 5) — a server that does not honour `seq > after` ends the drain
     * instead of spinning it.
     *
     * `V2SyncManager.swift:217-250` has no exit for this: its only breaks are an empty page
     * and a short page, and a repeating FULL page is neither. The failure mode is a loop at
     * full CPU with no log line, which is also why the property-3 guard needs this one to
     * be testable at all — without it, removing the cursor advance produces a hang, and a
     * hang is not a test result.
     *
     * The `@Test(timeout=…)` is the belt: if the bound is ever removed, this fails as a
     * named test in five seconds rather than hanging the suite.
     */
    @Test(timeout = 5_000)
    fun `a server that ignores the after cursor ends the drain instead of spinning`() {
        val e = engine()
        repeat(4) { e.archive(SyncProtocol.SyncKind.MESSAGE, "m$it", "b$it".toByteArray()).getOrThrow() }
        server.ignoreAfterOnPull = true
        server.pullCount = 0

        val (seen, applier) = collector()
        val report = SyncEngine(client, key, cursor, "dev-2", pageLimit = 2).drain(applier).getOrThrow()

        // First page: seq 1..2, cursor 0 -> 2, a full page so the loop continues.
        // Second page: seq 1..2 again, cursor stays at 2 -> property 5 breaks the loop.
        assertTrue("the drain must stop, not spin: ${server.pullCount} pulls", server.pullCount <= 3)
        assertEquals(2, cursor.lastSeq())
        assertTrue("what it did manage to read is still applied", report.applied >= 2)
        assertTrue(seen.isNotEmpty())
    }

    @Test
    fun `nothing new is a head call and no applies`() {
        val report = engine().drain { throw AssertionError("must not apply") }.getOrThrow()
        assertEquals(0, report.applied)
        assertEquals(0, report.headMaxSeq)
        assertFalse(engine().isBehind().getOrThrow())
    }

    @Test
    fun `isBehind sees a peer's push`() {
        assertFalse(engine("dev-2").isBehind().getOrThrow())
        engine("dev-1").archive(SyncProtocol.SyncKind.MESSAGE, "m", "x".toByteArray()).getOrThrow()
        assertTrue(SyncEngine(client, key, cursor, "dev-2").isBehind().getOrThrow())
    }

    // ────────────────────────────────────── failure mid-drain

    /**
     * A transport failure mid-drain must not read as an empty tail. It reports a failure
     * AND persists what was durably consumed, so the next pass resumes rather than replays.
     */
    @Test
    fun `a failed page is a failure and not an empty archive`() {
        val e = engine()
        repeat(4) { e.archive(SyncProtocol.SyncKind.MESSAGE, "m$it", "b$it".toByteArray()).getOrThrow() }
        server.failPullsRemaining = 1

        val result = SyncEngine(client, key, cursor, "dev-2", pageLimit = 2).drain { }
        assertTrue("a 500 must surface, not read as 'nothing to sync'", result.isFailure)

        // And the retry completes the drain.
        val (seen, applier) = collector()
        assertEquals(4, SyncEngine(client, key, cursor, "dev-2", pageLimit = 2).drain(applier).getOrThrow().applied)
        assertEquals(4, seen.size)
    }

    // ────────────────────────────────────── own items

    /**
     * Default is to APPLY own items (`V2SyncManager.swift:83-87`): a reinstalled device
     * keeps its deviceId and has none of the history, so skipping them would leave it
     * permanently missing everything it authored.
     */
    @Test
    fun `own items are applied by default and skipped when asked`() {
        engine("dev-1").archive(SyncProtocol.SyncKind.MESSAGE, "mine", "x".toByteArray()).getOrThrow()

        val a = SyncCursor(File(tmp.newFolder(), "a.json"))
        assertEquals(1, SyncEngine(client, key, a, "dev-1").drain { }.getOrThrow().applied)

        val b = SyncCursor(File(tmp.newFolder(), "b.json"))
        val report = SyncEngine(client, key, b, "dev-1", applyOwnItems = false).drain { }.getOrThrow()
        assertEquals(0, report.applied)
        assertEquals(1, report.skippedOwn)
        assertEquals("the cursor still advances past a skipped own item", 1, b.lastSeq())
    }

    // ────────────────────────────────────── conflict resolution

    /**
     * Last-writer-wins within one `itemId`, tie-broken by `seq` — the rule iOS states as
     * guidance and does not enforce ([SyncEngine]'s CONFLICT RESOLUTION).
     *
     * Seeded directly so two entries can share an itemId, which the real server's
     * idempotency would otherwise prevent — the situation arises when an item is
     * checkpointed away and re-pushed, or when a server has been restored from a backup.
     */
    @Test
    fun `within one itemId the later ts wins and an equal ts is broken by seq`() {
        val id = "contact:alice"
        server.seed(identity.userKey, id, "contact", SyncCrypto.seal(key, "old".toByteArray()), ts = 100L)
        server.seed(identity.userKey, id, "contact", SyncCrypto.seal(key, "new".toByteArray()), ts = 200L)
        server.seed(identity.userKey, id, "contact", SyncCrypto.seal(key, "stale".toByteArray()), ts = 150L)
        server.seed(identity.userKey, id, "contact", SyncCrypto.seal(key, "tie-later-seq".toByteArray()), ts = 200L)

        val (seen, applier) = collector()
        val report = SyncEngine(client, key, cursor, "dev-2").drain(applier).getOrThrow()

        assertEquals(listOf("old", "new", "tie-later-seq"), seen.map { String(it.payload) })
        assertEquals("the ts=150 record after ts=200 is stale", 1, report.skippedStale)
        assertEquals(4, cursor.lastSeq())
    }

    // ────────────────────────────────────── owner-only

    /**
     * GUARD — [V2SyncClient.assertOwner] refuses a foreign userKey.
     *
     * The server refuses it too (the 401 below), but the point of the client guard is that
     * a signed GET for someone else's archive is a signed statement, to a relay operator,
     * of whose data this account tried to read — and the 401 arrives after that statement
     * has been made.
     */
    @Test
    fun `addressing a foreign archive is refused by the client and by the server`() {
        val stranger = DesktopIdentity.generate().userKey
        val e = runCatching { client.assertOwner(stranger) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        client.assertOwner(identity.userKey)   // own key passes

        // And the server's own owner check: a client built for a foreign key gets a 401.
        val foreign = V2SyncClient(V2Http(DesktopV2Signer(identity), server.baseUrl), stranger)
        val result = foreign.head()
        assertTrue(result.isFailure)
        assertTrue("${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains("401"))
    }

    /**
     * GUARD — the refusal is wired into every route, so **no request leaves the machine**.
     *
     * The test above proves [V2SyncClient.assertOwner] refuses when called directly, and
     * that the server refuses too. Neither of those is the property that matters: iOS's
     * `syncPull` / `syncHead` / `syncCheckpoint` take a `userKey` and BUILD the request, so
     * the 401 arrives only after a signed statement of whose archive this account tried to
     * read has already been sent. This asserts the four routes refuse before the socket —
     * `requestedPaths` is empty afterwards.
     */
    @Test
    fun `every route refuses a foreign userKey before any request is sent`() {
        val stranger = DesktopIdentity.generate().userKey
        server.requestedPaths.clear()

        val calls: List<Pair<String, () -> Any>> = listOf(
            "head" to { client.head(userKey = stranger) },
            "pull" to { client.pull(userKey = stranger) },
            "checkpoint" to { client.checkpoint(upToSeq = 1, userKey = stranger) },
            "push" to {
                client.push(
                    listOf(SyncProtocol.SyncItem("msg:x", "msg", SyncCrypto.seal(key, "x".toByteArray()), "d", 1L)),
                    userKey = stranger,
                )
            },
        )
        for ((name, call) in calls) {
            val e = runCatching { call() }.exceptionOrNull()
            assertTrue("$name must refuse a foreign key, got $e", e is IllegalArgumentException)
        }

        assertTrue(
            "a signed request for a stranger's archive must never reach the wire: " +
                "${server.requestedPaths}",
            server.requestedPaths.isEmpty()
        )
        // The stranger's key must not appear in any path this client ever built.
        val strangerPath = SyncProtocol.v2PathKey(stranger)
        assertTrue(server.requestedPaths.none { it.contains(strangerPath) })
    }

    /** The V2 path key must be percent-encoded, or the route does not resolve at all. */
    @Test
    fun `the drain actually addresses the percent-encoded v2 path`() {
        engine().archive(SyncProtocol.SyncKind.MESSAGE, "m", "x".toByteArray()).getOrThrow()
        val expected = "/v2/sync/${SyncProtocol.v2PathKey(identity.userKey)}"
        assertTrue(
            "paths seen: ${server.requestedPaths.distinct()}",
            server.requestedPaths.contains(expected)
        )
        // …and never the legacy base64url spelling, which is a different storage slot.
        val legacy = "/v2/sync/${SyncProtocol.legacyPathKey(identity.userKey)}"
        assertFalse(server.requestedPaths.contains(legacy))
    }

    // ────────────────────────────────────── checkpoint

    /**
     * Checkpointing is never a side effect of a drain
     * (`V2SyncManager.CheckpointPolicy` defaults to `.disabled`, `swift:104-114`): trimming
     * is only safe once EVERY device has consumed that seq, and no device can know that.
     */
    @Test
    fun `a drain never trims the archive on its own`() {
        val e = engine()
        repeat(3) { e.archive(SyncProtocol.SyncKind.MESSAGE, "m$it", "x".toByteArray()).getOrThrow() }
        SyncEngine(client, key, cursor, "dev-2").drain { }.getOrThrow()
        assertEquals("the log must survive a drain", 3, server.logSize(identity.userKey))

        val r = client.checkpoint(2).getOrThrow()
        assertEquals(2, r.trimmed)
        assertEquals(1, r.remaining)
    }

    // ────────────────────────────────────── the legacy archive, end to end

    @Test
    fun `a legacy contacts blob round trips through the unauthenticated route`() {
        val legacyKey = SyncCrypto.legacyArchiveKey(identity.identity.priv)
        val legacy = LegacySyncClient(identity.userKey, "dev-1", legacyKey, server.baseUrl)
        val contacts = ContactStore(File(tmp.newFolder(), "contacts.json")).apply {
            seen("AAA=", 1L, "Alice"); seen("BBB=", 2L, "Bob")
        }

        legacy.push(SyncProtocol.LegacyKind.CONTACTS,
            ContactSyncRecord.encodeLegacyAliasMap(contacts)).getOrThrow()
        val pulled = legacy.pull(SyncProtocol.LegacyKind.CONTACTS).getOrThrow()!!

        val receiver = ContactStore(File(tmp.newFolder(), "contacts.json"))
        assertEquals(2, ContactSyncRecord.applyLegacyAliasMap(receiver, pulled, 3L))
        assertEquals("Alice", receiver.get("AAA=")!!.displayName)

        // The slot really is addressed by the base64url-unpadded key, not the percent one.
        assertTrue(server.legacyBlob("contacts", SyncProtocol.legacyPathKey(identity.userKey)) != null)
    }

    @Test
    fun `an empty legacy slot is success-with-null and not a failure`() {
        val legacy = LegacySyncClient(identity.userKey, "dev-1", ByteArray(32), server.baseUrl)
        val r = legacy.pull(SyncProtocol.LegacyKind.PROFILE)
        assertTrue(r.isSuccess)
        assertEquals(null, r.getOrThrow())
    }

    /**
     * GUARD — a legacy blob that does not decrypt is a FAILURE, not an empty archive.
     *
     * The route has no authentication ([LegacySyncClient]'s SECURITY PROPERTY): anyone
     * holding the public key can overwrite the slot. Reading an overwritten slot as "you
     * have no contacts" is how a stranger's write becomes a silent local wipe.
     */
    @Test
    fun `a legacy blob written by someone else is a failure and never an empty archive`() {
        val mine = SyncCrypto.legacyArchiveKey(identity.identity.priv)
        val legacy = LegacySyncClient(identity.userKey, "dev-1", mine, server.baseUrl)

        // A stranger POSTs to the slot — no signature required, which is the point.
        server.putLegacyBlob("contacts", SyncProtocol.legacyPathKey(identity.userKey),
            SyncCrypto.seal(ByteArray(32) { 0x5A }, """{"AAA=":"not yours"}""".toByteArray()))

        val r = legacy.pull(SyncProtocol.LegacyKind.CONTACTS)
        assertTrue("must not read as an empty archive", r.isFailure)
        assertTrue(r.exceptionOrNull() is SyncCryptoException)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("NO authentication"))
    }
}
