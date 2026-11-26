package com.oshi.desktop.store

import com.oshi.desktop.msg.AdvancedPrivacyPayload
import com.oshi.desktop.msg.AdvancedPrivacyPolicy
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ Which conversations have advanced chat privacy on, and which
 * peers read the `🛡ADV_PRIVACY🛡` payload. Desktop counterpart of Android
 * `AdvancedChatPrivacyStore` + `AdvancedChatPrivacyReaders` (iOS `AdvancedPrivacy.Store` /
 * `Readers`).
 *
 * Keyed by the canonical contact key for a 1:1, by the upper-cased group UUID for a group.
 *
 * Sealed at rest ([SealedSmallJson], purpose [LocalDataKeys.ADV_PRIVACY]) like the offline-only
 * map beside it: the list of conversations someone chose to protect, and of peers known to run
 * a recent build, is itself social-graph data.
 *
 * ## Who may receive the payload ([peerReads])
 *
 * A peer qualifies when its prekey bundle (or the server's `accountCaps`) advertises
 * [AdvancedPrivacyPolicy.CAP_ADVANCED_PRIVACY], or when it has itself sent us one. Unknown means
 * no: a false "no" costs one peer not applying a switch it could not apply anyway; a false "yes"
 * puts raw JSON in an iOS 1.0.43 thread. Both records are bounded at [MAX_PEERS].
 */
class AdvancedPrivacyStore(
    private val file: File?,
    private val atRestKey: ByteArray? = null,
) {
    data class State(val on: Boolean, val changedAtUnixSeconds: Long)

    private val lock = Any()
    private var loaded = false
    private var writable = true
    private var migrateOnLoad = false
    private val states = HashMap<String, State>()
    /** Peers that sent us a payload, oldest first. */
    private val readers = LinkedHashSet<String>()
    /** Last caps seen in a peer's bundle / `accountCaps`, oldest first. */
    private val caps = LinkedHashMap<String, Int>()
    /** Protected conversations currently on screen (window capture guard). Memory only. */
    private val onScreen = HashSet<String>()
    private val screenListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    // ------------------------------------------------------------------ conversation state

    fun isOn(conversationId: String): Boolean = conversationId.isNotEmpty() && synchronized(lock) {
        ensureLoaded()
        states[canonical(conversationId)]?.on == true
    }

    fun state(conversationId: String): State? = synchronized(lock) {
        ensureLoaded()
        states[canonical(conversationId)]
    }

    /** We switched it. Always wins locally: it is the newest thing that happened. */
    fun setLocal(change: AdvancedPrivacyPayload.Change, conversationId: String) {
        synchronized(lock) {
            ensureLoaded()
            states[canonical(conversationId)] = State(change.on, change.atUnixSeconds)
            persist()
        }
    }

    /**
     * A participant switched it. Last-writer-wins by `at`, their clock bounded by ours, a tie
     * resolving to ON. True when the state changed hands to this change.
     */
    fun applyIncoming(
        change: AdvancedPrivacyPayload.Change,
        conversationId: String,
        nowUnixSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Boolean {
        val at = AdvancedPrivacyPayload.clampedAt(change.atUnixSeconds, nowUnixSeconds)
        synchronized(lock) {
            ensureLoaded()
            val key = canonical(conversationId)
            val existing = states[key]
            if (!AdvancedPrivacyPayload.wins(existing?.on, existing?.changedAtUnixSeconds, change.on, at)) return false
            states[key] = State(change.on, at)
            persist()
            return true
        }
    }

    // ------------------------------------------------------------------ readers

    /** [peer] sent us an `🛡ADV_PRIVACY🛡` payload: it reads them. */
    fun noteReader(peer: String) {
        if (peer.isBlank()) return
        synchronized(lock) {
            ensureLoaded()
            val k = canonical(peer)
            if (!readers.add(k)) return
            while (readers.size > MAX_PEERS) readers.remove(readers.first())
            persist()
        }
    }

    /** A bundle / device list told us [peer]'s capability bits. Null = said nothing. */
    fun noteCaps(peer: String, peerCaps: Int?) {
        if (peer.isBlank() || peerCaps == null) return
        synchronized(lock) {
            ensureLoaded()
            val k = canonical(peer)
            if (caps[k] == peerCaps) return
            caps.remove(k)
            caps[k] = peerCaps
            while (caps.size > MAX_PEERS) caps.remove(caps.keys.first())
            persist()
        }
    }

    fun sentUsOne(peer: String): Boolean = peer.isNotBlank() && synchronized(lock) {
        ensureLoaded()
        canonical(peer) in readers
    }

    fun capsOf(peer: String): Int? = synchronized(lock) {
        ensureLoaded()
        caps[canonical(peer)]
    }

    /** The live answer for one peer: bundle caps or proof by payload. */
    fun peerReads(peer: String): Boolean =
        AdvancedPrivacyPolicy.peerReads(accountCaps = capsOf(peer), sentUsOne = sentUsOne(peer))

    // ------------------------------------------------------------------ window guard

    /** True while a protected conversation is on screen. */
    val captureProtectionRequested: Boolean get() = synchronized(lock) { onScreen.isNotEmpty() }

    /** The conversation shown is [conversationId] (null = none) and its protection is [on]. */
    fun setOnScreen(conversationId: String?, on: Boolean) {
        val (before, after) = synchronized(lock) {
            val b = onScreen.isNotEmpty()
            onScreen.clear()
            if (conversationId != null && on) onScreen += canonical(conversationId)
            b to onScreen.isNotEmpty()
        }
        if (before != after) screenListeners.forEach { runCatching { it(after) } }
    }

    fun onCaptureProtectionChange(l: (Boolean) -> Unit) { screenListeners += l }

    // ------------------------------------------------------------------ disk

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        val disk = SealedSmallJson.load(f, atRestKey, LocalDataKeys.ADV_PRIVACY)
        writable = disk.writable
        migrateOnLoad = disk.needsMigration
        val root = disk.json?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (root != null) {
            root.optJSONObject("states")?.let { s ->
                for (id in s.keys()) {
                    val o = s.optJSONObject(id) ?: continue
                    // The restrictive reading for a damaged entry: protected.
                    states[id] = State(o.optBoolean("on", true), o.optLong("at", 0L))
                }
            }
            root.optJSONArray("readers")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let(readers::add) }
            root.optJSONObject("caps")?.let { c -> for (k in c.keys()) caps[k] = c.optInt(k, 0) }
        }
        if (migrateOnLoad) { migrateOnLoad = false; persist() }
    }

    private fun persist() {
        val f = file ?: return
        if (!writable) return
        val s = JSONObject()
        states.forEach { (id, st) -> s.put(id, JSONObject().put("on", st.on).put("at", st.changedAtUnixSeconds)) }
        val root = JSONObject()
            .put("states", s)
            .put("readers", JSONArray(readers.toList()))
            .put("caps", JSONObject(caps as Map<*, *>))
        runCatching { SealedSmallJson.save(f, atRestKey, LocalDataKeys.ADV_PRIVACY, root.toString()) }
    }

    companion object {
        const val FILE_NAME = "advanced-chat-privacy.json"
        const val MAX_PEERS = 2000

        /**
         * Group ids are UUIDs that iOS spells upper-case and Android sometimes lower; contact
         * keys fold their base64 spellings.
         */
        fun canonical(id: String): String {
            if (id.isEmpty()) return id
            return runCatching { UUID.fromString(id.trim()).toString().uppercase(Locale.US) }
                .getOrElse { com.oshi.desktop.group.GroupIdentity.canonicalIdentity(id) }
        }
    }
}
