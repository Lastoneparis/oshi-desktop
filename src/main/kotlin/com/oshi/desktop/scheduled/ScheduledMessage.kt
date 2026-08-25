package com.oshi.desktop.scheduled

import com.oshi.desktop.msg.WireClock
import org.json.JSONObject

/**
 * Scheduled messages — PARITY.md row 0.25. The model, and the two incompatible on-disk
 * shapes it has to sit between.
 *
 * ============================================================ ANDROID HAS THEM TOO
 *
 * The ledger row names only `ScheduledMessageManager.swift` and the brief allows "iOS-only
 * feature" as a legitimate finding. It is not the finding: Android ships
 * `service/ScheduledMessageManager.kt` (431 lines) plus a `ScheduledMessageWorker`, a
 * `ScheduledMessageViewModel` and a settings screen. So the row has two references, not
 * one, and they disagree about almost everything below the concept.
 *
 * ============================================================ WHERE THE SCHEDULE LIVES
 *
 * **Purely local, on both platforms. The server is not involved at any point.** There is no
 * scheduled-message endpoint on the relay, no `kind` for it in the `/v2/sync` archive
 * (`V2SyncModels.swift:117-124`), and no route for it among the five live `/api/sync`
 * blobs — so a scheduled message does not even sync between a user's OWN devices. Schedule
 * something on your phone and your iPad knows nothing about it; if the phone never fires,
 * nothing does.
 *
 *   - iOS: `Documents/scheduled_messages.json`, `JSONEncoder().encode([ScheduledMessage])`
 *     (`ScheduledMessageManager.swift:88,268-273`).
 *   - Android: `filesDir/scheduled_messages.json`, `Gson().toJson(List<ScheduledMessage>)`
 *     (`ScheduledMessageManager.kt:112,315-322`).
 *
 * Same file NAME, mutually unreadable CONTENTS:
 *
 * | field | iOS | Android |
 * |---|---|---|
 * | id | `UUID` → **upper-case** hyphenated | `UUID.randomUUID().toString()` → **lower-case** |
 * | recipient | `conversationId` | `recipientPublicKey` |
 * | body | `generatedContent` + `userEditedContent` (`effectiveContent`) | one `content` |
 * | status | `"pending"`, `"sent"`, … (Swift raw values, lower) | `"PENDING"`, `"SENT"`, … (Gson enum name, upper) |
 * | tone | `"romantic"` … | `"ROMANTIC"` … |
 * | **`scheduledTime`** | **`Double`, Apple-epoch SECONDS** | **`Long`, Unix MILLIS** |
 * | **`createdAt`** | same | same |
 * | group target | absent | `isGroup: Boolean` |
 * | extra | `conversationName`, `wasAIGenerated` | `isAiGenerated` |
 *
 * ============================================================ THE EPOCH
 *
 * This is the answer to the brief's question and it is not visible in the Swift source,
 * because nothing in `ScheduledMessageManager.swift` mentions an epoch at all. It declares
 * `let scheduledTime: Date` (`:18`) and persists with a bare `JSONEncoder()` (`:270`).
 * A bare `JSONEncoder` uses `.deferredToDate`, which encodes a `Date` as a `Double` of
 * **seconds since 2001-01-01** — epoch 1 of the four in
 * [com.oshi.desktop.msg.WireClock]. Android wrote that inference down for the sync archive
 * in almost these words (`MultiDeviceSyncManager.kt:117-126`: *"Swift's `JSONEncoder`
 * default date strategy is `.deferredToDate`… Encoding epoch millis here instead would land
 * every timestamp somewhere in the year 32000 on iOS"*), and the same reasoning applies to
 * this file because it uses the same encoder with the same absence of a strategy.
 *
 * The comment `// UTC delivery time` on `:18` is true and says nothing about the epoch —
 * which is WireClock's own governing rule restated by accident: never infer an epoch from a
 * field's name or its comment.
 *
 * Android is the other one: `val scheduledTime: Long` compared directly against
 * `System.currentTimeMillis()` (`ScheduledMessageManager.kt:78,260,305`) — Unix millis,
 * epoch 2.
 *
 * **This client works in Unix millis internally and converts only at the iOS boundary,
 * through [WireClock] and nowhere else.** [toIosJson] and [fromIosJson] are the only two
 * functions in this package that touch an Apple-epoch value, and neither does the
 * arithmetic itself. There is no second `978_307_200` in this package; PLAN.md's count of
 * five restatements across the shipped trees is the reason.
 *
 * ============================================================ WHY MILLIS INTERNALLY
 *
 * Because the comparison that decides whether a message is due is against the local clock,
 * and every other clock in this client is Unix millis: [com.oshi.desktop.store.MessageStore]
 * ordering, the relay envelope `ts`, `x-oshi-timestamp`, the sync archive's `ts`. Holding
 * the schedule in Apple-epoch seconds to match the iOS FILE would mean converting on every
 * poll tick, i.e. moving the conversion from two boundary functions into the hot loop —
 * which is exactly how a project ends up with the fifth restatement of the constant.
 */
data class ScheduledMessage(
    val id: String,
    val recipient: String,
    val content: String,
    /** Unix epoch MILLISECONDS. See the class doc's THE EPOCH. */
    val scheduledAtMs: Long,
    val status: Status = Status.PENDING,
    val createdAtMs: Long,
    val isGroup: Boolean = false,
    val tone: Tone = Tone.CASUAL,
    val aiGenerated: Boolean = false,
    /**
     * How many delivery attempts have been made and refused-but-retryable. Neither shipped
     * platform persists this — iOS holds `sentDuringBGTask` in memory only
     * (`swift:104-113`) and Android leans on WorkManager's `runAttemptCount`
     * (`.kt:394,406`), which is lost when the row is re-enqueued by the 30-second sweep
     * (`.kt:304-313`). A desktop client that is stopped and started by a human needs the
     * count on disk or a permanently-refusing recipient is retried forever across restarts.
     */
    val attempts: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "scheduled message id must not be blank" }
        require(recipient.isNotBlank()) { "scheduled message recipient must not be blank" }
    }

    /** `pending`/`sent`/`cancelled`/`failed` on both platforms — only the CASE differs. */
    enum class Status(val iosWire: String) {
        PENDING("pending"), SENT("sent"), CANCELLED("cancelled"), FAILED("failed");

        /** Android's Gson spelling: the enum constant NAME, upper-case. */
        val androidWire: String get() = name

        companion object {
            /** Accepts either platform's spelling — case-insensitively, deliberately. */
            fun fromWire(raw: String): Status? =
                entries.firstOrNull { it.iosWire.equals(raw, ignoreCase = true) }
        }
    }

    /** `RelationshipTone` on iOS (`swift:29`), `MessageTone` on Android (`.kt:24-29`). */
    enum class Tone(val iosWire: String) {
        ROMANTIC("romantic"), FRIENDLY("friendly"), FAMILY("family"),
        CASUAL("casual"), FORMAL("formal");

        val androidWire: String get() = name

        companion object {
            fun fromWire(raw: String): Tone =
                entries.firstOrNull { it.iosWire.equals(raw, ignoreCase = true) } ?: CASUAL
        }
    }

    /** Due at [nowMs]? Inclusive, matching `scheduledTime <= now` on both platforms. */
    fun isDue(nowMs: Long): Boolean = status == Status.PENDING && scheduledAtMs <= nowMs

    companion object {

        /**
         * Encode in iOS's shape — Apple-epoch `Double` timestamps, lower-case raw values.
         *
         * Exists so a desktop schedule can be READ by an iPhone that imported the same
         * identity, not because anything transmits it today: nothing does, and the class
         * doc says why. It is here as the tested statement of what the iOS file looks
         * like, which is what the mutation guard protects.
         *
         * `conversationName` is emitted empty rather than omitted: iOS declares it
         * non-optional `let conversationName: String` (`swift:17`), and a missing key for a
         * non-optional property makes `JSONDecoder` throw `keyNotFound` — which on iOS
         * discards the WHOLE array, not the one element (`try? JSONDecoder().decode([ScheduledMessage].self…)`
         * at `swift:277-278` returns nil for the lot). That is the
         * `partial-dto-in-one-payload-empties-the-whole-list` shape, and it costs every
         * scheduled message the user has, not one.
         */
        fun toIosJson(m: ScheduledMessage, conversationName: String = ""): JSONObject =
            JSONObject().apply {
                put("id", m.id.uppercase())
                put("conversationId", m.recipient)
                put("conversationName", conversationName)
                put("scheduledTime", WireClock.toAppleSeconds(m.scheduledAtMs))
                put("generatedContent", m.content)
                put("relationshipTone", m.tone.iosWire)
                put("wasAIGenerated", m.aiGenerated)
                put("status", m.status.iosWire)
                put("createdAt", WireClock.toAppleSeconds(m.createdAtMs))
            }

        /**
         * Decode iOS's shape. Returns null when the record is unusable.
         *
         * `userEditedContent` overrides `generatedContent` — iOS's `effectiveContent`
         * (`swift:57-59`) is what actually gets sent, so a decoder that read only
         * `generatedContent` would send the AI's draft instead of the user's edit of it.
         *
         * The Apple→Unix conversion goes through [WireClock.toUnixMillis], which returns
         * null for a value that failed its magnitude guard. Null is treated as an
         * unusable RECORD here rather than as an unknown timestamp — the opposite of the
         * choice WireClock's doc makes for an edit/delete action, and for the opposite
         * reason: an action with an unknown time is still a valid action, whereas a
         * scheduled message with an unknown delivery time is a message with no schedule,
         * and guessing one means sending someone's words at a moment they did not choose.
         */
        fun fromIosJson(o: JSONObject): ScheduledMessage? {
            val id = o.optString("id", "").ifEmpty { return null }
            val recipient = o.optString("conversationId", "").ifEmpty { return null }
            if (!o.has("scheduledTime")) return null
            val scheduledMs = WireClock.toUnixMillis(o.optDouble("scheduledTime", Double.NaN))
                ?: return null
            val createdMs = WireClock.toUnixMillis(o.optDouble("createdAt", Double.NaN)) ?: scheduledMs
            val edited = o.optString("userEditedContent", "")
            return ScheduledMessage(
                id = id,
                recipient = recipient,
                content = edited.ifEmpty { o.optString("generatedContent", "") },
                scheduledAtMs = scheduledMs,
                status = Status.fromWire(o.optString("status", "")) ?: Status.PENDING,
                createdAtMs = createdMs,
                isGroup = false,
                tone = Tone.fromWire(o.optString("relationshipTone", "")),
                aiGenerated = o.optBoolean("wasAIGenerated", false),
            )
        }

        /** Android's shape: Unix millis, upper-case enum names, one `content` field. */
        fun toAndroidJson(m: ScheduledMessage): JSONObject = JSONObject().apply {
            put("id", m.id)
            put("recipientPublicKey", m.recipient)
            put("content", m.content)
            put("scheduledTime", m.scheduledAtMs)
            put("tone", m.tone.androidWire)
            put("status", m.status.androidWire)
            put("createdAt", m.createdAtMs)
            put("isAiGenerated", m.aiGenerated)
            put("isGroup", m.isGroup)
        }

        fun fromAndroidJson(o: JSONObject): ScheduledMessage? {
            val id = o.optString("id", "").ifEmpty { return null }
            val recipient = o.optString("recipientPublicKey", "").ifEmpty { return null }
            if (!o.has("scheduledTime")) return null
            return ScheduledMessage(
                id = id,
                recipient = recipient,
                content = o.optString("content", ""),
                scheduledAtMs = o.optLong("scheduledTime", 0L),
                status = Status.fromWire(o.optString("status", "")) ?: Status.PENDING,
                createdAtMs = o.optLong("createdAt", 0L),
                isGroup = o.optBoolean("isGroup", false),
                tone = Tone.fromWire(o.optString("tone", "")),
                aiGenerated = o.optBoolean("isAiGenerated", false),
            )
        }
    }
}
