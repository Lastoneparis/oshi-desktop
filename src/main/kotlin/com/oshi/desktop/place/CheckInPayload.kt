package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlJson
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ControlRead
import com.oshi.desktop.msg.WireClock
import org.json.JSONObject

/**
 * `🛡️CHECK_IN🛡️` — the "tell someone when I get there, alert them if I don't" payload.
 * PARITY.md row 0.19.
 *
 * iOS `CheckInMessage` (`OSHI/CheckInMessage.swift:211-410`) driven by `CheckInManager`;
 * Android `CheckInMessage` (`data/model/CheckInMessage.kt:259-403`).
 *
 * ============================================================ WHY THIS ONE IS DIFFERENT
 *
 * Every other payload in this project costs a tick, a bubble or a reaction when it goes
 * wrong. This one arms an emergency alert. Android's own wire comment says it plainly
 * (`CheckInMessage.kt:338-347`): without the epoch conversion *"the safety-critical
 * arrival deadline is decades off in both directions (iOS ETA read as millis → 1970;
 * Android ETA read as Apple seconds → year ~55000), so the emergency alert never arms
 * correctly."* An ETA read as 1970 is permanently overdue; one read as 55 000 never fires.
 * Both failures are silent and both are the same one-line mistake.
 *
 * That is why [decode] returns a payload with `estimatedArrivalMs` explicitly nullable and
 * why nothing in this file infers an epoch from a field's name — PLAN.md §4.2's rule,
 * restated because this is the row where it costs the most.
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 *     🛡️CHECK_IN🛡️{"type":"started",
 *                    "sessionId":"<uuid>",
 *                    "destinationAddress":"Home",        // OMITTED when absent
 *                    "transportMode":"walking",          // OMITTED when absent
 *                    "estimatedArrival":<apple seconds>, // OMITTED when absent
 *                    "currentLatitude":46.2,             // OMITTED when absent
 *                    "currentLongitude":6.1,             // OMITTED when absent
 *                    "emergencyData":{…},                // OMITTED unless type=emergency
 *                    "timestamp":<apple seconds>,
 *                    "senderName":"Alice"}               // OMITTED when absent
 *
 *     emergencyData = {"lastKnownLatitude":D,"lastKnownLongitude":D,
 *                      "lastLocationTime":<apple seconds>,
 *                      "batteryLevel":0.42,        // 0.0–1.0, NOT a percentage
 *                      "isCharging":false,
 *                      "networkType":"WiFi"|"Cellular"|"Offline",
 *                      "deviceName":"Pixel",
 *                      "lastMovementTime":<apple seconds>}   // OMITTED when absent
 *
 * Key order is Swift's declaration order (`CheckInMessage.swift:212-221`,
 * `EmergencyData` at `:87-95`). Only `type`, `sessionId` and `timestamp` are non-optional
 * on iOS; everything else is `T?` and therefore `decodeIfPresent`. Inside `emergencyData`
 * only `lastMovementTime` is optional — the other seven are required, so a partial
 * `emergencyData` sinks the WHOLE check-in on an iPhone, not just the emergency block.
 * [decode] keeps that: a present-but-unusable `emergencyData` returns null rather than a
 * check-in with a hole where the emergency is.
 *
 * ============================================================ THE PREFIX, AND ITS TWIN
 *
 * `🛡️CHECK_IN🛡️` is U+1F6E1 **U+FE0F** on both platforms — Android spells it in escapes
 * precisely so the invisible selector is visible in review
 * (`CheckInMessage.kt:272`), iOS writes it literally and then re-spells it in escapes at
 * three matching sites (`NotificationManager.swift:301,389,519`). So this is the prefix
 * [ControlPrefix]'s U+FE0F derivation was built for: a build that emits the bare `🛡` must
 * still match, and [ControlPrefix.match] handles both spellings without this file listing
 * either twice.
 *
 * `🛡️CHECKIN🛡️` — no underscore — is a SECOND live spelling. It is in both shipped
 * catalogs (`OSHIControlPayload.swift:99`, `.kt`) and iOS's group renderer accepts all four
 * combinations of the two spellings against the two selector forms
 * (`GroupViews.swift:2568`). But **no emitter on either platform produces it**: iOS's only
 * `messagePrefix` is the underscored one (`CheckInMessage.swift:223`) and so is Android's.
 * It is a receive-only alias for something older than both trees. [decode] accepts it,
 * [encode] never emits it — the accept-both-emit-one rule this project applies everywhere.
 *
 * ============================================================ THE EPOCH, AND A THRESHOLD DISAGREEMENT
 *
 * Four Apple-epoch fields, two of them nested: `timestamp`, `estimatedArrival`,
 * `emergencyData.lastLocationTime`, `emergencyData.lastMovementTime`. All four go through
 * [WireClock]; this package adds no offset constant of its own.
 *
 * Android's legacy-millis detector for this payload uses `1e11`
 * (`CheckInMessage.kt:350-352`) while its location and contact-card equivalents use `1e10`
 * (`LocationSharingManager.kt:513`, `ContactCardWire.kt:79`). Three thresholds, two values,
 * one codebase. This client takes the lower one — [WireClock.MAGNITUDE_LIMIT] — via
 * [LocationPayload.wireToUnixMillis], because the band between them is the years 2318–5170
 * and nothing that matters lives there.
 */
data class CheckInPayload(
    val type: CheckInType,
    val sessionId: String,
    val destinationAddress: String? = null,
    val transportMode: TransportMode? = null,
    /** The arrival deadline as Unix millis, or null when this type carries none. */
    val estimatedArrivalMs: Long? = null,
    /** Apple-epoch seconds exactly as they arrived, unconverted. */
    val appleEstimatedArrival: Double? = null,
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val emergencyData: EmergencyData? = null,
    /** When this payload was built, Unix millis. Never null: [decode] rejects an unreadable one. */
    val timestampMs: Long,
    val appleTimestamp: Double,
    val senderName: String? = null,
) {

    /**
     * True when the arrival deadline has passed as of [nowMs].
     *
     * The desktop's read of iOS's `CheckInSession.isOverdue` (`swift:140-142`). Note what
     * is NOT here: `extraTimeMinutes`. That field lives on the SENDER's `CheckInSession` and
     * never crosses the wire — an extension is communicated as a whole
     * `type: "extended"` message carrying a NEW `estimatedArrival`
     * (`CheckInMessage.swift:274-286`). A receiver therefore tracks the deadline by
     * replacing it on each `extended`, never by adding to it, which is why there is no
     * accumulator anywhere in this file.
     */
    fun isOverdue(nowMs: Long): Boolean {
        val eta = estimatedArrivalMs ?: return false
        return nowMs >= eta
    }

    /** One line a CLI can print. The seven types are localised strings on both phones. */
    fun renderToText(nowMs: Long): String {
        val who = senderName?.let { "$it: " } ?: ""
        val where = destinationAddress?.let { " → $it" } ?: ""
        val late = if (type == CheckInType.STARTED && isOverdue(nowMs)) " (overdue)" else ""
        return "🛡️ $who${type.label}$where$late"
    }

    companion object {

        /**
         * Emit the payload both phones decode. `type`, `sessionId` and `atUnixMillis` are
         * required exactly because iOS decodes those three non-optionally.
         *
         * Always emits the UNDERSCORED prefix — see THE PREFIX, AND ITS TWIN.
         */
        fun encode(
            type: CheckInType,
            sessionId: String,
            atUnixMillis: Long,
            destinationAddress: String? = null,
            transportMode: TransportMode? = null,
            estimatedArrivalUnixMillis: Long? = null,
            currentLatitude: Double? = null,
            currentLongitude: Double? = null,
            emergencyData: EmergencyData? = null,
            senderName: String? = null,
        ): String {
            require(sessionId.isNotBlank()) {
                "iOS decodes sessionId non-optionally (CheckInMessage.swift:213); a blank " +
                    "one cannot be matched to a session and the alert never disarms"
            }
            require(currentLatitude == null || currentLongitude == null ||
                LocationPayload.isPlausibleCoordinate(currentLatitude, currentLongitude)) {
                "currentLatitude=$currentLatitude currentLongitude=$currentLongitude is not on Earth"
            }
            val json = ControlJson()
                .str("type", type.wire)
                .str("sessionId", sessionId)
            json.optional("destinationAddress", destinationAddress)
            json.optional("transportMode", transportMode?.wire)
            if (estimatedArrivalUnixMillis != null) {
                json.num("estimatedArrival", WireClock.toAppleSeconds(estimatedArrivalUnixMillis))
            }
            if (currentLatitude != null) json.num("currentLatitude", currentLatitude)
            if (currentLongitude != null) json.num("currentLongitude", currentLongitude)
            if (emergencyData != null) json.raw("emergencyData", emergencyData.toJson())
            json.num("timestamp", WireClock.toAppleSeconds(atUnixMillis))
            json.optional("senderName", senderName)
            return ControlPrefix.CHECK_IN + json.build()
        }

        /**
         * Read a check-in, or null.
         *
         * Null on: a different sentinel, unreadable JSON, an unknown `type` (an enum iOS
         * decodes non-optionally, so an unrecognised case throws there and the whole
         * message is dropped — reproduced rather than softened, because guessing a safety
         * message's type is worse than dropping it), a blank `sessionId`, an unusable
         * `timestamp`, or a present-but-broken `emergencyData`.
         */
        fun decode(text: String): CheckInPayload? {
            val prefix = ControlPrefix.match(text)
            if (prefix != ControlPrefix.CHECK_IN && prefix != ControlPrefix.CHECKIN) return null
            val o = ControlRead.obj(ControlPrefix.strip(text)) ?: return null

            val type = CheckInType.fromWire(ControlRead.str(o, "type") ?: return null) ?: return null
            val session = ControlRead.str(o, "sessionId")?.takeIf { it.isNotBlank() } ?: return null
            val ts = ControlRead.num(o, "timestamp") ?: return null
            val tsMs = LocationPayload.wireToUnixMillis(ts) ?: return null

            val emergency = if (o.has("emergencyData") && !o.isNull("emergencyData")) {
                EmergencyData.fromJson(o.opt("emergencyData") as? JSONObject ?: return null) ?: return null
            } else {
                null
            }

            val eta = ControlRead.num(o, "estimatedArrival")
            val lat = ControlRead.num(o, "currentLatitude")
            val lon = ControlRead.num(o, "currentLongitude")
            if (lat != null && lon != null && !LocationPayload.isPlausibleCoordinate(lat, lon)) return null

            return CheckInPayload(
                type = type,
                sessionId = session,
                destinationAddress = ControlRead.str(o, "destinationAddress")?.takeIf { it.isNotEmpty() },
                transportMode = ControlRead.str(o, "transportMode")?.let { TransportMode.fromWire(it) },
                estimatedArrivalMs = eta?.let { LocationPayload.wireToUnixMillis(it) },
                appleEstimatedArrival = eta,
                currentLatitude = lat,
                currentLongitude = lon,
                emergencyData = emergency,
                timestampMs = tsMs,
                appleTimestamp = ts,
                senderName = ControlRead.str(o, "senderName")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * `CheckInMessageType` — iOS `swift:75-83`, Android `kt:59-70`. Seven cases, identical
 * spellings on both sides, lowercase, and they are the raw values on the wire (Swift's
 * `String`-backed `enum`, Kotlin's Gson-serialised enum constant names, which is why
 * Android's constants are lowercase in violation of every Kotlin convention — changing them
 * would change the bytes).
 */
enum class CheckInType(val wire: String, val label: String) {
    STARTED("started", "Check-in started"),
    UPDATE("update", "Location update"),
    ARRIVED("arrived", "Arrived safely"),
    EXTENDED("extended", "Time extended"),
    CANCELLED("cancelled", "Check-in cancelled"),
    WARNING("warning", "Check-in warning"),
    EMERGENCY("emergency", "Emergency alert");

    companion object {
        fun fromWire(s: String): CheckInType? = entries.firstOrNull { it.wire == s }
    }
}

/** `TransportMode` — iOS `swift:15-18`, Android `kt:13-16`. Three cases, lowercase wire. */
enum class TransportMode(val wire: String) {
    WALKING("walking"),
    DRIVING("driving"),
    TRANSIT("transit");

    companion object {
        fun fromWire(s: String): TransportMode? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * The block a phone sends when its owner did not check in — iOS `swift:87-110`, Android
 * `kt:107-126`.
 *
 * Seven required fields and one optional. `batteryLevel` is a FRACTION, 0.0–1.0, not a
 * percentage: both platforms multiply by 100 only for display
 * (`swift:101-103`, `kt:117-118`). Emitting 42 instead of 0.42 would render "4200%" and,
 * worse, would look plausible enough in a log to survive review.
 *
 * The two nested `Date` fields are the ones Android converts by hand key-by-key on both
 * sides of the wire (`CheckInMessage.kt:361-366, 394-403`) because Gson has no idea they
 * are dates. This port converts them through [WireClock] like everything else.
 */
data class EmergencyData(
    val lastKnownLatitude: Double,
    val lastKnownLongitude: Double,
    /** Unix millis. */
    val lastLocationTimeMs: Long,
    /** 0.0–1.0. See the class doc — not a percentage. */
    val batteryLevel: Double,
    val isCharging: Boolean,
    /** `"WiFi"`, `"Cellular"` or `"Offline"` in practice; a free string on both platforms. */
    val networkType: String,
    val deviceName: String,
    /** Unix millis, or null. The only optional field of the eight. */
    val lastMovementTimeMs: Long? = null,
) {
    /** Swift declaration order, absent-never-null, numbers through [WireClock.jsonNumber]. */
    fun toJson(): String {
        val json = ControlJson()
            .num("lastKnownLatitude", lastKnownLatitude)
            .num("lastKnownLongitude", lastKnownLongitude)
            .num("lastLocationTime", WireClock.toAppleSeconds(lastLocationTimeMs))
            .num("batteryLevel", batteryLevel)
            .bool("isCharging", isCharging)
            .str("networkType", networkType)
            .str("deviceName", deviceName)
        if (lastMovementTimeMs != null) {
            json.num("lastMovementTime", WireClock.toAppleSeconds(lastMovementTimeMs))
        }
        return json.build()
    }

    companion object {
        /**
         * Null when any of the seven required fields is missing or wrong-typed — which is
         * what an iPhone does with it too, except that on iPhone it takes the entire
         * check-in down with it. [CheckInPayload.decode] reproduces that rather than
         * delivering an emergency with an empty body.
         */
        fun fromJson(o: JSONObject): EmergencyData? {
            val lat = ControlRead.num(o, "lastKnownLatitude") ?: return null
            val lon = ControlRead.num(o, "lastKnownLongitude") ?: return null
            if (!LocationPayload.isPlausibleCoordinate(lat, lon)) return null
            val locTime = ControlRead.num(o, "lastLocationTime") ?: return null
            val battery = ControlRead.num(o, "batteryLevel") ?: return null
            val charging = ControlRead.bool(o, "isCharging") ?: return null
            val network = ControlRead.str(o, "networkType") ?: return null
            val device = ControlRead.str(o, "deviceName") ?: return null
            val movement = ControlRead.num(o, "lastMovementTime")
            return EmergencyData(
                lastKnownLatitude = lat,
                lastKnownLongitude = lon,
                lastLocationTimeMs = LocationPayload.wireToUnixMillis(locTime) ?: return null,
                batteryLevel = battery,
                isCharging = charging,
                networkType = network,
                deviceName = device,
                lastMovementTimeMs = movement?.let { LocationPayload.wireToUnixMillis(it) },
            )
        }
    }
}
