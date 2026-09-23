package com.oshi.desktop.net

import com.oshi.desktop.store.AtomicFile
import com.oshi.messenger.network.v2.V2RetryBudget
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The three pieces of bookkeeping [V2Router] cannot lose across a restart: how far the
 * relay cursor has advanced, which message ids have already been delivered, and how many
 * times each undecryptable envelope has been retried.
 *
 * None of it is secret — a sequence number and a set of UUIDs — so it lives in a plain
 * JSON file rather than in the key vault, which keeps the vault to material that is
 * actually sensitive and keeps this cheap to write on every poll. Both mobile clients
 * make the same call (SharedPreferences / UserDefaults).
 *
 * All three are load-bearing:
 *
 *  - **`lastSeq` is a cursor, not a count.** It only ever moves forward, and only after
 *    the relay accepted the ack — writing it first would silently skip everything the ack
 *    failed to cover.
 *  - **`seen` must be PERSISTED**, or a crash between delivering a message and acking it
 *    resurfaces that message as a duplicate bubble on the next poll.
 *  - **`retries` must be persisted too**, or the eight-attempt budget resets on every
 *    launch and a permanently-undecryptable envelope pins the cursor forever — the exact
 *    wedge the budget exists to prevent.
 */
class RouterState(private val file: File) {

    private var lastSeq: Long = 0
    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ The DEVICE view's cursor, kept apart from the legacy
     * one (CLIENT_SPEC.md §3.3): `seq` is global so `after` means the same thing in both, but
     * each view acks different items, so one cursor would skip what the other never acked.
     */
    private var deviceLastSeq: Long = 0
    private val seen = LinkedHashSet<String>()
    private val retries = LinkedHashMap<String, Int>()
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    fun lastSeq(): Long = lastSeq

    @Synchronized
    fun setLastSeq(v: Long) {
        if (v > lastSeq) { lastSeq = v; dirty = true }
    }

    @Synchronized
    fun deviceLastSeq(): Long = deviceLastSeq

    @Synchronized
    fun setDeviceLastSeq(v: Long) {
        if (v > deviceLastSeq) { deviceLastSeq = v; dirty = true }
    }

    /** @return true if this id is new (and now recorded); false if it was already delivered. */
    @Synchronized
    fun markSeen(msgId: String): Boolean {
        if (!seen.add(msgId)) return false
        dirty = true
        if (seen.size > SEEN_CAP) {
            val iter = seen.iterator()
            repeat(seen.size - SEEN_CAP) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        return true
    }

    /** Undo a [markSeen] for an envelope whose decrypt failed, so a retry can deliver it. */
    @Synchronized
    fun unmarkSeen(msgId: String) {
        if (seen.remove(msgId)) dirty = true
    }

    @Synchronized
    fun hasSeen(msgId: String): Boolean = msgId in seen

    /** The persistence seam [V2RetryBudget] injects. */
    fun retryStore(): V2RetryBudget.Store = object : V2RetryBudget.Store {
        override fun read(): Map<String, Int> = synchronized(this@RouterState) { LinkedHashMap(retries) }
        override fun write(counts: Map<String, Int>) = synchronized(this@RouterState) {
            retries.clear(); retries.putAll(counts); dirty = true
        }
    }

    /** Write, if anything changed. A quiet poll must not cost a file write. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val json = JSONObject()
            .put("lastSeq", lastSeq)
            .apply { if (deviceLastSeq > 0) put("deviceLastSeq", deviceLastSeq) }
            // Only the newest ids are persisted: the in-memory set bounds duplicates for
            // this run, the file only has to bound them across a restart. Both mobile
            // clients persist 500.
            .put("seen", JSONArray().apply {
                seen.asSequence().drop((seen.size - SEEN_PERSIST_CAP).coerceAtLeast(0)).forEach { put(it) }
            })
            .put("retries", JSONObject().apply { retries.forEach { (k, v) -> put(k, v) } })
        AtomicFile.write(file, json.toString().toByteArray(Charsets.UTF_8))
        dirty = false
    }

    @Synchronized
    fun clear() {
        lastSeq = 0; deviceLastSeq = 0; seen.clear(); retries.clear(); dirty = true
        flush()
    }

    private fun load() {
        if (!file.isFile) return
        try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            lastSeq = o.optLong("lastSeq", 0)
            deviceLastSeq = o.optLong("deviceLastSeq", 0)
            o.optJSONArray("seen")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let(seen::add)
            }
            o.optJSONObject("retries")?.let { r -> for (k in r.keys()) retries[k] = r.optInt(k, 0) }
        } catch (_: Exception) {
            // A corrupt cursor file degrades to "start from the beginning": the relay is
            // non-destructive until acked, so the worst case is re-pulling what is still
            // there — and the `seen` set, empty here, is what would let those arrive
            // twice, so anything already delivered may reappear once. Better than
            // refusing to start, and it self-heals on the next write.
        }
    }

    companion object {
        const val FILE_NAME = "router-state.json"
        const val SEEN_CAP = 4000
        const val SEEN_PERSIST_CAP = 500
    }
}
