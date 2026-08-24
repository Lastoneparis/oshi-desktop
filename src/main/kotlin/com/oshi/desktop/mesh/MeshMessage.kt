package com.oshi.desktop.mesh

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * `CrossPlatformMessage` — the ONE object that crosses the mesh wire.
 *
 * This is a reimplementation, not shared source: the Android original lives in
 * `network/mesh/CrossPlatformMesh.kt`, welded to android.util.Base64, android.util.Log,
 * NsdManager and the BLE stack. So this file is to the mesh what DesktopWire.kt is to
 * the relay — the single place where desktop can silently disagree with the two shipped
 * platforms about what a message *is*.
 *
 * The three facts that make this file load-bearing:
 *
 * 1. **iOS decodes with a bare `JSONDecoder().decode(CrossPlatformMessage.self)` and
 *    every one of the eleven properties is non-Optional** (OSHI/CrossPlatformMesh.swift
 *    :1664). Swift's synthesized decoder throws `keyNotFound` on a missing key and
 *    `fromJSON` returns nil — the iPhone drops the frame with one log line and no user
 *    signal. An omitted `recipientPublicKey` (natural for a broadcast!) is therefore
 *    not a smaller message; it is a message iOS cannot read at all. **Emit all eleven
 *    keys, always, and never JSON null.**
 *
 * 2. **`timestamp` is Unix epoch MILLISECONDS** — Android `System.currentTimeMillis()`,
 *    iOS `Date().timeIntervalSince1970 * 1000`. It is NOT the Apple 2001 epoch that so
 *    many payloads *inside* `payload` use, and not seconds. See PLAN.md §4.2: four
 *    epochs are live in this project and only the mesh envelope and the v2 relay
 *    envelope are unambiguous.
 *
 * 3. **Key ORDER is not load-bearing here, and we pin it anyway.** Both shipped
 *    platforms parse this by key. But the Maven `org.json` artifact backs JSONObject
 *    with a HashMap while Android's backs it with a LinkedHashMap (PLAN.md §3), so
 *    `JSONObject.toString()` on desktop emits a *different order on every JVM*. That
 *    would not break messaging — it would break every byte-for-byte diff against an
 *    Android frame, which is the only cheap way to check parity. [toWireBytes] emits
 *    through an ordered writer for that reason.
 *
 * What this file deliberately does NOT do: interpret `payload`. On the mesh, payload is
 * an opaque string produced by the layer above (ciphertext, base64 media, or a nested
 * JSON document depending on `type`). Routing must never depend on its shape — PLAN.md
 * §4.5, the most expensive bug in this project's history, was exactly that.
 */
data class MeshMessage(
    val id: String,
    val type: String,
    val senderPublicKey: String,
    val senderName: String,
    /** Empty string means broadcast. NEVER omitted, NEVER null — see fact (1) above. */
    val recipientPublicKey: String,
    val payload: String,
    /** Unix epoch MILLISECONDS. */
    val timestamp: Double,
    val hopCount: Int,
    val maxHops: Int,
    val seenBy: List<String>,
    /** "ios" | "android" | "desktop" — see [MeshIdentity.PLATFORM] for why not "ios". */
    val platform: String,
) {

    /**
     * The exact bytes to frame and send.
     *
     * Ordered to match the Swift struct's property declaration order, which is also the
     * order Android's `toJSON()` inserts them in and therefore the order an Android
     * frame comes out in. That makes `diff <(android.json) <(desktop.json)` meaningful.
     */
    fun toWireBytes(): ByteArray = buildString {
        append('{')
        str("id", id); append(',')
        str("type", type); append(',')
        str("senderPublicKey", senderPublicKey); append(',')
        str("senderName", senderName); append(',')
        str("recipientPublicKey", recipientPublicKey); append(',')
        str("payload", payload); append(',')
        raw("timestamp", formatEpochMillis(timestamp)); append(',')
        raw("hopCount", hopCount.toString()); append(',')
        raw("maxHops", maxHops.toString()); append(',')
        append("\"seenBy\":[")
        seenBy.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append('"').append(OSHICryptoV2.jsonEscape(s)).append('"')
        }
        append("],")
        str("platform", platform)
        append('}')
    }.toByteArray(Charsets.UTF_8)

    /** Marks this node as having handled the message and bumps the hop count. */
    fun relayedBy(myPublicKey: String): MeshMessage =
        copy(hopCount = hopCount + 1, seenBy = seenBy + myPublicKey)

    /** Strings go through the SHARED escaper — never a hand-rolled `.replace()` chain (PLAN.md §4.6). */
    private fun StringBuilder.str(key: String, value: String) {
        append('"').append(key).append("\":\"")
            .append(OSHICryptoV2.jsonEscape(value)).append('"')
    }

    private fun StringBuilder.raw(key: String, value: String) {
        append('"').append(key).append("\":").append(value)
    }

    companion object {
        /**
         * Swift's `Codable` synthesizes a decoder that requires EVERY stored property.
         * This is that list, and `MeshWireFormatTest` re-derives it from the Swift
         * source at test time so an iOS-side addition turns the desktop build red
         * instead of turning one iPhone silent.
         */
        val REQUIRED_KEYS: List<String> = listOf(
            "id", "type", "senderPublicKey", "senderName", "recipientPublicKey",
            "payload", "timestamp", "hopCount", "maxHops", "seenBy", "platform",
        )

        /**
         * Tolerant read, matching both platforms' behaviour on a bad frame: return null,
         * log, keep the connection. A throw here would take down the receive loop and
         * with it every later message from that peer.
         *
         * Tolerant of an UNKNOWN key (a 4th platform adding a field must not cost us the
         * frame) and of an absent `recipientPublicKey`/`seenBy` (Android's own reader
         * uses `optString`/`optJSONArray` for those two). Strict about the rest, because
         * a message without an `id` cannot be de-duplicated and one without a `type`
         * cannot be routed.
         */
        fun parse(data: ByteArray): MeshMessage? = try {
            val o = JSONObject(String(data, Charsets.UTF_8))
            val seen = ArrayList<String>()
            o.optJSONArray("seenBy")?.let { arr: JSONArray ->
                for (i in 0 until arr.length()) seen.add(arr.getString(i))
            }
            MeshMessage(
                id = o.getString("id"),
                type = o.getString("type"),
                senderPublicKey = o.getString("senderPublicKey"),
                senderName = o.getString("senderName"),
                recipientPublicKey = o.optString("recipientPublicKey", ""),
                payload = o.getString("payload"),
                timestamp = o.getDouble("timestamp"),
                hopCount = o.getInt("hopCount"),
                maxHops = o.getInt("maxHops"),
                seenBy = seen,
                platform = o.getString("platform"),
            )
        } catch (_: Exception) {
            null
        }

        /**
         * Emit an integral Double as `1708000000000`, not `1.708E12` and not
         * `1708000000000.0`.
         *
         * Kotlin's `Double.toString()` produces `1.708E12` for this magnitude, which is
         * legal JSON that Swift and org.json both parse back to the same Double — so
         * this is cosmetic *until* someone diffs two frames or greps a log for a
         * timestamp. Android's org.json already normalises integral doubles to an
         * integer literal (`JSONObject.numberToString`), so this keeps desktop frames
         * looking exactly like Android frames.
         */
        fun formatEpochMillis(ts: Double): String =
            if (ts.isFinite() && ts == Math.floor(ts) && Math.abs(ts) < 9.007199254740992E15)
                ts.toLong().toString()
            else
                ts.toString()
    }
}
