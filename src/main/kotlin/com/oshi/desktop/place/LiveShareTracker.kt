package com.oshi.desktop.place

/**
 * How a LIVE location share ENDS, and the one rule that keeps it endable.
 *
 * ============================================================ THE ANSWER: BOTH, AND NEITHER ALONE
 *
 * A live share has two independent terminators, and PARITY.md row 0.19's question — "an
 * explicit stop message, a wall-clock expiry, or both?" — has the answer *both*, on both
 * platforms:
 *
 * 1. **An explicit stop.** `📍LOCATION📍{… "isUpdate":true,"isStopped":true}`, built by
 *    `LocationMessage.stopped(...)` (`OSHI/LocationSharingManager.swift:993-1006`,
 *    `LocationSharingManager.kt:623-637`). iOS fires it from `stopLiveSharing()`'s
 *    `onStopCallback` for BOTH causes — the user tapping Stop and the end-timer running
 *    out (`swift:436-435`, "onStop fires once when sharing ends for ANY reason … Before
 *    this, natural expiry only posted a local event and the peer relied on a fragile local
 *    timer, so a dropped final update could leave them stuck live").
 *
 * 2. **A wall-clock expiry carried in every payload.** `expiresAt`, read by
 *    `isExpired` on the RECEIVER's own clock (`swift:834-839`, `kt:686-692`). This is the
 *    half that survives a lost stop message, and it is why iOS's comment above calls the
 *    timer-only design "fragile".
 *
 * A receiver therefore does not need the stop message to end a share — **provided the
 * wall clock it is measuring against cannot be pushed forward.** That proviso is the whole
 * reason this class exists.
 *
 * ============================================================ THE RATCHET, WHICH ANDROID STILL HAS
 *
 * A live share sends a ping every ~10 s, each one a full `📍LOCATION📍` payload carrying
 * its own `expiresAt`. If each ping recomputes `expiresAt = now + duration`, then the
 * receiver's view of the end time is dragged forward by the full duration on every tick and
 * the share **never expires on the receiver side**.
 *
 * iOS shipped that bug and fixed it on 2026-06-04. `liveUpdate` now reads the share's
 * FIXED `liveShareEndTime` (`swift:940-956`), and the fix comment says exactly what it
 * cost: *"every update extended the receiver's expiry by the full original duration — the
 * share never expired on the receiver side."* A sibling factory, `liveUpdateWithExpiry`,
 * takes the absolute instant so *"every update agrees on the SAME end-of-share moment"*
 * (`swift:958-971`).
 *
 * **Android never took that fix.** `LocationMessage.liveUpdate` is still
 * `expiresAt = now + durationSeconds` (`LocationSharingManager.kt:589-607`), and
 * `liveUpdateWithCustomDuration` just delegates to it (`:612-618`). So an Android sender's
 * share, watched from any client that honours the newest ping's `expiresAt`, keeps ending
 * "in `duration` from the last ping I heard" for as long as pings keep arriving.
 *
 * Is that *forever*? Not literally. It resolves when either the stop message lands or the
 * pings stop, at which point the last ping's expiry still runs out one full duration later
 * — up to **eight hours past** the moment the sender believed sharing ended, since 8 h is
 * the longest preset. But it does mean the honest answer to "does a lost stop message leave
 * the share live forever?" is: **on iOS no, on Android effectively yes for as long as the
 * sender's app keeps pinging, then for one more full duration after that.** Both halves get
 * said out loud rather than quietly reproduced.
 *
 * There is a second, sharper hole that needs no ratchet at all — a live share sent with no
 * `expiresAt` never expires on EITHER platform. That one is documented on
 * [LiveState.UNBOUNDED], where the code that refuses it lives.
 *
 * ============================================================ WHAT THIS CLASS DOES
 *
 * One rule, [EXPIRY_NEVER_EXTENDS]: **within a `sessionId`, the earliest expiry ever seen
 * wins.** A later ping may move the dot; it may never move the deadline outward. It may
 * move it INWARD, because that is what a stop is (`stopped()` sets `expiresAt = now`).
 *
 * This is not an invention. It is exactly the invariant iOS's 2026-06-04 fix establishes on
 * the SENDER side — one fixed `liveShareEndTime` for the life of a share — enforced on the
 * receiver side, where it also holds against a sender that never got the fix. An iPhone
 * peer is unaffected because its pings all carry the identical instant already; an Android
 * peer is held to the deadline of its FIRST ping, which is the deadline its own user chose.
 *
 * Sessions are keyed by `sessionId`. A payload without one is a one-time pin and is not
 * tracked — `live()` always sets a session id on both platforms
 * (`swift:919`, `kt:556`), so a live share arriving without one is malformed and
 * [observe] reports it as [LiveState.UNBOUNDED] rather than guessing a key.
 *
 * Not thread-safe, and deliberately not synchronized: the shipped clients drive their
 * equivalents from a single message-processing path and adding a lock here would imply a
 * concurrency story this row has not earned. Callers that need one wrap it.
 */
class LiveShareTracker {

    /**
     * The rule, as a named constant so it can be grepped and so the mutation test that
     * proves it has something to point at. Flipping it to `false` makes this class honour
     * whatever the newest ping claims — i.e. reproduces Android's ratchet — which is what
     * `LiveShareExpiryTest` checks it cannot do.
     */
    companion object {
        const val EXPIRY_NEVER_EXTENDS: Boolean = true
    }

    /** What this tracker believes about one session, after everything it has seen. */
    data class Session(
        val sessionId: String,
        /** The earliest expiry any payload in this session has ever carried, in Unix millis. */
        val pinnedExpiryMs: Long,
        /** True once any payload in this session carried `isStopped`. Never un-set. */
        val stopped: Boolean,
        /** The most recent coordinates seen, for rendering. */
        val latitude: Double,
        val longitude: Double,
        /** Unix millis of the newest payload accepted into this session. */
        val lastSeenMs: Long,
    )

    private val sessions = LinkedHashMap<String, Session>()

    /** Read-only view, for a CLI or a test. Insertion-ordered. */
    fun sessions(): Map<String, Session> = LinkedHashMap(sessions)

    fun session(sessionId: String): Session? = sessions[sessionId]

    /**
     * Fold [payload] into this tracker and report the state of its share as of [nowMs].
     *
     * Returns the payload's own [LocationPayload.liveState] untouched for anything this
     * tracker does not own: a one-time pin, a share with no session id, and any payload
     * whose own reading is already [LiveState.UNBOUNDED].
     *
     * For a tracked session the answer is taken against the PINNED expiry, not the
     * payload's — see the class doc.
     */
    fun observe(payload: LocationPayload, nowMs: Long): LiveState {
        val own = payload.liveState(nowMs)
        if (own == LiveState.NOT_LIVE || own == LiveState.UNBOUNDED) return own

        val id = payload.sessionId ?: return LiveState.UNBOUNDED
        val end = payload.expiresAtMs ?: return LiveState.UNBOUNDED

        val prior = sessions[id]
        val pinned = when {
            prior == null -> end
            // The rule. `minOf` is the whole enforcement: inward always, outward never.
            EXPIRY_NEVER_EXTENDS -> minOf(prior.pinnedExpiryMs, end)
            else -> end
        }
        val stopped = (prior?.stopped ?: false) || payload.isStopped

        sessions[id] = Session(
            sessionId = id,
            pinnedExpiryMs = pinned,
            stopped = stopped,
            latitude = payload.latitude,
            longitude = payload.longitude,
            lastSeenMs = maxOf(prior?.lastSeenMs ?: payload.timestampMs, payload.timestampMs),
        )

        if (stopped) return LiveState.STOPPED
        return if (nowMs >= pinned) LiveState.EXPIRED else LiveState.LIVE
    }

    /**
     * Drop every session that has ended as of [nowMs], and report how many went.
     *
     * The desktop equivalent of iOS's `pruneExpiredPeerLocations()`
     * (`LocationSharingManager.swift:758-767`), which iOS runs on
     * `willEnterForeground` because a suspended app's timers do not fire. A desktop client
     * has the same problem for the same reason — a laptop lid closes — and the shipped
     * comment (`swift:55-61`) is worth having read: *"if the user backgrounds the app for
     * the entire share duration, the timer never fires and the share keeps running past its
     * intended expiry."* Expiry here is evaluated, never scheduled, so a sleep cannot skip
     * it; this call only reclaims the memory.
     */
    fun pruneEnded(nowMs: Long): Int {
        val gone = sessions.filterValues { it.stopped || nowMs >= it.pinnedExpiryMs }.keys
        gone.forEach { sessions.remove(it) }
        return gone.size
    }
}
