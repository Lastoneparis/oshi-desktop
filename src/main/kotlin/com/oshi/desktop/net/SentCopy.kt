package com.oshi.desktop.net

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ "Sent from another of my devices" (CLIENT_SPEC.md §3.6).
 *
 * When a device sends a user message it also encrypts, for each OTHER device of its own
 * account, this wrapper around the exact payload it sent:
 *
 *     {"kind":"sent-copy","v":1,
 *      "conversation":{"type":"1to1","peer":<peerUserKey>} | {"type":"group","groupId":…},
 *      "message":<the payload sent to the peer>, "msgId":<same msgId>, "sentAt":<ISO-8601>}
 *
 * The receiving device inserts it as an OUTGOING message, status `sent`: no notification, no
 * receipt, no control processing, deduped by msgId (devsync may bring the same row later).
 *
 * `message` is the payload string this client sends (text, or the base64 group payload). A
 * phone may put a JSON object there (its SecureMessage); [parse] keeps that readable by
 * taking its `content`/`text`, else its JSON. `sentAt` is written to the SECOND with a `Z` —
 * iOS's default `ISO8601DateFormatter` rejects fractional seconds.
 */
data class SentCopy(
    val conversationType: String,
    val peer: String?,
    val groupId: String?,
    val message: String,
    val msgId: String,
    val sentAtMs: Long?,
    /** The sending device (the envelope's `fromDevice`), filled in by the router. */
    val fromDevice: String? = null,
) {
    companion object {
        const val KIND = "sent-copy"
        const val TYPE_1TO1 = "1to1"
        const val TYPE_GROUP = "group"

        fun conversation1to1(peer: String): JSONObject = JSONObject().put("type", TYPE_1TO1).put("peer", peer)
        fun conversationGroup(groupId: String): JSONObject = JSONObject().put("type", TYPE_GROUP).put("groupId", groupId)

        fun encode(conversation: JSONObject, message: String, msgId: String, sentAtMs: Long): ByteArray =
            JSONObject()
                .put("kind", KIND).put("v", 1)
                .put("conversation", conversation)
                .put("message", message)
                .put("msgId", msgId)
                .put("sentAt", Instant.ofEpochMilli(sentAtMs).truncatedTo(ChronoUnit.SECONDS).toString())
                .toString().toByteArray(Charsets.UTF_8)

        /** @return the copy, or null when [plaintext] is not a `sent-copy` (e.g. `kind:"state"`). */
        fun parse(plaintext: ByteArray): SentCopy? = runCatching {
            val o = JSONObject(String(plaintext, Charsets.UTF_8))
            if (o.optString("kind") != KIND) return null
            val c = o.getJSONObject("conversation")
            val type = c.getString("type")
            val msg = when (val m = o.opt("message")) {
                is String -> m
                is JSONObject -> m.optString("content").ifEmpty { m.optString("text") }.ifEmpty { m.toString() }
                else -> return null
            }
            val sentAt = o.optString("sentAt").takeIf { it.isNotEmpty() }?.let { s ->
                runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                    ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            }
            SentCopy(
                conversationType = type,
                peer = c.optString("peer").ifEmpty { null },
                groupId = c.optString("groupId").ifEmpty { null },
                message = msg,
                msgId = o.getString("msgId"),
                sentAtMs = sentAt,
            ).takeIf { (type == TYPE_1TO1 && it.peer != null) || (type == TYPE_GROUP && it.groupId != null) }
        }.getOrNull()
    }
}
