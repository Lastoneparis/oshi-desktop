package com.oshi.desktop.msg

import java.math.BigDecimal

/**
 * The date epochs of PARITY.md row 0.18, and the only place that converts between them.
 *
 * The ledger row for delivery receipts / typing / reactions / edit-delete carries one
 * warning and nothing else: *"four different date epochs live in these payloads."* This
 * file is the answer to that warning, written down before a single byte was emitted.
 *
 * ============================================================ THE FOUR EPOCHS
 *
 * PLAN.md §4.2 names four live conventions across the whole project. All four are in
 * contact with THIS row's payloads, and they are not evenly distributed — three of them
 * are the *neighbours* a receipt/typing/reaction/action implementation collides with,
 * and exactly one is what goes inside the payload body:
 *
 * 1. **APPLE REFERENCE EPOCH — seconds since 2001-01-01, JSON number (Double).**
 *    Offset from Unix: 978 307 200. This is the ONLY epoch that appears inside a row-0.18
 *    payload body, and it appears in every one of them that has a timestamp at all:
 *      - `⌨️TYPING⌨️` → `timestamp` (`OSHI/TypingIndicatorManager.swift:19`, a `Date`
 *        read by a BARE `JSONDecoder()`, i.e. `.deferredToDate`)
 *      - `🔥REACTION🔥` → `timestamp` (`OSHI/MessageReactionManager.swift:124`)
 *      - `🔧ACTION🔧` → `timestamp` (`OSHI/MessageManager.swift:8603`)
 *      - `📖READ_RECEIPT📖` → `timestamp`, in Android's JSON form only
 *        (`MessageRepository.kt:5286`, literally `(currentTimeMillis()/1000.0) - 978307200.0`)
 *      - the legacy IPFS `DeliveryReceipt` / `ReadReceipt` Codable structs
 *        (`OSHI/DeliveryReceiptManager.swift:25,52` — `timestamp: Date`, bare encoder)
 *    Android restates the constant in at least four places
 *    (`MessageRepository.APPLE_EPOCH_OFFSET_SECONDS`, `TypingIndicatorManager.APPLE_EPOCH_OFFSET`,
 *    `MessageReactionManager.appleEpochOffset`, `GroupManager.broadcastReaction`), which is
 *    itself the reason this desktop client keeps it in exactly one place.
 *
 * 2. **UNIX EPOCH MILLISECONDS, JSON number.** The `v=4` relay envelope's `ts` and the
 *    `x-oshi-timestamp` request header — i.e. the skin every one of these payloads travels
 *    inside (`DesktopWire.kt`, "epoch MILLISECONDS. Not seconds. Not ISO-8601."). It is
 *    also what Android's Room rows and this client's own [com.oshi.desktop.store.MessageStore]
 *    use locally. **The two epochs are one nesting level apart in the same transmission**:
 *    the envelope says millis-since-1970, the plaintext it carries says seconds-since-2001.
 *    Getting that backwards is the single most-repeated bug in this feature's history —
 *    THREE separate Android emitters shipped `System.currentTimeMillis()` into an
 *    Apple-epoch field (`MessageActionsManager.kt` edit, delete, and pin, all now deleted
 *    with tombstone comments saying exactly this). It does not throw on iOS. It decodes to
 *    roughly the year 55 000 and drives the "(edited)" stamp.
 *
 * 3. **UNIX EPOCH SECONDS, JSON number (Double).** The legacy IPFS wrapper the pre-V2
 *    receipt path rides in: `{"id","senderAddress","recipientAddress","encryptedContent",
 *    "timestamp"}` where `timestamp = Date().timeIntervalSince1970`
 *    (`OSHI/MessageManager.swift:535`, with the comment "Use TimeInterval, not ISO8601
 *    string"). So the legacy delivery receipt is Apple-epoch INSIDE a Unix-seconds
 *    wrapper — epochs 1 and 3 in the same object graph, one nesting level apart.
 *
 * 4. **ISO-8601 STRINGS.** Two places this row actually touches:
 *      - `🔧ACTION🔧.timestamp` lands in `SecureMessage.editedAt`
 *        (`OSHI/MessageManager.swift:7599`), and `SecureMessage` is PERSISTED by
 *        `FileStorage.saveMessages`, which sets `encoder.dateEncodingStrategy = .iso8601`
 *        (`OSHI/FileStorage.swift:98`). The same `Date` field is Apple-epoch on the wire
 *        and an ISO-8601 string on disk. Nothing announces the switch.
 *      - the `pinMessage` / `unpinMessage` halves of `🔧ACTION🔧` have a sibling channel
 *        in the group `MessageGroup` broadcast, which iOS decodes with
 *        `dateDecodingStrategy = .iso8601` (`OSHI/GroupMessaging.swift:934`) and Android
 *        emits with `iso8601(...)` (`GroupManager.kt:203`). A number where that decoder
 *        wants a string throws and discards the WHOLE group definition, members included
 *        (PLAN.md §4.4).
 *
 * The governing rule, restated from PLAN.md §4.2 because it is what makes this survivable:
 * **never guess an epoch from a field's name.** Every field above is called `timestamp`.
 *
 * ============================================================ WHAT THIS CLASS DOES
 *
 * It converts, and it refuses to convert nonsense. [toAppleSeconds] is the only way a
 * payload in this package gets a timestamp on the wire, and [toUnixMillis] is the only way
 * one comes off it. Neither ever sees an ISO-8601 string or a Unix-seconds Double, because
 * no row-0.18 payload body carries one — and if a future one does, it gets its own function
 * here rather than a `if (looksBig)` heuristic at the call site.
 *
 * **The magnitude guard.** Both shipped platforms carry a `> 1e10` sanity check on
 * Apple-epoch values, for the epoch-2 mix-up above; PLAN.md §4.2 says a desktop client
 * should too, and [MAGNITUDE_LIMIT] is it. Android's own wire-format test states the same
 * bound from the other side (`MessageActionWireFormatTest.kt`: `ts < 4_000_000_000.0`).
 *
 * What the guard CANNOT do, stated plainly so nobody trusts it further than it goes: it
 * separates epoch 2 from epoch 1, and nothing else. A Unix-SECONDS value for any date
 * between 1992 and 2087 is a perfectly plausible Apple-epoch value (2024's 1.7e9 Unix
 * seconds reads as 2054), so epochs 1 and 3 are **not distinguishable by magnitude at all**
 * within the range anyone cares about. [looksLikeUnixSeconds] exists to say so in code and
 * is advisory only — it is never used to silently "fix" a value, because a heuristic that
 * rewrites timestamps is how a clock skew becomes a data-loss bug.
 *
 * **A mis-encoded timestamp must not cost the payload.** [toUnixMillis] returns null rather
 * than throwing when a value fails the guard. The reason is concrete: an edit or a delete
 * from an older Android build carries a Unix-millis `timestamp`, and the ACTION is still
 * valid and still what the user asked for — dropping the whole payload would leave the
 * peer's message un-deleted, which is worse than an unknown edit time. The emitter is the
 * strict half: [toAppleSeconds] throws, because we control what we send.
 *
 * ============================================================ NUMBER FORMATTING
 *
 * [jsonNumber] exists for a reason that has already bitten this project once, in a
 * different disguise. PLAN.md §3 records that the Maven `org.json:json` artifact backs
 * `JSONObject` with a `HashMap` while Android's built-in `org.json` uses a `LinkedHashMap`
 * — same class name, different key order. The two implementations **also disagree about
 * how a whole-valued Double is printed**:
 *
 *   - Android's `JSONObject.numberToString` collapses an integral double to a long:
 *     `773100000.0` → `773100000`.
 *   - Maven's `org.json` 20231013 falls through to `Double.toString`, which switches to
 *     scientific notation at 10^7: `773100000.0` → `7.731E8`.
 *
 * Both are legal JSON and both decode to the same Double on every platform, so this is not
 * a correctness bug — it is a *diffability* bug, exactly like the key-order one, and it
 * would quietly destroy the value of any byte-for-byte comparison against an Android
 * payload. Swift's `JSONEncoder` agrees with Android here (whole doubles print without a
 * fractional part), so this formatter follows those two and never emits an exponent.
 */
object WireClock {

    /**
     * Seconds between 1970-01-01 and Apple's 2001-01-01 reference date.
     *
     * Written as an exact integral Double, matching every shipped restatement of it:
     * `MessageRepository.APPLE_EPOCH_OFFSET_SECONDS`, `TypingIndicatorManager.kt`,
     * `MessageReactionManager.kt`, `GroupManager.broadcastReaction`, and — on the iOS side
     * — Foundation's own `Date.timeIntervalSinceReferenceDate`.
     */
    const val APPLE_EPOCH_OFFSET_SECONDS: Double = 978_307_200.0

    /**
     * The shipped `> 1e10` sanity bound (PLAN.md §4.2). An Apple-epoch value of 1e10 is the
     * year 2318; a Unix-millis value for any date after 1970-04-26 is bigger. So this
     * separates "Apple-epoch seconds" from "somebody sent me milliseconds" and does not
     * pretend to do more.
     */
    const val MAGNITUDE_LIMIT: Double = 1e10

    /**
     * Unix-epoch millis → Apple-epoch seconds, for a value we are about to PUT ON THE WIRE.
     *
     * Strict on purpose: we control our own clock, so an implausible instant here is a bug
     * in this process, not a hostile peer, and it should stop at the emitter rather than
     * reach an iPhone and render as the year 55 000. The bound is the same calendar window
     * [com.oshi.desktop.store.Message] enforces, expressed in the epoch this function
     * consumes.
     */
    fun toAppleSeconds(unixMillis: Long): Double {
        require(unixMillis in MIN_PLAUSIBLE_UNIX_MS..MAX_PLAUSIBLE_UNIX_MS) {
            "unixMillis=$unixMillis is outside [2000-01-01, 2100-01-01] — an un-converted " +
                "epoch, most likely (PLAN.md §4.2). Row 0.18 payloads carry Apple-epoch " +
                "SECONDS; this function's INPUT is Unix MILLIS."
        }
        return (unixMillis / 1000.0) - APPLE_EPOCH_OFFSET_SECONDS
    }

    /**
     * Apple-epoch seconds off the wire → Unix-epoch millis, or **null** when the value
     * fails [MAGNITUDE_LIMIT] or is not a finite number.
     *
     * Null, not an exception: see the class doc. A payload from a build that put Unix
     * millis in this field still carries a real edit or a real delete, and the caller's job
     * is to apply that action with an unknown timestamp — not to discard it.
     */
    fun toUnixMillis(appleSeconds: Double): Long? {
        if (!appleSeconds.isFinite()) return null
        if (Math.abs(appleSeconds) >= MAGNITUDE_LIMIT) return null
        val ms = Math.round((appleSeconds + APPLE_EPOCH_OFFSET_SECONDS) * 1000.0)
        return ms
    }

    /**
     * True when [appleSeconds] is almost certainly Unix MILLISECONDS that were never
     * converted — the epoch-2-in-an-epoch-1-field mistake three shipped Android emitters
     * made. This is the same predicate [toUnixMillis] uses to return null; it is exposed
     * so a decoder can *report* the mistake instead of only swallowing it.
     */
    fun looksLikeUnixMillis(appleSeconds: Double): Boolean =
        !appleSeconds.isFinite() || Math.abs(appleSeconds) >= MAGNITUDE_LIMIT

    /**
     * Advisory only, and deliberately never acted on.
     *
     * A Unix-SECONDS timestamp (epoch 3) for any moment between 1992 and 2087 sits inside
     * the plausible Apple-epoch band, so no guard can tell it apart from a genuine
     * Apple-epoch value for a date ~31 years later. This returns true for the band where
     * the ambiguity is real, so a log line or a test can say "this could be either" — it
     * must NOT be used to rewrite a value. A heuristic that silently shifts timestamps by
     * 31 years is worse than the confusion it is trying to fix.
     */
    fun looksLikeUnixSeconds(appleSeconds: Double): Boolean =
        appleSeconds.isFinite() &&
            appleSeconds >= APPLE_EPOCH_OFFSET_SECONDS &&
            appleSeconds < MAGNITUDE_LIMIT

    /**
     * A JSON number with no exponent, matching Android's `org.json` and Swift's
     * `JSONEncoder` rather than Maven's `org.json`. See the class doc's NUMBER FORMATTING
     * section for why this is not just tidiness.
     *
     * Integral values print as integers (`773100000`), fractional values print in plain
     * decimal notation using the shortest round-tripping representation of the Double
     * (`773100000.123`) — `BigDecimal.valueOf` goes through `Double.toString`, so it
     * inherits that shortest form rather than the 50-digit exact binary expansion
     * `BigDecimal(double)` would produce.
     *
     * There is a sibling of this in the mesh package, `MeshMessage.formatEpochMillis`, which
     * handles the integral case only — correct there, because a mesh frame's `timestamp` is
     * Unix MILLIS and therefore always integral. An Apple-epoch value is a fractional number
     * of seconds, so this one has to handle the other half too; that is the whole difference
     * between the two functions, and the reason they are not one.
     */
    fun jsonNumber(value: Double): String {
        require(value.isFinite()) { "JSON has no encoding for $value" }
        if (value == Math.rint(value) && Math.abs(value) < 9.007e15) {
            return value.toLong().toString()
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    /** 2000-01-01T00:00:00Z — the low end of [com.oshi.desktop.store.Message]'s window. */
    private const val MIN_PLAUSIBLE_UNIX_MS = 946_684_800_000L

    /** 2100-01-01T00:00:00Z. */
    private const val MAX_PLAUSIBLE_UNIX_MS = 4_102_444_800_000L
}
