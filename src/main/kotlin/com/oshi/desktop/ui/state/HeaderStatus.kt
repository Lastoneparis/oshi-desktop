package com.oshi.desktop.ui.state

/**
 * The two live indicators in the Messages header, ported from the iOS list
 * (`MessagesListView.swift`, __TOOLBAR_DRIFT_2026_09_22__ / __LIVE_SHARE_COUNTER_2026_09_22__).
 * No compose imports: this is the part that is tested.
 */

/** What the reach badge draws. Always drawn — grey when [connected] is false. */
data class NetworkBadge(
    /** LAN mesh peers as DISPLAYED (a drop is held for [NetworkBadgeTracker.DROP_GRACE_MS]). */
    val meshPeers: Int,
    /** True while a Meshtastic radio is attached (`OshiClient.loraAttached`). */
    val loraAttached: Boolean,
    /** Other LoRa nodes heard within two hours; meaningful only when [loraAttached]. */
    val loraNodes: Int,
) {
    val connected: Boolean get() = meshPeers > 0 || loraAttached

    companion object {
        val OFFLINE = NetworkBadge(0, false, 0)
    }
}

/**
 * Holds a mesh peer DROP for a few seconds before showing it.
 *
 * mDNS peers come and go constantly; on iOS every flap redrew the bar, which is half of what
 * read as "the bar moves by itself". A rise is shown at once — a new peer is news — a fall only
 * once it has lasted [DROP_GRACE_MS].
 */
class NetworkBadgeTracker(private val dropGraceMs: Long = DROP_GRACE_MS) {

    companion object {
        const val DROP_GRACE_MS: Long = 8_000
    }

    private var shown = 0
    /** When the live count first fell below [shown], or null while it has not. */
    private var dropSinceMs: Long? = null

    fun observe(liveMeshPeers: Int, loraAttached: Boolean, loraNodes: Int, nowMs: Long): NetworkBadge {
        val live = liveMeshPeers.coerceAtLeast(0)
        when {
            live >= shown -> { shown = live; dropSinceMs = null }
            else -> {
                val since = dropSinceMs ?: nowMs.also { dropSinceMs = it }
                if (nowMs - since >= dropGraceMs) { shown = live; dropSinceMs = null }
            }
        }
        return NetworkBadge(shown, loraAttached, if (loraAttached) loraNodes.coerceAtLeast(0) else 0)
    }
}

/** One contact currently sharing their live location WITH this desktop. */
data class IncomingShare(
    val from: String,
    val label: String,
    /** Unix millis — the tracker's PINNED expiry, never extended by a later ping. */
    val endsAtMs: Long,
)

/**
 * INCOMING live shares, per sender.
 *
 * The phones count shares the user is SENDING. A desktop has no GPS (PARITY.md row 0.19:
 * receive-only by construction), so the meaningful counter here is the other direction: who is
 * sharing with me right now. The header says so ("LIVE"), it never says "sharing".
 *
 * One entry per SESSION, keyed by session id; a sender with two sessions counts once, with the
 * later end. Expiry is evaluated against the clock at read time, never scheduled — a laptop
 * lid that closes must not leave a share showing as live.
 */
class IncomingLiveShares {

    private data class Entry(val from: String, val endsAtMs: Long, val stopped: Boolean)

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Entry>()

    /**
     * Fold one received location. [live] is the tracked state (LIVE vs anything else);
     * [endsAtMs] the pinned expiry. A session that stops or expires stays stopped.
     */
    fun observe(from: String, sessionId: String, live: Boolean, endsAtMs: Long) = synchronized(lock) {
        val prior = sessions[sessionId]
        if (prior != null && prior.from != from) return@synchronized // a session id is not a transferable claim
        val stopped = (prior?.stopped ?: false) || !live
        sessions[sessionId] = Entry(from, minOf(prior?.endsAtMs ?: endsAtMs, endsAtMs), stopped)
        while (sessions.size > MAX_SESSIONS) sessions.entries.iterator().apply { next(); remove() }
    }

    /** Senders with a share still live at [nowMs], soonest-ending first. */
    fun active(nowMs: Long, labelFor: (String) -> String): List<IncomingShare> = synchronized(lock) {
        sessions.values.removeAll { it.stopped || nowMs >= it.endsAtMs }
        sessions.values
            .groupBy { it.from }
            .map { (from, entries) -> IncomingShare(from, labelFor(from), entries.maxOf { it.endsAtMs }) }
            .sortedBy { it.endsAtMs }
    }

    private companion object {
        const val MAX_SESSIONS = 128
    }
}
