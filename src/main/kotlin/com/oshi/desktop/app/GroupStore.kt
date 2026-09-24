package com.oshi.desktop.app

import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupIdentity
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.LocalDataKeys
import com.oshi.desktop.store.SealedJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where this client's groups live between runs — PARITY.md row 0.17's local half.
 *
 * ============================================================ WHY IT PERSISTS THE WIRE FORM
 *
 * A group is stored as **the exact JSON an iPhone would accept**, produced by
 * [GroupUpdateWire.encodeDefinition] and read back by [GroupUpdateWire.decodeDefinition].
 * There is no second, local-only schema, and that is a deliberate choice rather than
 * laziness:
 *
 *  - The authorizer ([com.oshi.desktop.group.GroupUpdateAuthorizer]) merges an INCOMING
 *    definition into OUR copy. If the two sides had different shapes, every merge would
 *    cross a translation layer that no byte test covers, and a field the local schema
 *    happened not to model would be silently dropped on the first inbound update — which
 *    is exactly how a group picture or an admin flag disappears.
 *  - Round-tripping through the shipped codec on every load means a field this client
 *    cannot represent fails LOUDLY at the store, not quietly at the merge.
 *
 * The cost is that a definition that stops decoding stops being a group. That is handled
 * the way [com.oshi.desktop.store.ContactStore] handles a damaged file — by raising rather
 * than by reading as empty, because "you are in no groups" is a lie a user acts on.
 *
 * __LOCAL_DATA_AT_REST_2026_09_22__ Encrypted at rest when [atRestKey] is given (always, from
 * `OshiClient`): a [SealedJsonFile] envelope under HKDF subkey [LocalDataKeys.GROUPS]. A legacy
 * plaintext file is migrated through the verified write and left untouched if that fails; a
 * sealed file that cannot be opened raises [GroupStoreException] and is never read as empty.
 */
class GroupStore(
    private val file: File = DesktopPaths.file("groups.json"),
    private val atRestKey: ByteArray? = null,
) {

    @Volatile
    private var cache: MutableMap<String, GroupDefinition>? = null

    /** Every group, ordered by most recent activity — what a groups list reads. */
    @Synchronized
    fun all(): List<GroupDefinition> = load().values.sortedByDescending { it.lastActivityUnixMillis }

    /** One group by id, matched on the CANONICAL id — see [GroupIdentity.canonicalGroupId]. */
    @Synchronized
    fun get(groupId: String): GroupDefinition? = load()[GroupIdentity.canonicalGroupId(groupId)]

    /** Insert or replace. Returns what was stored. */
    @Synchronized
    fun put(group: GroupDefinition): GroupDefinition {
        val map = load()
        val canonical = GroupIdentity.canonicalGroupId(group.groupId)
        map[canonical] = group
        persist(map)
        return group
    }

    /**
     * __GROUP_PARITY_2026_09_23__ Groups blocked on this device (iOS `blockedGroups`). Kept by id
     * and outliving the definition, like iOS's list: a blocked group that is deleted and comes
     * back is still blocked.
     */
    private var blocked: MutableSet<String> = mutableSetOf()

    @Synchronized
    fun isBlocked(groupId: String): Boolean { load(); return GroupIdentity.canonicalGroupId(groupId) in blocked }

    @Synchronized
    fun setBlocked(groupId: String, isBlocked: Boolean) {
        val map = load()
        val gid = GroupIdentity.canonicalGroupId(groupId)
        if (if (isBlocked) blocked.add(gid) else blocked.remove(gid)) persist(map)
    }

    /**
     * __GROUP_MUTE_SYNC_2026_09_24__ When this account last CHANGED a group's mute (ms), for
     * own-device sync (devsync `GROUPS.muted/mutedAt`, later wins). Kept beside the
     * definitions like `muted` itself; null = never toggled here (legacy: exported
     * untimestamped when muted, not at all when not).
     */
    private var mutedAt: MutableMap<String, Long> = mutableMapOf()

    @Synchronized
    fun mutedAt(groupId: String): Long? { load(); return mutedAt[GroupIdentity.canonicalGroupId(groupId)] }

    /**
     * Mute / unmute. A LOCAL change ([synced] false) is stamped max([atMs], previous + 1) and
     * only when the value changes; a SYNCED value keeps the merged timestamp as-is.
     */
    @Synchronized
    fun setMuted(groupId: String, muted: Boolean, atMs: Long?, synced: Boolean = false): GroupDefinition? {
        val map = load()
        val gid = GroupIdentity.canonicalGroupId(groupId)
        val g = map[gid] ?: return null
        val prev = mutedAt[gid]
        if (synced) {
            if (g.isMuted == muted && (atMs == null || atMs == prev)) return g
            atMs?.let { mutedAt[gid] = it }
        } else {
            if (g.isMuted == muted) return g
            mutedAt[gid] = maxOf(atMs ?: System.currentTimeMillis(), (prev ?: Long.MIN_VALUE) + 1)
        }
        val updated = g.copy(isMuted = muted)
        map[gid] = updated
        persist(map)
        return updated
    }

    /** Forget a group entirely — what leaving one does locally. */
    @Synchronized
    fun delete(groupId: String): Boolean {
        val map = load()
        val removed = map.remove(GroupIdentity.canonicalGroupId(groupId)) != null
        if (removed) persist(map)
        return removed
    }

    /** Group ids only, for a prefix resolver. */
    @Synchronized
    fun ids(): List<String> = load().keys.toList()

    @Synchronized
    private fun load(): MutableMap<String, GroupDefinition> {
        cache?.let { return it }
        val map = LinkedHashMap<String, GroupDefinition>()
        val read = try {
            SealedJsonFile.read(file, atRestKey, LocalDataKeys.GROUPS)
        } catch (e: Exception) {
            throw GroupStoreException("groups file at ${file.absolutePath} is unreadable", e)
        }
        if (read != null) {
            val o = try {
                JSONObject(read.json)
            } catch (e: Exception) {
                // Same refusal as ContactStore: a damaged file must not read as "no groups".
                throw GroupStoreException("groups file at ${file.absolutePath} is unreadable", e)
            }
            val arr = o.optJSONArray("groups") ?: JSONArray()
            // __GROUP_PARITY_2026_09_23__ `isMuted` is local-only and the wire codec always
            // writes false (Android's pin, iOS re-imposes its own), so it is kept beside the
            // definitions rather than inside them.
            val muted = o.optJSONArray("muted")?.let { m -> (0 until m.length()).map { m.optString(it) }.toSet() }.orEmpty()
            blocked = o.optJSONArray("blocked")?.let { b -> (0 until b.length()).map { b.optString(it) }.toMutableSet() } ?: mutableSetOf()
            mutedAt = o.optJSONObject("mutedAt")?.let { m -> m.keys().asSequence().associateWith { m.optLong(it) }.toMutableMap() } ?: mutableMapOf()
            for (i in 0 until arr.length()) {
                val body = arr.optString(i, "")
                if (body.isEmpty()) continue
                when (val decoded = GroupUpdateWire.decodeDefinition(body)) {
                    is GroupUpdateWire.GroupUpdateDecode.Ok -> {
                        val gid = GroupIdentity.canonicalGroupId(decoded.definition.groupId)
                        map[gid] = decoded.definition.copy(isMuted = gid in muted)
                    }
                    else -> throw GroupStoreException(
                        "a stored group definition no longer decodes (${decoded.javaClass.simpleName}). " +
                            "The store holds the same JSON an iPhone accepts, so this means the codec " +
                            "and the file have diverged — refusing to report the group as absent."
                    )
                }
            }
        }
        cache = map
        // Every definition decoded (a bad one throws above), so the sealed copy loses nothing.
        if (read != null && !read.sealed && atRestKey != null) {
            runCatching { persist(map) } // failure leaves the plaintext file; retried next open
        }
        return cache ?: map
    }

    private fun persist(map: Map<String, GroupDefinition>) {
        val arr = JSONArray()
        for (g in map.values) arr.put(GroupUpdateWire.encodeDefinition(g))
        val muted = JSONArray()
        for ((gid, g) in map) if (g.isMuted) muted.put(gid)
        val json = JSONObject().put("v", 1).put("groups", arr).put("muted", muted)
            .put("blocked", JSONArray(blocked.sorted()))
            .put("mutedAt", JSONObject(mutedAt.toSortedMap() as Map<*, *>)).toString()
        SealedJsonFile.write(file, atRestKey, LocalDataKeys.GROUPS, json) { back ->
            JSONObject(back).getJSONArray("groups").length() == map.size
        }
        cache = LinkedHashMap(map)
    }
}

class GroupStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
