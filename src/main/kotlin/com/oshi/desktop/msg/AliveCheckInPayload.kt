package com.oshi.desktop.msg

import org.json.JSONObject

/**
 * __ALIVE_CHECK_IN_2026_11_04__ `💓ALIVE💓` — "I'm alive", a recurring liveness
 * confirmation scoped to one conversation.
 *
 * Third implementation of a wire format the phones already speak
 * (`OSHI/AliveCheckIn.swift`, `OSHI-Android/.../data/model/AliveCheckIn.kt`), and
 * written against those rather than from a description of them.
 *
 * ## What it is, and what it is NOT
 *
 * Not [com.oshi.desktop.place.CheckInPayload] (`🛡️CHECK_IN🛡️`), which accompanies
 * ONE journey: an ETA, a route, a position, an emergency payload. That answers
 * "did she get home?".
 *
 * This answers a different question, repeatedly and with **no position at all**:
 * "is he still answering?". A person commits to confirming every N hours; the
 * other side sees a countdown and learns when the deadline passes unanswered.
 * One timestamp and nothing else is the design — a liveness signal that also
 * broadcast a position would be a tracker.
 *
 * ## Wire
 *
 * `💓ALIVE💓{"v":1,"t":"ok","at":1762300123.456,"iv":86400}`
 *
 * `at` is Unix **seconds**, fractional. Seconds because `Date`'s own encoding on
 * Apple platforms is seconds-based and this JSON crosses to it — the same trap
 * [OfflineOnlyPayload] and [PollPayload] document. Fractional because those
 * milliseconds are what make two events in the same second distinguishable,
 * which [Policy.accepts] needs in order to drop a duplicate without dropping a
 * real second event.
 *
 * `iv` is the sender's interval in seconds, restated on EVERY event rather than
 * only when it changes: a peer that was offline when the schedule was set would
 * otherwise hold a confirmation with no deadline attached to it, which is the
 * one display state that reassures without meaning anything.
 */
object AliveCheckInPayload {

    const val VERSION = 1

    /**
     * The interval bounds the phones enforce, restated so this client cannot announce a
     * schedule they would refuse to display.
     *
     * The floor is an hour: anything shorter turns a safety feature into a notification
     * every few minutes, and the first thing a user does with that is mute the channel
     * the overdue alert travels on. The ceiling is thirty days, past which "overdue"
     * stops describing anything.
     */
    const val MIN_INTERVAL_SECONDS = 3_600L
    const val MAX_INTERVAL_SECONDS = 30L * 24 * 3_600
    const val DEFAULT_INTERVAL_SECONDS = 24L * 3_600

    enum class Kind(val wire: String) {
        /** Starting, or re-timing, a schedule. Counts as a confirmation too. */
        ENABLED("on"),

        /** "I'm alive", at [Event.atUnix]. */
        CONFIRMED("ok"),

        /** Stopping. The other side goes back to showing nothing. */
        DISABLED("off");

        companion object {
            fun fromWire(raw: String): Kind? = entries.firstOrNull { it.wire == raw }
        }
    }

    data class Event(
        val kind: Kind,
        val atUnix: Double,
        /** Zero on [Kind.DISABLED], where there is no longer a deadline to describe. */
        val intervalSeconds: Long,
        val version: Int = VERSION,
    ) {
        /** True when this event means "I was alive at [atUnix]". */
        val isConfirmation: Boolean get() = kind == Kind.ENABLED || kind == Kind.CONFIRMED

        fun encode(): String = ControlPrefix.ALIVE_CHECK_IN + JSONObject()
            .put("v", version)
            .put("t", kind.wire)
            .put("at", atUnix)
            .put("iv", intervalSeconds)
            .toString()
    }

    /** Does this text carry the alive sentinel at all? */
    fun isEvent(text: String): Boolean =
        ControlPrefix.match(text) == ControlPrefix.ALIVE_CHECK_IN

    fun decode(text: String): Event? {
        if (!isEvent(text)) return null
        val body = ControlPrefix.strip(text)
        return try {
            val o = JSONObject(body)
            val v = o.optInt("v", 0)
            // A version from the future could redefine what "ok" means.
            if (v <= 0 || v > VERSION) return null
            val kind = Kind.fromWire(o.optString("t")) ?: return null
            val at = o.optDouble("at", 0.0)
            // A payload with no timestamp cannot be placed in time at all, and
            // substituting "now" would fabricate a fresh confirmation out of a
            // corrupt payload — the one error this feature cannot afford.
            if (at <= 0.0) return null
            Event(
                kind = kind,
                atUnix = at,
                intervalSeconds = o.optLong("iv", 0L).coerceAtLeast(0L),
                version = v,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * WHICH of the three events this text is, for a caller that has to print one
     * sentence about it. "A check-in happened" is the reading that tells the
     * reader nothing.
     *
     * A body we cannot parse comes back as [Kind.CONFIRMED], not [Kind.DISABLED]:
     * a row saying "stopped" on a corrupt payload would claim a schedule ended
     * when it has not, and the other side would stop expecting confirmations.
     *
     * Returns the KIND and not a catalog key on purpose. A key written as a bare
     * string literal is what `CatalogAuditTest` refuses, and it is right to — a
     * key that never passes through `t(...)` is one no locale-coverage check can
     * see, so it ships missing in thirty-three languages and nothing says so.
     */
    fun summaryKind(text: String): Kind = decode(text)?.kind ?: Kind.CONFIRMED

    /**
     * The decisions this feature makes, with no clock and no store of its own.
     *
     * Pure for a reason stronger than tidiness: the things that go wrong here —
     * a peer's broken clock reading as a fresh confirmation, a duplicate counted
     * twice — are invisible on screen and trivial to state as a test. Mirrors
     * `AliveCheckInPolicy` on both phones, rule for rule.
     */
    object Policy {

        /**
         * The sentence this whole feature has to keep saying.
         *
         * A missed check-in means a confirmation did not arrive. That is all it
         * can ever mean: the phone may be off, out of coverage, out of battery,
         * or in a pocket. No string this feature shows says "in danger", and
         * this constant exists so a future caller looking for the right words
         * finds the rule instead of inventing them.
         */
        const val OVERDUE_IS_NOT_PROOF = "no_response_not_proof_of_harm"

        /** A clock further back than this tells us nothing about time. */
        const val MAX_BACKDATE_MILLIS = 30L * 24 * 3_600 * 1_000

        /**
         * The instant a received confirmation actually vouches for.
         *
         * Two failures, pulling opposite ways. A clock AHEAD of ours puts the
         * deadline in the future and keeps the indicator green past the point it
         * should have gone red — the dangerous direction, so it is clamped to
         * our receive time. A clock far BEHIND makes a peer read as overdue the
         * instant they check in: merely wrong, but wrong in a way that cries
         * wolf, so anything past [MAX_BACKDATE_MILLIS] is clamped too.
         *
         * Between those limits the declared instant stands, which is what makes
         * an offline queue work: a confirmation that waited six hours in a relay
         * queue is still a confirmation from six hours ago, and dating it to
         * delivery would hide exactly the lateness this feature exists to show.
         */
        fun confirmedAtMillis(declaredMillis: Long, receivedMillis: Long): Long = when {
            declaredMillis <= 0L -> receivedMillis
            declaredMillis > receivedMillis -> receivedMillis
            receivedMillis - declaredMillis > MAX_BACKDATE_MILLIS -> receivedMillis
            else -> declaredMillis
        }

        /**
         * Should this event be applied, given the newest already applied from the
         * same peer?
         *
         * Duplicates are the normal case: one send can travel the relay, the mesh
         * and the radio, and delivery is not ordered. Strictly-greater, so a
         * replay changes nothing and a late older event cannot pull a
         * confirmation backwards and manufacture an overdue state.
         */
        fun accepts(eventAtUnix: Double, lastAppliedAtUnix: Double): Boolean =
            eventAtUnix > lastAppliedAtUnix

        fun dueAtMillis(lastConfirmedAtMillis: Long, intervalSeconds: Long): Long =
            lastConfirmedAtMillis + intervalSeconds * 1_000L

        /**
         * Absolute instants on both sides, which is the whole answer to time
         * zones: no local calendar is consulted to compute or compare a deadline.
         */
        fun isOverdue(lastConfirmedAtMillis: Long, intervalSeconds: Long, nowMillis: Long): Boolean =
            lastConfirmedAtMillis > 0L && intervalSeconds > 0L &&
                nowMillis >= dueAtMillis(lastConfirmedAtMillis, intervalSeconds)
    }
}
