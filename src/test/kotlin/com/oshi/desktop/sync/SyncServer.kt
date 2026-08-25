package com.oshi.desktop.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * A working sync store, in this process — the same posture as
 * [com.oshi.desktop.net.RelayServer]: it BEHAVES like the server rather than replaying a
 * canned answer, so the drain loop can be driven for real.
 *
 * Reproduces the four behaviours of `sync_store.js` that [SyncEngine] depends on, all four
 * stated in `V2SyncModels.swift` / `V2Client+Sync.swift`, because a lenient fake hides the
 * bugs worth finding:
 *
 *  - **`seq` is monotonic and server-assigned** (`V2SyncModels.swift:63-65`). The drain
 *    pages by it, so a fake that echoed the client's ordering would make the paging
 *    untestable.
 *  - **push is IDEMPOTENT on `itemId`**: a re-push returns the EXISTING seq and stores
 *    nothing new (`:37-42`). A fake that appended twice would hide the whole point of the
 *    idempotency key.
 *  - **pull is NON-DESTRUCTIVE** and returns everything with `seq > after`, capped by
 *    `limit` (`:90-93`). A fake that drained on read would make the "short page ends the
 *    drain" property untestable.
 *  - **every route is OWNER-ONLY**: the signing pubkey must equal the path key
 *    (`V2Client+Sync.swift:23-26`). Enforced here as a 401 so a test can watch a foreign
 *    request be refused by the SERVER as well as by the client.
 *
 * Also serves the five legacy `/api/sync/{kind}/{key}` blob routes, unauthenticated and
 * whole-blob-overwrite, exactly as `MultiDeviceSyncManager` uses them — including the
 * `{"success":true,"data":null}` empty answer (`.kt:1215-1218`).
 */
class SyncServer : AutoCloseable {

    private class Item(val seq: Int, val itemId: String, val kind: String,
                       val ciphertext: String, val deviceId: String, val ts: Long)

    /** userKey -> log. Insertion-ordered; seq is the index+1 of a NEW item. */
    private val logs = ConcurrentHashMap<String, MutableList<Item>>()

    /** "kind/key" -> the one blob for that slot. */
    private val blobs = ConcurrentHashMap<String, String>()

    /** Every RAW path this server was asked for — the bytes on the wire, not a decoding. */
    val requestedPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** Set to a non-null value to make the next N pull calls answer 500. */
    var failPullsRemaining: Int = 0

    /**
     * When true, every pull ignores `after` and returns the head of the log — a server
     * that does not honour `seq > after`.
     *
     * Not a hypothetical: a rolled-back or restored store, a proxy serving a cached page,
     * or a hostile relay all produce it, and the shipped `V2SyncManager.pull()` loop has no
     * exit for it (`swift:217-250`: only an empty page and a short page break). It is here
     * so [SyncEngine]'s property 5 can be watched holding rather than assumed.
     */
    var ignoreAfterOnPull: Boolean = false

    /** How many pull calls this server has served, so a test can bound a loop. */
    @Volatile
    var pullCount: Int = 0

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/v2/sync") { ex -> handle(ex) { v2(ex) } }
        server.createContext("/api/sync") { ex -> handle(ex) { legacy(ex) } }
        server.executor = null
        server.start()
    }

    override fun close() = server.stop(0)

    /** Seed the log directly, bypassing push — for corrupt-item and ordering fixtures. */
    fun seed(userKey: String, itemId: String, kind: String, ciphertext: String,
             deviceId: String = "seed-device", ts: Long = 1_700_000_000_000L): Int {
        val log = logs.getOrPut(userKey) { mutableListOf() }
        synchronized(log) {
            val seq = (log.lastOrNull()?.seq ?: 0) + 1
            log.add(Item(seq, itemId, kind, ciphertext, deviceId, ts))
            return seq
        }
    }

    fun logSize(userKey: String): Int = logs[userKey]?.size ?: 0

    fun legacyBlob(kind: String, pathKey: String): String? = blobs["$kind/$pathKey"]

    fun putLegacyBlob(kind: String, pathKey: String, ciphertextB64: String) {
        blobs["$kind/$pathKey"] = ciphertextB64
    }

    // ------------------------------------------------------------------ routing

    private fun handle(ex: HttpExchange, body: () -> Unit) {
        requestedPaths.add(ex.requestURI.rawPath)
        try { body() } catch (e: Exception) { respond(ex, 500, """{"error":"${e.message}"}""") }
        finally { ex.close() }
    }

    private fun v2(ex: HttpExchange) {
        // /v2/sync/<encodedKey>[/head|/checkpoint]
        //
        // RAW path, split first, percent-decode each segment after — the order
        // `sync_store.js` uses (Express routes on the raw path, then
        // `decodeURIComponent`s the segment). Decoding first would turn the `%2F` in a
        // base64 identity into a real slash and split the key in half; and `URLDecoder`
        // is the wrong decoder entirely here, because it turns `+` into a space and every
        // second base64 key contains a `+`.
        val rest = ex.requestURI.rawPath.removePrefix("/v2/sync/")
        val segments = rest.split('/')
        val pathKey = percentDecode(segments[0])
        val tail = segments.getOrNull(1).orEmpty()

        // Owner check: x-oshi-signing-pubkey is Ed25519 and is NOT the user key, so the
        // real server compares the VERIFIED identity; here the client's x-oshi-user header
        // is the X25519 identity and is what the path key must equal.
        val user = ex.requestHeaders.getFirst("x-oshi-user")
        if (user == null || user != pathKey) {
            respond(ex, 401, """{"error":"owner mismatch"}""")
            return
        }

        val log = logs.getOrPut(pathKey) { mutableListOf() }

        when {
            tail == "head" && ex.requestMethod == "GET" -> {
                val max = synchronized(log) { log.lastOrNull()?.seq ?: 0 }
                respond(ex, 200, JSONObject().put("maxSeq", max).put("count", log.size).toString())
            }

            tail == "checkpoint" && ex.requestMethod == "POST" -> {
                val upTo = JSONObject(read(ex)).optInt("upToSeq", 0)
                val trimmed = synchronized(log) {
                    val before = log.size
                    log.removeAll { it.seq <= upTo }
                    before - log.size
                }
                respond(ex, 200, JSONObject().put("trimmed", trimmed).put("remaining", log.size).toString())
            }

            tail.isEmpty() && ex.requestMethod == "POST" -> {
                val items = JSONObject(read(ex)).optJSONArray("items") ?: JSONArray()
                val accepted = JSONArray()
                synchronized(log) {
                    for (i in 0 until items.length()) {
                        val o = items.getJSONObject(i)
                        val id = o.getString("itemId")
                        val existing = log.firstOrNull { it.itemId == id }
                        val seq = if (existing != null) existing.seq else {
                            val s = (log.lastOrNull()?.seq ?: 0) + 1
                            log.add(Item(s, id, o.getString("kind"), o.getString("ciphertext"),
                                o.optString("deviceId", ""), o.optLong("ts", 0L)))
                            s
                        }
                        accepted.put(JSONObject().put("itemId", id).put("seq", seq))
                    }
                    respond(ex, 200, JSONObject().put("accepted", accepted)
                        .put("maxSeq", log.lastOrNull()?.seq ?: 0).toString())
                }
            }

            tail.isEmpty() && ex.requestMethod == "GET" -> {
                if (failPullsRemaining > 0) {
                    failPullsRemaining--
                    respond(ex, 500, """{"error":"synthetic"}""")
                    return
                }
                pullCount++
                val q = parseQuery(ex.requestURI.rawQuery)
                val after = if (ignoreAfterOnPull) 0 else q["after"]?.toIntOrNull() ?: 0
                val limit = q["limit"]?.toIntOrNull() ?: Int.MAX_VALUE
                val arr = JSONArray()
                var max = 0
                synchronized(log) {
                    max = log.lastOrNull()?.seq ?: 0
                    log.filter { it.seq > after }.take(limit).forEach {
                        arr.put(JSONObject()
                            .put("seq", it.seq).put("itemId", it.itemId).put("kind", it.kind)
                            .put("ciphertext", it.ciphertext).put("deviceId", it.deviceId)
                            .put("ts", it.ts))
                    }
                }
                respond(ex, 200, JSONObject().put("items", arr).put("maxSeq", max).toString())
            }

            else -> respond(ex, 404, """{"error":"no route"}""")
        }
    }

    /** `decodeURIComponent`, not `URLDecoder`: `+` is a literal plus in a path segment. */
    private fun percentDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
            } else {
                out.write(c.code); i++
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun legacy(ex: HttpExchange) {
        // /api/sync/<kind>/<base64url key>  — NO auth of any kind, by design.
        // The legacy key is base64url with no padding, so it contains nothing that needs
        // percent-encoding and the decoded path is safe to split.
        val rest = ex.requestURI.path.removePrefix("/api/sync/").split('/')
        if (rest.size < 2) { respond(ex, 404, "{}"); return }
        val slot = "${rest[0]}/${rest[1]}"
        when (ex.requestMethod) {
            "POST" -> {
                val o = JSONObject(read(ex))
                blobs[slot] = o.getString("data")
                respond(ex, 200, """{"success":true}""")
            }
            "GET" -> {
                val data = blobs[slot]
                val body = JSONObject().put("success", true)
                if (data == null) body.put("data", JSONObject.NULL) else body.put("data", data)
                respond(ex, 200, body.toString())
            }
            else -> respond(ex, 405, "{}")
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    private fun read(ex: HttpExchange): String = ex.requestBody.readBytes().toString(Charsets.UTF_8)

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
