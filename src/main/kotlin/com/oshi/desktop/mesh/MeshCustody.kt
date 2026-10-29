package com.oshi.desktop.mesh

/**
 * __DESKTOP_MESH_CUSTODY_2026_10_29__ A node that cannot pass a message on right now takes
 * custody of it and re-offers it when a peer appears — iOS's `MeshCustodyStore`.
 *
 * ## The gap this fills, and it is the same one iOS had
 *
 * The owner's ask, verbatim in iOS's file: *"Nobody has Internet. OSHI → BLE → BLE → BLE →
 * store-and-forward → recipient."* The multi-hop half already worked here —
 * `MeshNode.relayToRecipient` learns routes and forwards. The store-and-forward half did not
 * exist at all, and the drop is silent in exactly the same shape: the loop over `connections`
 * skips every peer that has seen the message or that sent it, and when nothing is eligible it
 * simply falls out of the loop having sent nothing. **So the chain only ever worked when every
 * hop was awake simultaneously — walk out of range for thirty seconds and the message was
 * gone.**
 *
 * A desktop is arguably the better custodian: a laptop left on has mains power and disk,
 * which is exactly what a phone does not.
 *
 * ## What it is carrying, and why every bound is deliberate
 *
 * Held payloads are OTHER PEOPLE'S traffic. They are already ciphertext — this node cannot
 * read them and never stores cleartext — but they are still somebody else's data on the
 * user's machine, costing their disk. Hence: a bounded count, a bounded byte total, a TTL,
 * oldest-first eviction, and release the moment the message has been handed on.
 *
 * **It is store-and-FORWARD, not store-and-confirm.** Custody ends when the message has been
 * passed to a peer that had not already seen it. There is no app-level ack on the mesh to
 * wait for, and inventing one here would mean holding strangers' data indefinitely on the
 * strength of a guess.
 *
 * ## The one rule that is not a bound
 *
 * A held message is stored EXACTLY as the forwarder built it — hop count already incremented,
 * this node already in `seenBy`. Re-originating it at hop 0 on release would hand it a fresh
 * hop budget and let a deferred packet outlive the ceiling, which is the same class of bug
 * the hop ceiling exists to prevent.
 */
object MeshCustodyPolicy {

    /**
     * How long this node will carry someone else's message.
     *
     * Six hours. iOS's reasoning, which is the interesting part: ten minutes does not survive
     * the case this exists for — a courier who walks out of range and comes back — and a day
     * is too long, because a personal machine is not a mail server and the window is how much
     * of other people's data sits on it. A long wait belongs on the relay, which has its own
     * store-and-forward; this is for the off-grid gap.
     */
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** Bounds, enforced together, oldest evicted first. */
    const val MAX_COUNT = 200
    const val MAX_BYTES = 4 * 1024 * 1024

    fun isExpired(takenAtMs: Long, nowMs: Long): Boolean = nowMs - takenAtMs >= MAX_AGE_MS

    /**
     * Whether a store holding [count] messages and [bytes] bytes has room for one more of
     * [incoming] bytes.
     *
     * **A single message larger than the whole budget is refused outright** rather than
     * evicting everything to make space for it: one oversized packet must not be able to
     * clear out two hundred small ones that are all still deliverable.
     */
    fun hasRoom(count: Int, bytes: Int, incoming: Int): Boolean {
        if (incoming > MAX_BYTES) return false
        return count < MAX_COUNT && bytes + incoming <= MAX_BYTES
    }
}

/**
 * The held messages themselves. Memory only, bounded, and never written to disk: custody is
 * for the minutes-to-hours gap while a courier is out of range, and persisting other people's
 * ciphertext across restarts would extend a six-hour promise into an open-ended one.
 */
class MeshCustody(private val enabled: Boolean = true) {

    /** One message in custody, exactly as the forwarder built it. */
    data class Held(val message: MeshMessage, val bytes: ByteArray, val takenAtMs: Long) {
        // `bytes` makes the generated equals/hashCode wrong by identity; custody is keyed by
        // message id and never compared, so they are defined away rather than left misleading.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val lock = Any()
    private val held = LinkedHashMap<String, Held>()
    private var bytesHeld = 0

    val size: Int get() = synchronized(lock) { held.size }
    val byteCount: Int get() = synchronized(lock) { bytesHeld }

    /**
     * Take custody of a message this node could not pass on.
     *
     * @return true when it was stored.
     */
    fun take(message: MeshMessage, bytes: ByteArray, nowMs: Long): Boolean {
        if (!enabled) return false
        synchronized(lock) {
            expire(nowMs)
            if (held.containsKey(message.id)) return false
            // THE OVERSIZED CASE IS REFUSED BEFORE ANY EVICTION, and this line is the fix for
            // a bug the test caught in the first version of this file. `hasRoom` is false for
            // an oversized message no matter how empty the store is, so the eviction loop
            // below happily emptied the whole store and THEN refused it — one oversized
            // packet clearing out two hundred deliverable ones, which is precisely what the
            // bound exists to prevent. Checked first, it costs nothing and cannot.
            if (bytes.size > MeshCustodyPolicy.MAX_BYTES) return false
            // Evict oldest-first until there is room, which the LinkedHashMap's insertion
            // order gives for nothing.
            while (!MeshCustodyPolicy.hasRoom(held.size, bytesHeld, bytes.size) && held.isNotEmpty()) {
                val oldest = held.keys.first()
                held.remove(oldest)?.let { bytesHeld -= it.bytes.size }
            }
            if (!MeshCustodyPolicy.hasRoom(held.size, bytesHeld, bytes.size)) return false
            held[message.id] = Held(message, bytes, nowMs)
            bytesHeld += bytes.size
            return true
        }
    }

    /**
     * Messages worth offering to [peerKey] — those it has not already seen and did not send.
     *
     * Expired entries are dropped first, so a peer appearing after six hours is not handed a
     * stale packet.
     */
    fun offersFor(peerKey: String, nowMs: Long): List<Held> = synchronized(lock) {
        expire(nowMs)
        held.values.filter { it.message.seenBy.none { k -> k == peerKey } && it.message.senderPublicKey != peerKey }
    }

    /** Custody ends: the message reached a peer that had not seen it. */
    fun release(messageId: String) {
        synchronized(lock) { held.remove(messageId)?.let { bytesHeld -= it.bytes.size } }
    }

    /** Drop everything past its TTL. Called on every take and offer; safe to call often. */
    fun expire(nowMs: Long) {
        synchronized(lock) {
            val dead = held.entries.filter { MeshCustodyPolicy.isExpired(it.value.takenAtMs, nowMs) }
            for (e in dead) { held.remove(e.key); bytesHeld -= e.value.bytes.size }
        }
    }
}
