package com.oshi.desktop.msg

import com.oshi.desktop.call.CallEndReason

/**
 * The call-history row a finished call leaves in its conversation — the phones' own format,
 * so a desktop row and a phone row read the same and a synced one renders on either.
 *
 * Transcribed, not reinterpreted:
 *  - answered:  `📱CALL_SUMMARY📱📞↗️call.outgoing|2:10` / `…📞↙️call.incoming|2:10`
 *    (`OSHIControlPayload.answeredCallSummary`, duration from `MessageManager.callDurationText`:
 *    `m:ss`, or `h:mm:ss` from an hour);
 *  - not answered (`ChatView.handleMissedCall`): I called → `📱CALL_SUMMARY📱📞↗️call.no.answer`
 *    or `…📞↗️call.declined`; they called → `📱CALL_SUMMARY📱📞↙️call.declined` when I declined,
 *    otherwise `📱MISSED_CALL📱❌call.missed` (no answer, or they hung up before I answered).
 *
 * The BODY carries a localization KEY, never text, so each device renders it in its own
 * language ([render], after `MessagesListView.localizeCallContent`).
 *
 * WRITTEN LOCALLY, NEVER SENT. iOS moved the answered summary to a local write on
 * 2026-08-22 (`__CALL_SUMMARY_LOCAL_2026_08_22__`) because both ends sending one produced two
 * rows per call; the desktop writes all of them locally for the same reason — the peer's own
 * client records its own side of the same call.
 */
object CallSummary {

    private const val OUT = "📞↗️"
    private const val IN = "📞↙️"
    private const val MISSED = "❌"

    /** The row for a finished call, or null for a call that leaves no row. */
    fun forEndedCall(outgoing: Boolean, connected: Boolean, reason: CallEndReason, durationSeconds: Long): String =
        when {
            connected -> ControlPrefix.CALL_SUMMARY + (if (outgoing) OUT + "call.outgoing" else IN + "call.incoming") +
                "|" + duration(durationSeconds)
            outgoing -> ControlPrefix.CALL_SUMMARY + OUT +
                (if (reason == CallEndReason.DECLINED) "call.declined" else "call.no.answer")
            reason == CallEndReason.DECLINED -> ControlPrefix.CALL_SUMMARY + IN + "call.declined"
            else -> ControlPrefix.MISSED_CALL + MISSED + "call.missed"
        }

    /** `MessageManager.callDurationText`: `m:ss`, `h:mm:ss` from an hour, never negative. */
    fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun isCallSummary(content: String?): Boolean =
        content != null && (content.startsWith(ControlPrefix.CALL_SUMMARY) || content.startsWith(ControlPrefix.MISSED_CALL))

    /** True for a missed-call row — the one a list may want to draw as unread-worthy. */
    fun isMissed(content: String?): Boolean = content?.startsWith(ControlPrefix.MISSED_CALL) == true

    /**
     * One line for a bubble or a preview: `📞↙️ Incoming call • 2:10`, `❌ Missed call`.
     * [localize] resolves a key; an unknown key is drawn as-is, like the phones.
     */
    fun render(content: String, localize: (String) -> String): String {
        val body = content.removePrefix(ControlPrefix.CALL_SUMMARY).removePrefix(ControlPrefix.MISSED_CALL)
        val (head, duration) = body.split("|", limit = 2).let { it[0] to it.getOrNull(1) }
        val icon = listOf(OUT, IN, MISSED).firstOrNull { head.startsWith(it) }.orEmpty()
        val key = head.removePrefix(icon)
        val text = if (key.contains('.')) localize(key) else key
        return listOf(icon, text).filter { it.isNotEmpty() }.joinToString(" ") +
            (duration?.let { " • $it" } ?: "")
    }
}
