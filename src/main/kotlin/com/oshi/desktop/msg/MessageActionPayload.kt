package com.oshi.desktop.msg

/**
 * Edit, delete-for-everyone, pin and unpin — iOS's UNIFIED `MessageActionPayload`
 * (`OSHI/MessageManager.swift:8593-8624`), Android's `MessageRepository.buildActionPayload`
 * (`:185-206`).
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 *     🔧ACTION🔧{"actionType":"deleteForEveryone"|"editMessage"|"pinMessage"|"unpinMessage",
 *                "targetMessageId":"<uuid>",
 *                "newContent":"corrected text",   // OMITTED when nil, never null
 *                "timestamp":<apple-epoch seconds>}
 *
 * Four keys, Swift declaration order (`swift:8603-8606`), and Android's builder `put`s them
 * in exactly that order with the same omission rule. The emoji precedes the `{`, so anything
 * scanning for a leading brace misses this payload entirely — Android's own wire test
 * asserts `payload.indexOf('{') > 0` for that reason.
 *
 * ============================================================ ONE PAYLOAD, FOUR ACTIONS
 *
 * PLAN.md §4.5's rule — never route by prefix, parse and then branch — is not advice here,
 * it is the only way this payload works at all: all four actions share `🔧ACTION🔧` and are
 * distinguished only by a field INSIDE the JSON. Android's `isEdit`/`isDelete` do exactly
 * this (parse, then read `actionType`), and a prefix-only router would apply a delete for
 * every pin.
 *
 * Android ALSO has two legacy Android-only prefixes for the same two operations —
 * `✏️EDIT✏️` (`✏️EDIT✏️`) and `🗑️DELETE🗑️` — with a different key
 * name for the target (`messageId`, not `targetMessageId`). No iPhone has ever read either:
 * they are not in iOS's catalog at all, so an iPhone renders them as raw text or drops them.
 * Android's live emitter has moved to `🔧ACTION🔧`; its decoders still accept the legacy
 * pair for messages already on devices. **This client emits `🔧ACTION🔧` only** and accepts
 * the legacy pair on ingest for the same reason Android does. They are deliberately NOT in
 * [ControlPrefix]'s catalog, because that catalog mirrors iOS's — adding two prefixes iOS
 * has never heard of would make the desktop the only client whose "single source of truth"
 * disagrees with the other two.
 *
 * ============================================================ THE EPOCH, AND ITS HISTORY
 *
 * `timestamp` is **Apple-epoch seconds**. This field has the most-documented failure history
 * in the row, and it is worth having in one place because the failure is silent on both
 * sides:
 *
 *  - iOS builds it from `Date()` under a bare `JSONEncoder` — `.deferredToDate`, i.e.
 *    seconds since 2001-01-01 (`swift:7537,7551,7559,7565`).
 *  - THREE Android emitters shipped `System.currentTimeMillis()` into it. All three are now
 *    deleted, each with a tombstone comment naming this as one of their defects
 *    (`MessageActionsManager.kt` edit, delete, and the pin pair).
 *  - It does not throw on iOS. It lands in `messages[index].editedAt` (`swift:7599`) and
 *    drives the "(edited)" stamp — for a message edited in the year 55 000.
 *  - Android's `MessageActionWireFormatTest` pins the correct encoding with a named test,
 *    "timestamp is Apple-epoch seconds, not Unix milliseconds", asserting
 *    `(nowMs / 1000.0) - 978307200.0` and bounding the result below 4e9.
 *
 * See [WireClock] for where the other three epochs sit relative to this one — including the
 * fourth, which is this same value AFTER iOS stores it: `editedAt` is persisted by
 * `FileStorage.saveMessages` with `dateEncodingStrategy = .iso8601`, so the identical field
 * is an Apple-epoch number on the wire and an ISO-8601 string on disk.
 *
 * ============================================================ newContent: ABSENT, NOT NULL
 *
 * Swift's synthesized `Codable` uses `encodeIfPresent` for Optionals, so a nil `newContent`
 * is an **absent key**. Android's now-deleted delete emitter put `JSONObject.NULL` there
 * instead — listed as its fourth defect. `MessageRepository.buildActionPayload` gets it
 * right and its test asserts `assertFalse("newContent must be omitted, not null", j.has(...))`.
 * [ControlJson.optional] is how this client cannot get it wrong.
 *
 * ============================================================ THE OWNERSHIP RULE (C-MSG-4)
 *
 * This payload is the one place in row 0.18 where parsing correctly is not enough. iOS's
 * `handleMessageAction` refuses a delete-for-everyone or an edit unless the ACTION's sender
 * is the sender of the TARGET message:
 *
 *     guard messages[index].senderPublicKey == fromSenderKey else { … return }
 *
 * — with the comment "without this check an attacker can remotely delete ANY message (see
 * security review C-MSG-4)" (`swift:7574-7578`, and again at `:7592-7596` for edit).
 * Android states the same rule at `MessageRepository.kt:5675`: "without the check, a crafted
 * 🔧ACTION🔧 could rewrite OUR OWN sent messages in our thread."
 *
 * The check is NOT in this file, because it needs the stored message and this file is a
 * codec. It lives in [ControlPayloadRouter], is stated there in the same terms, and has its
 * own named test. This paragraph exists so that a reader who finds the payload before the
 * router does not conclude the rule was forgotten.
 *
 * ============================================================ PIN: A THIRD DISAGREEMENT
 *
 * Android's `MessageActionsManager` comments that "a 1:1 pin is not something iOS listens
 * for on `🔧ACTION🔧`; the pin that iOS actually acts on rides inside the `MessageGroup`
 * payload as `pinnedMessageId`/`pinnedBy`" — and deletes its pin emitters on that basis.
 *
 * **That comment is wrong about the 1:1 case, and the shipped Swift says so.** iOS both
 * SENDS a 1:1 pin (`pinMessageAndSync` / `unpinMessageAndSync`, `swift:7556-7567`) and ACTS
 * on an incoming one (`handleMessageAction`'s `.pinMessage` branch writes
 * `pinnedMessages[fromSenderKey]` and persists it, `swift:7607-7615`). Both statements are
 * true at once: the GROUP pin travels in `MessageGroup`, and the 1:1 pin travels in
 * `🔧ACTION🔧`. Android deleted its emitters for three other good reasons (plaintext send,
 * Unix millis, and `JSONObject.NULL`) and then attributed the deletion to a fourth that is
 * not the case — so today an Android user's 1:1 pin does not reach an iPhone that would
 * happily have shown it.
 *
 * This client therefore encodes and decodes all four action types, and takes iOS's side.
 * Applying a pin needs somewhere to put it, which this row does not have yet; see
 * [ControlPayloadRouter.Outcome.PIN_NOT_APPLIED].
 */
data class MessageActionPayload(
    val actionType: ActionType,
    val targetMessageId: String,
    /** Present only for [ActionType.EDIT_MESSAGE]. Absent — never null — on the wire. */
    val newContent: String?,
    /** Apple-epoch seconds exactly as they appeared on the wire, unconverted. */
    val appleTimestamp: Double?,
    /** [appleTimestamp] as Unix millis, or null when absent or failing [WireClock]'s guard. */
    val timestampMs: Long?,
) {
    /**
     * `MessageActionPayload.ActionType` (`swift:8596-8601`). The wire values are Swift's
     * `String` raw values, i.e. the case names verbatim.
     *
     * There is no UNKNOWN case, and that is not an oversight: iOS's `ActionType` is a plain
     * `String`-raw-value `Codable` enum with no tolerant `init(from:)`, so an unknown value
     * makes the WHOLE payload fail to decode on an iPhone. [decode] returns null for the
     * same input, because being more permissive than the peer would mean acting on an action
     * the peer ignores. (Contrast `DeliveryStatus` in the store package, which DOES degrade
     * tolerantly — because there, iOS degrades tolerantly too, by hand-written decoder.)
     */
    enum class ActionType(val wire: String) {
        DELETE_FOR_EVERYONE("deleteForEveryone"),
        EDIT_MESSAGE("editMessage"),
        PIN_MESSAGE("pinMessage"),
        UNPIN_MESSAGE("unpinMessage");

        companion object {
            fun fromWire(raw: String): ActionType? = entries.firstOrNull { it.wire == raw }
        }
    }

    companion object {

        /** Legacy Android-only edit prefix. Accepted on ingest, never emitted. */
        const val LEGACY_EDIT_PREFIX = "✏️EDIT✏️"

        /** Legacy Android-only delete prefix. Accepted on ingest, never emitted. */
        const val LEGACY_DELETE_PREFIX = "🗑️DELETE🗑️"

        /**
         * `🔧ACTION🔧{…}` — the exact bytes iOS's `MessageActionPayload.toMessageString()`
         * produces and Android's `buildActionPayload` reproduces.
         *
         * [newContent] is required for [ActionType.EDIT_MESSAGE] and forbidden for the other
         * three, which is what iOS's own emitters do (`swift:7537,7551,7559,7565` pass nil
         * for delete, pin and unpin). Enforcing it makes the two mistakes that matter
         * impossible: an edit with nothing to change (iOS applies it and blanks the bubble,
         * since `plaintextContent = payload.newContent`), and a delete carrying content
         * (harmless on iOS, but it is a byte an iPhone never sends and would break any
         * byte-for-byte diff).
         */
        fun encode(
            actionType: ActionType,
            targetMessageId: String,
            atUnixMillis: Long,
            newContent: String? = null,
        ): String {
            require(targetMessageId.isNotBlank()) { "an action with no targetMessageId targets nothing" }
            if (actionType == ActionType.EDIT_MESSAGE) {
                require(!newContent.isNullOrEmpty()) {
                    "editMessage with no newContent: iOS assigns plaintextContent = newContent " +
                        "(MessageManager.swift:7601), so this blanks the peer's bubble"
                }
            } else {
                require(newContent == null) {
                    "$actionType must omit newContent — iOS's own emitters pass nil " +
                        "(MessageManager.swift:7537,7559,7565)"
                }
            }
            return ControlPrefix.ACTION + ControlJson()
                .str("actionType", actionType.wire)
                .str("targetMessageId", targetMessageId)
                .optional("newContent", newContent)
                .num("timestamp", WireClock.toAppleSeconds(atUnixMillis))
                .build()
        }

        /**
         * Decode a `🔧ACTION🔧` payload, or one of Android's two legacy prefixes, or null.
         *
         * Null when: the text is not an action payload; the body is not JSON; `actionType`
         * is missing or unrecognised (see [ActionType]); or `targetMessageId` is missing.
         * The legacy forms carry the target under `messageId` instead, and Android's
         * decoders read `optString("messageId").ifEmpty { optString("targetMessageId") }` —
         * accepting both key names, which this reproduces.
         *
         * A mis-encoded `timestamp` yields a null [timestampMs] and keeps the action. That
         * is the whole reason [WireClock.toUnixMillis] returns null instead of throwing: an
         * edit from an older Android build is still an edit the user asked for, and refusing
         * it would leave the peer's message un-corrected over a decoration.
         */
        fun decode(text: String): MessageActionPayload? {
            val body = when {
                ControlPrefix.match(text) == ControlPrefix.ACTION -> ControlPrefix.strip(text)
                text.startsWith(LEGACY_EDIT_PREFIX) -> text.removePrefix(LEGACY_EDIT_PREFIX)
                text.startsWith(LEGACY_DELETE_PREFIX) -> text.removePrefix(LEGACY_DELETE_PREFIX)
                else -> return null
            }
            val o = ControlRead.obj(body) ?: return null

            // The legacy prefixes carry no actionType — the prefix IS the action.
            val type = ControlRead.str(o, "actionType")?.let { ActionType.fromWire(it) }
                ?: when {
                    text.startsWith(LEGACY_EDIT_PREFIX) -> ActionType.EDIT_MESSAGE
                    text.startsWith(LEGACY_DELETE_PREFIX) -> ActionType.DELETE_FOR_EVERYONE
                    else -> null
                }
                ?: return null

            val target = (ControlRead.str(o, "targetMessageId") ?: ControlRead.str(o, "messageId"))
                ?.takeIf { it.isNotBlank() } ?: return null

            val ts = ControlRead.num(o, "timestamp")
            return MessageActionPayload(
                actionType = type,
                targetMessageId = target,
                newContent = ControlRead.str(o, "newContent"),
                appleTimestamp = ts,
                timestampMs = ts?.let { WireClock.toUnixMillis(it) },
            )
        }
    }
}
