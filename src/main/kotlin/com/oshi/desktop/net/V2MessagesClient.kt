package com.oshi.desktop.net

import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.DesktopEnvelope
import org.json.JSONArray
import org.json.JSONObject

/** What one pull returned, plus the sequence number to ack or to resume from. */
data class V2Pull(val messages: List<DesktopEnvelope>, val maxSeq: Long)

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ A device-mode pull/ack/batch answer with its status
 * kept: `410 device-unknown` and `409 device-mailbox-disabled` each demand a different
 * reaction (CLIENT_SPEC.md §3.3), and a bare null cannot say which one happened.
 */
data class V2DeviceCall<T>(val code: Int, val value: T?, val body: String = "") {
    val ok: Boolean get() = code in 200..299 && value != null
    val error: String? get() = runCatching { JSONObject(body).optString("error").ifEmpty { null } }.getOrNull()
}

/** `409 device-list-mismatch {to, missing, stale, devices}` — the sender's view was stale. */
data class DeviceListMismatch(val to: String, val missing: List<String>, val stale: List<String>, val devices: List<String>)

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

    // ------------------------------------------------------------ device mode

    /**
     * POST several envelopes as ONE batch (`{"envelopes":[…]}`): the per-device copies of one
     * msgId and the self copies travel together, so the server can check the device set is
     * complete before it queues any of them (all-or-nothing).
     */
    fun sendBatch(envelopes: List<DesktopEnvelope>): V2DeviceCall<List<String>> {
        val bytes = buildString {
            append("{\"envelopes\":[")
            envelopes.forEachIndexed { i, e -> if (i > 0) append(','); append(String(e.toWireBytes(), Charsets.UTF_8)) }
            append("]}")
        }.toByteArray(Charsets.UTF_8)
        val resp = http.postJson("/v2/messages", bytes, device = http.deviceSigner != null && envelopes.any { it.fromDevice != null })
        if (!resp.isSuccess) return V2DeviceCall(resp.code, null, resp.body)
        val accepted = runCatching {
            val a = JSONObject(resp.body).optJSONArray("accepted") ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        }.getOrNull()
        return V2DeviceCall(resp.code, accepted, resp.body)
    }

    /** The device view of our mailbox (`x-oshi-device` + signature). */
    fun pullDevice(myUserKey: String, afterSeq: Long): V2DeviceCall<V2Pull> {
        val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(myUserKey)}"
        val resp = http.get(path, query = "after=$afterSeq", device = true)
        if (!resp.isSuccess) return V2DeviceCall(resp.code, null, resp.body)
        return V2DeviceCall(resp.code, parsePull(resp.body, afterSeq), resp.body)
    }

    /** Acks THIS device only: its own copies go, its claim on account items is released. */
    fun ackDevice(myUserKey: String, upToSeq: Long): V2DeviceCall<Unit> {
        val bytes = JSONObject().put("upToSeq", upToSeq).toString().toByteArray(Charsets.UTF_8)
        val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(myUserKey)}/ack"
        val resp = http.postJson(path, bytes, device = true)
        return V2DeviceCall(resp.code, if (resp.isSuccess) Unit else null, resp.body)
    }

    private fun parsePull(body: String, afterSeq: Long): V2Pull? = try {
        val json = JSONObject(body)
        val arr = json.optJSONArray("messages") ?: JSONArray()
        val msgs = ArrayList<DesktopEnvelope>(arr.length())
        for (i in 0 until arr.length()) {
            runCatching { DesktopEnvelope.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let(msgs::add)
        }
        V2Pull(msgs, json.optLong("maxSeq", afterSeq))
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Parse a 409 body; null when it is not a device-list mismatch. */
        fun parseMismatch(body: String): DeviceListMismatch? = runCatching {
            val o = JSONObject(body)
            if (o.optString("error") != "device-list-mismatch") return null
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            DeviceListMismatch(o.getString("to"), list("missing"), list("stale"), list("devices"))
        }.getOrNull()
    }

    /** Tell the relay it may drop everything up to and including [upToSeq]. */
    fun ack(myUserKey: String, upToSeq: Long): Boolean {
        val bytes = JSONObject().put("upToSeq", upToSeq).toString().toByteArray(Charsets.UTF_8)
        val path = "/v2/messages/${DesktopV2Signer.encodeIdentity(myUserKey)}/ack"
        return http.postJson(path, bytes).isSuccess
    }
}
