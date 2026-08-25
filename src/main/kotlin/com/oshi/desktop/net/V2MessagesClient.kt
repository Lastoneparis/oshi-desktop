package com.oshi.desktop.net

import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.DesktopEnvelope
import org.json.JSONArray
import org.json.JSONObject

/** What one pull returned, plus the sequence number to ack or to resume from. */
data class V2Pull(val messages: List<DesktopEnvelope>, val maxSeq: Long)

/**
 * `/v2/messages` — send, pull, ack. Desktop port of Android `V2MessagesClient`.
 *
 * The relay is a mailbox, not a broker: `pull` is NON-DESTRUCTIVE and returns everything
 * after a sequence number, and `ack` is what tells the server it may drop up to a seq.
 * Those two facts carry the single most important rule on this path:
 *
 * > **Never ack an envelope you could not decrypt.**
 *
 * Acking is irreversible — the relay drops it — so an envelope acked before it was
 * durably handled is a message that no longer exists anywhere. The first message from a
 * new contact is the one this loses, and it loses it silently. The retry budget in the
 * router is what holds the seq instead; this class deliberately offers no "pull and ack"
 * convenience method, because the two must be separated by the work in between.
 */
class V2MessagesClient(private val http: V2Http) {

    /** Send one envelope. Returns the msgIds the relay accepted; empty means not sent. */
    fun send(envelope: DesktopEnvelope): List<String> {
        // toWireBytes(), not toJson().toString(): serialize ONCE, hash and post the same
        // array (V2Http's contract), and emit deterministically ordered keys.
        val bytes = envelope.toWireBytes()
        val resp = http.postJson("/v2/messages", bytes)
        if (!resp.isSuccess) return emptyList()
        return try {
            val accepted = JSONObject(resp.body).optJSONArray("accepted") ?: JSONArray()
            (0 until accepted.length()).map { accepted.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Pull everything after [afterSeq]. Null means the request failed — which is NOT the
     * same as "nothing new", and a caller that conflates them will advance its cursor
     * past messages it never saw.
     */
    fun pull(myUserKey: String, afterSeq: Long): V2Pull? {
        val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(myUserKey)}"
        val resp = http.get(path, query = "after=$afterSeq")   // query is NOT signed
        if (!resp.isSuccess) return null
        return try {
            val json = JSONObject(resp.body)
            val arr = json.optJSONArray("messages") ?: JSONArray()
            val msgs = ArrayList<DesktopEnvelope>(arr.length())
            for (i in 0 until arr.length()) {
                // One malformed envelope must not cost the whole response. Android's
                // strict reader took the entire array down on a single unknown field
                // (PLAN.md §4.4); here a bad element is skipped and the rest deliver.
                runCatching { DesktopEnvelope.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let(msgs::add)
            }
            V2Pull(msgs, json.optLong("maxSeq", afterSeq))
        } catch (_: Exception) {
            null
        }
    }

    /** Tell the relay it may drop everything up to and including [upToSeq]. */
    fun ack(myUserKey: String, upToSeq: Long): Boolean {
        val bytes = JSONObject().put("upToSeq", upToSeq).toString().toByteArray(Charsets.UTF_8)
        val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(myUserKey)}/ack"
        return http.postJson(path, bytes).isSuccess
    }
}
