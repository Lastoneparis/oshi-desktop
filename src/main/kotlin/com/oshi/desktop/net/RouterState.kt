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
    private val retries = LinkedHashMap<String, V2RetryBudget.Entry>()

    /**
     * __DESKTOP_RATCHET_HEAL_2026_10_29__ When a peer's session first looked broken, and when
     * we last sent that peer a heal acknowledgement.
     *
     * **Persisted, and that is the point rather than a nicety.** `RatchetHealSignal.Policy`
     * has a one-minute cooldown and a `me < peer` tie-break in which the non-initiator waits
     * a full cooldown from FIRST EVIDENCE. Both numbers are useless if a restart forgets
     * them: the cooldown stops bounding anything, and — worse — the non-initiator's clock
     * restarts, so a pair that keeps reconnecting could both act and reproduce exactly the
     * symmetric loop the tie-break exists to prevent.
     *
     * Kept apart from [retries] for the reason the stall clock is: the retry map is CAPPED
     * and evicts, and a rate limit that can be evicted is not a rate limit.
     */
    private val healEvidence = HashMap<String, Long>()
    private val healSent = HashMap<String, Long>()

    /**
     * __BUDGET_REFUND_DEADLOCK_2026_10_20__ Epoch millis since which the ack cursor
     * has NOT advanced, or 0 when it has never been set.
     *
     * Kept apart from [retries] on purpose, and that separation IS the guarantee:
     * the retry map is capped, so any ceiling written into it is refunded the
     * moment an entry is evicted — which, with a backlog larger than the cap, is
     * the normal case and not the rare one. A ceiling that can be evicted holds
     * nothing. This one number never can be.
     */
    private var stallSince: Long = 0
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

    /** The per-envelope persistence seam [V2RetryBudget] injects. */
    fun retryStore(): V2RetryBudget.Store = object : V2RetryBudget.Store {
        override fun read(): Map<String, V2RetryBudget.Entry> =
            synchronized(this@RouterState) { LinkedHashMap(retries) }
        override fun write(entries: Map<String, V2RetryBudget.Entry>) = synchronized(this@RouterState) {
            retries.clear(); retries.putAll(entries); dirty = true
        }
    }

    /** The cursor-stall seam, deliberately NOT part of [retryStore]. */
    fun retryStall(): V2RetryBudget.Stall = object : V2RetryBudget.Stall {
        override fun since(): Long = synchronized(this@RouterState) { stallSince }
        override fun set(millis: Long) = synchronized(this@RouterState) {
            stallSince = millis; dirty = true
        }
    }

    /**
     * When this peer's session first looked broken. Records [nowMs] the first time and
     * returns it unchanged afterwards, so the non-initiator's wait is measured from the
     * FIRST evidence and not from the latest envelope in a long broken batch.
     */
    @Synchronized
    fun healEvidenceAt(peer: String, nowMs: Long): Long =
        healEvidence.getOrPut(peer) { nowMs.also { dirty = true } }

    /** When we last sent this peer a heal acknowledgement, or null. */
    @Synchronized
    fun healSentAt(peer: String): Long? = healSent[peer]

    /**
     * Record that an acknowledgement went out, and CLEAR the evidence stamp: the next
     * breakage is a new episode, and measuring its wait from an old stamp would let the
     * non-initiator act immediately for ever after one slow repair.
     */
    @Synchronized
    fun noteHealSent(peer: String, nowMs: Long) {
        healSent[peer] = nowMs
        healEvidence.remove(peer)
        dirty = true
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
            // `a` attempts, `t` first-failure millis. The timestamp is what makes
            // eviction deterministic; without it a restart refunds the budget of
            // whatever the cap dropped.
            .put("retries", JSONObject().apply {
                retries.forEach { (k, v) ->
                    put(k, JSONObject().put("a", v.attempts).put("t", v.firstFailedAt))
                }
            })
            .apply { if (stallSince > 0) put("cursorStalledSince", stallSince) }
            .apply {
                if (healEvidence.isNotEmpty()) put("healEvidence", JSONObject().apply {
                    healEvidence.forEach { (k, v) -> put(k, v) }
                })
                if (healSent.isNotEmpty()) put("healSent", JSONObject().apply {
                    healSent.forEach { (k, v) -> put(k, v) }
                })
            }
        AtomicFile.write(file, json.toString().toByteArray(Charsets.UTF_8))
        dirty = false
    }

    @Synchronized
    fun clear() {
        lastSeq = 0; deviceLastSeq = 0; seen.clear(); retries.clear(); stallSince = 0
        healEvidence.clear(); healSent.clear(); dirty = true
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
            // Two shapes on disk. The object form is current. A bare int is a file
            // written before the budget had a timestamp: the ATTEMPTS are what the
            // bound is made of, so they are kept, and the missing first-failure is
            // dated now — the alternative, zero, would make every migrated entry the
            // first thing evicted and hand back exactly the budget this is here to
            // spend.
            o.optJSONObject("healEvidence")?.let { h ->
                for (k in h.keys()) h.optLong(k, 0L).takeIf { it > 0 }?.let { healEvidence[k] = it }
            }
            o.optJSONObject("healSent")?.let { h ->
                for (k in h.keys()) h.optLong(k, 0L).takeIf { it > 0 }?.let { healSent[k] = it }
            }
            o.optJSONObject("retries")?.let { r ->
                val now = System.currentTimeMillis()
                for (k in r.keys()) {
                    val entry = r.optJSONObject(k)
                    retries[k] = if (entry != null) {
                        V2RetryBudget.Entry(entry.optInt("a", 0), entry.optLong("t", now))
                    } else {
                        V2RetryBudget.Entry(r.optInt(k, 0), now)
                    }
                }
            }
            stallSince = o.optLong("cursorStalledSince", 0)
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
