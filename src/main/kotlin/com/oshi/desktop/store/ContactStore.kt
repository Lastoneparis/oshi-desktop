package com.oshi.desktop.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Contacts — PARITY.md row 0.14. Mirrors Android's `Contact` entity
 * (`data/model/Message.kt`: `publicKey`, `alias`, `isVerified`, `isBlocked`, `lastSeen`,
 * `createdAt`) and what iOS's `ContactPresenceManager` tracks per peer, minus the raw
 * avatar bytes (a pointer only — media storage is row 0.15, not this one).
 *
 * ============================================================ FORMAT
 *
 * ONE file, rewritten wholesale on every change, through [AtomicFile] (`SecretStore.kt`)
 * — the exact opposite structure from [MessageStore], and deliberately so: a contact
 * list is bounded by a person's social graph, not by how long they have used the app, so
 * "rewrite everything" costs a few contacts' worth of JSON even after years of use. That
 * assumption is precisely the one [MessageStore]'s doc comment explains does NOT hold
 * for message history, which is why that store is append-only instead.
 *
 *     <dataDir>/contacts.json  =  {"v":1,"contacts":[{...}, ...]}  (sorted by address)
 *
 * Sorting on every write is not required for correctness — this file is never read by
 * two different implementations, so there is no cross-platform key-order hazard the way
 * PLAN.md §4.3 describes for the wire — but it keeps the file useful for what KeyVault's
 * doc comment calls being "inspected without this code," and it means a diff between two
 * versions of this file is a diff of what actually changed, not of hash-map iteration
 * noise.
 *
 * ============================================================ BLOCKED-CONTACT RULES
 *
 * 1. Blocking sets a flag; it does not delete the contact or touch [MessageStore] at
 *    all. Message history is a record of what happened and blocking is a policy about
 *    what happens NEXT — conflating the two is how a block-then-unblock loses a year of
 *    conversation to what should have been a reversible UI toggle (the same failure
 *    shape as `groupless-keychain-delete-hits-every-group` in spirit: a state change
 *    that was supposed to be additive quietly became destructive).
 * 2. [visible] is what a chats list / new-message picker should call: blocked contacts
 *    are excluded. [all] is what a "Blocked contacts" settings screen should call: it
 *    returns everyone, so the block can be reviewed and reversed. Neither call ever
 *    deletes a row — only [delete] does that, and it is a distinct, explicit operation
 *    a caller has to choose on purpose (e.g. "remove this contact" in a UI), never a
 *    side effect of blocking.
 * 3. Unblocking is a pure flag flip back. Because nothing was destroyed by blocking,
 *    nothing needs to be reconstructed by unblocking — the contact's [Contact.firstSeenMs],
 *    display name and verification state are exactly what they were.
 * 4. This store does not, itself, stop a blocked contact's messages from being stored —
 *    that is a UI/inbound-routing concern layered on top ([isBlocked] is the query it
 *    should consult before rendering). Keeping that decision out of this class is
 *    deliberate: [MessageStore] has no dependency on [ContactStore] and vice versa, so
 *    neither can develop a load-bearing coupling to the other's internals.
 *
 * ============================================================ VERIFICATION STATE
 *
 * [VerificationState] is a PLACEHOLDER for PARITY.md row 0.20 (safety numbers), which is
 * explicitly unimplemented — the ledger's own note says "three mutually incompatible
 * implementations shipped once already," and inventing a fourth here, off to the side of
 * that row, would be exactly that mistake again. This field exists so the schema does
 * not need a breaking migration once row 0.20 lands; nothing computes it yet, and
 * [setVerification] is the only way it ever changes.
 *
 * ============================================================ THREAT MODEL
 *
 * __LOCAL_DATA_AT_REST_2026_09_22__ Encrypted at rest when [atRestKey] is given, which
 * `OshiClient` always does: the file becomes a [SealedJsonFile] envelope (AES-256-GCM, key
 * = HKDF subkey [LocalDataKeys.CONTACTS] of a vault entry). A legacy plaintext file is read
 * once and rewritten sealed through the verified write (temp, fsync, decrypt-back compare,
 * atomic move); if that fails the plaintext stays and the upgrade is retried on next open.
 * A sealed file with no key, or one that fails authentication, throws [ContactStoreException]
 * — it never reads as "no contacts", so nothing can overwrite it with an empty list.
 * With a null key (tests, pre-vault tools) the file is plaintext, as before.
 */
class ContactStore(
    private val file: File = DesktopPaths.file("contacts.json"),
    private val atRestKey: ByteArray? = null,
) {

    enum class VerificationState(val wire: String) {
        UNVERIFIED("unverified"), VERIFIED("verified"), SAFETY_NUMBER_CHANGED("changed");

        companion object {
            fun fromWire(raw: String): VerificationState = entries.firstOrNull { it.wire == raw } ?: UNVERIFIED
        }
    }

    data class Contact(
        val address: String,
        val displayName: String? = null,
        val firstSeenMs: Long,
        val lastSeenMs: Long? = null,
        val verification: VerificationState = VerificationState.UNVERIFIED,
        val blocked: Boolean = false,
        val avatarRef: String? = null,
        /**
         * __SHARED_NICKNAME_2026_09_22__ The nickname the PEER shares about themselves
         * (`displayName` of their `📸PROFILE_UPDATE📸`), already sanitized by
         * [com.oshi.desktop.msg.PeerNickname.sanitize]. Kept apart from [displayName], which
         * is OUR alias for them: a peer renaming themselves must never overwrite what the
         * user of this machine chose, and [seen]'s hint path never writes here.
         */
        val sharedNickname: String? = null,
        /**
         * __BLOCK_SYNC_LWW_2026_09_24__ When [blocked] last CHANGED by a deliberate act (the user
         * blocking/unblocking here, or a synced value from another of the account's devices,
         * which keeps ITS time). Null = never toggled on this install (legacy): devsync then
         * exports a block without a timestamp and an unblock not at all (gap-fill only).
         */
        val blockedAtMs: Long? = null,
    ) {
        init {
            require(address.isNotBlank()) { "contact address must not be blank" }
        }

        /** The name to draw: local alias, else shared nickname, else [fallback]. */
        fun label(fallback: String): String =
            com.oshi.desktop.msg.PeerNickname.resolve(displayName, sharedNickname, fallback)
    }

    @Volatile
    private var cache: MutableMap<String, Contact>? = null

    /** Every contact, blocked or not. What a "Blocked contacts" settings screen reads. */
    @Synchronized
    fun all(): List<Contact> = load().values.sortedBy { it.address }

    /** Everyone EXCEPT blocked contacts. What a chats list / new-message picker reads. */
    @Synchronized
    fun visible(): List<Contact> = all().filterNot { it.blocked }

    @Synchronized
    fun get(address: String): Contact? = load()[address]

    @Synchronized
    fun isBlocked(address: String): Boolean = load()[address]?.blocked ?: false

    /**
     * Record contact with this address at this instant. If new, [firstSeenMs] is set and
     * never moves again — that is what "first seen" means. If already known, only
     * [lastSeenMs] advances, and only forward: an out-of-order arrival (mesh vs relay,
     * same race [MessageStore]'s doc comment describes) must not rewind it. [displayNameHint]
     * is applied ONLY when no display name is set yet — a name the user has already
     * chosen for this contact is never silently overwritten by metadata off a message.
     */
    @Synchronized
    fun seen(address: String, atMs: Long, displayNameHint: String? = null): Contact {
        val map = load()
        val existing = map[address]
        val updated = if (existing == null) {
            Contact(address = address, displayName = displayNameHint, firstSeenMs = atMs, lastSeenMs = atMs)
        } else {
            existing.copy(
                lastSeenMs = maxOf(existing.lastSeenMs ?: Long.MIN_VALUE, atMs),
                displayName = existing.displayName ?: displayNameHint,
            )
        }
        map[address] = updated
        persist(map)
        return updated
    }

    @Synchronized
    fun setDisplayName(address: String, name: String?) = mutate(address) { it.copy(displayName = name) }

    /**
     * __SHARED_NICKNAME_2026_09_22__ Store (or clear, with null) the peer's shared nickname.
     * No-op on an unknown contact, and no rewrite of the file when nothing changed — a peer
     * re-sends the same profile update on every first contact.
     */
    @Synchronized
    fun setSharedNickname(address: String, name: String?): Contact? {
        val existing = load()[address] ?: return null
        if (existing.sharedNickname == name) return existing
        return mutate(address) { it.copy(sharedNickname = name) }
    }

    @Synchronized
    fun setVerification(address: String, state: VerificationState) = mutate(address) { it.copy(verification = state) }

    /**
     * Block. Stamps [Contact.blockedAtMs] only when the state actually changes, strictly after
     * any previous stamp (last-writer-wins across the account's devices).
     */
    @Synchronized
    fun block(address: String, atMs: Long = System.currentTimeMillis()) = setBlocked(address, true, atMs)

    @Synchronized
    fun unblock(address: String, atMs: Long = System.currentTimeMillis()) = setBlocked(address, false, atMs)

    /** A LOCAL block/unblock: a change is stamped max([atMs], previous + 1); no change, no stamp. */
    @Synchronized
    fun setBlocked(address: String, blocked: Boolean, atMs: Long): Contact? = mutate(address) {
        if (it.blocked == blocked) it
        else it.copy(blocked = blocked, blockedAtMs = maxOf(atMs, (it.blockedAtMs ?: Long.MIN_VALUE) + 1))
    }

    /**
     * __BLOCK_SYNC_LWW_2026_09_24__ Apply a value merged from another device: its timestamp is
     * kept as-is (never "now"), so the next export does not look newer than it is.
     */
    @Synchronized
    fun applySyncedBlock(address: String, blocked: Boolean, atMs: Long?): Contact? = mutate(address) {
        it.copy(blocked = blocked, blockedAtMs = atMs ?: it.blockedAtMs)
    }

    /** True deletion: distinct from [block], and never a side effect of it. */
    @Synchronized
    fun delete(address: String) {
        val map = load()
        if (map.remove(address) != null) persist(map)
    }

    // ---------------------------------------------------------------------------- plumbing

    private fun mutate(address: String, f: (Contact) -> Contact): Contact? {
        val map = load()
        val existing = map[address] ?: return null
        val updated = f(existing)
        map[address] = updated
        persist(map)
        return updated
    }

    private fun load(): MutableMap<String, Contact> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Contact>()
        val read = try {
            SealedJsonFile.read(file, atRestKey, LocalDataKeys.CONTACTS)
        } catch (e: Exception) {
            throw ContactStoreException("contacts file is not readable: ${file.absolutePath}", e)
        }
        if (read != null) {
            val o = try {
                JSONObject(read.json)
            } catch (e: Exception) {
                // Unlike KeyVault, this is not secret material where "read as empty"
                // risks minting a new identity over an existing one — but silently
                // discarding every contact (including every blocked one — see rule 2)
                // on a damaged file is still a real loss of a deliberate user choice.
                // Surface it instead of pretending there are no contacts.
                throw ContactStoreException("contacts file is not readable JSON: ${file.absolutePath}", e)
            }
            val arr = o.optJSONArray("contacts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = parseContact(arr.getJSONObject(i))
                map[c.address] = c
            }
        }
        cache = map
        // Every row parsed (a bad one throws above), so the sealed copy loses nothing.
        if (read != null && !read.sealed && atRestKey != null) {
            runCatching { persist(map) } // failure leaves the plaintext file; retried next open
        }
        return cache ?: map
    }

    private fun serialize(map: Map<String, Contact>): String {
        val arr = JSONArray()
        for (c in map.values.sortedBy { it.address }) arr.put(toJson(c))
        return JSONObject().put("v", 1).put("contacts", arr).toString()
    }

    private fun persist(map: Map<String, Contact>) {
        SealedJsonFile.write(file, atRestKey, LocalDataKeys.CONTACTS, serialize(map)) { back ->
            JSONObject(back).getJSONArray("contacts").length() == map.size
        }
        cache = LinkedHashMap(map)
    }

    private fun toJson(c: Contact): JSONObject = JSONObject().apply {
        put("address", c.address)
        c.displayName?.let { put("displayName", it) }
        put("firstSeenMs", c.firstSeenMs)
        c.lastSeenMs?.let { put("lastSeenMs", it) }
        put("verification", c.verification.wire)
        if (c.blocked) put("blocked", true)
        c.blockedAtMs?.let { put("blockedAtMs", it) }
        c.avatarRef?.let { put("avatarRef", it) }
        c.sharedNickname?.let { put("sharedNickname", it) }
    }

    private fun parseContact(o: JSONObject): Contact = Contact(
        address = o.getString("address"),
        displayName = o.optString("displayName", "").ifEmpty { null },
        firstSeenMs = o.getLong("firstSeenMs"),
        lastSeenMs = if (o.has("lastSeenMs")) o.getLong("lastSeenMs") else null,
        verification = VerificationState.fromWire(o.optString("verification", "")),
        blocked = o.optBoolean("blocked", false),
        blockedAtMs = if (o.has("blockedAtMs")) o.getLong("blockedAtMs") else null,
        avatarRef = o.optString("avatarRef", "").ifEmpty { null },
        // Absent in every file written before __SHARED_NICKNAME_2026_09_22__.
        sharedNickname = o.optString("sharedNickname", "").ifEmpty { null },
    )
}

class ContactStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
