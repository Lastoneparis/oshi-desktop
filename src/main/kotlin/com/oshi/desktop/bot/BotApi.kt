package com.oshi.desktop.bot

import com.oshi.desktop.net.V2Http
import com.oshi.messenger.network.v2.OSHICryptoV2
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `/api/bot/…` control surface — PARITY.md row 0.26's "server-side API already exists".
 *
 * (The path is written with an ellipsis rather than a `*` wildcard throughout this file on
 * purpose: Kotlin block comments NEST, so the two characters `/` and `*` adjacent inside a
 * KDoc open a nested comment and swallow the rest of the file.)
 *
 * All seven routes live in `ServerVPS/api/message_queue_server.js`, on the same
 * unauthenticated legacy server as the pending queue
 * ([BotQueueClient]) and NOT on the V2 relay: a grep of
 * `ServerVPS/v2/relay_v2.js` and `v2_server.js` for "bot" returns one hit, and it is the
 * word "omit" inside a comment. V2 knows nothing about bots.
 *
 * ============================================================ THE ROUTES
 *
 * | verb + path | credential | body / query |
 * |---|---|---|
 * | `POST /api/bot/register` | **none** | `{token, botName, ownerPublicKey, groups:[{id,name,members:[key]}]}` (`:474-507`) |
 * | `POST /api/bot/send` | token in body | `{token, groupId, content?, mediaType?, mediaData?, mediaFileName?}` (`:513-670`) |
 * | `GET /api/bot/info?token=` | token in query | — (`:675-713`) |
 * | `POST /api/bot/update-groups` | token in body | `{token, groups:[…]}` — **full replacement** (`:718-748`) |
 * | `POST /api/bot/add-member` | token in body | `{token, groupId, memberPublicKey}` (`:771-815`) |
 * | `DELETE /api/bot/unregister?token=` | token in query | — (`:820-850`) |
 * | `GET /api/bot/list?token=` | token in query | — (`:867-900`) |
 *
 * Two more that Android calls and **the server does not implement** — `POST /api/bot/pin`
 * (`BotManager.kt:399`) and `POST /api/bot/manage-member` (`:429`). A whole-tree grep of
 * ServerVPS finds neither. They are not implemented here; a client method whose only
 * possible outcome is a 404 is worse than no method.
 *
 * ============================================================ THE TOKEN IS THE WHOLE
 * SECURITY MODEL, AND IT IS BEARER-ONLY
 *
 * 32 lowercase hex characters, derived on the phones from 32 random bytes through SHA-256
 * (iOS takes the first 16 BYTES hexed, `BotManager.swift:1083-1087`; Android takes the
 * first 32 hex CHARS, `BotManager.kt:654-659` — same length, different mapping, and it does
 * not matter because the token is opaque). It is a **bearer credential in a request body or
 * a query string**, with no signature and no binding to the identity that created it.
 *
 * Consequences, each read off the route:
 *
 *  - `register` checks nothing and does `botRegistry.set(token, record)` unconditionally
 *    (`:497`). Anyone who learns a token can **overwrite that bot's registration**,
 *    including its group roster. Anyone can register a token they invent, with a roster of
 *    arbitrary public keys, and then `send` into those accounts' pending queues under a bot
 *    name of their choosing.
 *  - `register` uploads the group NAME and every MEMBER PUBLIC KEY in cleartext (`:484-491`),
 *    handing the relay a membership graph the V2 group design deliberately withholds from it
 *    (row 0.17: "relay knows nothing about membership").
 *  - `list` used to require nothing and returned every bot on the platform; the fix is
 *    dated 2026-08-04 in a comment that measures the leak at **43 bots** with names, token
 *    prefixes, message counts and activity timestamps (`:854-866`). It now answers only for
 *    the caller's own token.
 *  - `info` echoes the owner's key truncated to 12 characters + `"..."` (`:697`), which is
 *    not a privacy control — it is a public key.
 *  - Rate limit: 60 messages per minute per token, fixed window (`:74-75,118-130`). Media
 *    cap: 10 MB **after base64 decode** (`:545-552`).
 *
 * A desktop client that stores a token stores something that can post as that bot to every
 * member of every group it is registered in. [BotApi] therefore never takes a token from
 * anywhere but its caller and never persists one; where it goes is a decision for whatever
 * owns the key vault (row 0.5), not for a network client.
 *
 * ============================================================ WHAT THIS DOES NOT DO
 *
 * **It does not send.** [send] is written, tested and callable, and nothing in this package
 * calls it: `/api/bot/send` puts the
 * message body in cleartext on the relay and in a push notification
 * (`:630-660`). A desktop feature that posts user text through it would be a plaintext
 * channel inside an end-to-end encrypted messenger, and any UI offering it has to say so.
 * That is a product decision, not a transport one — the same shape as PARITY.md row 0.16's
 * three options.
 *
 * **There is no discovery.** The row's "how is a bot listed" has an answer and the answer is
 * "it is not". `list` returns exactly one bot — yours — and there is no directory endpoint,
 * no manifest and no search. A client knows only the bots it created locally: iOS from the
 * Keychain item `"oshi_bots_v1"` (`BotManager.swift:287`), Android from a Room table
 * (`BotManager.kt:84`). The nearest thing to a catalogue is a hardcoded 6-entry template
 * array (`BotManager.swift:1135-1230`), reproduced in [BotTemplates].
 */
class BotApi(
    private val baseUrl: String = V2Http.defaultBaseUrl(),
    connectTimeout: Duration = Duration.ofSeconds(15),
    private val readTimeout: Duration = Duration.ofSeconds(30),
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** One group as `register` / `update-groups` carry it (`message_queue_server.js:487`). */
    data class BotGroup(val id: String, val name: String, val members: List<String>)

    /** What `GET /api/bot/list` says about the caller's own bot (`:888-895`). */
    data class BotListEntry(
        val botName: String,
        val tokenPrefix: String,
        val groups: Int,
        val messagesSent: Int,
        val lastActivity: String?,
        val registeredAt: String?,
    )

    /** What `POST /api/bot/send` returns on success (`:672-679` region). */
    data class SendResult(
        val delivered: Int,
        val totalMembers: Int,
        val messageId: String,
        val groupName: String,
        val hasMedia: Boolean,
    )

    /**
     * A non-2xx from any bot route, carrying the status AND the server's own `error` string.
     *
     * Both are needed and neither alone is enough. The status separates the four ways this
     * API says no — `400` malformed, `401` unknown token, `403` bot not registered in that
     * group, `429` rate limited — and the body is where the server puts the only
     * actionable part: a `403` also returns `registeredGroups: [{id,name}]`, i.e. the list
     * of groups the bot IS in (`:575-580`). Discarding the body would turn "you sent to the
     * wrong group, here are the right ones" into a bare 403.
     */
    class BotApiException(val status: Int, val serverError: String, val body: String) :
        RuntimeException("bot API HTTP $status: $serverError")

    /**
     * `POST /api/bot/register`.
     *
     * Registration is what makes a token usable by [send]; on both phones only a
     * `webhook`-type bot is ever registered (`BotManager.swift:872-874`,
     * `BotManager.kt:572`), so a "local" bot has no server presence at all.
     *
     * **Partial rosters are refused here, not sent.** iOS aborts a sync when the groups it
     * resolved do not number the same as the groups the bot is assigned to
     * (`BotManager.swift:916-920`, the `__BOT_PARTIAL_SYNC_2026_08_22__` incident — the
     * comment records **6 300 HTTP 403s** caused by uploading a short roster, because
     * `update-groups` and `register` both REPLACE the list wholesale (`:736`) rather than
     * merging it). This method cannot see the caller's intent, so the guard it can enforce
     * is the one that is checkable: a group with an empty id or an empty member list is
     * rejected, since either would register a group nothing can ever be delivered to and
     * would silently displace a good entry on the next full replacement.
     */
    fun register(
        token: String,
        botName: String,
        ownerPublicKey: String,
        groups: List<BotGroup>,
    ): Result<Unit> {
        requireToken(token)
        require(botName.isNotEmpty()) { "botName is required (message_queue_server.js:477)" }
        require(ownerPublicKey.isNotEmpty()) { "ownerPublicKey is required (:477)" }
        checkGroups(groups)
        val body = buildString {
            append("{\"token\":\"").append(esc(token)).append('"')
            append(",\"botName\":\"").append(esc(botName)).append('"')
            append(",\"ownerPublicKey\":\"").append(esc(ownerPublicKey)).append('"')
            append(",\"groups\":").append(encodeGroups(groups)).append('}')
        }
        return postJson("/api/bot/register", body).map { }
    }

    /**
     * `POST /api/bot/update-groups` — a **full replacement** of the roster (`:736`).
     *
     * Named `replaceGroups`, not `updateGroups`, because "update" is what the endpoint is
     * called and "replace" is what it does, and the gap between the two is the 6 300-403
     * incident above.
     */
    fun replaceGroups(token: String, groups: List<BotGroup>): Result<Unit> {
        requireToken(token)
        checkGroups(groups)
        val body = "{\"token\":\"${esc(token)}\",\"groups\":${encodeGroups(groups)}}"
        return postJson("/api/bot/update-groups", body).map { }
    }

    /** `POST /api/bot/add-member` — additive, unlike [replaceGroups] (`:771-815`). */
    fun addMember(token: String, groupId: String, memberPublicKey: String): Result<Unit> {
        requireToken(token)
        require(groupId.isNotEmpty()) { "groupId is required" }
        require(memberPublicKey.isNotEmpty()) { "memberPublicKey is required" }
        val body = "{\"token\":\"${esc(token)}\",\"groupId\":\"${esc(groupId)}\"," +
            "\"memberPublicKey\":\"${esc(memberPublicKey)}\"}"
        return postJson("/api/bot/add-member", body).map { }
    }

    /**
     * `POST /api/bot/send` — **plaintext to the relay**. See the class doc; nothing in this
     * package calls it.
     *
     * The server's own validation, reproduced client-side so a caller gets an argument
     * error instead of a 400 round trip (`:519-556`):
     *   - `token` and `groupId` required;
     *   - at least one of `content` / `mediaData`;
     *   - when `mediaData` is present, `mediaType` must be one of
     *     [BotEnvelope.MEDIA_TYPES] and `mediaFileName` is required. Note `image` is NOT
     *     accepted here even though [BotEnvelope.normaliseMediaType] accepts it on RECEIVE
     *     — the server's allow-list has four entries and sending `image` is a guaranteed
     *     400. Lenient inbound, strict outbound, which is the right way round.
     *   - decoded media must be ≤ 10 MB.
     */
    fun send(
        token: String,
        groupId: String,
        content: String? = null,
        mediaType: String? = null,
        mediaDataBase64: String? = null,
        mediaFileName: String? = null,
    ): Result<SendResult> {
        requireToken(token)
        require(groupId.isNotEmpty()) { "groupId is required (message_queue_server.js:519)" }
        require(!content.isNullOrEmpty() || !mediaDataBase64.isNullOrEmpty()) {
            "provide content and/or mediaData (message_queue_server.js:526)"
        }
        if (!mediaDataBase64.isNullOrEmpty()) {
            require(mediaType in BotEnvelope.MEDIA_TYPES) {
                "mediaType must be one of ${BotEnvelope.MEDIA_TYPES} when sending media; " +
                    "\"image\" is accepted on RECEIVE only (message_queue_server.js:536-542)"
            }
            require(!mediaFileName.isNullOrEmpty()) {
                "mediaFileName is required when sending media (message_queue_server.js:544-549)"
            }
            val rawSize = decodedLengthOfBase64(mediaDataBase64)
            require(rawSize <= MAX_MEDIA_BYTES) {
                "media is $rawSize bytes decoded, over the ${MAX_MEDIA_BYTES}-byte cap " +
                    "(message_queue_server.js:545-552)"
            }
        }
        val body = buildString {
            append("{\"token\":\"").append(esc(token)).append('"')
            append(",\"groupId\":\"").append(esc(groupId)).append('"')
            if (content != null) append(",\"content\":\"").append(esc(content)).append('"')
            if (mediaType != null) append(",\"mediaType\":\"").append(esc(mediaType)).append('"')
            if (mediaDataBase64 != null) append(",\"mediaData\":\"").append(esc(mediaDataBase64)).append('"')
            if (mediaFileName != null) append(",\"mediaFileName\":\"").append(esc(mediaFileName)).append('"')
            append('}')
        }
        return postJson("/api/bot/send", body).mapCatching { json ->
            SendResult(
                delivered = json.optInt("delivered", 0),
                totalMembers = json.optInt("totalMembers", 0),
                messageId = json.optString("messageId", ""),
                groupName = json.optString("groupName", ""),
                hasMedia = json.optBoolean("hasMedia", false),
            )
        }
    }

    /**
     * `GET /api/bot/list?token=` — which returns **your own bot and nothing else**.
     *
     * A list of one. Kept as a list because that is the shape on the wire (`{count, bots:[…]}`)
     * and flattening it here would hide that the endpoint used to be a platform directory
     * and no longer is. See the class doc for the 43-bot leak this replaced.
     */
    fun list(token: String): Result<List<BotListEntry>> {
        requireToken(token)
        return getJson("/api/bot/list?token=${enc(token)}").mapCatching { json ->
            val arr = json.optJSONArray("bots") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BotListEntry(
                    botName = o.optString("botName", ""),
                    tokenPrefix = o.optString("tokenPrefix", ""),
                    groups = o.optInt("groups", 0),
                    messagesSent = o.optInt("messagesSent", 0),
                    lastActivity = o.optString("lastActivity", "").ifEmpty { null },
                    registeredAt = o.optString("registeredAt", "").ifEmpty { null },
                )
            }
        }
    }

    /** `DELETE /api/bot/unregister?token=`. A 404 means the token was already gone. */
    fun unregister(token: String): Result<Unit> {
        requireToken(token)
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/bot/unregister?token=${enc(token)}"))
            .timeout(readTimeout).DELETE().build()
        return exchange(req).map { }
    }

    private fun postJson(path: String, body: String): Result<JSONObject> {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(readTimeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray(Charsets.UTF_8)))
            .build()
        return exchange(req)
    }

    private fun getJson(pathAndQuery: String): Result<JSONObject> {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$pathAndQuery"))
            .timeout(readTimeout).GET().build()
        return exchange(req)
    }

    private fun exchange(req: HttpRequest): Result<JSONObject> = try {
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val text = resp.body() ?: ""
        if (resp.statusCode() in 200..299) {
            Result.success(runCatching { JSONObject(text) }.getOrElse { JSONObject() })
        } else {
            val err = runCatching { JSONObject(text).optString("error", "") }.getOrElse { "" }
            Result.failure(BotApiException(resp.statusCode(), err, text))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * `groups` as a JSON array, hand-built in the server's field order (`id, name, members`)
     * so a captured phone payload diffs cleanly — the argument
     * [com.oshi.desktop.sync.SyncProtocol.encodePushBody] makes, for the same reason.
     */
    private fun encodeGroups(groups: List<BotGroup>): String = buildString {
        append('[')
        groups.forEachIndexed { i, g ->
            if (i > 0) append(',')
            append("{\"id\":\"").append(esc(g.id)).append('"')
            append(",\"name\":\"").append(esc(g.name)).append('"')
            append(",\"members\":[")
            g.members.forEachIndexed { j, m ->
                if (j > 0) append(',')
                append('"').append(esc(m)).append('"')
            }
            append("]}")
        }
        append(']')
    }

    private fun checkGroups(groups: List<BotGroup>) {
        groups.forEach { g ->
            require(g.id.isNotEmpty()) {
                "a bot group with an empty id can never match a send (the server compares " +
                    "groupId case-insensitively at message_queue_server.js:573) and would " +
                    "displace a good entry on the next full replacement"
            }
            require(g.members.isNotEmpty()) {
                "a bot group with no members delivers to nobody — `delivered` would be 0 " +
                    "with a 200 (message_queue_server.js:599-619), which reads as success"
            }
        }
    }

    private fun requireToken(token: String) {
        require(token.isNotEmpty()) { "a bot token is required — it is the only credential this API has" }
    }

    /**
     * Bytes a base64 string decodes to, without decoding it.
     *
     * `Buffer.byteLength(mediaData, 'base64')` is what the server measures against its cap
     * (`:545`), and it counts the DECODED length. Computing it arithmetically rather than
     * decoding means a 13 MB attachment is refused without first materialising 10 MB of it
     * — which is the point of a cap.
     *
     * Padding and line breaks are stripped before the arithmetic, so this agrees with Node
     * on unpadded input too: Node's base64 byteLength drops `=` and any character outside
     * the alphabet, then returns `floor(n * 3 / 4)`.
     */
    internal fun decodedLengthOfBase64(b64: String): Long {
        val clean = b64.count { it != '=' && it != '\n' && it != '\r' && it != ' ' }
        return clean.toLong() * 3L / 4L
    }

    private fun esc(s: String): String = OSHICryptoV2.jsonEscape(s)

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    companion object {
        /** 10 MB of DECODED media (`message_queue_server.js:545-552`). */
        const val MAX_MEDIA_BYTES: Long = 10L * 1024L * 1024L

        /** 60 sends per minute per token, fixed window (`message_queue_server.js:74-75`). */
        const val RATE_LIMIT_PER_MINUTE: Int = 60
    }
}
