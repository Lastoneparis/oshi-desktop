package com.oshi.desktop.msg

import org.json.JSONObject

/**
 * __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1 (docs/RESTRICTED_NETWORK_HINT.md, owned by
 * Android release/1.7.0): "this message was sent through a restricted network".
 *
 * The flag is an invisible suffix — `U+2063 U+2064 U+2063` — at the very end of a user-typed
 * 1:1 text, or at the end of the `content` VALUE of a `💬REPLY💬` envelope. It lives only
 * inside the end-to-end encrypted plaintext; the relay and any observer see nothing new.
 * Old clients render the three Default_Ignorable (Cf) characters with zero width, so they
 * show the message exactly as before.
 *
 * Receivers strip it at ingestion ([ingest]), so it never reaches the screen, a notification,
 * a quote, a copy, a search or a forward; the flag is kept locally on the stored row.
 */
object RestrictedNetworkHint {
    const val MARKER = "⁣⁤⁣"

    data class Ingested(val text: String, val flagged: Boolean)

    /** Sender: append the marker to a plain text (never twice). */
    fun mark(text: String): String = if (text.endsWith(MARKER)) text else text + MARKER

    /**
     * Receiver: the decoded inbound 1:1 plaintext → (text without the flag, flag). Tests the
     * DECODED `content` of a reply envelope, never its raw bytes (org.json escapes the marker
     * as `⁣…`, iOS's JSONEncoder writes it raw — both decode the same).
     */
    fun ingest(raw: String): Ingested {
        if (raw.startsWith(ReplyEnvelope.REPLY_PREFIX)) {
            val o = try { JSONObject(raw.removePrefix(ReplyEnvelope.REPLY_PREFIX)) } catch (_: Exception) { return Ingested(raw, false) }
            val content = o.optString("content", "")
            if (!content.endsWith(MARKER)) return Ingested(raw, false)
            o.put("content", content.removeSuffix(MARKER))
            return Ingested(ReplyEnvelope.REPLY_PREFIX + o.toString(), true)
        }
        if (raw.endsWith(MARKER)) return Ingested(raw.removeSuffix(MARKER), true)
        return Ingested(raw, false)
    }
}
