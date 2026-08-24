package com.oshi.desktop

import com.oshi.messenger.network.v2.V2Session
import org.json.JSONObject
import java.util.Base64

/**
 * The `/v2/messages` relay envelope (v=4), desktop copy.
 *
 * This is a REIMPLEMENTATION, not shared source, because the Android original lives in
 * `V2MessagesClient.kt` next to OkHttp, Hilt and android.util.Base64. It is therefore
 * the single most dangerous file in the skeleton: it is the one place where desktop
 * can silently disagree with iOS and Android about what a message looks like.
 *
 * Everything below is load-bearing and each line is asserted in WireFormatTest:
 *
 *   - `v` is the integer 4, and it is emitted FIRST.
 *   - byte fields (`header`, `ciphertext`, and every key inside `x3dh`) are STANDARD
 *     PADDED base64 — Android's `Base64.NO_WRAP`. Not base64url. Not unpadded.
 *   - `type` defaults to the literal "1to1".
 *   - `groupId` and `oneTimePreKeyId` are OMITTED when null, never emitted as JSON null.
 *     Android reads them with `has() && !isNull()`; iOS reads them into an Optional.
 *     Emitting an explicit null is the classic way to break the stricter of the two.
 *   - `ts` is EPOCH MILLISECONDS as a JSON number. Not seconds. Not ISO-8601. Not the
 *     Apple 2001 epoch. The v2 relay envelope is the one place OSHI is unambiguous
 *     about time, and it must stay that way — see PLAN.md risk #2 for the payloads
 *     INSIDE the ciphertext, which are not so lucky.
 *   - `seq` is set by the server on pull and is NEVER sent.
 *
 * Note the envelope is the AEAD's outer skin, not its input: the 40-byte ratchet header
 * is authenticated as associated data in its RAW form, so JSON key order does not affect
 * the ciphertext here. Key order still matters for the request SIGNATURE, because the
 * signature covers a SHA-256 of the exact bytes posted — see [DesktopV2Signer].
 */
data class DesktopEnvelope(
    val msgId: String,
    val from: String,
    val to: String,
    val type: String = "1to1",
    val groupId: String? = null,
    val x3dh: V2Session.X3DHHeader? = null,
    val header: ByteArray,      // 40-byte ratchet header, raw
    val ciphertext: ByteArray,  // ct‖tag
    val ts: Long,               // epoch MILLISECONDS
    val seq: Long = -1,         // server-assigned on pull only; never serialized
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 4)
        put("msgId", msgId)
        put("from", from)
        put("to", to)
        put("type", type)
        groupId?.let { put("groupId", it) }
        x3dh?.let {
            put("x3dh", JSONObject().apply {
                put("identityKey", b64(it.identityKey))
                put("ephemeralKey", b64(it.ephemeralKey))
                put("signedPreKeyId", it.signedPreKeyId)
                it.oneTimePreKeyId?.let { id -> put("oneTimePreKeyId", id) }
            })
        }
        put("header", b64(header))
        put("ciphertext", b64(ciphertext))
        put("ts", ts)
    }

    /**
     * The exact bytes to POST — and the exact bytes the signature hashes. Serialize ONCE,
     * hash this array, send this array; re-serializing in between can reorder keys and
     * earns a 401 that looks like clock skew.
     *
     * Emitted through an explicitly ordered writer rather than `toJson().toString()`.
     * Discovered while building this skeleton: the Maven `org.json:json` artifact backs
     * JSONObject with a HashMap, so `toString()` emits keys in hash order, while ANDROID's
     * org.json uses a LinkedHashMap and preserves insertion order. Same class name, same
     * method, different output — the desktop was reproducing exactly the random-key-order
     * hazard that already cost this project photos on four iPhone launches in five.
     *
     * Nothing on the wire strictly requires this order. We fix it anyway so that a
     * byte-for-byte diff of a desktop envelope against an Android one stays a meaningful
     * test, instead of failing at random.
     */
    fun toWireBytes(): ByteArray = buildString {
        append("{")
        field("v", 4); comma()
        field("msgId", msgId); comma()
        field("from", from); comma()
        field("to", to); comma()
        field("type", type)
        groupId?.let { comma(); field("groupId", it) }
        x3dh?.let {
            comma(); append("\"x3dh\":{")
            field("identityKey", b64(it.identityKey)); comma()
            field("ephemeralKey", b64(it.ephemeralKey)); comma()
            field("signedPreKeyId", it.signedPreKeyId)
            it.oneTimePreKeyId?.let { id -> comma(); field("oneTimePreKeyId", id) }
            append("}")
        }
        comma(); field("header", b64(header))
        comma(); field("ciphertext", b64(ciphertext))
        comma(); field("ts", ts)
        append("}")
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.comma() { append(",") }

    /** Strings go through the SHARED escaper — never a hand-rolled `.replace()` chain. */
    private fun StringBuilder.field(key: String, value: Any) {
        append("\"").append(key).append("\":")
        when (value) {
            is String -> append("\"").append(
                com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(value)).append("\"")
            else -> append(value.toString())
        }
    }

    companion object {
        private val ENC: Base64.Encoder = Base64.getEncoder()
        private val DEC: Base64.Decoder = Base64.getDecoder()
        private fun b64(b: ByteArray) = ENC.encodeToString(b)
        private fun unb64(s: String) = DEC.decode(s)

        /**
         * Tolerant read, matching Android. Note what this does NOT do: it does not fail
         * on an unknown top-level field. A future platform adding `"edited":true` must
         * not cost the reader the whole message — and, on a pull, the whole ARRAY.
         */
        fun fromJson(o: JSONObject): DesktopEnvelope {
            val x = o.optJSONObject("x3dh")?.let {
                V2Session.X3DHHeader(
                    identityKey = unb64(it.getString("identityKey")),
                    ephemeralKey = unb64(it.getString("ephemeralKey")),
                    signedPreKeyId = it.optString("signedPreKeyId"),
                    oneTimePreKeyId =
                        if (it.has("oneTimePreKeyId") && !it.isNull("oneTimePreKeyId"))
                            it.optString("oneTimePreKeyId") else null,
                )
            }
            return DesktopEnvelope(
                msgId = o.getString("msgId"),
                from = o.getString("from"),
                to = o.getString("to"),
                type = o.optString("type", "1to1"),
                groupId = if (o.has("groupId") && !o.isNull("groupId")) o.optString("groupId") else null,
                x3dh = x,
                header = unb64(o.getString("header")),
                ciphertext = unb64(o.getString("ciphertext")),
                ts = o.optLong("ts", 0),
                seq = o.optLong("seq", -1),
            )
        }
    }
}
