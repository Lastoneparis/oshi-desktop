package com.oshi.desktop.sync

import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.store.ContactStore
import org.json.JSONObject

/**
 * The contact record inside a sync archive item — and the one place PARITY.md row 0.24
 * refuses to reproduce what both shipped clients do.
 *
 * ============================================================ THE FINDING, VERIFIED
 *
 * PARITY.md row 0.21 records Android's gap as *"`syncContactsToLinkedDevices` builds its
 * payload from a query that already excludes blocked rows, so the `isBlocked` field it
 * sends can only ever be `false`"*. Checked against the source rather than taken on
 * trust, and it holds — with two things the note does not say, one worse and one better:
 *
 * **Confirmed.** `MultiDeviceSyncManager.kt:554` calls `db.contactDao().getAllContactsList()`,
 * which is `AppDatabase.kt:604-605`:
 *
 *     @Query("SELECT * FROM contacts WHERE isBlocked = 0 ORDER BY alias ASC")
 *     suspend fun getAllContactsList(): List<Contact>
 *
 * and `:563` then writes `put("isBlocked", contact.isBlocked)` for each row of that list.
 * Every row came from `WHERE isBlocked = 0`, so the field is a constant `false` on the
 * wire. The DAO even carries a comment two lines below saying so — `getEveryContact()`
 * exists at `:610-611` precisely because *"`getAllContactsList` filters blocked out, so it
 * cannot be reused"* — and the sync path was not one of the call sites that got moved to it.
 *
 * **Worse than "blocking never propagates".** The receiving side does not merely fail to
 * learn about a block; it can DESTROY one. `handleContactsSync` reads
 * `optBoolean("isBlocked", false)` (`:869`) and, when the incoming per-contact stamp wins,
 * writes it straight over the local row (`:888-893`):
 *
 *     } else if (incomingTs > localTs) {
 *         db.contactDao().update(existing.copy(… isBlocked = isBlocked …))
 *
 * A contact blocked on device B but not on device A is present in A's payload with
 * `isBlocked=false`, and B clears its own block. The stamp comparison does not save it:
 * the sender defaults an unstamped contact's `syncTimestamp` to `System.currentTimeMillis()`
 * (`:556`, `:559`), so a contact nobody has ever edited carries a brand-new timestamp and
 * beats essentially any local stamp. So the honest statement of the defect is: **sync
 * cannot propagate a block, and can silently undo one.**
 *
 * **Better than it looks, today only.** That payload is fanned out to
 * `_linkedDevices`, and nothing on a shipped Android can put a device in that list.
 * `linkDevice()` builds the `🔗LINK🔗` request JSON, logs it, and never transmits it —
 * `// Simulate success for now` followed by an unconditional `callback(true, …)`
 * (`:336-360`). The only other way in is receiving a `link_request` whose code matches a
 * locally-generated pending code, which requires some client to send one. So the list stays
 * empty, `sendSyncToLinkedDevices` fans out to nobody, and `handleSyncData` rejects every
 * inbound sync as coming from an unlinked device (`:488-492`). The defect is unreachable
 * in the shipped binary. It is one wired-up `vpsClient.sendMessage` away from being live,
 * and the code that would unblock people is already written.
 *
 * **iOS does not have this bug because iOS does not have the feature.** Its contact sync
 * uploads `JSONEncoder().encode([String: String])` — a bare `publicKey → alias`
 * dictionary out of `@AppStorage("contactAliases")` (`MultiDeviceSyncManager.swift:1007-1017`)
 * — and merges pulled entries gap-fill-only, never overwriting a local alias
 * (`:634-640`). No block state, no verification state, no timestamps, and therefore
 * nothing to get wrong. Android's server-side path matches iOS exactly for
 * cross-compatibility (`.kt:1227-1254`); the richer record exists only on the inert
 * peer-to-peer path.
 *
 * ============================================================ WHAT THIS CLIENT DOES
 *
 * Three rules, each of which is a named guard with a test that has been watched failing.
 *
 * 1. **[exportAll] takes the store, not a list.** The whole defect is a caller handing a
 *    pre-filtered collection to an encoder that then labels each element with a field the
 *    filter has already decided. This function reads [ContactStore.all] itself — the
 *    method whose doc comment says "Every contact, blocked or not" — so there is no
 *    parameter through which a `visible()` can arrive. There is deliberately no overload
 *    that accepts a `List<Contact>`: an API that cannot be called wrongly beats a comment
 *    asking callers not to.
 *
 * 2. **[assertBlocksRepresented] is checked before anything is emitted.** Rule 1 makes the
 *    filtered list unreachable through the signature; this makes it unreachable through a
 *    later edit. It compares the blocked set of the store against the blocked set of the
 *    records about to go out and throws if the archive would under-report. A refactor that
 *    reintroduces a filter fails here rather than in a user's block list six months later.
 *
 * 3. **A block decision is never inferred from a missing field.** [parse] returns
 *    `blocked = null` when the record has no `isBlocked` key at all, and [applyTo] treats
 *    null as "this record says nothing about blocking" and leaves the local flag alone.
 *    That is the difference between this and `optBoolean("isBlocked", false)`: an alias-only
 *    record from an iPhone, or from an older desktop, carries no opinion about blocking and
 *    must not be read as the opinion "not blocked". The default in `optBoolean` is exactly
 *    how a missing field became an unblock on Android.
 *
 * The address is compared through [BlockPolicy.normalizeKey] rather than raw, because row
 * 0.21's fourth iOS defect is precisely a raw-vs-normalised comparison mismatch
 * (`BlockedContactsManager.swift:131` vs `:90-103`) and a record arriving with a different
 * base64 spelling of the same key must land on the same contact.
 *
 * ============================================================ WIRE SHAPE
 *
 * Key order is Android's emission order (`MultiDeviceSyncManager.kt:561-566`):
 *
 *     {"publicKey":…,"alias":…,"isBlocked":…,"isVerified":…,"syncTimestamp":…}
 *
 * `syncTimestamp` is Unix MILLIS (`System.currentTimeMillis()`, `:556`), like the V2
 * archive item's `ts` and unlike the Apple-epoch seconds inside a legacy MESSAGE record —
 * see [SyncProtocol]'s EPOCHS section. Nothing here converts an epoch, so nothing here
 * touches [com.oshi.desktop.msg.WireClock]; if a future record body carries an
 * Apple-epoch field it goes through WireClock and not through a second converter.
 */
object ContactSyncRecord {

    /**
     * One contact as it travels. [blocked] and [verified] are nullable because "the peer
     * said nothing about this" and "the peer said false" are different facts and this
     * client is the one that stopped conflating them.
     */
    data class Record(
        val publicKey: String,
        val alias: String?,
        val blocked: Boolean?,
        val verified: Boolean?,
        val syncTimestampMs: Long,
    )

    /**
     * Build the outbound record set from the store — every contact, blocked included.
     *
     * @param nowMs the stamp for a contact with no recorded edit time. Android's default
     *   here is `System.currentTimeMillis()` (`:556`), which makes an unedited contact win
     *   every conflict; this client uses [ContactStore.Contact.firstSeenMs] instead, so a
     *   contact that has never been touched carries the oldest plausible stamp and LOSES to
     *   a peer that has actually edited it. [nowMs] is only the floor for a record with no
     *   first-seen time at all, which the store's `require` makes impossible today.
     */
    fun exportAll(contacts: ContactStore, nowMs: Long): List<Record> {
        val records = contacts.all().map { c ->
            Record(
                publicKey = c.address,
                alias = c.displayName,
                blocked = c.blocked,
                verified = c.verification == ContactStore.VerificationState.VERIFIED,
                syncTimestampMs = if (c.firstSeenMs > 0) c.firstSeenMs else nowMs,
            )
        }
        assertBlocksRepresented(contacts, records)
        return records
    }

    /**
     * Refuse to emit an archive that under-reports the blocked set.
     *
     * The guard the Android path is missing. It is stated as a comparison of two SETS
     * rather than as "the list came from `all()`", because the failure it exists to catch
     * is a caller who genuinely believes their list is complete — which is exactly what a
     * `SELECT … WHERE isBlocked = 0` looks like from the call site.
     *
     * @throws SyncExportException naming the contacts that would have been dropped.
     */
    fun assertBlocksRepresented(contacts: ContactStore, records: List<Record>) {
        val emitted = records.asSequence()
            .filter { it.blocked == true }
            .map { BlockPolicy.normalizeKey(it.publicKey) }
            .toSet()
        val missing = contacts.all()
            .filter { it.blocked }
            .map { BlockPolicy.normalizeKey(it.address) }
            .filterNot { it in emitted }
        if (missing.isNotEmpty()) {
            throw SyncExportException(
                "contact sync payload omits ${missing.size} blocked contact(s): " +
                    missing.sorted().joinToString(", ") { it.take(12) + "…" } +
                    " — this is MultiDeviceSyncManager.kt:554+563 (a payload built from " +
                    "`WHERE isBlocked = 0` whose isBlocked field can only be false). " +
                    "Blocking must propagate to a linked device, not only unblocking."
            )
        }
    }

    /**
     * Encode one record. `isBlocked` / `isVerified` are omitted when unknown rather than
     * defaulted — the emitter half of rule 3.
     */
    fun encode(r: Record): JSONObject = JSONObject().apply {
        put("publicKey", r.publicKey)
        put("alias", r.alias ?: "")
        r.blocked?.let { put("isBlocked", it) }
        r.verified?.let { put("isVerified", it) }
        put("syncTimestamp", r.syncTimestampMs)
    }

    /** The whole payload body: `{"type":"contacts","contacts":[…],"timestamp":…}` (`.kt:567-572`). */
    fun encodePayload(records: List<Record>, timestampMs: Long, deviceId: String): ByteArray {
        val arr = org.json.JSONArray()
        for (r in records) arr.put(encode(r))
        return JSONObject()
            .put("type", "contacts")
            .put("contacts", arr)
            .put("timestamp", timestampMs)
            .put("deviceId", deviceId)
            .toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode one record. **`has` before `opt`** — the receiver half of rule 3, and the
     * single line that separates this from `optBoolean("isBlocked", false)`.
     */
    fun parse(o: JSONObject): Record? {
        val key = o.optString("publicKey", "")
        if (key.isBlank()) return null
        return Record(
            publicKey = key,
            alias = o.optString("alias", "").ifEmpty { null },
            blocked = if (o.has("isBlocked")) o.optBoolean("isBlocked", false) else null,
            verified = if (o.has("isVerified")) o.optBoolean("isVerified", false) else null,
            syncTimestampMs = o.optLong("syncTimestamp", o.optLong("timestamp", 0L)),
        )
    }

    fun parsePayload(body: ByteArray): List<Record> {
        val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val arr = o.optJSONArray("contacts") ?: return emptyList()
        val fallbackTs = o.optLong("timestamp", 0L)
        val out = ArrayList<Record>(arr.length())
        for (i in 0 until arr.length()) {
            val r = parse(arr.optJSONObject(i) ?: continue) ?: continue
            out.add(if (r.syncTimestampMs == 0L) r.copy(syncTimestampMs = fallbackTs) else r)
        }
        return out
    }

    /** What [applyTo] did, so a caller can log it and a test can assert it. */
    data class ApplyOutcome(
        val inserted: Int,
        val updated: Int,
        val ignoredAsStale: Int,
        val blockDecisionsApplied: Int,
        val blockDecisionsAbsent: Int,
    )

    /**
     * Merge inbound records into the local store, last-writer-wins per contact on
     * `syncTimestamp` — the rule `MultiDeviceSyncManager.kt:858-905` defines, with the
     * three corrections above.
     *
     * @param localStampOf the local edit stamp for a contact. Android keeps this in a
     *   side `SharedPreferences` file (`contact_sync_timestamps`, `.kt:854-856`); this
     *   client has nowhere to put it yet, so the default is [ContactStore.Contact.lastSeenMs]
     *   — which is a weaker stamp and is called out here rather than hidden: `lastSeen`
     *   advances when a message arrives, not when the user edits the contact, so a chatty
     *   contact resists remote edits more than it should. A dedicated per-contact edit
     *   stamp belongs with whichever row adds contact editing to the UI; inventing a second
     *   store for it here would be a schema this row cannot justify.
     *
     * A new contact is inserted with its remote flags, including a remote BLOCK: a device
     * that has never heard of a contact has no local decision to protect, and the whole
     * point of the row is that a block travels.
     */
    fun applyTo(
        contacts: ContactStore,
        records: List<Record>,
        nowMs: Long,
        localStampOf: (ContactStore.Contact) -> Long = { it.lastSeenMs ?: it.firstSeenMs },
    ): ApplyOutcome {
        var inserted = 0
        var updated = 0
        var stale = 0
        var blockApplied = 0
        var blockAbsent = 0

        val byNormalised = contacts.all().associateBy { BlockPolicy.normalizeKey(it.address) }

        for (r in records) {
            val existing = byNormalised[BlockPolicy.normalizeKey(r.publicKey)]
            if (r.blocked == null) blockAbsent++

            if (existing == null) {
                contacts.seen(r.publicKey, nowMs, displayNameHint = r.alias)
                if (r.blocked == true) { contacts.block(r.publicKey, r.syncTimestampMs); blockApplied++ }
                if (r.verified == true) {
                    contacts.setVerification(r.publicKey, ContactStore.VerificationState.VERIFIED)
                }
                inserted++
                continue
            }

            if (r.syncTimestampMs <= localStampOf(existing)) { stale++; continue }

            r.alias?.let { contacts.setDisplayName(existing.address, it) }
            // Rule 3: only a record that CARRIES a block decision may change one.
            when (r.blocked) {
                true -> { contacts.block(existing.address, r.syncTimestampMs); blockApplied++ }
                // __BLOCK_SYNC_LWW_2026_09_24__ the record's own time, not "now" (devsync LWW).
                false -> { BlockPolicy.unblockEverySpelling(contacts, existing.address, r.syncTimestampMs); blockApplied++ }
                null -> Unit
            }
            if (r.verified != null) {
                contacts.setVerification(
                    existing.address,
                    if (r.verified) ContactStore.VerificationState.VERIFIED
                    else ContactStore.VerificationState.UNVERIFIED,
                )
            }
            updated++
        }
        return ApplyOutcome(inserted, updated, stale, blockApplied, blockAbsent)
    }

    /**
     * The LEGACY contacts payload — the alias-only `[publicKey: alias]` map both shipped
     * clients exchange (`MultiDeviceSyncManager.swift:1013-1017`, `.kt:1227-1235`).
     *
     * Kept separate from [encodePayload] rather than derived from it, because it is a
     * different protocol on a different route and it deliberately carries NO block state.
     * Emitting `isBlocked` into this map would not propagate a block — it would produce an
     * alias whose value is a JSON object, which iOS's `[String: String]` decoder rejects
     * outright, discarding the whole archive.
     *
     * Aliases only, and only non-empty ones, matching Android's `if (c.alias.isNotEmpty())`.
     */
    fun encodeLegacyAliasMap(contacts: ContactStore): ByteArray {
        val o = JSONObject()
        for (c in contacts.all()) {
            val alias = c.displayName
            if (!alias.isNullOrEmpty()) o.put(c.address, alias)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Merge a pulled legacy alias map: **gap-fill only, never overwrite** — iOS
     * `:634-640`, Android `:1245-1250`. A local alias the user chose is never replaced by a
     * remote one, which is also [ContactStore.seen]'s own rule for a display-name hint.
     */
    fun applyLegacyAliasMap(contacts: ContactStore, body: ByteArray, nowMs: Long): Int {
        val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return 0
        var added = 0
        for (key in o.keys()) {
            val alias = o.optString(key, "")
            if (alias.isEmpty()) continue
            if (contacts.get(key) != null) continue
            contacts.seen(key, nowMs, displayNameHint = alias)
            added++
        }
        return added
    }
}

class SyncExportException(message: String) : RuntimeException(message)
