package com.oshi.desktop.ui.state

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * __CONVERSATION_EXPORT_2027__ One conversation as plain text — Android's "Export as Text".
 *
 * Pure (no store, no clock), so the format is pinned by a test rather than by eye:
 *
 *     OSHI — <title>
 *     <blank>
 *     [2026-11-19 07:40] Camille: Are you coming tonight?
 *     [2026-11-19 07:41] Me: Yes
 *
 * Timestamps are ISO-like and locale-free on purpose: a file someone greps or pastes into a
 * report must not change shape with the machine's language. Multi-line messages keep their
 * lines, indented under the header line so a reader can still tell where one ends.
 */
object ConversationTextExport {

    data class Line(val atMs: Long, val who: String, val text: String)

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun render(title: String, rows: List<Line>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("OSHI — ").append(title).append('\n').append('\n')
        for (r in rows) {
            val stamp = if (r.atMs > 0) STAMP.format(Instant.ofEpochMilli(r.atMs).atZone(zone)) else "—"
            val body = r.text.replace("\r\n", "\n").lines()
            append('[').append(stamp).append("] ").append(r.who).append(": ").append(body.firstOrNull().orEmpty()).append('\n')
            for (more in body.drop(1)) append("    ").append(more).append('\n')
        }
    }
}
