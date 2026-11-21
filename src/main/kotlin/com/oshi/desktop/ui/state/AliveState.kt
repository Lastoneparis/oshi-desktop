package com.oshi.desktop.ui.state

import com.oshi.desktop.msg.AliveCheckInPayload
import com.oshi.desktop.store.Message

/**
 * __DESKTOP_1_6_PARITY__ The safety check-in ("I'm alive") of one conversation, read off its
 * messages.
 *
 * The phones keep this in a store of their own (iOS `AliveCheckInStore`, Android
 * `AliveCheckInWatcher`). The desktop does not need one: every event of the feature is a
 * `💓ALIVE💓` message in the conversation — `on` starts a schedule (and counts as a
 * confirmation), `ok` confirms, `off` stops — so the newest event per side IS the state. Only
 * rows that still carry the payload count; 1.5.1 stored inbound events as a translated
 * sentence, and those are history, not state.
 *
 * Two lanes, as on the phones: [mine] (what I promised this person) and [theirs] (what they
 * promised me). The sender of an inbound event is the AUTHENTICATED envelope sender the row
 * was filed under, never a field of the JSON (the payload has none, by design).
 */
data class AliveState(val mine: Lane?, val theirs: Lane?) {

    /** One side's schedule: when they last confirmed and how often they promised to. */
    data class Lane(val lastConfirmedMs: Long, val intervalSeconds: Long) {
        val dueAtMs: Long get() = lastConfirmedMs + intervalSeconds * 1000
        fun overdue(nowMs: Long): Boolean = nowMs > dueAtMs
        /** Positive: time left. Negative: how long it has been overdue. */
        fun remainingMs(nowMs: Long): Long = dueAtMs - nowMs
    }

    companion object {
        val NONE = AliveState(null, null)

        fun from(messages: List<Message>): AliveState {
            var mine: Lane? = null
            var theirs: Lane? = null
            var mineSeen = false
            var theirsSeen = false
            for (m in messages.sortedByDescending { it.sentAtMs }) {
                if (mineSeen && theirsSeen) break
                if (m.isDeletedForEveryone || m.hiddenLocally) continue
                val e = AliveCheckInPayload.decode(m.content ?: continue) ?: continue
                if (m.fromMe) {
                    if (mineSeen) continue
                    mineSeen = true
                    mine = lane(e, m)
                } else {
                    if (theirsSeen) continue
                    theirsSeen = true
                    theirs = lane(e, m)
                }
            }
            return AliveState(mine, theirs)
        }

        private fun lane(e: AliveCheckInPayload.Event, m: Message): Lane? {
            if (e.kind == AliveCheckInPayload.Kind.DISABLED || e.intervalSeconds <= 0) return null
            // The sender's own clock says when they confirmed, bounded by when it reached us:
            // a future-dated or very old `at` cannot move a deadline (the payload's policy).
            val declared = (e.atUnix * 1000).toLong()
            // Our own row is dated by this machine when it was sent; that is the confirmation.
            val at = if (m.fromMe) m.sentAtMs
            else AliveCheckInPayload.Policy.confirmedAtMillis(declared, m.sentAtMs)
            return Lane(at, e.intervalSeconds)
        }

        /** A span as days / hours / minutes, for the phones' `alive.duration.*` words. */
        data class Span(val days: Int, val hours: Int, val minutes: Int)

        fun span(ms: Long): Span {
            val minutes = kotlin.math.abs(ms) / 60_000
            return Span((minutes / (60 * 24)).toInt(), ((minutes / 60) % 24).toInt(), (minutes % 60).toInt())
        }

        /** The interval presets both phones offer: every 24, 48 and 72 hours. */
        val PRESET_HOURS = listOf(24L, 48L, 72L)
    }
}
