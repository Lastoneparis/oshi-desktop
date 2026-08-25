package com.oshi.desktop.msg

/**
 * Message reactions — iOS `ReactionPayload` (`OSHI/MessageReactionManager.swift:115-189`),
 * Android `MessageReactionManager.sendReactionNotification` (`:319-345`) and
 * `GroupManager.broadcastReaction` (`:3702-3726`).
 *
 * ============================================================ THE WIRE, EXACTLY
 *
 *     🔥REACTION🔥{"messageId":"<uuid>","emoji":"👍","senderPublicKey":"<base64 X25519>",
 *                  "senderName":"","timestamp":<apple-epoch seconds>,"action":"add"}
 *
 * Key order is Swift's property declaration order (`swift:120-125`), which is exactly the
 * order both Android emitters `put` them in. Every key is REQUIRED: `ReactionPayload` has
 * no Optionals at all, so `senderName` is written even when empty — Android emits `""` and
 * comments "iOS falls back to 'Someone' when empty" (`MessageReactionManager.kt:339`).
 * Omitting it makes iOS's strict `Codable` tier throw `keyNotFound` and fall through to its
 * lenient tier, which — see below — reads `action` differently.
 *
 * ============================================================ FOUR DISAGREEMENTS
 *
 * **1. The prefix.** `🔥REACTION🔥` (iOS, and Android today) vs `👍 REACTION👍` (legacy
 * Android — note the space after the first emoji and none before the second; that
 * asymmetry is in both shipped catalogs and is not a typo). Both platforms parse both.
 * **We emit `🔥REACTION🔥`**, which is what both shipped clients emit; Android switched to
 * it deliberately with the comment "Use iOS prefix so both platforms decode it natively."
 *
 * **2. `action` vs `isAdding`.** iOS's struct declares `action: ReactionAction` — a
 * non-optional `"add"`/`"remove"` string. Legacy Android sent `"isAdding": true`. Both
 * decoders accept both, and **they disagree about which wins when both are present**:
 *
 *   - iOS's STRICT `Codable` tier runs first (`swift:152-155`) and reads `action`; an extra
 *     `isAdding` is an unknown key Swift ignores. So on iOS, `action` wins.
 *   - iOS's LENIENT tier (`swift:168`, reached only when the strict decode fails) reads
 *     `json["isAdding"] ?? (action == "add")` — `isAdding` wins.
 *   - Android's 1:1 decoder checks `has("isAdding")` FIRST (`MessageReactionManager.kt:216`)
 *     — `isAdding` wins.
 *   - Android's GROUP decoder checks `has("action")` first (`GroupManager.kt:437`) —
 *     `action` wins.
 *
 * Four decoders, two precedences, inside two shipped apps. **We emit `action` only** (so
 * the ambiguity cannot arise from us) and, on ingest, **prefer `action` when present** —
 * matching what iOS actually does with a well-formed payload, and matching Android's group
 * decoder. This is written down rather than chosen quietly because it is the one place in
 * this row where an honest reading of the shipped code gives two answers.
 *
 * **3. `emoji` is a bare string, and the object form is a trap.** `ReactionEmoji` is a
 * `String`-raw-value enum, so a default `JSONEncoder` emits `"emoji":"👍"` — never
 * `{"rawValue":"👍"}`. Android's own comment (`MessageReactionManager.kt:181-186`) records
 * that its documentation used to claim the opposite and warns: do NOT "fix" the emitter to
 * match, because iOS's lenient tier does `json["emoji"] as? String` and **an object there
 * is the one shape it cannot recover** — the whole payload returns nil. We emit the bare
 * string; [decode] tolerates `{"rawValue":…}` on ingest, exactly as both Android decoders
 * do, for older builds.
 *
 * **4. The emoji set is CLOSED on iOS and OPEN on Android.** [IOS_EMOJI] is the complete
 * 20-value `ReactionEmoji` enum. Android stores a free `String` and has no whitelist at
 * all. This matters more than it looks: an emoji outside the twenty fails iOS's strict
 * `Codable` tier (an unknown raw value throws), reaches its lenient tier, and there
 * `ReactionEmoji.allCases.first { $0.rawValue == emojiStr } ?? .thumbsUp` (`swift:165`)
 * **silently substitutes 👍**. So sending 😀 to an iPhone does not fail loudly and does not
 * render 😀 — it renders a thumbs-up the user never chose.
 *
 * Working rule 2 says the stricter option wins, so [encode] **rejects** an emoji outside
 * the twenty rather than emitting a reaction that will be quietly rewritten on the other
 * device. [decode] does NOT apply the whitelist: an inbound emoji is kept verbatim
 * (Android's behaviour) with [isIosRenderable] to say whether an iPhone would have shown
 * it — coercing on ingest would mean this client displays 👍 for a reaction the Android
 * peer's own screen shows as 😀, which is a divergence invented here.
 *
 * ============================================================ THE EPOCH
 *
 * `timestamp` is **Apple-epoch seconds**, stated identically in three shipped places
 * (`MessageReactionManager.kt:317` "seconds since Apple reference date (Jan 1, 2001…)",
 * the inline `- 978307200.0` in `GroupManager.broadcastReaction`, and iOS's bare
 * `JSONEncoder`). See [WireClock] for the other three epochs. Note where the group
 * reaction sits: an Apple-epoch body inside a `GroupMessage` whose own `timestamp` Android
 * stamps in Unix millis — two epochs, one nesting level apart, in one transmission.
 *
 * ============================================================ ONE REACTION PER PERSON
 *
 * Both platforms enforce "one reaction per user per message" at the STORE, not on the wire:
 * iOS removes the sender's other reactions before adding (`swift:233-238`) and Android's
 * `addReactionLocally` does `removeAll { it.senderPublicKey == … }` first. There is no
 * "replace" action on the wire — an add from someone who already reacted IS the replace.
 * [com.oshi.desktop.store.MessageStore.setReaction] already implements exactly this rule,
 * which is why this package does not restate it.
 */
data class ReactionPayload(
    val messageId: String,
    /** Verbatim from the wire. May be outside [IOS_EMOJI] — see [isIosRenderable]. */
    val emoji: String,
    val senderPublicKey: String,
    val senderName: String,
    /** True for `"action":"add"` / `"isAdding":true`. */
    val isAdding: Boolean,
    /** Apple-epoch seconds exactly as they appeared on the wire, unconverted. */
    val appleTimestamp: Double?,
    /** [appleTimestamp] as Unix millis, or null when absent or failing [WireClock]'s guard. */
    val timestampMs: Long?,
) {
    /**
     * Would an iPhone render THIS emoji, or would it silently substitute 👍?
     *
     * See disagreement 4 in the class doc. False does not mean the payload is invalid — it
     * means the two platforms will show different reactions for the same event, and a
     * caller that cares (a UI, a log, a future compatibility warning) can say so.
     */
    val isIosRenderable: Boolean get() = emoji in IOS_EMOJI

    companion object {

        /**
         * `ReactionEmoji`'s complete raw-value set, in Swift declaration order
         * (`OSHI/MessageReactionManager.swift:20-49`).
         *
         * Written as UTF-16 escapes rather than as literal emoji, for one specific reason:
         * five of them (`❤️`, `✝️`, `☪️`, `✡️`, `☸️`) carry a trailing U+FE0F that is
         * invisible in a diff, and a raw value that differs from Swift's by one invisible
         * character is a reaction iOS rewrites to 👍. The escapes make the difference
         * reviewable; `ControlPayloadWireFormatTest` re-extracts the set from the shipped
         * Swift file and fails if the two ever drift.
         */
        val IOS_EMOJI: List<String> = listOf(
            "\u2705",        // ✅ check
            "\u274C",        // ❌ cross
            "\uD83D\uDE22",  // 😢 sad
            "\uD83D\uDD95",  // 🖕 middleFinger
            "\uD83D\uDE18",  // 😘 kiss
            "\uD83D\uDC4D",  // 👍 thumbsUp
            "\uD83D\uDC4E",  // 👎 thumbsDown
            "\uD83D\uDCAF",  // 💯 hundred
            "\uD83D\uDE02",  // 😂 laugh
            "\u2764\uFE0F",  // ❤️ heart
            "\uD83D\uDD25",  // 🔥 fire
            "\uD83D\uDE2E",  // 😮 wow
            "\uD83D\uDC4F",  // 👏 clap
            "\uD83C\uDF89",  // 🎉 party
            "\uD83E\uDD14",  // 🤔 think
            "\uD83D\uDC40",  // 👀 eyes
            "\u271D\uFE0F",  // ✝️ christian
            "\u262A\uFE0F",  // ☪️ muslim
            "\u2721\uFE0F",  // ✡️ jewish
            "\u2638\uFE0F",  // ☸️ buddhist
        )

        /** iOS's fallback when the emoji is unknown — `?? .thumbsUp` (`swift:165`). */
        const val IOS_FALLBACK_EMOJI = "\uD83D\uDC4D"   // 👍

        /**
         * The exact bytes to put inside a v2 envelope's ciphertext.
         *
         * Throws when [emoji] is outside [IOS_EMOJI]. That is the strict half of working
         * rule 2 and the caller cannot opt out of it, because the failure it prevents is
         * invisible: a rejected emoji is an error a developer sees, an accepted one is a
         * thumbs-up an iPhone user sees instead of what was sent.
         *
         * [senderName] defaults to `""` — what Android emits — and is always written,
         * because iOS's field is non-Optional. See THE WIRE, EXACTLY.
         */
        fun encode(
            messageId: String,
            emoji: String,
            senderPublicKey: String,
            isAdding: Boolean,
            atUnixMillis: Long,
            senderName: String = "",
        ): String {
            require(messageId.isNotBlank()) { "a reaction with no target message id acks nothing" }
            require(senderPublicKey.isNotBlank()) { "iOS decodes senderPublicKey non-optionally" }
            require(emoji in IOS_EMOJI) {
                "emoji '$emoji' is not one of iOS's 20 ReactionEmoji raw values. iOS would " +
                    "not throw — its lenient decoder substitutes ${IOS_FALLBACK_EMOJI} " +
                    "(MessageReactionManager.swift:165), so the peer would see a reaction " +
                    "nobody sent. Pick from ReactionPayload.IOS_EMOJI."
            }
            return ControlPrefix.REACTION + ControlJson()
                .str("messageId", messageId)
                .str("emoji", emoji)
                .str("senderPublicKey", senderPublicKey)
                .str("senderName", senderName)
                .num("timestamp", WireClock.toAppleSeconds(atUnixMillis))
                .str("action", if (isAdding) "add" else "remove")
                .build()
        }

        /**
         * Decode either prefix and either add/remove spelling, or null when [text] is not a
         * reaction or carries no usable `messageId`/`emoji`.
         *
         * Those two are the only hard requirements, matching `GroupManager.parseGroupReaction`
         * ("Returns null when [raw] […] carries no usable messageId/emoji"). `senderPublicKey`
         * and `senderName` default to `""`: the transport already knows who sent the
         * envelope, so a missing key here costs attribution, not the reaction —
         * [ControlPayloadRouter] substitutes the transport sender, exactly as the group
         * decoder's `transportSenderKey` fallback does.
         */
        fun decode(text: String): ReactionPayload? {
            val prefix = ControlPrefix.match(text)
            if (prefix != ControlPrefix.REACTION && prefix != ControlPrefix.REACTION_LEGACY) return null
            val o = ControlRead.obj(ControlPrefix.strip(text)) ?: return null

            val messageId = ControlRead.str(o, "messageId")?.takeIf { it.isNotBlank() } ?: return null

            // The object form `{"rawValue":"👍"}` is legacy-Android only and is tolerated
            // exactly as both shipped Android decoders tolerate it. We never EMIT it: iOS's
            // lenient tier cannot read it and returns nil for the whole payload.
            val emoji = (o.opt("emoji") as? org.json.JSONObject)?.let { ControlRead.str(it, "rawValue") }
                ?: ControlRead.str(o, "emoji")
            if (emoji.isNullOrEmpty()) return null

            // `action` wins over `isAdding` — see disagreement 2 in the class doc.
            val action = ControlRead.str(o, "action")
            val isAdding = when {
                action != null -> action != "remove"
                else -> ControlRead.bool(o, "isAdding") ?: true
            }

            val ts = ControlRead.num(o, "timestamp")
            return ReactionPayload(
                messageId = messageId,
                emoji = emoji,
                senderPublicKey = ControlRead.str(o, "senderPublicKey") ?: "",
                senderName = ControlRead.str(o, "senderName") ?: "",
                isAdding = isAdding,
                appleTimestamp = ts,
                timestampMs = ts?.let { WireClock.toUnixMillis(it) },
            )
        }
    }
}
