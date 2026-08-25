package com.oshi.desktop.bot

import java.util.Base64
import org.json.JSONObject

/**
 * The bot wire format — PARITY.md row 0.26.
 *
 * The ledger row says "server-side API already exists", and it does; what it does not say
 * is that **the bot lane is not the V2 relay and not the IPFS path**. It is a third
 * transport, and this file exists because a desktop client that implements rows 0.1–0.25
 * perfectly still receives zero bot messages.
 *
 * ============================================================ THE ENVELOPE
 *
 * A bot message does not travel in an envelope at all. It travels **in the hash slot** of
 * the legacy pending queue ([BotQueueClient]), as one string:
 *
 * ```
 * bot:<messageId>:<standard base64 of a JSON object>
 * ```
 *
 * built at `ServerVPS/api/message_queue_server.js:609`:
 * `'bot:' + messageId + ':' + Buffer.from(JSON.stringify(botMessage)).toString('base64')`.
 * `GET /api/pending/{key}` returns it beside real CIDs and both shipped clients test for
 * the `bot:` prefix BEFORE any IPFS fetch — iOS `MessageManager.swift:2427-2431`, Android
 * `VPSClient.kt:644-647`. There is no emoji sentinel: unlike every payload in
 * [com.oshi.desktop.msg.ControlPrefix], the discriminator here is an ASCII prefix on the
 * queue entry plus `"type":"bot_message"` inside.
 *
 * **Split with a limit of 3, never a plain split.** The base64 alphabet contains no colon,
 * so an unbounded split would happen to work today — but both shipped parsers bound it
 * anyway (iOS `split(separator: ":", maxSplits: 2)` at `:2674`, Android
 * `split(":", limit = 3)` at `VPSClient.kt:1034`) and both then require **exactly** three
 * parts. [parse] does the same, because the day someone base64s with a URL-safe-plus-
 * separator variant is the day an unbounded split starts silently truncating payloads.
 *
 * The JSON, key order as emitted (`message_queue_server.js:584-596` — Node preserves
 * insertion order, and the three conditional spreads append at the end):
 *
 * | key | type | notes |
 * |---|---|---|
 * | `type` | String | always the literal `"bot_message"` |
 * | `messageId` | String | lowercase UUIDv4; duplicates the envelope's middle field |
 * | `botToken` | String | `token.substring(0,8) + '...'` — 8 hex chars then three literal dots |
 * | `botName` | String | |
 * | `groupId` | String | as the caller sent it; the server matches case-INsensitively (`:573`) |
 * | `groupName` | String | **the group's name, in cleartext, on the server** |
 * | `content` | String | `content \|\| ''` — always present, possibly empty |
 * | `timestamp` | String | **ISO-8601 UTC with milliseconds**, `new Date().toISOString()` |
 * | `mediaType` | String? | present only when set; `photo`/`video`/`audio`/`document` |
 * | `mediaData` | String? | standard base64 of the raw bytes, **inlined in the envelope** |
 * | `mediaFileName` | String? | |
 *
 * ============================================================ THE EPOCH IS A FIFTH ONE,
 * AND IT IS NOT NEW
 *
 * `timestamp` here is an **ISO-8601 string** — epoch 4 of [com.oshi.desktop.msg.WireClock],
 * the one row 0.17 met on the group definition. It is NOT the Apple-reference Double of
 * epoch 1, not the Unix millis of epoch 2, and not the Unix-seconds Double of epoch 3 the
 * pre-V2 payloads that share this queue carry. Two entries in the SAME `/api/pending`
 * array can therefore be stamped in two different date encodings.
 *
 * So [parse] converts through `WireClock.fromIso8601` and nowhere else. There is no second
 * converter in this package, and the millisecond precision is the reason that matters:
 * Android hardcodes `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")` and falls back to
 * the second-precision pattern (`VPSClient.kt:1073-1085`), iOS tries
 * `.withFractionalSeconds` then without (`MessageManager.swift:2711-2721`), and BOTH fall
 * back to "now" on failure. `WireClock.fromIso8601` accepts either spelling in one call.
 *
 * **An unparseable timestamp yields null, not `now`.** Both phones substitute the receive
 * time silently, which makes a bot with a broken clock indistinguishable from a bot with a
 * correct one and reorders a conversation permanently. Null lets the caller stamp a receive
 * time *and know that it did*.
 *
 * ============================================================ ENCRYPTION: THERE IS NONE
 *
 * Stated plainly, because this is a shipped property of a shipped messenger and softening
 * it here would be the mistake PARITY.md row 0.16 refuses to make.
 *
 * **Bot traffic is base64, not ciphertext.** No ECDH, no ratchet, no group key, nothing.
 * The server holds and forwards the plaintext, and it says so itself
 * (`message_queue_server.js:754-767`): *"a bot message is queued per member as `'bot:' +
 * id + ':' + base64(JSON)` — base64, NOT encrypted"* … *"add your key, poll the queue, read
 * every bot post in cleartext."* Everything follows from that:
 *
 *  - `GET /api/pending/{publicKey}` authenticates nothing (`:286-306`), and a public key is
 *    a public key — so anyone holding one reads that account's bot traffic.
 *  - `POST /api/bot/register` uploads **the group name and the full roster of member public
 *    keys in cleartext** (`:473-491`). The relay learns a membership graph that the V2 path
 *    (row 0.17: "relay knows nothing about membership") deliberately keeps from it.
 *  - The push fallback puts the message body in the notification:
 *    `` `[${bot.botName}] ${content.substring(0,100)}` `` (`:630-660`).
 *
 * This client therefore treats a decoded bot message as **untrusted, server-visible input**
 * and never as an authenticated message from a contact — see [BotMessage.senderAddress].
 *
 * ============================================================ ADDRESSING: A BOT HAS NO KEY
 *
 * There is no X25519 identity for a bot anywhere in either tree. What appears in the
 * sender field is a synthetic string with a literal `bot:` prefix, and the three shipped
 * spellings of it do not agree:
 *
 *  - server push payload: `"bot:" + token.substring(0,8)` (`message_queue_server.js:640`)
 *  - iOS on receive: `"bot:" + botToken.replacingOccurrences(of: "...", with: "")`
 *    (`MessageManager.swift:2800`) — strips the ellipsis **by content**
 *  - Android on receive: `"bot:" + botToken.take(8)` (`VPSClient.kt:1087`) — **by length**
 *  - iOS for a LOCAL bot: `"bot:" + bot.id.uuidString.prefix(8)` (`BotManager.swift:599`)
 *    — an uppercase UUID prefix, a completely different namespace from the token one
 *
 * The first three coincide today only because `botToken` is exactly `<8 hex>...`.
 * [BotMessage.senderAddress] takes iOS's by-content form, because it is the one that still
 * produces the right answer if the server ever changes the ellipsis, and because a
 * by-length strip of a field that no longer ends in dots would silently truncate a real
 * token. The `bot:` prefix is load-bearing downstream — `GroupManager.kt:2098-2104` skips
 * key-rotation validation for senders that start with it, and six iOS view sites branch on
 * `hasPrefix("bot:")`.
 *
 * ============================================================ WHAT IS NOT REPRODUCED
 *
 * Three iOS-only extensions to the payload, all deliberately left out:
 *
 *  1. **`mediaURL`** — a relative path resolved against a hardcoded
 *     `https://oshi-messenger.com` (`MessageManager.swift:2745`). The server never emits
 *     it. Android ignores it. Following a URL out of an unauthenticated, server-visible
 *     payload is a fetch this client is not going to make on a field nothing produces.
 *  2. **Media-in-content** — iOS re-parses `content` as JSON when it trims to something
 *     starting with `{` AND literally contains `"type":"media"` (`:2757-2778`). A second,
 *     undocumented shape inside a field typed as display text, produced by nothing in the
 *     trees.
 *  3. **The image-URL auto-download** — iOS regex-scans `content` for
 *     `https?://…\.(jpg|jpeg|png|gif|webp)` and **synchronously downloads the first match**
 *     (`:2782-2797`). A server-visible, unauthenticated string that makes the client issue
 *     an arbitrary outbound GET. Android does neither. Not reproduced, and named here so
 *     the omission is a decision rather than an oversight.
 *
 * `mediaType: "image"` IS accepted as an alias for `photo`, because that one is a real
 * cross-platform spelling difference iOS already absorbs (`:2731`) and refusing it would
 * drop messages both phones render.
 */
object BotEnvelope {

    /** The literal prefix on a queue entry that marks a bot message. */
    const val PREFIX = "bot:"

    /** The value of the payload's `type` field, always (`message_queue_server.js:585`). */
    const val TYPE = "bot_message"

    /** The literal the server appends after the token's first 8 chars (`:587`). */
    const val TOKEN_ELLIPSIS = "..."

    /** The synthetic sender-address prefix. Six iOS view sites and one Android
     *  key-rotation bypass branch on it. */
    const val SENDER_PREFIX = "bot:"

    /**
     * `mediaType` values the server accepts on `/api/bot/send`
     * (`message_queue_server.js:545-552`). `image` is not one of them but iOS maps it to
     * `photo` on receive, so it is accepted here and normalised — see [normaliseMediaType].
     */
    val MEDIA_TYPES = setOf("photo", "video", "audio", "document")

    class MalformedBotEnvelopeException(message: String) : IllegalArgumentException(message)

    /**
     * A parsed bot message.
     *
     * [unixMillis] is null when `timestamp` was absent or unparseable — see the class doc's
     * epoch section for why this is not silently replaced with the receive time.
     */
    data class BotMessage(
        val envelopeMessageId: String,
        val payloadMessageId: String,
        val botToken: String,
        val botName: String,
        val groupId: String,
        val groupName: String,
        val content: String,
        val unixMillis: Long?,
        val mediaType: String?,
        val mediaFileName: String?,
        val mediaData: ByteArray?,
    ) {
        /**
         * The synthetic sender string, `bot:<8 hex>` — iOS's by-content form. See the class
         * doc's ADDRESSING section.
         *
         * This is **not an identity**. Nothing signs it, the server chose it, and two bots
         * whose tokens share eight hex characters share it. It exists so a conversation view
         * can group and label these messages, and it must never be fed to anything that
         * treats a sender string as a key — key lookup, safety numbers (row 0.20), blocking
         * (row 0.21) — because there is no key behind it.
         */
        val senderAddress: String
            get() = SENDER_PREFIX + botToken.replace(TOKEN_ELLIPSIS, "")

        /**
         * True when the envelope's own message id and the payload's disagree.
         *
         * The server writes the same `crypto.randomUUID()` into both
         * (`message_queue_server.js:583-586,609`), so they cannot differ in traffic this
         * server produced. A mismatch means the string was assembled by something else —
         * and since the ack ([BotQueueClient.ackBot]) keys on the
         * ENVELOPE id while a UI would key on the payload id, a caller that ignored this
         * could ack one message and display another. Reported rather than thrown, because
         * one odd id is not a reason to drop a message a phone would show.
         */
        val idsDisagree: Boolean get() = envelopeMessageId != payloadMessageId

        override fun equals(other: Any?): Boolean =
            other is BotMessage && envelopeMessageId == other.envelopeMessageId &&
                payloadMessageId == other.payloadMessageId && botToken == other.botToken &&
                botName == other.botName && groupId == other.groupId &&
                groupName == other.groupName && content == other.content &&
                unixMillis == other.unixMillis && mediaType == other.mediaType &&
                mediaFileName == other.mediaFileName &&
                (mediaData?.contentEquals(other.mediaData ?: ByteArray(0)) ?: (other.mediaData == null))

        override fun hashCode(): Int =
            (((envelopeMessageId.hashCode() * 31 + botToken.hashCode()) * 31 +
                groupId.hashCode()) * 31 + content.hashCode()) * 31 + (mediaData?.size ?: 0)
    }

    /** Cheap prefix test, so a caller can route without paying for a base64 decode. */
    fun looksLikeBotEnvelope(queueEntry: String): Boolean = queueEntry.startsWith(PREFIX)

    /**
     * The middle field of `bot:<id>:<b64>`, without decoding the payload.
     *
     * This is the whole reason `/api/bot-received` exists: an envelope with inlined media is
     * hundreds of kilobytes, so the message id has to be reachable without touching the
     * base64, and the ack has to carry the id rather than the string
     * (`message_queue_server.js:417-425`). iOS uses exactly this to skip re-decoding an
     * envelope it has already handled this session (`MessageManager.swift:2681-2694`).
     *
     * @return null when [queueEntry] is not a well-formed three-part bot envelope.
     */
    fun messageIdOf(queueEntry: String): String? {
        if (!looksLikeBotEnvelope(queueEntry)) return null
        val parts = queueEntry.split(":", limit = 3)
        if (parts.size != 3) return null
        return parts[1].ifEmpty { null }
    }

    /**
     * Parse a whole bot envelope.
     *
     * @throws MalformedBotEnvelopeException on: no `bot:` prefix, not exactly three
     *   colon-delimited parts, an empty message id, payload that is not standard base64,
     *   payload that is not a JSON object, or `type` that is not `"bot_message"`.
     *
     * The `type` check is stricter than either phone — iOS never reads the field at all in
     * `processBotEnvelope`, and Android reads it only for logging. It is enforced here
     * because the `bot:` prefix is the *only* thing separating this from a CID in a shared
     * queue slot, and one literal check is what stops a future queue entry type from being
     * decoded as a bot message with every field defaulted to empty. Both phones would
     * render that as a message from `bot:` with no text.
     *
     * Fields the phones default rather than require — `botName`, `content`, `groupId`,
     * `groupName`, `botToken` — are defaulted here too (to `""`), matching
     * `MessageManager.swift:2707-2726`. The server always emits all of them, so a missing
     * one is a foreign producer, and refusing it would drop traffic an iPhone displays.
     */
    fun parse(queueEntry: String): BotMessage {
        if (!looksLikeBotEnvelope(queueEntry)) {
            throw MalformedBotEnvelopeException(
                "not a bot envelope: expected the literal prefix \"$PREFIX\""
            )
        }
        val parts = queueEntry.split(":", limit = 3)
        if (parts.size != 3) {
            throw MalformedBotEnvelopeException(
                "bot envelope must be exactly 3 colon-delimited parts, got ${parts.size} " +
                    "(MessageManager.swift:2677, VPSClient.kt:1035)"
            )
        }
        val envelopeMessageId = parts[1]
        if (envelopeMessageId.isEmpty()) {
            throw MalformedBotEnvelopeException(
                "bot envelope has an empty messageId — it is the ack key " +
                    "(/api/bot-received), so an envelope without one can never be cleared"
            )
        }

        val payloadBytes = try {
            Base64.getDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw MalformedBotEnvelopeException(
                "bot payload is not STANDARD base64 (${e.message}). The server emits " +
                    "Buffer.toString('base64') — padded, standard alphabet, no wrapping"
            )
        }

        val json = try {
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw MalformedBotEnvelopeException("bot payload is not a JSON object: ${e.message}")
        }

        val type = json.optString("type", "")
        if (type != TYPE) {
            throw MalformedBotEnvelopeException(
                "bot payload type is \"$type\", expected \"$TYPE\" " +
                    "(message_queue_server.js:585)"
            )
        }

        val tsRaw = json.optString("timestamp", "")
        val unixMillis = if (tsRaw.isEmpty()) null else com.oshi.desktop.msg.WireClock.fromIso8601(tsRaw)

        val mediaB64 = json.optString("mediaData", "")
        val mediaData = if (mediaB64.isEmpty()) null else try {
            Base64.getDecoder().decode(mediaB64)
        } catch (e: IllegalArgumentException) {
            // The text of the message is still deliverable without its attachment, and both
            // phones would show it — iOS's `Data(base64Encoded:)` returns nil and it carries
            // on (`MessageManager.swift:2740`). Dropping the whole message over an
            // undecodable attachment would lose more than it protects.
            null
        }

        return BotMessage(
            envelopeMessageId = envelopeMessageId,
            payloadMessageId = json.optString("messageId", ""),
            botToken = json.optString("botToken", ""),
            botName = json.optString("botName", ""),
            groupId = json.optString("groupId", ""),
            groupName = json.optString("groupName", ""),
            content = json.optString("content", ""),
            unixMillis = unixMillis,
            mediaType = normaliseMediaType(json.optString("mediaType", "")),
            mediaFileName = json.optString("mediaFileName", "").ifEmpty { null },
            mediaData = mediaData,
        )
    }

    /**
     * `""` → null, `"image"` → `"photo"`, a known type → itself, anything else → null.
     *
     * The `image` alias is iOS's (`MessageManager.swift:2731`); Android does not have it, so
     * an Android client shows such a message as text with a dropped attachment. Taking
     * iOS's side here is the lenient choice rather than the strict one, and it is the right
     * way round: the alternative loses an attachment that one shipped client renders.
     *
     * An unrecognised value maps to null rather than passing through, so a caller cannot be
     * handed a media type it has no branch for.
     */
    fun normaliseMediaType(raw: String): String? = when {
        raw.isEmpty() -> null
        raw in MEDIA_TYPES -> raw
        raw.lowercase() == "image" -> "photo"
        else -> null
    }
}
