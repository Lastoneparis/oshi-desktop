package com.oshi.desktop.msg

/**
 * The typing indicator — iOS `TypingStatus` (`OSHI/TypingIndicatorManager.swift:14-62`),
 * Android `TypingIndicatorManager.buildTypingPayload` (`:72-84`).
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 *     ⌨️TYPING⌨️{"senderPublicKey":"<base64 X25519>","isTyping":true,
 *                "timestamp":<apple-epoch seconds>,
 *                "groupId":"<uuid>",        // OMITTED for a 1-on-1 ping
 *                "senderName":"Alice"}      // OMITTED when unknown
 *
 * Key order is Swift's property declaration order, which is also `CodingKeys`' order
 * (`swift:22-24`) and also the order Android's builder `put`s them in. Absent optionals are
 * OMITTED, never `null` — Swift's synthesized `encode(to:)` uses `encodeIfPresent`, and iOS
 * defaults `groupId` to nil for exactly this back-compat reason.
 *
 * ============================================================ THE THREE REQUIRED FIELDS
 *
 * `senderPublicKey`, `isTyping` and `timestamp` are decoded with `try c.decode(...)` —
 * **non-optional** (`swift:36-42`). Any one of them missing throws, `JSONDecoder` returns
 * nil at `swift:57`, and `fromMessageString` yields nil: **the ping is dropped in silence**,
 * because `⌨️TYPING⌨️` is in iOS's SILENT catalog and a silent payload that fails to parse
 * produces no error, no log a user sees, and no bubble.
 *
 * Android shipped `{"isTyping":true}` alone for a long time. The 1.5.6 release fixed the
 * PREFIX (see below) and still never made an iPhone show "… is typing" — it only changed
 * the failure from "renders as raw JSON" to "silently discarded." Android's own comment
 * says this (`TypingIndicatorManager.kt:55-71`). That is why [encode] takes all three as
 * required parameters instead of defaulting any of them: a typing payload that cannot be
 * incomplete cannot reproduce that bug.
 *
 * ============================================================ THE PREFIX DISAGREEMENT
 *
 * Two spellings are live:
 *
 *   `⌨️TYPING⌨️` (U+2328 U+FE0F)  — iOS's `TypingStatus.messagePrefix`, and the only one
 *                                    iOS's `fromMessageString` accepts (`swift:54`).
 *   `✍️TYPING✍️` (U+270D U+FE0F)  — legacy Android. iOS lists it in its SILENT catalog, so
 *                                    an iPhone receiving it does not render it and does not
 *                                    parse it: the user simply never sees the peer typing.
 *
 * **We emit `⌨️TYPING⌨️` and accept both**, which is where Android landed too, with the
 * comment "iOS only accepts that form […] Android parses BOTH prefixes on receive, so this
 * stays Android↔Android-safe" (`TypingIndicatorManager.kt:249-252`). [ControlPrefix] also
 * accepts the U+FE0F-stripped spellings of both, because older iOS builds emit the bare
 * codepoint — see that file's U+FE0F section.
 *
 * ============================================================ GROUP SCOPE
 *
 * A `groupId` turns a 1-on-1 ping into a group ping, and its ABSENCE is the discriminator
 * on both platforms (`TypingIndicatorManager.receiveTypingStatus` routes on
 * `status.groupId != nil`; Android's `handleIncomingTypingIndicator` on
 * `optString("groupId","").takeIf { it.isNotEmpty() }`). Note the asymmetry that follows:
 * an explicit `"groupId":null` would be read as ABSENT by Android's `optString` but would
 * throw nothing on iOS either — yet emitting it is still forbidden, because
 * `"groupId":null` is precisely the shape PLAN.md §4.4's "absent optionals as omitted keys,
 * never JSON null" rule exists to forbid, and this project has already shipped it once in
 * an envelope.
 *
 * There is one more group-side detail this client deliberately does NOT replicate:
 * Android canonicalises the group id to an UPPERCASE UUID before putting it on the wire
 * (`GroupManager.canonicalGroupId`, used by `buildGroupTypingPayload`). Groups are
 * PARITY.md row 0.17 and are not started; canonicalisation is a group-identity decision
 * that belongs with the rest of that row, not smuggled in here. [encode] passes `groupId`
 * through verbatim, and this paragraph is the note for whoever implements 0.17.
 *
 * ============================================================ WHAT ISN'T HERE
 *
 * The 5-second auto-clear and the 2-second send debounce are both platforms' policy
 * (`typingTimeout` / `sendDebounce` on iOS, `TYPING_TIMEOUT_MS` / `lastSendTime` on
 * Android) and both are *behaviour*, not wire format. They belong to whatever drives this
 * codec, not to the codec — a payload class that owned a timer would be untestable without
 * one. The privacy gate ("Typing Indicators" off ⇒ emit nothing) is the same: both
 * platforms check it at the SEND site, and this package never decides on its own to send.
 */
data class TypingPayload(
    val senderPublicKey: String,
    val isTyping: Boolean,
    /** Apple-epoch seconds exactly as they appeared on the wire, unconverted. */
    val appleTimestamp: Double?,
    /** [appleTimestamp] as Unix millis, or null when absent or failing [WireClock]'s guard. */
    val timestampMs: Long?,
    /** Null ⇒ a 1-on-1 ping. Non-null ⇒ scoped to that group. */
    val groupId: String? = null,
    val senderName: String? = null,
) {
    companion object {

        /**
         * The exact bytes to put inside a v2 envelope's ciphertext.
         *
         * All three of iOS's required fields are parameters with no defaults — see THE
         * THREE REQUIRED FIELDS. [atUnixMillis] is Unix MILLISECONDS (this process's own
         * clock) and is converted here; the wire carries Apple-epoch seconds.
         */
        fun encode(
            senderPublicKey: String,
            isTyping: Boolean,
            atUnixMillis: Long,
            groupId: String? = null,
            senderName: String? = null,
        ): String {
            require(senderPublicKey.isNotBlank()) {
                "iOS decodes senderPublicKey non-optionally (TypingIndicatorManager.swift:38); " +
                    "a blank one makes the ping unattributable"
            }
            return ControlPrefix.TYPING_IOS + ControlJson()
                .str("senderPublicKey", senderPublicKey)
                .bool("isTyping", isTyping)
                .num("timestamp", WireClock.toAppleSeconds(atUnixMillis))
                .optional("groupId", groupId)
                .optional("senderName", senderName)
                .build()
        }

        /**
         * Decode either prefix, or null when [text] is not a typing ping or is missing one
         * of iOS's three required fields.
         *
         * Returning null on a missing required field is deliberate and matches iOS exactly:
         * we must not be more lenient than the client we are talking to. A payload this
         * function accepted but an iPhone silently dropped would be a desktop that shows a
         * peer typing when the peer's own phone would not — a divergence invented here
         * rather than inherited.
         *
         * The `timestamp` is the one exception: a mis-encoded one (Unix millis, the bug
         * three Android emitters shipped) yields a null [timestampMs] and keeps the ping.
         * iOS would decode that same value to the year 55 000 and still show the indicator,
         * which the 5-second auto-clear then retires anyway — so dropping it here would be
         * stricter than iOS in the one place where strictness costs a working feature.
         */
        fun decode(text: String): TypingPayload? {
            val prefix = ControlPrefix.match(text)
            if (prefix != ControlPrefix.TYPING_IOS && prefix != ControlPrefix.TYPING_ANDROID) return null
            val o = ControlRead.obj(ControlPrefix.strip(text)) ?: return null
            val sender = ControlRead.str(o, "senderPublicKey") ?: return null
            val typing = ControlRead.bool(o, "isTyping") ?: return null
            val ts = ControlRead.num(o, "timestamp") ?: return null
            return TypingPayload(
                senderPublicKey = sender,
                isTyping = typing,
                appleTimestamp = ts,
                timestampMs = WireClock.toUnixMillis(ts),
                groupId = ControlRead.str(o, "groupId")?.takeIf { it.isNotEmpty() },
                senderName = ControlRead.str(o, "senderName"),
            )
        }
    }
}
