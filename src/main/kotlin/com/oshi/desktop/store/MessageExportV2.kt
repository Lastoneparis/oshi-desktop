package com.oshi.desktop.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * __EXPORT_V2_INTEROP_2026_09_22__ The platform-neutral payload (version 2) that goes INSIDE the
 * `.oshiexport` container. Spec: `docs/OSHI_EXPORT_FORMAT.md`; shared fixture:
 * `docs/fixtures/export_v2_sample.json` (parsed by this project's tests, by OSHI-Android's and
 * by the iOS storage self-test — that is the interop proof).
 *
 * Version 1 payloads carried each platform's NATIVE message objects, so an iPhone export could
 * not be imported here (nor the reverse). Version 2 carries one documented shape that all three
 * writers emit and all three readers map to their own models.
 *
 * This file has three layers:
 *  1. [ExportV2] — the neutral model (no desktop type in it);
 *  2. [ExportV2.encode] / [ExportV2.parse] — its JSON codec, tolerant on read (unknown fields
 *     ignored, unknown conversation kinds skipped, a malformed message counted, never fatal);
 *  3. [DesktopExportV2] — the mapping to and from [Message] / [MessageStore].
 */
object ExportV2 {

    const val VERSION = 2

    const val KIND_DIRECT = "direct"
    const val KIND_GROUP = "group"
    /** Desktop-only threads (bots, radio): restored by a desktop, skipped by the phones. */
    const val KIND_DESKTOP_LOCAL = "desktop.local"

    /** Total raw media bytes one export may carry (same budget on all three platforms). */
    const val MEDIA_BUDGET_BYTES: Long = 48L * 1024 * 1024

    data class Contact(val publicKey: String, val alias: String?, val nickname: String? = null)

    data class Conversation(
        val id: String,
        val kind: String,
        val peerPublicKey: String? = null,
        val groupId: String? = null,
        val groupName: String? = null,
        /** Only for [KIND_DESKTOP_LOCAL]: the desktop's own conversation id. */
        val nativeId: String? = null,
    )

    data class Media(
        val type: String,
        val fileName: String? = null,
        val mime: String? = null,
        val data: ByteArray? = null,
        /** Why [data] is absent: `viewOnce`, `sizeBudget` or `unavailable`. */
        val omitted: String? = null,
    )

    data class Msg(
        val id: String,
        val conversation: String,
        val outgoing: Boolean,
        val senderPublicKey: String,
        val senderName: String? = null,
        val timestampMs: Long,
        val text: String?,
        val status: String,
        val read: Boolean,
        val editedText: String? = null,
        val editedAtMs: Long? = null,
        val deleted: Boolean = false,
        val replyToId: String? = null,
        val viewOnce: Boolean = false,
        val media: Media? = null,
        val reactions: Map<String, List<String>> = emptyMap(),
    )

    data class Payload(
        val platform: String,
        val exportedAtMs: Long,
        val accountPublicKey: String?,
        val contacts: List<Contact>,
        val conversations: List<Conversation>,
        val messages: List<Msg>,
    )

    /** A parsed payload plus what was left out, so an import can say so. */
    data class Parsed(
        val payload: Payload,
        /** Messages that were malformed or referenced an undeclared conversation. */
        val invalid: Int,
        /** Messages in a conversation kind this reader does not know. */
        val skippedUnknownKind: Int,
    )

    // ------------------------------------------------------------------ helpers

    private val ISO_MS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun isoMs(ms: Long): String = ISO_MS.format(Instant.ofEpochMilli(ms))

    /** ISO-8601 with or without fractional seconds, `Z` or an offset. */
    fun parseIso(s: String): Long =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrElse { OffsetDateTime.parse(s).toInstant().toEpochMilli() }

    /**
     * Standard, padded base64 of a 32-byte key — the one spelling every platform compares on.
     * A base64url or unpadded spelling of the same bytes maps to it; anything that is not a
     * 32-byte base64 value is returned unchanged.
     */
    fun canonicalKey(key: String): String {
        val t = key.trim()
        val bytes = runCatching { Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(t.trimEnd('=')) }.getOrNull()
            ?: return key
        return if (bytes.size == 32) Base64.getEncoder().encodeToString(bytes) else key
    }

    /** Control payloads that are not conversation: never exported, dropped on import. */
    val CONTROL_PREFIXES = listOf(
        "\uD83D\uDCF8PROFILE_UPDATE\uD83D\uDCF8", "\uD83D\uDCF8PROFILE_REQUEST\uD83D\uDCF8",
        "\uD83D\uDCD6READ_RECEIPT\uD83D\uDCD6", "\uD83D\uDCECDELIVERY_RECEIPT\uD83D\uDCEC",
        "\uD83D\uDD17LINKS_VISIBILITY\uD83D\uDD17", "\uD83C\uDFA8WALLPAPER_UPDATE\uD83C\uDFA8",
    )

    fun isControl(content: String?): Boolean = content != null && CONTROL_PREFIXES.any { content.startsWith(it) }

    fun isGroupId(s: String): Boolean = runCatching { UUID.fromString(s); true }.getOrDefault(false)

    fun canonicalGroupId(s: String): String =
        runCatching { UUID.fromString(s).toString().uppercase(java.util.Locale.US) }.getOrDefault(s)

    fun directConversationId(peer: String) = "direct:" + canonicalKey(peer)
    fun groupConversationId(groupId: String) = "group:" + canonicalGroupId(groupId)

    /** MIME for a canonical media type, refined by the file extension when there is one. */
    fun guessMime(type: String, fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "vcf" -> "text/vcard"
            else -> when (type) {
                "image" -> "image/jpeg"
                "gif" -> "image/gif"
                "video" -> "video/mp4"
                "audio" -> "audio/mp4"
                "contact" -> "application/json"
                "location" -> "application/json"
                else -> "application/octet-stream"
            }
        }
    }

    // ------------------------------------------------------------------ encode

    fun encode(p: Payload): ByteArray {
        val root = JSONObject()
            .put("version", VERSION)
            .put("platform", p.platform)
            .put("exportedAt", isoMs(p.exportedAtMs))
        p.accountPublicKey?.let { root.put("account", JSONObject().put("publicKey", it)) }
        root.put("contacts", JSONArray().apply {
            p.contacts.forEach { c ->
                put(JSONObject().put("publicKey", c.publicKey).apply {
                    c.alias?.let { put("alias", it) }
                    c.nickname?.let { put("nickname", it) }
                })
            }
        })
        root.put("conversations", JSONArray().apply {
            p.conversations.forEach { c ->
                put(JSONObject().put("id", c.id).put("kind", c.kind).apply {
                    c.peerPublicKey?.let { put("peerPublicKey", it) }
                    c.groupId?.let { put("groupId", it) }
                    c.groupName?.let { put("groupName", it) }
                    c.nativeId?.let { put("nativeId", it) }
                })
            }
        })
        val b64 = Base64.getEncoder()
        root.put("messages", JSONArray().apply {
            p.messages.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("conversation", m.conversation)
                    put("direction", if (m.outgoing) "out" else "in")
                    put("senderPublicKey", m.senderPublicKey)
                    m.senderName?.let { put("senderName", it) }
                    put("timestamp", isoMs(m.timestampMs))
                    m.text?.let { put("text", it) }
                    put("status", m.status)
                    put("read", m.read)
                    if (m.editedText != null) {
                        put("edited", JSONObject().put("text", m.editedText).apply {
                            m.editedAtMs?.let { put("at", isoMs(it)) }
                        })
                    }
                    if (m.deleted) put("deleted", true)
                    m.replyToId?.let { put("replyToId", it) }
                    if (m.viewOnce) put("viewOnce", true)
                    m.media?.let { md ->
                        put("media", JSONObject().put("type", md.type).apply {
                            md.fileName?.let { put("fileName", it) }
                            md.mime?.let { put("mime", it) }
                            md.data?.let { put("dataBase64", b64.encodeToString(it)) }
                            md.omitted?.let { put("omitted", it) }
                        })
                    }
                    if (m.reactions.isNotEmpty()) {
                        put("reactions", JSONObject().apply {
                            m.reactions.toSortedMap().forEach { (emoji, keys) -> put(emoji, JSONArray(keys.sorted())) }
                        })
                    }
                })
            }
        })
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ parse

    /**
     * Parse a version-2 payload. Throws only when the document as a whole is not a v2 payload;
     * a single bad message is counted in [Parsed.invalid] and skipped.
     */
    fun parse(root: JSONObject): Parsed {
        require(root.optInt("version", -1) == VERSION) { "not a version 2 payload" }
        val contacts = ArrayList<Contact>()
        root.optJSONArray("contacts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pk = o.optStr("publicKey") ?: continue
                contacts += Contact(canonicalKey(pk), o.optStr("alias"), o.optStr("nickname"))
            }
        }
        val conversations = LinkedHashMap<String, Conversation>()
        root.optJSONArray("conversations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optStr("id") ?: continue
                val kind = o.optStr("kind") ?: continue
                conversations[id] = Conversation(
                    id = id,
                    kind = kind,
                    peerPublicKey = o.optStr("peerPublicKey")?.let(::canonicalKey),
                    groupId = o.optStr("groupId"),
                    groupName = o.optStr("groupName"),
                    nativeId = o.optStr("nativeId"),
                )
            }
        }
        val known = setOf(KIND_DIRECT, KIND_GROUP, KIND_DESKTOP_LOCAL)
        val messages = ArrayList<Msg>()
        var invalid = 0
        var skipped = 0
        val b64 = Base64.getDecoder()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { invalid++; continue }
            val parsed = runCatching {
                val conv = conversations[o.getString("conversation")] ?: return@runCatching null
                if (conv.kind !in known) return@runCatching conv
                if (conv.kind == KIND_DIRECT && conv.peerPublicKey == null) return@runCatching null
                if (conv.kind == KIND_GROUP && conv.groupId == null) return@runCatching null
                val direction = o.getString("direction")
                require(direction == "in" || direction == "out")
                val edited = o.optJSONObject("edited")
                val media = o.optJSONObject("media")?.let { md ->
                    Media(
                        type = md.getString("type"),
                        fileName = md.optStr("fileName"),
                        mime = md.optStr("mime"),
                        data = md.optStr("dataBase64")?.let { b64.decode(it) },
                        omitted = md.optStr("omitted"),
                    )
                }
                val reactions = o.optJSONObject("reactions")?.let { r ->
                    r.keys().asSequence().associateWith { e ->
                        val a = r.getJSONArray(e)
                        (0 until a.length()).map { canonicalKey(a.getString(it)) }
                    }
                } ?: emptyMap()
                Msg(
                    id = o.getString("id").also { require(it.isNotBlank()) },
                    conversation = conv.id,
                    outgoing = direction == "out",
                    senderPublicKey = canonicalKey(o.getString("senderPublicKey")),
                    senderName = o.optStr("senderName"),
                    timestampMs = parseIso(o.getString("timestamp")),
                    text = o.optStr("text"),
                    status = o.optStr("status") ?: if (direction == "out") "sent" else "delivered",
                    read = o.optBoolean("read", false),
                    editedText = edited?.optStr("text"),
                    editedAtMs = edited?.optStr("at")?.let(::parseIso),
                    deleted = o.optBoolean("deleted", false),
                    replyToId = o.optStr("replyToId"),
                    viewOnce = o.optBoolean("viewOnce", false),
                    media = media,
                    reactions = reactions,
                )
            }.getOrNull()
            when (parsed) {
                is Msg -> messages += parsed
                is Conversation -> skipped++
                else -> invalid++
            }
        }
        return Parsed(
            Payload(
                platform = root.optString("platform", ""),
                exportedAtMs = root.optStr("exportedAt")?.let { runCatching { parseIso(it) }.getOrNull() } ?: 0L,
                accountPublicKey = root.optJSONObject("account")?.optStr("publicKey")?.let(::canonicalKey),
                contacts = contacts,
                conversations = conversations.values.toList(),
                messages = messages,
            ),
            invalid,
            skipped,
        )
    }

    private fun JSONObject.optStr(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}

/**
 * The desktop side of [ExportV2]: [Message] ⇄ neutral model, and the merge into [MessageStore].
 *
 * Conversation kinds on this client:
 *  - an id that is a 32-byte base64 key → `direct` (the peer's address);
 *  - an id that is a UUID → `group` (see `GroupIdentity.canonicalGroupId`);
 *  - anything else (bot threads `bot!…`, radio, local test ids) → `desktop.local`, which only a
 *    desktop restores.
 */
class DesktopExportV2(
    /** This account's address: standard base64 of the X25519 public key. */
    private val selfAddress: String,
    private val groupName: (String) -> String? = { null },
    /** A group we hold — group messages for any other group are skipped on import. */
    private val groupExists: (String) -> Boolean = { true },
    /** Plaintext bytes of a message's media, or null. Default: the file [Message.mediaRef] names. */
    private val mediaReader: (Message) -> ByteArray? = { m ->
        m.mediaRef?.let { File(it) }?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    },
    /** Store imported media bytes and return the new [Message.mediaRef], or null if not stored. */
    private val mediaWriter: ((id: String, fileName: String, bytes: ByteArray) -> String?)? = null,
) {

    data class Built(val payload: ExportV2.Payload, val mediaSkipped: Int)

    fun build(
        messages: List<Message>,
        contacts: List<ExportV2.Contact>,
        exportedAtMs: Long,
        mediaBudget: Long = ExportV2.MEDIA_BUDGET_BYTES,
    ): Built {
        val me = ExportV2.canonicalKey(selfAddress)
        val conversations = LinkedHashMap<String, ExportV2.Conversation>()
        val out = ArrayList<ExportV2.Msg>(messages.size)
        var budget = mediaBudget
        var skipped = 0
        for (m in messages) {
            if (ExportV2.isControl(m.content)) continue
            val cid = m.conversationId
            val conv = when {
                ExportV2.isGroupId(cid) -> {
                    val gid = ExportV2.canonicalGroupId(cid)
                    ExportV2.Conversation(ExportV2.groupConversationId(gid), ExportV2.KIND_GROUP, groupId = gid, groupName = groupName(gid))
                }
                isKey(cid) -> {
                    val peer = ExportV2.canonicalKey(cid)
                    ExportV2.Conversation(ExportV2.directConversationId(peer), ExportV2.KIND_DIRECT, peerPublicKey = peer)
                }
                else -> ExportV2.Conversation("desktop:$cid", ExportV2.KIND_DESKTOP_LOCAL, nativeId = cid)
            }
            conversations.putIfAbsent(conv.id, conv)

            val isMedia = m.mediaType != null
            val media = if (isMedia) {
                val type = m.mediaType!!.wire.let { if (it == "unknown") "document" else it }
                val fileName = if (m.mediaType == MediaType.CONTACT) null else m.content
                var omitted: String? = null
                val data = when {
                    m.isViewOnce -> { omitted = "viewOnce"; null }
                    else -> {
                        val bytes = runCatching { mediaReader(m) }.getOrNull()
                        when {
                            bytes == null -> { omitted = "unavailable"; null }
                            bytes.size > budget -> { omitted = "sizeBudget"; skipped++; null }
                            else -> { budget -= bytes.size; bytes }
                        }
                    }
                }
                ExportV2.Media(type, fileName, ExportV2.guessMime(type, fileName ?: m.mediaRef), data, omitted)
            } else null

            out += ExportV2.Msg(
                id = m.id,
                conversation = conv.id,
                outgoing = m.fromMe,
                senderPublicKey = if (m.fromMe) me else ExportV2.canonicalKey(m.senderAddress),
                timestampMs = m.sentAtMs,
                text = when {
                    m.isDeletedForEveryone -> null
                    isMedia && m.mediaType != MediaType.CONTACT -> null
                    else -> m.content
                },
                status = m.deliveryStatus.wire,
                read = m.deliveryStatus == DeliveryStatus.READ,
                editedText = m.editedContent,
                editedAtMs = m.editedAtMs,
                deleted = m.isDeletedForEveryone,
                replyToId = m.replyToId,
                viewOnce = m.isViewOnce,
                media = media,
                reactions = m.reactions.mapValues { (_, v) -> v.map(ExportV2::canonicalKey) },
            )
        }
        return Built(
            ExportV2.Payload(
                platform = EncryptedMessageExport.PLATFORM,
                exportedAtMs = exportedAtMs,
                accountPublicKey = me,
                contacts = contacts,
                conversations = conversations.values.toList(),
                messages = out,
            ),
            skipped,
        )
    }

    data class Mapped(val messages: List<Message>, val invalid: Int, val skippedGroups: Int)

    /** Neutral → [Message]. Group messages for groups we do not hold are counted, not mapped. */
    fun toNative(payload: ExportV2.Payload): Mapped {
        val me = ExportV2.canonicalKey(selfAddress)
        val convs = payload.conversations.associateBy { it.id }
        val out = ArrayList<Message>()
        var invalid = 0
        var skippedGroups = 0
        for (m in payload.messages) {
            if (ExportV2.isControl(m.text)) { invalid++; continue }
            val c = convs[m.conversation] ?: run { invalid++; null } ?: continue
            val cid = when (c.kind) {
                ExportV2.KIND_DIRECT -> c.peerPublicKey!!
                ExportV2.KIND_GROUP -> {
                    val gid = ExportV2.canonicalGroupId(c.groupId!!)
                    if (!groupExists(gid)) { skippedGroups++; continue }
                    gid
                }
                ExportV2.KIND_DESKTOP_LOCAL -> c.nativeId ?: run { invalid++; null } ?: continue
                else -> continue
            }
            val sender = if (m.outgoing) me else m.senderPublicKey
            val recipient = when {
                c.kind == ExportV2.KIND_GROUP -> cid
                m.outgoing -> cid
                else -> me
            }
            val md = m.media
            val mediaType = md?.let { mediaTypeFor(it.type, it.mime) }
            val mediaRef = if (md?.data != null && mediaWriter != null) {
                runCatching { mediaWriter!!.invoke(m.id, md.fileName ?: "file", md.data) }.getOrNull()
            } else null
            val content = when {
                m.deleted -> null
                m.text != null -> m.text
                md != null -> md.fileName ?: ""
                else -> ""
            }
            val mapped = runCatching {
                Message(
                    id = m.id,
                    conversationId = cid,
                    senderAddress = sender,
                    recipientAddress = recipient,
                    fromMe = m.outgoing,
                    content = content,
                    mediaType = mediaType,
                    mediaRef = mediaRef,
                    replyToId = m.replyToId,
                    sentAtMs = m.timestampMs,
                    sentAtSource = TimestampSource.ISO8601,
                    deliveryStatus = DeliveryStatus.fromWire(m.status),
                    transport = "import",
                    editedAtMs = m.editedAtMs,
                    editedContent = m.editedText,
                    isDeletedForEveryone = m.deleted,
                    isViewOnce = m.viewOnce,
                    // A view-once whose bytes were not exported is, here, already spent.
                    viewOnceOpened = m.viewOnce && md?.data == null,
                    reactions = m.reactions.mapValues { it.value.toSet() },
                )
            }.getOrNull()
            if (mapped == null) invalid++ else out += mapped
        }
        return Mapped(out, invalid, skippedGroups)
    }

    private fun isKey(s: String): Boolean =
        s.length in 43..44 && ExportV2.canonicalKey(s).let { k ->
            k.length == 44 && runCatching { Base64.getDecoder().decode(k).size == 32 }.getOrDefault(false)
        }

    companion object {
        fun mediaTypeFor(type: String, mime: String?): MediaType = when (type) {
            "gif" -> MediaType.IMAGE
            else -> MediaType.fromWire(type).takeIf { it != MediaType.UNKNOWN } ?: MediaType.forMime(mime)
        }
    }
}
