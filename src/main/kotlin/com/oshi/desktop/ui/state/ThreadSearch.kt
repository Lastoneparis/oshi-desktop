package com.oshi.desktop.ui.state

import java.time.LocalDate
import java.time.ZoneId

/**
 * __DESKTOP_THREAD_SEARCH_2026_10_29__ Finding a message inside one conversation, by words
 * or by the day it was sent.
 *
 * The second of the three gaps PARITY row 0.0's sweep left open. This client could filter the
 * conversation LIST (`ConversationFilter`) and could not search INSIDE a thread at all, while
 * iOS has a calendar-based message search (`DateSearchView`) and Android a "Search" item in
 * the same conversation menu this now sits in.
 *
 * ## Pure, and therefore testable
 *
 * No store, no clock reached for, no Compose. The interesting cases are the ones nobody
 * reproduces by hand — an accented query, a day boundary at midnight, a search that matches a
 * deleted row — and they are only reachable if the decision is a function.
 *
 * ## Two filters that AND, and a deliberate asymmetry
 *
 * A query with no date searches the whole thread; a date with no query shows that day. Both
 * together narrow. What they do NOT do is fall back: an empty result stays empty rather than
 * widening to "did you mean", because a search that silently shows something other than what
 * was asked is how a person concludes a message is missing when it is simply not on that day.
 */
object ThreadSearch {

    /**
     * Rows matching [query] and [day].
     *
     * @param query matched case- and accent-insensitively against the body. Blank matches all.
     * @param day the local day the message was sent, or null for any day.
     * @param zone the viewer's zone — passed in rather than read, because "which day" is a
     *   question only a zone can answer and a test must be able to fix it.
     */
    fun apply(
        rows: List<MessageRow>,
        query: String,
        day: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MessageRow> {
        val needle = fold(query)
        if (needle.isEmpty() && day == null) return rows
        return rows.filter { row ->
            // A deleted row has no body to match and must never be findable by its old text:
            // the body is already "(deleted)", so matching it would let someone search for a
            // word and be shown a message whose words were revoked.
            if (row.deleted && needle.isNotEmpty()) return@filter false
            if (needle.isNotEmpty() && !fold(row.body).contains(needle)) return@filter false
            if (day != null) {
                if (row.sentAtMs <= 0L) return@filter false
                val rowDay = java.time.Instant.ofEpochMilli(row.sentAtMs).atZone(zone).toLocalDate()
                if (rowDay != day) return@filter false
            }
            true
        }
    }

    /**
     * Case- and accent-insensitive. `ConversationFilter` folds the same way, and the two must
     * agree: a person who finds a conversation by typing `jose` and then cannot find the
     * message inside it would reasonably conclude the message is gone.
     */
    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Days in this thread that have at least one message, newest first — for a day picker. */
    fun daysWithMessages(rows: List<MessageRow>, zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> =
        rows.filter { it.sentAtMs > 0L }
            .map { java.time.Instant.ofEpochMilli(it.sentAtMs).atZone(zone).toLocalDate() }
            .distinct()
            .sortedDescending()
}
