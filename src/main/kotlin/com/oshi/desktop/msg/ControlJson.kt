package com.oshi.desktop.msg

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONObject

/**
 * The ordered JSON writer every row-0.18 payload emits through, and the tolerant reader
 * every one of them ingests through.
 *
 * ============================================================ WHY NOT JSONObject.toString()
 *
 * Because `org.json` is two different libraries wearing one name, and the desktop got the
 * other one. PLAN.md §3 records the first half of this: the Maven `org.json:json` artifact
 * backs `JSONObject` with a `HashMap`, while Android's built-in `org.json` uses a
 * `LinkedHashMap`. Same class, same `toString()`, different key order — and the very first
 * envelope this project's skeleton emitted had its keys scrambled, spontaneously
 * reproducing the random-key-order hazard that already cost the project photos on four
 * iPhone launches in five. `DesktopEnvelope.toWireBytes()` fixed it for the envelope with
 * an explicitly ordered writer; this is the same fix for the payloads inside it.
 *
 * The second half is [WireClock.jsonNumber]: the two `org.json` implementations also
 * disagree about how a whole-valued Double prints (`773100000` vs `7.731E8`). Every
 * number written here goes through that formatter.
 *
 * ============================================================ WHY KEY ORDER MATTERS HERE
 *
 * Not for correctness. Swift's `Codable` and `org.json` both read keyed containers, so no
 * shipped client cares what order these arrive in. It matters for two other reasons, and
 * they are the reasons PLAN.md §4.3 gives:
 *
 *  - **A byte-for-byte diff against an Android payload has to stay meaningful.** If the
 *    desktop emits keys in hash order, every such comparison fails at random and the whole
 *    parity-testing method stops working. That is a bigger loss than any single bug.
 *  - **Swift's declaration order is the documented shape.** iOS's structs define the field
 *    order, Android's builders reproduce it exactly (`buildActionPayload`,
 *    `buildTypingPayload`, `sendReactionNotification` all `put` in Swift's order). Matching
 *    it means the three emitters can be read side by side, which is how every cross-platform
 *    defect in this project was actually found.
 *
 * ============================================================ ABSENT, NEVER NULL
 *
 * [optional] omits the key entirely when the value is null. It does not write `null`, and
 * that is a hard rule from PLAN.md §4.4: Swift's synthesized `Codable` encodes Optionals
 * with `encodeIfPresent`, so iOS EXPECTS an absent key. Android's now-deleted action
 * emitter put `JSONObject.NULL` there instead — one of the four defects listed in its
 * tombstone comment (`MessageActionsManager.kt:440-450`).
 *
 * ============================================================ ESCAPING
 *
 * Strings go through `OSHICryptoV2.jsonEscape` — the SHARED escaper compiled straight out
 * of the Android tree, not a hand-rolled `.replace()` chain. `DesktopEnvelope` made the
 * same choice for the same reason: an edit payload's `newContent` is arbitrary user text,
 * quotes, backslashes, newlines and all, and there is no upside to owning a second
 * implementation of that.
 */
internal class ControlJson {

    private val sb = StringBuilder("{")
    private var first = true

    private fun sep() {
        if (first) first = false else sb.append(',')
    }

    fun str(key: String, value: String): ControlJson = apply {
        sep()
        sb.append('"').append(OSHICryptoV2.jsonEscape(key)).append("\":\"")
            .append(OSHICryptoV2.jsonEscape(value)).append('"')
    }

    fun bool(key: String, value: Boolean): ControlJson = apply {
        sep()
        sb.append('"').append(OSHICryptoV2.jsonEscape(key)).append("\":").append(value)
    }

    /** Always through [WireClock.jsonNumber] — never `toString()`. See the class doc. */
    fun num(key: String, value: Double): ControlJson = apply {
        sep()
        sb.append('"').append(OSHICryptoV2.jsonEscape(key)).append("\":")
            .append(WireClock.jsonNumber(value))
    }

    /** Omits the key when [value] is null. Never writes JSON `null`. See ABSENT, NEVER NULL. */
    fun optional(key: String, value: String?): ControlJson = apply {
        if (value != null) str(key, value)
    }

    /**
     * A NESTED object, whose [json] was itself produced by a [ControlJson] and is therefore
     * already ordered, already escaped and already number-formatted.
     *
     * Added for PARITY.md row 0.19: `🛡️CHECK_IN🛡️` carries `emergencyData`, the first
     * payload in this project with a sub-object, and row 0.18 had no use for one. It takes
     * a String rather than a `ControlJson` on purpose — the nested value is built and
     * validated by the type that owns it
     * ([com.oshi.desktop.place.EmergencyData.toJson]), so this writer's contract stays
     * "place these bytes here" and cannot silently re-order someone else's keys.
     *
     * It is NOT an escape hatch for arbitrary text. Everything that reaches it must have
     * come out of a `ControlJson.build()`; a caller that pastes user input here would
     * bypass the shared escaper, which is the one thing this class exists to prevent.
     */
    fun raw(key: String, json: String): ControlJson = apply {
        sep()
        sb.append('"').append(OSHICryptoV2.jsonEscape(key)).append("\":").append(json)
    }

    fun build(): String = sb.toString() + "}"
}

/**
 * Tolerant reads, shared by every payload parser in this package.
 *
 * PLAN.md §4.4's ingest half: unknown fields ignored, defaulted accessors everywhere, and
 * — critically — a type mismatch treated as absence rather than as a throw. Swift's
 * `decodeIfPresent` is NOT `try?`: it tolerates absence but still throws on a wrong type,
 * which is how six `MessageGroup` fields could each discard an entire group definition.
 * This client does not inherit that behaviour for the payloads it owns, because the whole
 * point of a control payload is to be applied, and a wrong-typed `senderName` is not a
 * reason to leave a message un-deleted.
 */
internal object ControlRead {

    /** A required string, or null when absent, JSON-null, or not a string. */
    fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return (o.opt(key) as? String)
    }

    /** A boolean, or null. Accepts only a real JSON boolean — never `"true"` the string. */
    fun bool(o: JSONObject, key: String): Boolean? {
        if (!o.has(key) || o.isNull(key)) return null
        return (o.opt(key) as? Boolean)
    }

    /** A number, or null. Accepts any JSON number (Int, Long, Double, BigDecimal). */
    fun num(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        return (o.opt(key) as? Number)?.toDouble()
    }

    /**
     * Parse [text] as a JSON object, or null.
     *
     * Never throws: a control payload whose body is unreadable is a payload we cannot act
     * on, which is a different thing from an error worth propagating. Both shipped clients
     * reach the same conclusion by wrapping in `try?` / `try { } catch { }`.
     */
    fun obj(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
}
