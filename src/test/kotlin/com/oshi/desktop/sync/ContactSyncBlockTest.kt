package com.oshi.desktop.sync

import com.oshi.desktop.store.ContactStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The block-propagation defect, and this client's refusal to repeat it — PARITY.md
 * rows 0.24 and 0.21.
 *
 * The defect in one sentence, verified against
 * `OSHI-Android/app/src/main/java/com/oshi/messenger/service/MultiDeviceSyncManager.kt`:
 * `syncContactsToLinkedDevices` (`:550`) builds its payload from
 * `db.contactDao().getAllContactsList()` (`:554`), which is
 * `@Query("SELECT * FROM contacts WHERE isBlocked = 0 …")` (`AppDatabase.kt:604-605`), and
 * then writes `put("isBlocked", contact.isBlocked)` (`:563`) — a field that can only ever
 * be `false`.
 *
 * [androidShapedExport] reproduces that exact construction so the failure is demonstrated
 * rather than described, and every assertion below is against what this client does with
 * it.
 */
class ContactSyncBlockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ContactStore = ContactStore(File(tmp.newFolder(), "contacts.json"))

    private val ALICE = "QWxpY2VBbGljZUFsaWNlQWxpY2VBbGljZUFsaWNlMTI="
    private val BOB = "Qm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iQm9iMTI="
    private val MALLORY = "TWFsbG9yeU1hbGxvcnlNYWxsb3J5TWFsbG9yeTEyMzQ="

    private fun seeded(): ContactStore = store().apply {
        seen(ALICE, 1_000L, "Alice")
        seen(BOB, 2_000L, "Bob")
        seen(MALLORY, 3_000L, "Mallory")
        block(MALLORY)
    }

    /**
     * `MultiDeviceSyncManager.kt:550-577` transcribed: build the record list from the
     * block-EXCLUDING query, then label each surviving row with its own `isBlocked`.
     */
    private fun androidShapedExport(contacts: ContactStore, nowMs: Long): List<ContactSyncRecord.Record> =
        contacts.visible().map {          // ← `WHERE isBlocked = 0`
            ContactSyncRecord.Record(
                publicKey = it.address,
                alias = it.displayName,
                blocked = it.blocked,     // ← :563, structurally false for every row
                verified = false,
                syncTimestampMs = nowMs,  // ← :556,559 — an unstamped contact wins every conflict
            )
        }

    // ────────────────────────────────────── the defect, demonstrated

    /** The field is a constant. Not "usually false" — structurally, for every row. */
    @Test
    fun `the android construction can only ever emit isBlocked false`() {
        val contacts = seeded()
        val records = androidShapedExport(contacts, 9_000L)

        assertTrue("MultiDeviceSyncManager.kt:563 over AppDatabase.kt:604 emits no true",
            records.none { it.blocked == true })
        assertTrue("the blocked contact is absent from the payload entirely",
            records.none { it.publicKey == MALLORY })
        // …while the store plainly knows about it.
        assertTrue(contacts.isBlocked(MALLORY))
        assertEquals(3, contacts.all().size)
        assertEquals(2, records.size)
    }

    /**
     * And it is worse than "blocking does not propagate": on the receiving side, a
     * `false` that wins the timestamp comparison CLEARS an existing local block
     * (`.kt:888-893`, `isBlocked = isBlocked` inside the `existing.copy`).
     *
     * Driven through THIS client's applier, which honours a record that explicitly says
     * `false` — that part is correct and must stay correct, because it is how an unblock
     * travels. The bug is upstream: nothing should have said `false` about a contact the
     * sender had blocked, and nothing should have said anything at all about a contact the
     * sender merely omitted.
     */
    @Test
    fun `an android-shaped payload silently unblocks on the receiving device`() {
        val sender = seeded()                       // Mallory blocked here…
        val receiver = store().apply {
            seen(MALLORY, 1_000L, "Mallory")
            block(MALLORY)                          // …and blocked here too.
        }
        assertTrue(receiver.isBlocked(MALLORY))

        // The sender ALSO has an unblocked spelling of Mallory in its list on the other
        // device — the ordinary case where two devices disagree about one contact.
        val senderWithoutBlock = store().apply { seen(MALLORY, 1_000L, "Mallory") }
        val payload = androidShapedExport(senderWithoutBlock, 9_999_999L)

        ContactSyncRecord.applyTo(receiver, payload, nowMs = 10_000_000L)
        assertFalse(
            "this is the shipped Android behaviour: sync UNDOES a block",
            receiver.isBlocked(MALLORY)
        )
    }

    // ────────────────────────────────────── GUARD 1: the export cannot be built wrong

    /**
     * [ContactSyncRecord.exportAll] takes the STORE, not a list, and reads
     * [ContactStore.all] itself — so there is no parameter through which a `visible()` can
     * arrive. A blocked contact is present, and present as blocked.
     */
    @Test
    fun `exportAll carries every blocked contact with the flag set`() {
        val records = ContactSyncRecord.exportAll(seeded(), nowMs = 9_000L)
        assertEquals(3, records.size)
        val mallory = records.first { it.publicKey == MALLORY }
        assertEquals(true, mallory.blocked)
        assertEquals(false, records.first { it.publicKey == ALICE }.blocked)
    }

    /**
     * GUARD — [ContactSyncRecord.assertBlocksRepresented] refuses a payload that
     * under-reports the blocked set.
     *
     * Guard 1 makes the filtered list unreachable through the signature; this makes it
     * unreachable through a later edit. Fed the Android-shaped construction directly, it
     * throws and names what would have been dropped.
     */
    @Test
    fun `a payload that omits a blocked contact is refused`() {
        val contacts = seeded()
        val e = runCatching {
            ContactSyncRecord.assertBlocksRepresented(contacts, androidShapedExport(contacts, 9_000L))
        }.exceptionOrNull()

        assertTrue("expected SyncExportException, got $e", e is SyncExportException)
        assertTrue(
            "the refusal must name the shipped defect it exists to prevent: ${e!!.message}",
            e.message!!.contains("MultiDeviceSyncManager.kt:554")
        )
        // A payload that DOES carry the block passes.
        ContactSyncRecord.assertBlocksRepresented(contacts, ContactSyncRecord.exportAll(contacts, 9_000L))
    }

    // ────────────────────────────────────── GUARD 2: a missing field is not a decision

    /**
     * `optBoolean("isBlocked", false)` (`.kt:869`) is how a MISSING field became an
     * unblock. [ContactSyncRecord.parse] distinguishes absent from false.
     */
    @Test
    fun `an absent isBlocked field parses as unknown and not as false`() {
        val withField = ContactSyncRecord.parse(
            JSONObject("""{"publicKey":"$ALICE","alias":"A","isBlocked":false,"syncTimestamp":5}""")
        )!!
        assertEquals(false, withField.blocked)

        val without = ContactSyncRecord.parse(
            JSONObject("""{"publicKey":"$ALICE","alias":"A","syncTimestamp":5}""")
        )!!
        assertNull("absent must not read as false — that default IS the defect", without.blocked)
    }

    /**
     * GUARD — a record carrying no block opinion leaves a local block alone, even when its
     * timestamp wins everything else on the record.
     *
     * This is the case an alias-only record from an iPhone produces: iOS's contact sync is
     * a bare `[publicKey: alias]` map (`MultiDeviceSyncManager.swift:1013-1017`) with no
     * block state in it at all, so every iOS-originated record has an absent `isBlocked`.
     */
    @Test
    fun `an alias-only record never clears a local block`() {
        val receiver = store().apply { seen(MALLORY, 1_000L, "Mallory"); block(MALLORY) }
        val aliasOnly = ContactSyncRecord.Record(
            publicKey = MALLORY, alias = "Mallory (work)", blocked = null, verified = null,
            syncTimestampMs = 9_999_999L,
        )

        val outcome = ContactSyncRecord.applyTo(receiver, listOf(aliasOnly), nowMs = 10_000_000L)

        assertTrue("the block survives an alias update", receiver.isBlocked(MALLORY))
        assertEquals("Mallory (work)", receiver.get(MALLORY)!!.displayName)
        assertEquals(1, outcome.updated)
        assertEquals(0, outcome.blockDecisionsApplied)
        assertEquals(1, outcome.blockDecisionsAbsent)
    }

    // ────────────────────────────────────── the feature itself: a block travels

    @Test
    fun `a block propagates to a device that had not blocked`() {
        val sender = seeded()
        val receiver = store().apply { seen(MALLORY, 1_000L, "Mallory") }
        assertFalse(receiver.isBlocked(MALLORY))

        val payload = ContactSyncRecord.exportAll(sender, nowMs = 9_000L)
            .map { it.copy(syncTimestampMs = 9_999_999L) }
        ContactSyncRecord.applyTo(receiver, payload, nowMs = 10_000_000L)

        assertTrue("blocking must propagate, not only unblocking", receiver.isBlocked(MALLORY))
    }

    @Test
    fun `a block propagates to a device that had never heard of the contact`() {
        val payload = ContactSyncRecord.exportAll(seeded(), nowMs = 9_000L)
        val fresh = store()
        val outcome = ContactSyncRecord.applyTo(fresh, payload, nowMs = 10_000L)

        assertEquals(3, outcome.inserted)
        assertTrue(fresh.isBlocked(MALLORY))
        assertFalse(fresh.isBlocked(ALICE))
    }

    /** An explicit `false` still unblocks — that half of the feature must keep working. */
    @Test
    fun `an explicit unblock still travels`() {
        val receiver = store().apply { seen(MALLORY, 1_000L, "Mallory"); block(MALLORY) }
        val unblock = ContactSyncRecord.Record(MALLORY, "Mallory", blocked = false, verified = null, 9_999_999L)
        ContactSyncRecord.applyTo(receiver, listOf(unblock), nowMs = 10_000_000L)
        assertFalse(receiver.isBlocked(MALLORY))
    }

    /**
     * Row 0.21's fourth iOS defect is a raw-vs-normalised comparison mismatch
     * (`BlockedContactsManager.swift:131` vs `:90-103`). A record arriving with a different
     * base64 spelling of the same key must land on the same contact.
     */
    @Test
    fun `a record in a different base64 spelling lands on the same contact`() {
        val receiver = store().apply { seen(MALLORY, 1_000L, "Mallory") }
        val urlSafeUnpadded = MALLORY.replace('+', '-').replace('/', '_').trimEnd('=')
        val rec = ContactSyncRecord.Record(urlSafeUnpadded, "Mallory", blocked = true, verified = null, 9_999_999L)

        val outcome = ContactSyncRecord.applyTo(receiver, listOf(rec), nowMs = 10_000_000L)

        assertEquals("must UPDATE the existing contact, not insert a second one", 1, outcome.updated)
        assertEquals(0, outcome.inserted)
        assertEquals(1, receiver.all().size)
        assertTrue(receiver.isBlocked(MALLORY))
    }

    // ────────────────────────────────────── last-writer-wins

    @Test
    fun `a stale record loses to the local state`() {
        val receiver = store().apply { seen(MALLORY, 1_000L, "Mallory"); block(MALLORY) }
        // lastSeenMs is 1000; a record stamped earlier must not win.
        val stale = ContactSyncRecord.Record(MALLORY, "Old name", blocked = false, verified = null, 500L)
        val outcome = ContactSyncRecord.applyTo(receiver, listOf(stale), nowMs = 10_000L)

        assertEquals(1, outcome.ignoredAsStale)
        assertTrue(receiver.isBlocked(MALLORY))
        assertEquals("Mallory", receiver.get(MALLORY)!!.displayName)
    }

    /**
     * Android stamps an unstamped contact with `System.currentTimeMillis()` (`.kt:556,559`),
     * so a contact nobody has ever edited beats any local stamp. This client stamps it with
     * `firstSeenMs`, so it loses.
     */
    @Test
    fun `an unedited contact carries its first-seen stamp and not now`() {
        val contacts = store().apply { seen(ALICE, 1_234L, "Alice") }
        val rec = ContactSyncRecord.exportAll(contacts, nowMs = 9_999_999L).single()
        assertEquals(
            "an unedited contact must not carry a brand-new timestamp (MultiDeviceSyncManager.kt:556)",
            1_234L, rec.syncTimestampMs
        )
    }

    // ────────────────────────────────────── the legacy alias map

    /**
     * The legacy contacts route is alias-only on BOTH platforms
     * (`MultiDeviceSyncManager.swift:1013-1017`, `.kt:1227-1235`) — a bare
     * `publicKey → alias` dictionary. Emitting anything richer into this slot makes an
     * iPhone's `[String: String]` decoder throw and discard the WHOLE archive.
     */
    @Test
    fun `the legacy alias map is a flat string to string dictionary`() {
        val o = JSONObject(
            ContactSyncRecord.encodeLegacyAliasMap(seeded()).toString(Charsets.UTF_8)
        )
        assertEquals(setOf(ALICE, BOB, MALLORY), o.keySet())
        for (k in o.keySet()) {
            assertTrue("values must be plain strings, not objects", o.get(k) is String)
        }
        assertFalse("no block state fits in this shape at all", o.toString().contains("isBlocked"))
    }

    /** Gap-fill only, never overwrite — iOS `:634-640`, Android `:1245-1250`. */
    @Test
    fun `pulling a legacy alias map never overwrites a local alias`() {
        val receiver = store().apply { seen(ALICE, 1_000L, "My name for Alice") }
        val remote = JSONObject().put(ALICE, "Remote name").put(BOB, "Bob").toString()
            .toByteArray(Charsets.UTF_8)

        val added = ContactSyncRecord.applyLegacyAliasMap(receiver, remote, nowMs = 2_000L)

        assertEquals(1, added)
        assertEquals("My name for Alice", receiver.get(ALICE)!!.displayName)
        assertEquals("Bob", receiver.get(BOB)!!.displayName)
    }

    /** …and a legacy pull can never change a block, because it carries none. */
    @Test
    fun `a legacy alias map cannot change a block state`() {
        val receiver = store().apply { seen(MALLORY, 1_000L, "Mallory"); block(MALLORY) }
        val remote = JSONObject().put(MALLORY, "Mallory").toString().toByteArray(Charsets.UTF_8)
        ContactSyncRecord.applyLegacyAliasMap(receiver, remote, nowMs = 9_999_999L)
        assertTrue(receiver.isBlocked(MALLORY))
    }

    // ────────────────────────────────────── round trip

    @Test
    fun `the record payload round trips including an absent block field`() {
        val records = listOf(
            ContactSyncRecord.Record(ALICE, "Alice", blocked = false, verified = true, 100L),
            ContactSyncRecord.Record(MALLORY, "Mallory", blocked = true, verified = false, 200L),
            ContactSyncRecord.Record(BOB, "Bob", blocked = null, verified = null, 300L),
        )
        val back = ContactSyncRecord.parsePayload(
            ContactSyncRecord.encodePayload(records, timestampMs = 400L, deviceId = "d")
        )
        assertEquals(records, back)
        assertNull(back[2].blocked)
    }

    /** Key order is Android's emission order (`.kt:561-566`). */
    @Test
    fun `the record encodes the field names android emits`() {
        val o = ContactSyncRecord.encode(
            ContactSyncRecord.Record(ALICE, "Alice", blocked = true, verified = false, 42L)
        )
        assertEquals(setOf("publicKey", "alias", "isBlocked", "isVerified", "syncTimestamp"), o.keySet())
        assertEquals(true, o.getBoolean("isBlocked"))
        assertEquals(42L, o.getLong("syncTimestamp"))
    }
}
