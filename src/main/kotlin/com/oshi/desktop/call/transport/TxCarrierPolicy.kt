package com.oshi.desktop.call.transport

/**
 * Which ONE carrier a media frame leaves on — the phones' policy, ported so both ends
 * reach the same conclusion from the same evidence.
 *
 * iOS `VoiceCallManager.swift` `chooseAudioTxPrimary` (:10079-10117), Android
 * `EnhancedCallManager.kt` `chooseAudioTxPrimary` (:1004-1049):
 *
 *  1. **P2P** (the selected pair, direct or via our TURN allocation) while it is healthy —
 *     i.e. while it is DELIVERING the peer's media to us, after a grace period.
 *  2. **Evidence**: the relay (`:8089`) or the WebSocket relay if the peer's media is
 *     arriving on it right now.
 *  3. **Total blackout** (nothing arriving on any carrier): rotate through the candidates
 *     every [BLACKOUT_ROTATE_MS], so two ends that each demoted a carrier because the
 *     other stopped using it cannot deadlock (iOS :10064-10072).
 *  4. **Availability**: the relay if registered and answering, else the WebSocket relay.
 *
 * WHY ONE CARRIER AND NOT "P2P + relay" (the bug this class fixes, measured with a real
 * iPhone and a real Samsung on 2026-09-23): the desktop used to send every frame on the
 * selected pair AND on the relay once its own P2P went quiet. The phone kept receiving
 * our direct audio, so ITS P2P looked healthy and it kept sending direct — into a path
 * that never reached us. Samsung: socket rx 40 vs relay rx 877, about 5 accepted frames/s
 * instead of 50, "signal faible". iPhone: 20 packets in 28 s, CONNECTION_LOST. Stopping
 * our P2P sends when our P2P isn't delivering is what makes the phone's P2P go stale too,
 * so both ends converge on the carrier that works in both directions.
 *
 * After every change of primary the previous carrier is mirrored for
 * [DUPLICATE_WINDOW_MS] (make-before-break, iOS `txDuplicateWindowSeconds`); the peer's
 * replay window drops the duplicates.
 */
class TxCarrierPolicy {

    enum class Carrier { P2P, UDP_RELAY, WS }

    /** What the chooser sees at one instant. Pure data, so the policy is unit-testable. */
    data class Evidence(
        /** A pair is selected and it has delivered the peer's media recently (or is in grace). */
        val p2pHealthy: Boolean,
        /** A pair is selected at all — P2P can be probed during a blackout. */
        val p2pPlausible: Boolean,
        val relayUsable: Boolean,
        val relayDelivering: Boolean,
        val wsAvailable: Boolean,
        val wsUsable: Boolean,
        val wsDelivering: Boolean,
        /** The peer's media arrived on SOME carrier inside [LIVENESS_MS]. */
        val anythingArriving: Boolean,
    )

    @Volatile var current: Carrier? = null
        private set
    @Volatile private var changedAtMs = 0L
    @Volatile private var previous: Carrier? = null
    @Volatile private var blackoutRotatedAtMs = 0L
    @Volatile private var blackoutCursor = 0

    /** How many times the primary changed — a diagnostic. */
    @Volatile var switches = 0
        private set

    /**
     * Pick the carrier for this frame. Only the audio loop passes [advance] = true, so
     * video (another thread) cannot steal blackout-rotation ticks (iOS :10074-10078).
     * Returns null when no carrier exists at all.
     */
    @Synchronized
    fun choose(nowMs: Long, e: Evidence, advance: Boolean = true): Carrier? {
        val pick = decide(nowMs, e, advance)
        if (advance && pick != current) {
            previous = current
            current = pick
            changedAtMs = nowMs
            if (previous != null) switches++
        }
        return pick
    }

    /** The carrier to mirror this frame onto, if a hand-over is in progress. */
    fun mirror(nowMs: Long): Carrier? {
        val p = previous ?: return null
        if (p == current || nowMs - changedAtMs >= DUPLICATE_WINDOW_MS) return null
        return p
    }

    private fun decide(nowMs: Long, e: Evidence, advance: Boolean): Carrier? {
        if (e.p2pHealthy) return Carrier.P2P
        // Evidence first: a carrier landing the peer's frames wins over any state.
        if (e.relayDelivering) return Carrier.UDP_RELAY
        if (e.wsDelivering) return Carrier.WS
        if (!e.anythingArriving) {
            val candidates = ArrayList<Carrier>(3)
            if (e.relayUsable) candidates.add(Carrier.UDP_RELAY)
            if (e.wsAvailable) candidates.add(Carrier.WS)
            if (e.p2pPlausible) candidates.add(Carrier.P2P)
            if (candidates.isEmpty()) return null
            if (advance) {
                if (blackoutRotatedAtMs == 0L) blackoutRotatedAtMs = nowMs
                else if (nowMs - blackoutRotatedAtMs >= BLACKOUT_ROTATE_MS) {
                    blackoutRotatedAtMs = nowMs
                    blackoutCursor++
                }
            }
            return candidates[Math.floorMod(blackoutCursor, candidates.size)]
        }
        if (advance) blackoutRotatedAtMs = 0L
        return when {
            e.relayUsable -> Carrier.UDP_RELAY
            e.wsUsable -> Carrier.WS
            e.p2pPlausible -> Carrier.P2P
            else -> null
        }
    }

    companion object {
        /** iOS `transportLivenessWindow`. */
        const val LIVENESS_MS = 2_000L
        /** iOS `txBlackoutRotateSeconds`. */
        const val BLACKOUT_ROTATE_MS = 2_000L
        /** iOS `txDuplicateWindowSeconds`. */
        const val DUPLICATE_WINDOW_MS = 2_000L
    }
}
