package com.oshi.messenger.network.v2

/**
 * Bounded retry budget for envelopes we could not decrypt (or whose media blob we
 * could not fetch). Straight port of iOS `V2SyncState.shouldRetryUndecryptable` /
 * `clearRetries` (MessageManager+V2.swift:239-269).
 *
 * WHY IT EXISTS — holding the relay ack unconditionally, which is what Android did
 * before, means ONE permanently-undecryptable envelope (peer reinstalled, our SPK
 * regenerated after a Keystore wipe, a forged header) pins `ackSeq` at
 * `lowestFailedSeq - 1` FOREVER. Every message behind it is re-pulled and never
 * delivered: the mailbox is wedged for the life of the install. Not holding at all
 * is the opposite failure — an out-of-order arrival or a momentary ratchet desync
 * loses a message the user was already pushed about. So retries are BOUNDED: hold
 * while budget remains, then let the ack pass so the queue drains.
 *
 * Semantics are iOS's EXACTLY: the counter is incremented on every call and the
 * call returns `n <= maxRetries`, so attempts 1..8 hold and the 9th gives up.
 *
 * Storage is injected so the pure counting logic is unit-testable on the JVM
 * without SharedPreferences.
 */
class V2RetryBudget(private val store: Store) {

    /** Persistence seam: a msgId → attempt-count map. */
    interface Store {
        fun read(): Map<String, Int>
        fun write(counts: Map<String, Int>)
    }

    /** True while this envelope still deserves another attempt (iOS :252-261). */
    fun shouldRetry(msgId: String): Boolean {
        val r = LinkedHashMap(store.read())
        val n = (r[msgId] ?: 0) + 1
        r[msgId] = n
        // Bound the map too — this is SharedPreferences, not a database, and a
        // long-lived install must not accumulate an entry per failure forever.
        // iOS keeps the LAST `seenCap` entries (MessageManager+V2.swift:258).
        if (r.size > CAP) {
            val keep = r.entries.toList().takeLast(CAP)
            r.clear()
            keep.forEach { r[it.key] = it.value }
        }
        store.write(r)
        return n <= MAX_RETRIES
    }

    /** Called once an envelope finally decodes, so a recovered id starts clean (iOS :265-269). */
    fun clear(msgId: String) {
        val r = store.read()
        if (!r.containsKey(msgId)) return
        store.write(LinkedHashMap(r).apply { remove(msgId) })
    }

    companion object {
        /** iOS `V2SyncState.maxRetries` (MessageManager+V2.swift:244). */
        const val MAX_RETRIES = 8

        /** iOS `V2SyncState.seenCap` (MessageManager+V2.swift:193). */
        const val CAP = 500
    }
}
