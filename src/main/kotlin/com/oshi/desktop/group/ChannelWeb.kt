package com.oshi.desktop.group

import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.store.MediaType
import com.oshi.desktop.store.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/**
 * __CHANNEL_WEB_2026_11_26__ Public web preview for channels — the desktop half of
 * `ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md` (server: branch feat/group-handle-registry-server,
 * 055e72f1a). Telegram-style post links `https://oshi-messenger.com/c/<handle>/<n>` and a
 * one-line embed. Port of iOS `ChannelWeb.swift` (5e3982caa), rule for rule.
 *
 * This file is PURE (no I/O, no clock): URLs, the embed snippet, deep-link parsing, the app → web
 * markup, what part of a channel message is published, the request bodies, the outbox model and
 * the outcome of one HTTP answer. The runner is [ChannelWebPublisher]; the image step is
 * [ChannelWebImage].
 */
object ChannelWeb {
    const val SITE = "https://oshi-messenger.com"
    const val MAX_TEXT_CODE_POINTS = 4096
    const val MAX_TITLE_CODE_POINTS = 64
    const val MAX_DESCRIPTION_CODE_POINTS = 255
    const val MAX_IMAGE_BYTES = 10_485_760

    private val POST_ID = Regex("^[A-Za-z0-9._:-]{1,128}$")

    /** §3.4 `^[A-Za-z0-9._:-]{1,128}$`. */
    fun isValidPostId(s: String): Boolean = POST_ID.matches(s)

    /** The id a channel message is published under: its UUID, upper-case (the wire spelling everywhere). */
    fun postId(messageId: String): String = messageId.uppercase(Locale.ROOT)

    fun channelUrl(handle: String): String = "$SITE/c/$handle"
    fun postUrl(handle: String, n: Int): String = "$SITE/c/$handle/$n"
    /** Without the scheme, for display: `oshi-messenger.com/c/<handle>`. */
    fun channelDisplay(handle: String): String = "oshi-messenger.com/c/$handle"
    fun appLink(handle: String, n: Int?): String = if (n == null) "oshi://channel?h=$handle" else "oshi://channel?h=$handle&n=$n"

    /** §8, byte for byte. [dark] null → the reader's system theme (no attribute). */
    fun embedSnippet(handle: String, n: Int, dark: Boolean? = null): String {
        val darkAttr = if (dark == null) "" else " data-dark=\"${if (dark) "1" else "0"}\""
        return "<script async src=\"https://oshi-messenger.com/js/oshi-widget.js?1\" " +
            "data-oshi-post=\"$handle/$n\" data-width=\"100%\"$darkAttr></script>"
    }

    // Wire paths (callers pass validated handles / post ids only: `[a-z0-9_]`, `[A-Za-z0-9._:-]`).
    fun pathChannel(h: String) = "/v2/c/$h"
    fun pathMedia(h: String) = "/v2/c/$h/media"
    fun pathPost(h: String, postId: String) = "/v2/c/$h/posts/$postId"
    fun pathAdmin(h: String) = "/v2/c/$h/admin"
    fun pathPublicPost(h: String, n: Int) = "/v2/c/$h/$n"

    /** A link that opens a channel (and maybe one of its posts). [handle] is normalised and valid. */
    data class Link(val handle: String, val n: Int?)

    private val HOSTS = setOf("oshi-messenger.com", "www.oshi-messenger.com", "oshi-messenger.org", "www.oshi-messenger.org")

    /**
     * `oshi://channel?h=<h>[&n=<n>]` and `https://oshi-messenger.com/c/<h>[/<n>]` (+ www., .org).
     * `/c/<h>/media/…` is not a channel link. A malformed `n` is ignored rather than refusing the
     * whole link: the channel still opens. An invalid handle → null (not ours to open).
     */
    fun parseLink(raw: String?): Link? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        fun number(s: String?): Int? =
            s?.takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "oshi" -> {
                // `oshi://channel?h=x` — an opaque-looking URI to java.net.URI only without "//".
                if (uri.host?.lowercase(Locale.ROOT) != "channel") return null
                val q = query(uri.rawQuery)
                val h = GroupSettings.normalizeHandle(q["h"]) ?: return null
                return Link(h, number(q["n"]))
            }
            "https", "http" -> {
                if (uri.scheme.lowercase(Locale.ROOT) != "https") return null
                if (uri.host?.lowercase(Locale.ROOT) !in HOSTS) return null
                val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
                if (parts.size !in 2..3 || parts[0] != "c") return null
                if (parts.size == 3 && parts[2] == "media") return null
                val h = GroupSettings.normalizeHandle(decode(parts[1])) ?: return null
                return Link(h, if (parts.size == 3) number(parts[2]) else null)
            }
            else -> return null
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrDefault(s)

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to decode(p.substring(i + 1))
        }.toMap()
}

// ---------------------------------------------------------------------------- text

object ChannelWebText {
    private const val MARKERS = "*_~`"

    private fun isBoundary(c: Char?): Boolean = c == null || c.isWhitespace() || !c.isLetterOrDigit()

    /**
     * The app's markup (desktop [com.oshi.desktop.msg.InlineMarkup]: `*b*` bold, `_i_` italic,
     * `~s~` strike, `` `c` `` code; iOS also writes `**b**` bold and `__u__` underline) rewritten
     * in the web's §5 markup (`**bold**`, `_italic_`). Underline / strike / code have no web form:
     * the text stays, the markers go. Same conservative rules as the app's parser: an opening
     * marker after a boundary, a closing one on the SAME line followed by a boundary, a run that
     * neither starts nor ends with whitespace — `snake_case_name` and `2 * 3 * 4` stay literal.
     */
    fun webMarkup(input: String): String {
        if (input.none { it in MARKERS }) return input
        val out = StringBuilder(input.length + 8)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c !in MARKERS || !isBoundary(out.lastOrNull())) { out.append(c); i++; continue }
            // Doubled `**` / `__` first (iOS bold / underline).
            if ((c == '*' || c == '_') && i + 1 < input.length && input[i + 1] == c) {
                val close = closing(input, i + 2, "$c$c")
                if (close > 0) {
                    val inner = input.substring(i + 2, close)
                    out.append(if (c == '*') "**${webMarkup(inner)}**" else webMarkup(inner))
                    i = close + 2
                    continue
                }
            }
            val close = closing(input, i + 1, c.toString())
            if (close < 0) { out.append(c); i++; continue }
            val inner = input.substring(i + 1, close)
            out.append(
                when (c) {
                    '*' -> "**${webMarkup(inner)}**"
                    '_' -> "_${webMarkup(inner)}_"
                    '~' -> webMarkup(inner)
                    else -> inner   // code: literal, markers gone
                },
            )
            i = close + 1
        }
        return out.toString()
    }

    /**
     * Index of the closing [marker] for a run starting at [from], on the same line, with a
     * non-empty inner that does not start/end with whitespace and a boundary after; -1 = none.
     * Like the app's parser it looks at the FIRST candidate only (no backtracking).
     */
    private fun closing(s: String, from: Int, marker: String): Int {
        var j = from
        while (j < s.length) {
            val d = s[j]
            if (d == '\n' || d == '\r') return -1
            if (s.startsWith(marker, j)) {
                val after = s.getOrNull(j + marker.length)
                val inner = s.substring(from, j)
                if (inner.isEmpty() || inner.first().isWhitespace() || inner.last().isWhitespace()) return -1
                return if (isBoundary(after)) j else -1
            }
            j++
        }
        return -1
    }

    /**
     * The text of a channel message as it goes to the web, or null when there is nothing to
     * publish: control payloads (polls, locations, reactions, call summaries…) are not posts; a
     * reply / forward envelope publishes its body.
     */
    fun publishableText(raw: String?): String? {
        var t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ReplyEnvelope.isWrapped(t)) {
            t = ReplyEnvelope.unwrap(t)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        }
        if (ControlPrefix.isControl(t) || ReplyEnvelope.isWrapped(t)) return null
        if (t.startsWith("{") && t.contains("\"type\":\"media\"")) return null
        val web = webMarkup(t).trim()
        return if (web.isEmpty()) null else truncate(web, ChannelWeb.MAX_TEXT_CODE_POINTS)
    }

    /** At most [max] code points (the server counts code points), never splitting a pair; "…" marks a cut. */
    fun truncate(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end) + "…"
    }

    /** One line, ≤ 64 code points (`title`). */
    fun title(s: String): String =
        truncate(s.split('\n', '\r').joinToString(" ").trim(), ChannelWeb.MAX_TITLE_CODE_POINTS)
}

// ---------------------------------------------------------------------------- what a message publishes

/** One channel post's web content. [hasImage]: the message's own photo / GIF (one per OSHI message). */
data class ChannelWebPostSource(val text: String, val hasImage: Boolean, val createdAtMs: Long)

/** What the channel profile (`PUT /v2/c/<handle>`) carries. [avatarKey] identifies the picture cheaply. */
data class ChannelWebProfile(
    val title: String,
    val description: String?,
    val searchIndexing: Boolean,
    /** A hash of the picture's SOURCE (not of the re-encoded bytes), or null for no picture. */
    val avatarKey: String?,
) {
    val signature: String get() = ChannelWebContent.sha12("$title\u0001${description.orEmpty()}\u0001$searchIndexing\u0001${avatarKey ?: "-"}")
}

object ChannelWebContent {

    /** Photos and GIFs only (§9.2); a sticker is not a post image (iOS: file name contains "sticker"). */
    fun isPublishableImage(m: Message): Boolean {
        if (m.mediaType != MediaType.IMAGE || m.mediaRef.isNullOrBlank()) return false
        val name = File(m.mediaRef).name.lowercase(Locale.ROOT)
        if (name.contains("sticker")) return false
        // The desktop's own mascot stickers go out as `oshi-<name>.gif` (OshiMascot.gifFileName).
        if (name.startsWith("oshi-") && name.endsWith(".gif")) return false
        return true
    }

    /**
     * The caption of a media row. A desktop media row stores the file NAME as its content when
     * there is no caption (OshiClient.sendGroupFile), so a content equal to it is no caption.
     */
    fun caption(m: Message): String? {
        val c = (m.editedContent ?: m.content)?.takeIf { it.isNotBlank() } ?: return null
        val name = m.mediaRef?.let { File(it).name }
        return if (name != null && c == name) null else c
    }

    /** null = nothing to publish (or not a post at all). */
    fun source(m: Message): ChannelWebPostSource? {
        if (m.isDeletedForEveryone || m.hiddenLocally || m.commentOn != null) return null
        if (m.mediaType == MediaType.LOCATION || m.mediaType == MediaType.CONTACT) return null
        val raw = if (m.mediaType == null) (m.editedContent ?: m.content) else caption(m)
        val text = ChannelWebText.publishableText(raw).orEmpty()
        val image = isPublishableImage(m)
        if (text.isEmpty() && !image) return null
        return ChannelWebPostSource(text, image, m.sentAtMs)
    }

    /** §3.4 body: `text`, `media`, `createdAt`, in that order (the §10 vector's bytes). */
    fun postBody(text: String, media: List<String>, createdAtMs: Long): ByteArray =
        buildString {
            append("{\"text\":").append(jsonString(text))
            append(",\"media\":[").append(media.joinToString(",") { jsonString(it) }).append(']')
            append(",\"createdAt\":").append(createdAtMs).append('}')
        }.toByteArray(Charsets.UTF_8)

    /** Sentinel for "send `avatarMediaId: null`" (remove) in [profileBody]. */
    const val REMOVE_AVATAR = "\u0000remove"

    /**
     * §3.1 body. [avatar]: null = key absent (unchanged), [REMOVE_AVATAR] = `null` (remove), else
     * the media id. Title/description always sent (null clears), `kind` asserts a channel.
     */
    fun profileBody(title: String?, description: String?, searchIndexing: Boolean, avatar: String? = null): ByteArray =
        buildString {
            append("{\"kind\":\"channel\"")
            val t = title?.let { ChannelWebText.title(it) }?.takeIf { it.isNotEmpty() }
            append(",\"title\":").append(t?.let(::jsonString) ?: "null")
            val d = description?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { ChannelWebText.truncate(ChannelWebText.webMarkup(it), ChannelWeb.MAX_DESCRIPTION_CODE_POINTS) }
            append(",\"description\":").append(d?.let(::jsonString) ?: "null")
            if (avatar != null) append(",\"avatarMediaId\":").append(if (avatar == REMOVE_AVATAR) "null" else jsonString(avatar))
            append(",\"searchIndexing\":").append(searchIndexing).append('}')
        }.toByteArray(Charsets.UTF_8)

    /** JSON string literal: `"` `\` and controls escaped, everything else (including `/` and non-ASCII) as is. */
    fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun sha12(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------- durable state

/** The outbox and the postId → n map. Mutable; guarded by [ChannelWebPublisher]'s lock. */
class ChannelWebState {
    data class PostRecord(
        val handle: String,
        val groupId: String,
        val n: Int? = null,
        /** Media ids attached to the published version (reused by edits). */
        val mediaIds: List<String> = emptyList(),
        /** true when THIS device published it, false when learned from `GET …/admin` or a public read. */
        val mine: Boolean = false,
    )

    data class ChannelRecord(
        val groupId: String,
        /** [ChannelWebProfile.signature] of the last profile the server accepted. */
        val profileSignature: String? = null,
        /** [ChannelWebProfile.avatarKey] of the avatar the server holds. */
        val avatarKey: String? = null,
        val lastAdminSyncMs: Long? = null,
    )

    enum class Kind(val wire: String) {
        PROFILE("profile"), DISABLE("disable"), PUBLISH("publish"), DELETE("delete"), SYNC("sync");
        companion object { fun of(w: String?): Kind? = entries.firstOrNull { it.wire == w } }
    }

    data class Job(
        val id: String = java.util.UUID.randomUUID().toString(),
        val kind: Kind,
        val groupId: String,
        val handle: String,
        val postId: String? = null,
        val attempts: Int = 0,
        val notBeforeMs: Long = 0L,
        val lastError: String? = null,
    ) {
        /** Deletes and disables never give up: they are what removes something from the public web. */
        val forever: Boolean get() = kind == Kind.DELETE || kind == Kind.DISABLE
    }

    val posts: LinkedHashMap<String, PostRecord> = LinkedHashMap()      // key: postId
    val channels: LinkedHashMap<String, ChannelRecord> = LinkedHashMap() // key: handle
    val jobs: ArrayList<Job> = ArrayList()

    fun n(postId: String, handle: String): Int? = posts[postId]?.takeIf { it.handle == handle }?.n

    fun postId(handle: String, n: Int): String? = posts.entries.firstOrNull { it.value.handle == handle && it.value.n == n }?.key

    /**
     * Queue with coalescing: one pending publish per post (it reads the latest content when it
     * runs, so an edit queued behind it is already covered); a delete cancels the publish; a
     * disable cancels every write for the handle; a profile replaces an older profile (but not a
     * disable: a purge that was asked for still happens first); one sync per handle.
     */
    fun enqueue(job: Job) {
        when (job.kind) {
            Kind.PUBLISH -> {
                if (jobs.any { it.kind == Kind.PUBLISH && it.postId == job.postId && it.handle == job.handle }) return
                jobs.removeAll { it.kind == Kind.DELETE && it.postId == job.postId && it.handle == job.handle }
            }
            Kind.DELETE -> jobs.removeAll { (it.kind == Kind.PUBLISH || it.kind == Kind.DELETE) && it.postId == job.postId && it.handle == job.handle }
            Kind.DISABLE -> jobs.removeAll { it.handle == job.handle }
            Kind.PROFILE -> jobs.removeAll { it.handle == job.handle && it.kind == Kind.PROFILE }
            Kind.SYNC -> if (jobs.any { it.kind == Kind.SYNC && it.handle == job.handle }) return
        }
        jobs.add(job)
    }

    /** The next job allowed to run at [nowMs] (FIFO among due jobs). */
    fun nextDue(nowMs: Long): Job? = jobs.firstOrNull { it.notBeforeMs <= nowMs }

    fun toJson(): String {
        val o = JSONObject().put("v", 1)
        o.put("posts", JSONObject().also { p ->
            for ((k, r) in posts) p.put(k, JSONObject().put("handle", r.handle).put("groupId", r.groupId)
                .put("n", r.n ?: JSONObject.NULL).put("mediaIds", JSONArray(r.mediaIds)).put("mine", r.mine))
        })
        o.put("channels", JSONObject().also { c ->
            for ((k, r) in channels) c.put(k, JSONObject().put("groupId", r.groupId)
                .put("profileSignature", r.profileSignature ?: JSONObject.NULL)
                .put("avatarKey", r.avatarKey ?: JSONObject.NULL)
                .put("lastAdminSync", r.lastAdminSyncMs ?: JSONObject.NULL))
        })
        o.put("jobs", JSONArray().also { a ->
            for (j in jobs) a.put(JSONObject().put("id", j.id).put("kind", j.kind.wire).put("groupId", j.groupId)
                .put("handle", j.handle).put("postId", j.postId ?: JSONObject.NULL).put("attempts", j.attempts)
                .put("notBefore", j.notBeforeMs).put("lastError", j.lastError ?: JSONObject.NULL))
        })
        return o.toString()
    }

    companion object {
        /** Lenient: a malformed entry is skipped, never the whole state. */
        fun fromJson(json: String?): ChannelWebState {
            val s = ChannelWebState()
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return s
            fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k, "").takeIf { it.isNotEmpty() }
            o.optJSONObject("posts")?.let { p ->
                for (k in p.keySet()) {
                    val r = p.optJSONObject(k) ?: continue
                    val h = r.str("handle") ?: continue
                    val g = r.str("groupId") ?: continue
                    val ids = r.optJSONArray("mediaIds")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
                    s.posts[k] = PostRecord(h, g, if (r.isNull("n")) null else r.optInt("n").takeIf { it > 0 }, ids, r.optBoolean("mine"))
                }
            }
            o.optJSONObject("channels")?.let { c ->
                for (k in c.keySet()) {
                    val r = c.optJSONObject(k) ?: continue
                    val g = r.str("groupId") ?: continue
                    s.channels[k] = ChannelRecord(g, r.str("profileSignature"), r.str("avatarKey"),
                        if (r.isNull("lastAdminSync")) null else r.optLong("lastAdminSync"))
                }
            }
            o.optJSONArray("jobs")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.optJSONObject(i) ?: continue
                    val kind = Kind.of(j.str("kind")) ?: continue
                    s.jobs.add(Job(
                        id = j.str("id") ?: java.util.UUID.randomUUID().toString(), kind = kind,
                        groupId = j.str("groupId") ?: continue, handle = j.str("handle") ?: continue,
                        postId = j.str("postId"), attempts = j.optInt("attempts"),
                        notBeforeMs = j.optLong("notBefore"), lastError = j.str("lastError"),
                    ))
                }
            }
            return s
        }
    }
}

// ---------------------------------------------------------------------------- outcome of one answer

enum class ChannelWebOutcome {
    DONE,
    /** Transient: back off, re-sign, try again. */
    RETRY,
    /** `400 unknown-media`: forget the media ids, upload again. */
    REUPLOAD_MEDIA,
    /** A request this build should not have sent, or a state waiting will not change. */
    DROP;

    companion object {
        /** Publishes, profiles and syncs give up after this many tries; deletes / disables never do. */
        const val MAX_ATTEMPTS = 25

        fun errorCode(body: String?): String? =
            body?.let { runCatching { JSONObject(it).opt("error") as? String }.getOrNull() }

        /** §6 table. [status] < 100 = no HTTP answer (network). */
        fun classify(status: Int, error: String?, kind: ChannelWebState.Kind): ChannelWebOutcome = when {
            status in 200..299 -> DONE
            status == 404 && (kind == ChannelWebState.Kind.DELETE || kind == ChannelWebState.Kind.DISABLE) -> DONE   // already gone
            status == 400 && error == "unknown-media" -> REUPLOAD_MEDIA
            status == 401 || status == 408 || status == 425 || status == 429 || status in 500..599 -> RETRY   // 507 included
            status < 100 -> RETRY
            else -> DROP
        }

        /** 5 s, 10 s, 20 s … capped at one hour, ±20 % jitter ([jitter] in 0..1). */
        fun backoffMs(attempt: Int, jitter: Double): Long {
            val base = minOf(3_600_000.0, 5_000.0 * Math.pow(2.0, (attempt - 1).coerceAtLeast(0).toDouble()))
            return (base * (0.8 + 0.4 * jitter.coerceIn(0.0, 1.0))).toLong()
        }

        /**
         * Reader side: a post of the public feed matched by send time (±1 ms: platforms round
         * differently), else by same text within 2 s. Pure. Each element: `n`, `createdAt`, `text`.
         */
        fun matchFeed(posts: List<JSONObject>, createdAtMs: Long, text: String?): Int? {
            fun ts(p: JSONObject): Long? = (p.opt("createdAt") as? Number)?.toLong()
            fun n(p: JSONObject): Int? = (p.opt("n") as? Number)?.toInt()
            posts.firstOrNull { p -> ts(p)?.let { kotlin.math.abs(it - createdAtMs) <= 1 } == true }?.let { n(it) }?.let { return it }
            if (text.isNullOrEmpty()) return null
            return posts.firstOrNull { p ->
                ts(p)?.let { kotlin.math.abs(it - createdAtMs) <= 2000 } == true && p.optString("text", "\u0000") == text
            }?.let { n(it) }
        }
    }
}
