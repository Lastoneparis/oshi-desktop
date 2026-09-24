package com.oshi.desktop.group

import com.oshi.desktop.msg.WireClock
import org.json.JSONObject
import java.util.Base64

/**
 * The group MESSAGE payload — the thing that actually carries what somebody typed.
 *
 * ============================================================ IT IS NOT A GROUP UPDATE
 *
 * [GroupUpdateWire] carries the group's DEFINITION and encodes its dates as ISO-8601
 * strings. This file carries a MESSAGE in that group and encodes its date as an Apple-epoch
 * number. The two payloads are twenty lines apart in the same Swift file
 * (`OSHI/GroupMessaging.swift:2247` sets `.iso8601` for the definition; `:2761` uses a BARE
 * `JSONEncoder()` for the message) and both fields are called some flavour of `timestamp`.
 * There is no way to tell them apart from a field name, which is PLAN.md §4.2's rule
 * restated by example. Both conversions go through [WireClock] and neither goes through the
 * other's function.
 *
 * ============================================================ THE DOUBLE WRAP
 *
 * A group message reaches a peer as **base64 of a JSON `GroupMessage`**, and that base64
 * string is the PLAINTEXT of an ordinary v2 ratchet envelope. Not a prefix, not a sentinel:
 *
 *     V2 envelope { type:"group", groupId:"…", ciphertext: E(base64(json GroupMessage)) }
 *
 * Android's router says so in as many words (`V2MessageRouter.kt:17-21`): *"`groupId` is
 * non-null for group traffic and MUST be routed to the group funnel — iOS sends group
 * payloads as bare base64(JSON GroupMessage) with no `👥GROUP_MESSAGE👥` prefix … a dropped
 * `groupId` lands them as base64 garbage in the 1:1 thread."* iOS builds it at
 * `OSHI/GroupMessaging.swift:2761-2762` and hands it to `v2TrySendGroup`
 * (`MessageManager+V2.swift:466-473`); Android builds the same object at
 * `GroupManager.kt:2294-2318`.
 *
 * So the envelope's `groupId` is not decoration and not redundancy — it is the ONLY thing
 * that says "this ciphertext is a group message". A desktop client that forgets to set it
 * delivers base64 into a 1:1 chat window.
 *
 * ============================================================ WHAT `encryptedContent` IS
 *
 * Not ciphertext. The name is a fossil. By the time this JSON is built the body is either
 * UTF-8 prose or a media JSON blob, and it is base64'd into `encryptedContent` purely
 * because Swift encodes `Data` as base64 (`OSHI/GroupMessaging.swift:2554` — "`encryptedContent`
 * is a raw plaintext buffer at this point"). The real encryption happens one layer out, in
 * the ratchet.
 *
 * ============================================================ THE CAPTION PRECEDENCE
 *
 * [resolveIncomingBody] is a port of Android's fix for a P0 that ate photos
 * (`GroupManager.kt:362-373`). iOS puts a captioned photo's CAPTION in `plaintextContent`
 * and the media JSON in `encryptedContent`. The obvious fallback chain — `content`, then
 * `plaintextContent`, then `encryptedContent` — resolves a captioned photo to the caption,
 * never runs the `{`-prefixed media branch, and discards the image bytes. An UNcaptioned
 * photo has no `plaintextContent`, falls through, and works, "which is exactly why this
 * survived testing."
 *
 * The rule is therefore: **the media JSON wins wherever it turned up**, and only then does
 * the ordered fallback run. And the media test is `startsWith("{")` AND
 * `contains("\"type\":\"media\"")` — not `{` alone, or a location payload would be promoted
 * over a real caption.
 *
 * ============================================================ IOS AND ANDROID DISAGREE
 *
 *  1. **Three Android-only keys.** Android appends `messageId`, `content` and `senderName`
 *     (`GroupManager.kt:2313-2315`). iOS's `GroupMessage` is `Codable` with an explicit
 *     `CodingKeys` list (`swift:377-385`), so it IGNORES all three — and its Codable path is
 *     tried FIRST (`swift:716`), so they never even reach the tolerant parser that would
 *     misuse them. Emitted here, because they cost nothing on iOS and they are what Android
 *     reads.
 *  2. **`content` outranks `plaintextContent` in iOS's tolerant parser** (`swift:740-741`) —
 *     the same precedence Android had to fix on its own ingest. It is only reachable when
 *     the Codable decode fails, so it does not bite today; recorded because emitting BOTH
 *     keys with different values would arm it.
 *  3. **Timestamp magnitude sniffing.** iOS's tolerant parser guesses the epoch from the
 *     magnitude (`swift:752-761`): `>1e12` millis, `>1e9` Unix seconds, else Apple epoch.
 *     That is precisely the heuristic [WireClock.looksLikeUnixSeconds] documents as unable
 *     to work — a Unix-seconds value between 1992 and 2087 is a plausible Apple-epoch value
 *     too. This client emits Apple-epoch seconds, which is what both Codable paths expect,
 *     and never relies on the sniffing.
 *  4. **`mediaType` spelling.** The GROUP `MediaType` enum says `photo`; the 1:1 enum says
 *     `image`. They are separate Swift types and iOS's own comment forbids bridging them by
 *     rawValue (`swift:2717-2719`). [GroupMediaType] is the group one.
 */
object GroupMessageWire {

    /**
     * iOS group `MediaType` (`OSHI/GroupMessaging.swift:405-412`). `photo`, not `image` —
     * see the class doc. Android maps its own uppercase enum onto these at
     * `GroupManager.mapIOSMediaType` (`GroupManager.kt:64-77`), which accepts `image` as an
     * inbound alias for `photo` and emits `photo`.
     */
    enum class GroupMediaType(val raw: String) {
        PHOTO("photo"),
        VIDEO("video"),
        AUDIO("audio"),
        DOCUMENT("document"),
        CONTACT("contact");

        companion object {
            /** Inbound: accepts iOS's spellings plus `image`, the 1:1 alias Android sends. */
            fun fromRaw(raw: String?): GroupMediaType? = when (raw?.lowercase()) {
                "photo", "image" -> PHOTO
                "video" -> VIDEO
                "audio" -> AUDIO
                "document" -> DOCUMENT
                "contact" -> CONTACT
                else -> null
            }
        }
    }

    /** One group message, as it crosses the wire. */
    data class GroupMessagePayload(
        val messageId: String,
        val groupId: String,
        val senderPublicKey: String,
        /** Prose, or a media JSON blob. Base64'd into `encryptedContent` on the wire. */
        val body: String,
        val timestampUnixMillis: Long,
        val mediaType: GroupMediaType? = null,
        /** The caption, for a media message only. See the class doc. */
        val plaintextContent: String? = null,
        val senderName: String? = null,
        val chainIndex: Int = 0,
        /** __GROUP_E2E_V2_2026_09_23__ spec §2.2 `mediaFileName` (v2 media). */
        val mediaFileName: String? = null,
        /**
         * __GROUP_E2E_V2_2026_09_23__ spec §2.4: `message_edited` / `message_deleted`. When set the
         * message is a COMMAND: `senderPublicKey:"SYSTEM"`, `encryptedContent:""`, `isRead:true`.
         */
        val systemMessageType: String? = null,
        /** `actorPublicKey`, `editedMessageId`/`editedContent` or `deletedMessageId`. Strings only. */
        val systemMessageData: Map<String, String>? = null,
        /** __MENTIONS_2026_09_23__ optional `mentions` key, see [MentionWire]. Unfiltered on decode. */
        val mentions: List<MentionWire.Mention> = emptyList(),
    ) {
        val isSystem: Boolean get() = systemMessageType != null || senderPublicKey == SYSTEM_SENDER
    }

    /** `senderPublicKey` of a system message (spec §2.4). */
    const val SYSTEM_SENDER: String = "SYSTEM"
    const val SYSTEM_EDITED: String = "message_edited"
    const val SYSTEM_DELETED: String = "message_deleted"

    /**
     * The JSON an iPhone's `JSONDecoder().decode(GroupMessage.self, …)` accepts, in Android's
     * `put` order (`GroupManager.kt:2294-2316`) so a byte diff against a real Android payload
     * stays meaningful.
     *
     * `timestamp` goes through [WireClock.toAppleSeconds], which THROWS on an implausible
     * instant. That is deliberate and it is the emitter half of PLAN.md §4.2: we control our
     * own clock, so a Unix-millis value reaching this field is a bug in this process, and it
     * should stop here rather than render as the year 55 000 on somebody's phone.
     */
    fun encode(msg: GroupMessagePayload): String {
        if (msg.systemMessageType != null) {
            // __GROUP_E2E_V2_2026_09_23__ spec §2.4 / vectors `edit`, `delete`.
            val data = GroupJson()
            msg.systemMessageData.orEmpty().forEach { (k, v) -> data.str(k, v) }
            return GroupJson()
                .str("id", msg.messageId)
                .str("groupId", GroupIdentity.canonicalGroupId(msg.groupId))
                .str("senderPublicKey", SYSTEM_SENDER)
                .str("encryptedContent", "")
                .num("timestamp", WireClock.toAppleSeconds(msg.timestampUnixMillis))
                .bool("isRead", true)
                .int("messageChainIndex", 0)
                .str("systemMessageType", msg.systemMessageType)
                .obj("systemMessageData", data.build())
                .build()
        }
        val json = GroupJson()
            .str("id", msg.messageId)
            .str("groupId", GroupIdentity.canonicalGroupId(msg.groupId))
            .str("senderPublicKey", msg.senderPublicKey)
            .str(
                "encryptedContent",
                Base64.getEncoder().encodeToString(msg.body.toByteArray(Charsets.UTF_8)),
            )
            .num("timestamp", WireClock.toAppleSeconds(msg.timestampUnixMillis))
            .bool("isRead", false)
            .int("messageChainIndex", msg.chainIndex)
        msg.mediaType?.let { json.str("mediaType", it.raw) }
        if (msg.mediaType != null) json.optional("mediaFileName", msg.mediaFileName?.takeIf { it.isNotEmpty() })
        // Caption: media only. On a plain text message the body already travels in
        // `encryptedContent`, and duplicating it here makes iOS draw the same string twice
        // (Android's own note, GroupManager.kt:2305-2311).
        if (msg.mediaType != null) {
            msg.plaintextContent?.takeIf { it.isNotEmpty() }?.let { json.str("plaintextContent", it) }
        }
        // Android compatibility keys. iOS's CodingKeys omit all three, so they are inert
        // there; Android reads `content`.
        json.str("messageId", msg.messageId)
        json.str("content", msg.body)
        json.str("senderName", msg.senderName.orEmpty())
        // __MENTIONS_2026_09_23__ last, optional, ignored by every shipped decoder (MentionWire).
        MentionWire.render(msg.mentions)?.let { json.obj(MentionWire.FIELD, it) }
        return json.build()
    }

    /**
     * [encode] base64'd — the exact bytes that become a v2 group envelope's PLAINTEXT.
     *
     * There is no sentinel prefix and there must not be one: iOS sends "bare
     * base64(JSON GroupMessage) with no `👥GROUP_MESSAGE👥` prefix"
     * (`OSHI-Android/.../network/v2/V2MessageRouter.kt:18-20`), and the envelope's `groupId`
     * is what routes it.
     */
    fun encodeForEnvelope(msg: GroupMessagePayload): String =
        Base64.getEncoder().encodeToString(encode(msg).toByteArray(Charsets.UTF_8))

    /** The media blob that rides in `encryptedContent` — iOS `swift:2534-2546`. */
    fun encodeMediaBlob(
        mediaType: GroupMediaType,
        contentBase64: String,
        size: Int,
        caption: String? = null,
        fileName: String? = null,
    ): String {
        val json = GroupJson()
            .str("type", "media")
            .str("mediaType", mediaType.raw)
            .str("content", contentBase64)
            .int("size", size)
        caption?.takeIf { it.isNotEmpty() }?.let { json.str("caption", it) }
        fileName?.takeIf { it.isNotEmpty() }?.let { json.str("fileName", it) }
        return json.build()
    }

    /** Decode a v2 group envelope's plaintext (base64 of the JSON) back to a payload. */
    fun decodeFromEnvelope(base64Plaintext: String): GroupMessagePayload? {
        val bytes = runCatching { Base64.getDecoder().decode(base64Plaintext.trim()) }.getOrNull()
            ?: return null
        return decode(String(bytes, Charsets.UTF_8))
    }

    /** Decode the JSON form. Null when it is not a group message. */
    fun decode(json: String): GroupMessagePayload? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val groupId = o.opt("groupId") as? String ?: return null
        val sender = (o.opt("senderPublicKey") as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val id = (o.opt("id") as? String) ?: (o.opt("messageId") as? String) ?: return null
        val decodedEncrypted = (o.opt("encryptedContent") as? String)
            ?.let { enc -> runCatching { String(Base64.getDecoder().decode(enc), Charsets.UTF_8) }.getOrNull() }
            .orEmpty()
        val body = resolveIncomingBody(
            contentField = (o.opt("content") as? String).orEmpty(),
            plaintextContentField = (o.opt("plaintextContent") as? String).orEmpty(),
            decodedEncryptedContent = decodedEncrypted,
        )
        val ts = (o.opt("timestamp") as? Number)?.toDouble()?.let(WireClock::toUnixMillis)
            ?: return null
        return GroupMessagePayload(
            messageId = id,
            groupId = GroupIdentity.canonicalGroupId(groupId),
            senderPublicKey = sender,
            body = body,
            timestampUnixMillis = ts,
            mediaType = GroupMediaType.fromRaw(o.opt("mediaType") as? String),
            plaintextContent = (o.opt("plaintextContent") as? String)?.takeIf { it.isNotEmpty() },
            senderName = (o.opt("senderName") as? String)?.takeIf { it.isNotEmpty() },
            chainIndex = (o.opt("messageChainIndex") as? Number)?.toInt() ?: 0,
            mediaFileName = (o.opt("mediaFileName") as? String)?.takeIf { it.isNotEmpty() },
            systemMessageType = (o.opt("systemMessageType") as? String)?.takeIf { it.isNotEmpty() },
            systemMessageData = (o.opt("systemMessageData") as? JSONObject)?.let { d ->
                d.keys().asSequence().mapNotNull { k -> (d.opt(k) as? String)?.let { k to it } }.toMap()
            },
            mentions = MentionWire.parse(o.opt(MentionWire.FIELD)),
        )
    }

    /**
     * **The guard that keeps a captioned photo's bytes.** Android
     * `GroupManager.resolveIncomingBody` (`GroupManager.kt:362-373`); see the class doc.
     *
     * The media JSON wins wherever it turned up; only then does the ordered fallback run.
     */
    fun resolveIncomingBody(
        contentField: String,
        plaintextContentField: String,
        decodedEncryptedContent: String,
    ): String {
        val mediaJson = listOf(decodedEncryptedContent, contentField, plaintextContentField)
            .firstOrNull { it.trimStart().startsWith("{") && it.contains("\"type\":\"media\"") }
        if (mediaJson != null) return mediaJson
        return contentField.takeIf { it.isNotEmpty() }
            ?: plaintextContentField.takeIf { it.isNotEmpty() }
            ?: decodedEncryptedContent
    }

    /**
     * Case-insensitive group-message id comparison, and the reason it has to be.
     *
     * iOS's `GroupMessage.id` is a `UUID`. `UUID(uuidString:)` is case-INsensitive on the way
     * in and `uuidString` is UPPERCASE on the way out, so an Android-minted lowercase id
     * comes back from an iPhone uppercased. Every iOS reaction, read receipt, deletion and
     * reply-quote targets that uppercase form. Android compared with a plain `==` and
     * "silently dropped in both directions" every one of them
     * (`GroupManager.kt:3629-3641`).
     *
     * Ids are compared case-insensitively and **stored verbatim** — never case-folded at
     * rest, because the stored id is what we echo back on our own outbound payloads.
     */
    fun sameMessageId(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
}
