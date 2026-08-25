package com.oshi.desktop.place

import com.oshi.desktop.msg.ControlJson
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ControlRead
import com.oshi.desktop.msg.WireClock

/**
 * `📍LOCATION📍` — one-time pins and LIVE location shares. PARITY.md row 0.19.
 *
 * iOS `LocationMessage` (`OSHI/LocationSharingManager.swift:788-1025`), Android
 * `LocationMessage` (`network/location/LocationSharingManager.kt:485-705`).
 *
 * ============================================================ WHAT A DESKTOP CAN DO HERE
 *
 * Stated first because it bounds everything below: **this client has no GPS.** Windows and
 * Linux have location APIs, the JDK has none, and PLAN.md §1's rule is no new dependency
 * without a reason that survives being written down — "so the CLI can pretend to be
 * somewhere" is not one. So row 0.19's location half is deliberately RECEIVE-ONLY in
 * practice:
 *
 *  - **In scope, and implemented here:** decoding a peer's pin or live share, deciding
 *    whether that share is still live, rendering it to text, and expiring it.
 *  - **Out of scope, and NOT faked:** originating a share from a real device sensor. There
 *    is no `LocationSource`, no "desktop location provider", and no 0,0 placeholder that a
 *    future caller could mistake for a fix.
 *
 * [encode] exists anyway and takes explicit coordinates, because the emitter is what the
 * wire-format tests assert against and because a desktop CAN legitimately forward a
 * coordinate a human typed or pasted. It cannot invent one.
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 *     📍LOCATION📍{"latitude":48.85661,"longitude":2.35222,
 *                  "timestamp":<apple-epoch seconds>,
 *                  "isLive":false,
 *                  "expiresAt":<apple-epoch seconds>,   // OMITTED unless live
 *                  "accuracy":12.5,                     // OMITTED when unknown
 *                  "address":"Rue de Rivoli, Paris",    // OMITTED when not geocoded
 *                  "sessionId":"<uuid>",                // OMITTED for a one-time pin
 *                  "isUpdate":false,"isStopped":false}
 *
 * Key order is Swift's property declaration order, which is also its explicit `CodingKeys`
 * (`swift:1006`) and also the order Android's `@Serializable` constructor declares. Ten
 * keys and no others — Android's own test pins that set literally
 * (`LocationWireFormatTest.kt:32-35`).
 *
 * **The prefix carries NO variation selector.** `📍` is U+1F4CD, which has no text
 * presentation form, so unlike `🛡️CHECK_IN🛡️` there is nothing for [ControlPrefix]'s
 * U+FE0F derivation to strip here. Android asserts the absence explicitly
 * (`LocationWireFormatTest.kt:56-65`) because a build once shipped `📍️LOCATION📍️` with a
 * stray selector that "could never match" — Android's `NotificationPresentation.kt:78`
 * still carries the scar. Matching goes through [ControlPrefix.match] regardless, so that
 * spelling is handled here for free.
 *
 * ============================================================ THE THREE REQUIRED FIELDS
 *
 * `latitude`, `longitude` and `isLive` are `try container.decode(...)` on iOS —
 * non-optional (`swift:1013-1016`) — as is `timestamp`. Everything else is
 * `decodeIfPresent`, with `isUpdate`/`isStopped` defaulting to `false` "for old messages"
 * (iOS's own comment, `swift:1021`). [decode] enforces exactly that split, so a payload an
 * iPhone would silently discard is discarded here too rather than half-rendered.
 *
 * ============================================================ COORDINATE ENCODING
 *
 * Plain JSON `Double` degrees, WGS-84, full precision, in that order — **no fixed-point
 * scaling, no rounding, no truncation, and no privacy fuzzing anywhere in either shipped
 * tree.** Worth writing down because "coordinates are rounded for privacy" is a thing
 * messengers do and this one does not: the payload carries whatever `CLLocation` /
 * `android.location.Location` reported, to the last bit of the Double. `accuracy` is the
 * horizontal radius in METRES (`horizontalAccuracy` / `Location.accuracy`) and iOS's can be
 * NEGATIVE, which is CoreLocation's way of saying "invalid" — it is passed through
 * unaltered rather than clamped, because a negative accuracy is information.
 *
 * Numbers go out through [WireClock.jsonNumber] like every other number in this project,
 * so a whole-valued coordinate prints `0` and not `7.731E8` — see that function for why
 * Maven's `org.json` would otherwise disagree with both phones.
 *
 * ============================================================ THE EPOCH
 *
 * `timestamp` and `expiresAt` are Apple-reference seconds (epoch 1 of the four PLAN.md
 * §4.2 names) because iOS encodes `Date` with a bare `JSONEncoder`, i.e.
 * `.deferredToDate`. Every conversion in this file goes through
 * [com.oshi.desktop.msg.WireClock] and there is no second offset constant in this package —
 * the shipped trees restate `978307200.0` in at least six files and that is precisely the
 * habit this port refuses to inherit.
 *
 * One shipped behaviour [WireClock] does not have and this file does not add to it: both
 * phones treat a value above `1e10` as **legacy Unix millis written into the Apple-epoch
 * field by an old Android build** and read it straight through
 * (`LocationSharingManager.kt:511-518`, `ContactCardWire.kt:78-80`). [decode] reproduces
 * that, and it is not a second converter — `WireClock.looksLikeUnixMillis` makes the
 * decision and the "conversion" is `Double.toLong()`, no epoch arithmetic at all. See
 * [LEGACY_MILLIS_PASSTHROUGH].
 *
 * ============================================================ HOW A LIVE SHARE ENDS
 *
 * See [LiveState] and [LiveShareTracker]. The short version, because it is a privacy
 * property and not a detail: it ends **two ways at once**, and this client refuses to
 * depend on either one alone.
 */
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    /** Apple-epoch seconds exactly as they appeared on the wire, unconverted. */
    val appleTimestamp: Double,
    /** [appleTimestamp] as Unix millis. Never null: [decode] rejects an unreadable one. */
    val timestampMs: Long,
    val isLive: Boolean,
    /** Apple-epoch seconds, or null when the sender sent no expiry at all. */
    val appleExpiresAt: Double? = null,
    /** [appleExpiresAt] as Unix millis, or null when absent or unreadable. */
    val expiresAtMs: Long? = null,
    /** Horizontal radius in metres. Negative means "invalid fix" on iOS; passed through. */
    val accuracy: Double? = null,
    /** Reverse-geocoded street/city/country, sender-side. Absent when not geocoded. */
    val address: String? = null,
    /** Non-null ⇒ part of a live session. Null ⇒ a one-time pin. */
    val sessionId: String? = null,
    /** A periodic ping inside an existing session, not the start of one. */
    val isUpdate: Boolean = false,
    /** The sender explicitly ended the share. */
    val isStopped: Boolean = false,
) {

    /**
     * True when this payload must not raise a banner on arrival.
     *
     * **This is the one place iOS and Android actively disagree today, and it is not a
     * drift — it is a fix one side took and the other did not.**
     *
     *   iOS:     `isUpdate && !isStopped`   (`LocationSharingManager.swift:861`)
     *   Android: `isUpdate || isStopped`    (`LocationSharingManager.kt:680-681`)
     *
     * iOS's line carries a dated tombstone, `__LOCATION_PING_SPAM_2026_08_24__`, saying it
     * *used* to read `isUpdate || isStopped` and that this "also swallowed the stop
     * signal", because `stopped()` is built with `isUpdate: true` on both platforms. So
     * Android is running iOS's pre-fix behaviour while its own comment claims to be a port
     * of the fixed line, and its wire test asserts the old semantics in as many words
     * (`LocationWireFormatTest.kt:222`, "the stop signal must not raise a banner").
     *
     * **This client takes iOS's side**, per PARITY.md working rule 2 — where the two
     * disagree the disagreement is recorded and the stricter option wins, and "the person
     * watching you on a map has stopped sharing" is an event a human wants told. The cost
     * of iOS's reading is one extra banner per share; the cost of Android's is a dot that
     * silently stops moving with nothing to say the share ended, which is the worse
     * failure for a privacy feature.
     *
     * Note what this predicate is NOT: a prefix test. The initial share and every ping
     * carry the identical `📍LOCATION📍` sentinel and differ only inside the JSON, which is
     * why `📍LOCATION📍` is absent from [ControlPrefix.silentNoPushPrefixes] and why both
     * platforms need this extra term next to their static list.
     */
    val isSilentForPush: Boolean get() = isUpdate && !isStopped

    /**
     * What this payload says about the state of the share, as of [nowMs].
     *
     * Deliberately a function of an explicit clock rather than of `System.currentTimeMillis`:
     * expiry is the security property of this whole row and a property that reads a global
     * clock cannot be tested at a boundary.
     */
    fun liveState(nowMs: Long): LiveState {
        if (isStopped) return LiveState.STOPPED
        if (!isLive) return LiveState.NOT_LIVE
        val end = expiresAtMs ?: return LiveState.UNBOUNDED
        if (!withinMaxDuration(appleTimestamp, appleExpiresAt)) return LiveState.UNBOUNDED
        return if (nowMs >= end) LiveState.EXPIRED else LiveState.LIVE
    }

    /** Milliseconds left on this share as of [nowMs], or null when it is not running. */
    fun remainingMs(nowMs: Long): Long? {
        if (liveState(nowMs) != LiveState.LIVE) return null
        return (expiresAtMs ?: return null) - nowMs
    }

    /**
     * One line of text a CLI or a future bubble can show, given the clock.
     *
     * The rendering rule this encodes is the one both shipped clients apply and which
     * matters more than its wording: **an expired, stopped or unbounded live share must
     * never be drawn as live.** iOS gates every "live" affordance on
     * `isLive && !isExpired` in eight separate places (`LocationSharingView.swift:669`,
     * `:715`, `:830`, `:854`, `:951`, `:1175`, `:1177`, and `GroupChatScreen.kt:3515` on
     * Android); collapsing that to one function is the point of it living here.
     */
    fun renderToText(nowMs: Long): String {
        val where = address ?: "${fmt(latitude)}, ${fmt(longitude)}"
        return when (liveState(nowMs)) {
            LiveState.NOT_LIVE -> "📍 Location: $where"
            LiveState.STOPPED -> "📍 Live location ended: $where"
            LiveState.EXPIRED -> "📍 Live location expired: $where"
            LiveState.UNBOUNDED -> "📍 Location (no valid expiry): $where"
            LiveState.LIVE -> {
                val mins = ((remainingMs(nowMs) ?: 0L) + 59_999L) / 60_000L
                "📍 Live location, ${mins}m left: $where"
            }
        }
    }

    private fun fmt(d: Double): String = WireClock.jsonNumber(Math.round(d * 100_000.0) / 100_000.0)

    companion object {

        /**
         * The longest live share either UI can start: iOS `LiveDuration.eightHours`
         * (`LocationSharingManager.swift:407`), Android `LiveDuration.EIGHT_HOURS`
         * (`LocationSharingManager.kt:476`). Both enums are byte-identical — 2, 5, 15, 30,
         * 60 and 480 minutes — so this bound is not a guess about product intent.
         *
         * A payload claiming `expiresAt - timestamp` beyond this could not have been
         * produced by either shipped UI, so this client refuses to call it LIVE. Neither
         * phone checks it. This is the row's [LiveState.UNBOUNDED] half of "fail closed":
         * a share we cannot bound is a share we do not render as running.
         */
        const val MAX_LIVE_DURATION_SECONDS: Double = 28_800.0

        /**
         * Both phones read an Apple-epoch field above `1e10` as raw Unix millis from an old
         * Android build rather than as a date in the year 55 800
         * (`LocationSharingManager.kt:511-518`, and Android's test asserts it at
         * `LocationWireFormatTest.kt:201-208`). This client does the same.
         *
         * `1e10` and not the `1e11` `CheckInMessage.kt:350` picked: the two shipped
         * thresholds disagree, [WireClock.MAGNITUDE_LIMIT] is the lower one, and the band
         * between them is years 2318–5170 — no real timestamp lives there, so taking the
         * stricter bound costs nothing and keeps ONE threshold in this codebase.
         */
        fun legacyMillisPassthrough(wire: Double): Long? =
            if (LEGACY_MILLIS_PASSTHROUGH && WireClock.looksLikeUnixMillis(wire) && wire.isFinite()) {
                wire.toLong()
            } else {
                null
            }

        /** @see legacyMillisPassthrough */
        const val LEGACY_MILLIS_PASSTHROUGH: Boolean = true

        /**
         * An Apple-epoch wire value as Unix millis, taking the legacy escape hatch when the
         * value cannot be Apple-epoch. Null only when the value is not a usable instant at
         * all (NaN, infinity).
         */
        fun wireToUnixMillis(wire: Double): Long? =
            WireClock.toUnixMillis(wire) ?: legacyMillisPassthrough(wire)

        /**
         * True when `expiresAt - timestamp` is a duration one of the six shipped presets
         * could have produced. Absent expiry is vacuously fine (a one-time pin has none);
         * a NEGATIVE span is fine too, because `stopped()` sets `expiresAt = now` and clock
         * skew between two devices makes "already expired" the normal case for a stop.
         *
         * @see MAX_LIVE_DURATION_SECONDS
         */
        fun withinMaxDuration(appleTimestamp: Double, appleExpiresAt: Double?): Boolean {
            val end = appleExpiresAt ?: return true
            if (!end.isFinite() || !appleTimestamp.isFinite()) return false
            return (end - appleTimestamp) <= MAX_LIVE_DURATION_SECONDS
        }

        /**
         * True when [lat]/[lon] are a point on Earth.
         *
         * Neither shipped client checks, and both would happily draw a marker at latitude
         * 400. The refusal is deliberate and its ONE uncomfortable consequence is written
         * down rather than discovered later: a STOP ping whose coordinates are out of range
         * is dropped, and the share it was ending then has to be ended by its wall clock
         * instead. That is survivable precisely because this client never lets the wall
         * clock be pushed forward — see [LiveShareTracker]. It would NOT be survivable if
         * we trusted the stop message alone, which is the whole argument for having both.
         *
         * `0, 0` is valid and must stay valid: both platforms' `stopped()` factory falls
         * back to `0, 0` when there is no last known location
         * (`swift:993-1002`, `kt:623-637`).
         */
        fun isPlausibleCoordinate(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() &&
                lat >= -90.0 && lat <= 90.0 &&
                lon >= -180.0 && lon <= 180.0

        /**
         * Emit the payload an iPhone and an Android phone both decode.
         *
         * Coordinates are a REQUIRED parameter with no default. There is no
         * `encode(fromCurrentLocation)` and there will not be one: see WHAT A DESKTOP CAN
         * DO HERE. `atUnixMillis` goes through [WireClock.toAppleSeconds], which throws on
         * an implausible instant — the emitter is the strict half on purpose.
         */
        fun encode(
            latitude: Double,
            longitude: Double,
            atUnixMillis: Long,
            isLive: Boolean,
            expiresAtUnixMillis: Long? = null,
            accuracy: Double? = null,
            address: String? = null,
            sessionId: String? = null,
            isUpdate: Boolean = false,
            isStopped: Boolean = false,
        ): String {
            require(isPlausibleCoordinate(latitude, longitude)) {
                "latitude=$latitude longitude=$longitude is not a point on Earth"
            }
            require(!isLive || sessionId != null) {
                "a live share with no sessionId cannot be tracked or stopped; both shipped " +
                    "factories always set one (LocationSharingManager.swift:919, .kt:556)"
            }
            val json = ControlJson()
                .num("latitude", latitude)
                .num("longitude", longitude)
                .num("timestamp", WireClock.toAppleSeconds(atUnixMillis))
                .bool("isLive", isLive)
            if (expiresAtUnixMillis != null) json.num("expiresAt", WireClock.toAppleSeconds(expiresAtUnixMillis))
            if (accuracy != null) json.num("accuracy", accuracy)
            json.optional("address", address)
            json.optional("sessionId", sessionId)
            return ControlPrefix.LOCATION + json
                .bool("isUpdate", isUpdate)
                .bool("isStopped", isStopped)
                .build()
        }

        /**
         * Read a `📍LOCATION📍` payload, or null.
         *
         * Null on: a different sentinel, prose, unreadable JSON, any of the four fields iOS
         * decodes non-optionally being absent or wrong-typed, a timestamp that is not a
         * usable instant, or a coordinate that is not on Earth. Everything else degrades a
         * FIELD, never the payload — an unreadable `expiresAt` leaves [appleExpiresAt] set
         * and [expiresAtMs] null, which [liveState] reads as [LiveState.UNBOUNDED] rather
         * than as "runs forever".
         */
        fun decode(text: String): LocationPayload? {
            if (ControlPrefix.match(text) != ControlPrefix.LOCATION) return null
            val o = ControlRead.obj(ControlPrefix.strip(text)) ?: return null

            val lat = ControlRead.num(o, "latitude") ?: return null
            val lon = ControlRead.num(o, "longitude") ?: return null
            if (!isPlausibleCoordinate(lat, lon)) return null

            val ts = ControlRead.num(o, "timestamp") ?: return null
            val tsMs = wireToUnixMillis(ts) ?: return null
            val live = ControlRead.bool(o, "isLive") ?: return null

            val exp = ControlRead.num(o, "expiresAt")

            return LocationPayload(
                latitude = lat,
                longitude = lon,
                appleTimestamp = ts,
                timestampMs = tsMs,
                isLive = live,
                appleExpiresAt = exp,
                expiresAtMs = exp?.let { wireToUnixMillis(it) },
                accuracy = ControlRead.num(o, "accuracy"),
                address = ControlRead.str(o, "address")?.takeIf { it.isNotEmpty() },
                sessionId = ControlRead.str(o, "sessionId")?.takeIf { it.isNotEmpty() },
                isUpdate = ControlRead.bool(o, "isUpdate") ?: false,
                isStopped = ControlRead.bool(o, "isStopped") ?: false,
            )
        }

        /**
         * Whether raw wire text is a live-location ping that must not banner.
         *
         * Port of iOS `LocationMessage.isSilentPushContent` (`swift:864-868`) and Android's
         * (`kt:648-651`), including their shared insistence that this cannot be answered
         * from the prefix. Non-location text is never silent, which is why the prefix check
         * comes first: a `📬DELIVERY_RECEIPT📬` is silent for an entirely different reason
         * and answering "true" here would hide that reason.
         */
        fun isSilentPushContent(text: String): Boolean = decode(text)?.isSilentForPush == true
    }
}

/**
 * The state of a share, which is NOT the same question as `isLive`.
 *
 * `isLive` is what the sender claimed when the bytes left their phone. This is what the
 * share is doing NOW, on this machine's clock, and it is the value every rendering decision
 * must be taken against.
 */
enum class LiveState {
    /** A one-time pin. It has no expiry and does not need one. */
    NOT_LIVE,

    /** Running: `expiresAt` is in the future and inside the shipped duration bound. */
    LIVE,

    /** The sender sent an explicit stop (`isStopped:true`). Ends it regardless of clock. */
    STOPPED,

    /** `expiresAt` has passed. Ends it regardless of whether a stop was ever received. */
    EXPIRED,

    /**
     * The sender said `isLive:true` and gave this client nothing it can expire on: no
     * `expiresAt`, one it cannot read, or one further out than any shipped UI can produce.
     *
     * **Both shipped clients render this as LIVE FOREVER.** iOS: `guard let expiresAt =
     * expiresAt else { return false }` inside `isExpired` (`swift:837`). Android: `val
     * expires = expiresAt ?: return false` (`kt:688`). A peer that sends
     * `📍LOCATION📍{"latitude":…,"longitude":…,"timestamp":…,"isLive":true}` — four fields,
     * all required, nothing invalid — gets a share that never expires on either phone, and
     * no UI on either platform offers a way to dismiss it. That is a real hole in shipped
     * code, it is reachable by a peer rather than only by a bug, and PARITY.md working rule
     * 2 says the stricter option wins: this client treats it as not-live and says so in the
     * rendered text instead of drawing a live dot with no end.
     */
    UNBOUNDED,
}
