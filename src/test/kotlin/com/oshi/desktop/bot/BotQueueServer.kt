package com.oshi.desktop.bot

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * `ServerVPS/api/message_queue_server.js`, in this process — the bot half of it.
 *
 * Same posture as [com.oshi.desktop.sync.SyncServer] and
 * [com.oshi.desktop.net.RelayServer]: it BEHAVES like the server rather than replaying a
 * canned answer, because the behaviours that matter to row 0.26 are exactly the ones a
 * lenient fake would paper over.
 *
 * The pin/blob routes of the same server are NOT served here — nothing in this client
 * fetches by CID (see [BotQueueClient]). A content identifier can still be SEEDED into a
 * queue, because telling one apart from a bot envelope in a shared slot is a property row
 * 0.26 depends on.
 *
 * Reproduced deliberately, each with the line it comes from:
 *
 *  - **`normalizePublicKey`** (`:56-62`): `decodeURIComponent`, then `-`→`+`, `_`→`/`, then
 *    re-pad to a multiple of 4. This is what makes the base64url path key and the raw
 *    base64 key land in the SAME slot — and what makes a percent-encoded key land in a
 *    different one. A fake that keyed on the raw path segment could not show either.
 *  - **the queue is per-recipient and de-duplicated by hash** (`:180-189`), so a re-enqueue
 *    is not a second entry.
 *  - **`/api/pending` filters by `receivedBy`** and treats a `default` ack as covering every
 *    device (`:290-293`) — the multi-device rule.
 *  - **`/api/received` is per-device and does NOT delete** (`:369-395`).
 *  - **a bot envelope occupies the hash slot** verbatim, so `/api/pending` returns CIDs and
 *    `bot:…` strings mixed in one array — the property row 0.26 depends on.
 *  - **`/api/bot/…` token checks**: 401 on an unregistered token, 403 on a group the bot is
 *    not registered in *with the `registeredGroups` list attached* (`:573-580`).
 *
 * Not reproduced: rate limiting, push fallback, persistence to `bot_registry.json`.
 */
class BotQueueServer : AutoCloseable {

    private class Entry(val hash: String, val receivedBy: MutableSet<String> = mutableSetOf())

    private val queues = ConcurrentHashMap<String, MutableList<Entry>>()

    /** token -> {botName, ownerPublicKey, groups:[{id,name,members}]}. */
    private val bots = ConcurrentHashMap<String, JSONObject>()

    /** token -> sent count. */
    private val sent = ConcurrentHashMap<String, Int>()

    /** Every RAW path this server was asked for — bytes on the wire, not a decoding. */
    val requestedPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** `X-OSHI-Caps` of every `/api/pending` request, in order (null when absent). */
    val pendingCaps: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf<String?>())

    /** Set to make the next N `/api/pending` calls answer 500. */
    var failPendingRemaining: Int = 0

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/api/") { ex -> handle(ex) }
        server.executor = null
        server.start()
    }

    override fun close() = server.stop(0)

    // ------------------------------------------------------------------ test fixtures

    /** Enqueue a bare content identifier for [publicKey] — an entry this client
     *  classifies but never resolves. */
    fun seedCid(publicKey: String, cid: String) {
        queueFor(normalize(publicKey)).let { q -> if (q.none { it.hash == cid }) q.add(Entry(cid)) }
    }

    /** Enqueue a raw string (a `bot:…` envelope, or anything else) for [publicKey]. */
    fun seedRaw(publicKey: String, raw: String) {
        queueFor(normalize(publicKey)).let { q -> if (q.none { it.hash == raw }) q.add(Entry(raw)) }
    }

    fun queueSize(publicKey: String): Int = queueFor(normalize(publicKey)).size

    /** Entries this device has NOT acked — what a poll would return. */
    fun pendingFor(publicKey: String, deviceId: String): List<String> =
        queueFor(normalize(publicKey))
            .filter { deviceId !in it.receivedBy && "default" !in it.receivedBy }
            .map { it.hash }

    fun registerBot(token: String, botName: String, owner: String, groups: JSONArray) {
        bots[token] = JSONObject()
            .put("botName", botName).put("ownerPublicKey", owner).put("groups", groups)
        sent[token] = 0
    }

    fun isRegistered(token: String): Boolean = bots.containsKey(token)

    fun registeredGroups(token: String): JSONArray? = bots[token]?.optJSONArray("groups")

    // ------------------------------------------------------------------ routing

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val query = ex.requestURI.rawQuery ?: ""
        requestedPaths.add(if (query.isEmpty()) path else "$path?$query")
        try {
            when {
                path.startsWith("/api/pending/") -> pending(ex, path, query)
                path.startsWith("/api/received/") -> received(ex, path, query)
                path.startsWith("/api/bot-received/") -> botReceived(ex, path)
                path == "/api/bot/register" -> botRegister(ex)
                path == "/api/bot/send" -> botSend(ex)
                path == "/api/bot/list" -> botList(ex, query)
                path == "/api/bot/unregister" -> botUnregister(ex, query)
                path == "/api/bot/update-groups" -> botUpdateGroups(ex)
                else -> send(ex, 404, "{\"error\":\"no route\"}")
            }
        } catch (e: Exception) {
            send(ex, 500, "{\"error\":\"${e.message}\"}")
        } finally {
            ex.close()
        }
    }

    private fun pending(ex: HttpExchange, path: String, query: String) {
        pendingCaps.add(ex.requestHeaders.getFirst("X-OSHI-Caps"))
        if (failPendingRemaining > 0) {
            failPendingRemaining--
            send(ex, 500, "{\"error\":\"induced\"}")
            return
        }
        val key = normalize(path.removePrefix("/api/pending/"))
        val deviceId = param(query, "deviceId") ?: "default"
        val out = JSONArray()
        queueFor(key).filter { deviceId !in it.receivedBy && "default" !in it.receivedBy }
            .forEach { out.put(it.hash) }
        send(ex, 200, out.toString())
    }


    private fun received(ex: HttpExchange, path: String, query: String) {
        val rest = path.removePrefix("/api/received/")
        val slash = rest.indexOf('/')
        val key = normalize(rest.substring(0, slash))
        val hash = dec(rest.substring(slash + 1))
        val deviceId = param(query, "deviceId") ?: "default"
        queueFor(key).firstOrNull { it.hash == hash }?.receivedBy?.add(deviceId)
        send(ex, 200, "{\"success\":true}")
    }

    private fun botReceived(ex: HttpExchange, path: String) {
        val key = normalize(path.removePrefix("/api/bot-received/"))
        val body = JSONObject(readBody(ex))
        val messageId = body.optString("messageId", "")
        val deviceId = body.optString("deviceId", "default")
        var marked = 0
        queueFor(key).forEach { e ->
            // The server matches a bot envelope by its MIDDLE field, which is why the ack
            // does not have to carry the whole (possibly 600 KB) string.
            if (e.hash.startsWith("bot:") && e.hash.split(":", limit = 3).getOrNull(1) == messageId) {
                e.receivedBy.add(deviceId); marked++
            }
        }
        send(ex, 200, "{\"success\":true,\"marked\":$marked}")
    }



    private fun botRegister(ex: HttpExchange) {
        val b = JSONObject(readBody(ex))
        val token = b.optString("token", "")
        val name = b.optString("botName", "")
        val owner = b.optString("ownerPublicKey", "")
        if (token.isEmpty() || name.isEmpty() || owner.isEmpty()) {
            send(ex, 400, "{\"success\":false,\"error\":\"Missing required fields: token, botName, ownerPublicKey\"}")
            return
        }
        // Unconditional set — no ownership check. See BotApi's class doc.
        registerBot(token, name, owner, b.optJSONArray("groups") ?: JSONArray())
        send(ex, 200, "{\"success\":true,\"bot\":{\"botName\":\"$name\"}}")
    }

    private fun botUpdateGroups(ex: HttpExchange) {
        val b = JSONObject(readBody(ex))
        val token = b.optString("token", "")
        val rec = bots[token]
        if (rec == null) { send(ex, 401, "{\"success\":false,\"error\":\"Invalid bot token\"}"); return }
        // FULL REPLACEMENT, not a merge (message_queue_server.js:736).
        rec.put("groups", b.optJSONArray("groups") ?: JSONArray())
        send(ex, 200, "{\"success\":true}")
    }

    private fun botSend(ex: HttpExchange) {
        val b = JSONObject(readBody(ex))
        val token = b.optString("token", "")
        val groupId = b.optString("groupId", "")
        val rec = bots[token]
        if (rec == null) { send(ex, 401, "{\"success\":false,\"error\":\"Invalid bot token.\"}"); return }
        val groups = rec.optJSONArray("groups") ?: JSONArray()
        var group: JSONObject? = null
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            if (g.optString("id").lowercase() == groupId.lowercase()) { group = g; break }
        }
        if (group == null) {
            val reg = JSONArray()
            for (i in 0 until groups.length()) {
                val g = groups.getJSONObject(i)
                reg.put(JSONObject().put("id", g.optString("id")).put("name", g.optString("name")))
            }
            send(ex, 403, JSONObject()
                .put("success", false)
                .put("error", "Bot is not assigned to this group. Assign it from the OSHI app first.")
                .put("registeredGroups", reg).toString())
            return
        }
        val messageId = java.util.UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("type", "bot_message")
            .put("messageId", messageId)
            .put("botToken", token.take(8) + "...")
            .put("botName", rec.optString("botName"))
            .put("groupId", groupId)
            .put("groupName", group.optString("name"))
            .put("content", b.optString("content", ""))
            .put("timestamp", java.time.Instant.now().toString())
        val envelope = "bot:$messageId:" +
            java.util.Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
        val members = group.optJSONArray("members") ?: JSONArray()
        var delivered = 0
        for (i in 0 until members.length()) {
            val q = queueFor(normalize(members.getString(i)))
            if (q.none { it.hash == envelope }) { q.add(Entry(envelope)); delivered++ }
        }
        sent[token] = (sent[token] ?: 0) + 1
        send(ex, 200, JSONObject()
            .put("success", true).put("delivered", delivered)
            .put("totalMembers", members.length()).put("messageId", messageId)
            .put("groupName", group.optString("name")).put("hasMedia", false).toString())
    }

    private fun botList(ex: HttpExchange, query: String) {
        val token = param(query, "token")
        if (token.isNullOrEmpty()) {
            send(ex, 401, "{\"success\":false,\"error\":\"Missing required query parameter: token. " +
                "This endpoint returns only your own bot.\"}")
            return
        }
        val rec = bots[token]
        if (rec == null) { send(ex, 401, "{\"success\":false,\"error\":\"Invalid bot token\"}"); return }
        val one = JSONObject()
            .put("botName", rec.optString("botName"))
            .put("tokenPrefix", token.take(8) + "...")
            .put("groups", (rec.optJSONArray("groups") ?: JSONArray()).length())
            .put("messagesSent", sent[token] ?: 0)
            .put("lastActivity", JSONObject.NULL)
            .put("registeredAt", "2026-08-25T00:00:00.000Z")
        send(ex, 200, JSONObject().put("success", true).put("count", 1)
            .put("bots", JSONArray().put(one)).toString())
    }

    private fun botUnregister(ex: HttpExchange, query: String) {
        val token = param(query, "token")
        if (token.isNullOrEmpty()) { send(ex, 400, "{\"error\":\"Missing token\"}"); return }
        if (bots.remove(token) == null) { send(ex, 404, "{\"error\":\"Bot not found.\"}"); return }
        sent.remove(token)
        send(ex, 200, "{\"success\":true,\"message\":\"unregistered\"}")
    }

    // ------------------------------------------------------------------ helpers

    private fun queueFor(key: String): MutableList<Entry> =
        queues.computeIfAbsent(key) { java.util.Collections.synchronizedList(mutableListOf()) }

    /**
     * `normalizePublicKey` (`message_queue_server.js:56-62`), byte for byte.
     *
     * The re-padding at the end is the part with a sharp edge, and it bit this fake's own
     * test data: the function pads to a multiple of 4 *unconditionally*, so a key whose
     * length is not ≡ 0 (mod 4) once its padding is stripped normalises to a DIFFERENT
     * string depending on whether it arrived raw or base64url-spelled. The raw form keeps
     * its own length and gains nothing; the stripped form gains up to three `=`. Real
     * base64 public keys are always 44 characters and never hit it — but it means the
     * server's normaliser is only an identity over *valid base64*, and anything else lands
     * in two slots with no error, which is the same silent-two-slots failure PARITY.md
     * row 0.24 records for `/api/sync`.
     */
    private fun normalize(raw: String): String {
        var k = try { dec(raw) } catch (e: Exception) { raw }
        k = k.replace("-", "+").replace("_", "/")
        while (k.length % 4 != 0) k += "="
        return k
    }

    /**
     * Percent-decode, **leaving `+` alone**.
     *
     * The server calls `decodeURIComponent`, which decodes `%XX` and does NOT treat `+` as
     * a space. Java's `URLDecoder` is an `application/x-www-form-urlencoded` decoder and
     * does — so a naive `URLDecoder.decode` turns the `+` in a base64 public key into a
     * space and every subsequent lookup misses. That is not a hypothetical: it is what this
     * fake did on its first run, and six queue tests reported an empty queue rather than a
     * mismatch, which is exactly the silent-wrong-slot failure mode PARITY.md row 0.24
     * describes for the real routes.
     */
    private fun dec(s: String): String =
        java.net.URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

    private fun param(query: String, name: String): String? =
        query.split("&").firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.let { dec(it) }

    private fun readBody(ex: HttpExchange): String {
        val b = ex.requestBody.readBytes()
        return if (b.isEmpty()) "{}" else String(b, Charsets.UTF_8)
    }

    private fun send(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
