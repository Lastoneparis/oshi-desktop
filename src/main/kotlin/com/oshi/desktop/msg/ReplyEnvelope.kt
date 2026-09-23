package com.oshi.desktop.msg

import org.json.JSONObject

/**
 * __GROUP_PARITY_2026_09_23__ Replies (1:1 AND groups) and forwarded messages — the two
 * `Kind.ENVELOPE` payloads of [ControlPrefix]: a user message wrapped in a sentinel, to be
 * UNWRAPPED for display and never summarised.
 *
 * ============================================================ THE WIRE (iOS, emitted)
 *
 * `💬REPLY💬{"content":…,"replyTo":{"originalMessageId","originalSenderKey","originalText",
 * "originalTimestamp","originalMediaType"?}}` — iOS `MessageWithReply.toMessageString()`
 * (`OSHI/MessageActionsManager.swift:137-168`), encoded with a DEFAULT `JSONEncoder`, so
 * `originalTimestamp` is an Apple-epoch Double and is **required**: iOS decodes it as a
 * non-optional `Date`, and a missing or non-numeric value fails the whole decode and drops
 * the reply (Android's note at `MessageActionsManager.kt:177`). `originalMediaType` is an
 * optional iOS omits when nil. The same envelope rides inside a group message body — iOS
 * `GroupViews.swift:5328`, Android `GroupManager.buildReplyEnvelope` — so one codec serves
 * both conversation kinds. `originalText` is truncated at 180, iOS `GroupViews.swift:5290`.
 *
 * ============================================================ ACCEPTED (both phones)
 *
 * - iOS / current Android: the shape above. Android's GROUP path puts
 *   `"originalMediaType": null` rather than omitting it — accepted.
 * - Android before its RP fix: `↩️REPLY↩️{"originalId","originalContent","originalSender",
 *   "replyContent"}` (`MessageActionsManager.kt:34, :249-256`). Still parsed by Android
 *   itself, so a peer on an old build can still send it.
 * - `➡️FORWARDED➡️{"originalSenderName","content","isForwarded","forwardCount"}` — iOS
 *   `ForwardedMessage` (`MessageActionsManager.swift:172-200`).
 *
 * A body that carries the prefix but does not parse is returned as NULL, and the caller
 * shows the raw text: a mangled envelope is visible rather than silently eaten.
 */
object ReplyEnvelope {

    const val REPLY_PREFIX = "💬REPLY💬"
    const val ANDROID_LEGACY_REPLY_PREFIX = "↩️REPLY↩️"
    const val FORWARD_PREFIX = "➡️FORWARDED➡️"

    /** iOS `GroupViews.swift:5290` — the quote never carries more than this. */
    const val QUOTE_MAX_CHARS = 180

    /** iOS `ReplyMessage`. [originalTimestampMs] is Unix millis in memory, Apple-epoch on the wire. */
    data class Quote(
        val originalMessageId: String,
        val originalSenderKey: String,
        val originalText: String,
        val originalTimestampMs: Long,
        val originalMediaType: String? = null,
    )

    /** What a wrapped body resolves to for display. */
    data class Unwrapped(
        val content: String,
        val quote: Quote? = null,
        /** Non-null only for a forwarded message; may be blank when the sender was unnamed. */
        val forwardedFrom: String? = null,
    )

    fun isWrapped(text: String?): Boolean = text != null &&
        (text.startsWith(REPLY_PREFIX) || text.startsWith(ANDROID_LEGACY_REPLY_PREFIX) ||
            text.startsWith(FORWARD_PREFIX))

    /** Wrap [content] as a reply to [quote], in the iOS format both phones parse. */
    fun wrap(content: String, quote: Quote): String {
        val replyTo = JSONObject()
            .put("originalMessageId", quote.originalMessageId)
            .put("originalSenderKey", quote.originalSenderKey)
            .put("originalText", quote.originalText.take(QUOTE_MAX_CHARS))
            // Required by iOS (non-optional Date). A quote with no usable time (Android's
            // legacy schema carries none) gets "now" rather than a value iOS would reject.
            .put(
                "originalTimestamp",
                runCatching { WireClock.toAppleSeconds(quote.originalTimestampMs) }
                    .getOrElse { WireClock.toAppleSeconds(System.currentTimeMillis()) },
            )
        quote.originalMediaType?.let { replyTo.put("originalMediaType", it) }
        return REPLY_PREFIX + JSONObject().put("content", content).put("replyTo", replyTo).toString()
    }

    /**
     * Wrap [content] as a forwarded message, iOS `createForwardedMessage`: a forward of a
     * forward keeps the ORIGINAL sender and increments `forwardCount`; `originalSenderName` is
     * omitted when unknown (Swift drops a nil optional).
     */
    fun forward(content: String, originalSenderName: String?): String {
        val existing = runCatching {
            if (content.startsWith(FORWARD_PREFIX)) JSONObject(content.removePrefix(FORWARD_PREFIX)) else null
        }.getOrNull()
        val o = JSONObject()
        if (existing != null) {
            if (!existing.isNull("originalSenderName") && existing.has("originalSenderName")) {
                o.put("originalSenderName", existing.getString("originalSenderName"))
            }
            o.put("content", existing.optString("content", ""))
            o.put("isForwarded", true)
            o.put("forwardCount", existing.optInt("forwardCount", 1) + 1)
        } else {
            val inner = unwrap(content)?.content ?: content // forward the TEXT of a reply, not its envelope
            originalSenderName?.takeIf { it.isNotBlank() }?.let { o.put("originalSenderName", it) }
            o.put("content", inner)
            o.put("isForwarded", true)
            o.put("forwardCount", 1)
        }
        return FORWARD_PREFIX + o.toString()
    }

    /**
     * Unwrap any of the three envelopes, or null when [raw] is not one (or is a broken one).
     * A plain body returns null too — callers fall back to showing the text unchanged.
     */
    fun unwrap(raw: String?): Unwrapped? {
        if (raw == null) return null
        return runCatching {
            when {
                raw.startsWith(REPLY_PREFIX) -> {
                    val o = JSONObject(raw.removePrefix(REPLY_PREFIX))
                    val content = o.getString("content")
                    val r = o.optJSONObject("replyTo")
                    Unwrapped(content, r?.let(::quoteOf))
                }
                raw.startsWith(ANDROID_LEGACY_REPLY_PREFIX) -> {
                    val o = JSONObject(raw.removePrefix(ANDROID_LEGACY_REPLY_PREFIX))
                    Unwrapped(
                        content = o.getString("replyContent"),
                        quote = Quote(
                            originalMessageId = o.optString("originalId", ""),
                            originalSenderKey = o.optString("originalSender", ""),
                            originalText = o.optString("originalContent", "").take(QUOTE_MAX_CHARS),
                            originalTimestampMs = 0L,
                        ),
                    )
                }
                raw.startsWith(FORWARD_PREFIX) -> {
                    val o = JSONObject(raw.removePrefix(FORWARD_PREFIX))
                    Unwrapped(
                        content = o.getString("content"),
                        forwardedFrom = o.optString("originalSenderName", "").let { if (o.isNull("originalSenderName")) "" else it },
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun quoteOf(r: JSONObject): Quote {
        val ts = r.optDouble("originalTimestamp", Double.NaN)
        return Quote(
            originalMessageId = r.optString("originalMessageId", ""),
            originalSenderKey = r.optString("originalSenderKey", ""),
            originalText = r.optString("originalText", "").take(QUOTE_MAX_CHARS),
            originalTimestampMs = if (ts.isNaN()) 0L else WireClock.toUnixMillis(ts) ?: 0L,
            originalMediaType = if (r.isNull("originalMediaType")) null else r.optString("originalMediaType", "").ifEmpty { null },
        )
    }

    /**
     * The text a quote of [body] should carry: the unwrapped content of a nested envelope
     * (never the JSON — iOS sanitises at construction, `MessageActionsManager.swift:49-58`),
     * and a neutral label for any other sentinel payload rather than its raw JSON.
     */
    fun quotableText(body: String?, mediaLabel: String? = null): String {
        val unwrapped = unwrap(body)?.content ?: body.orEmpty()
        val text = if (ControlPrefix.isControl(unwrapped)) "…" else unwrapped
        return (text.ifBlank { mediaLabel.orEmpty() }).replace('\n', ' ').take(QUOTE_MAX_CHARS)
    }
}
