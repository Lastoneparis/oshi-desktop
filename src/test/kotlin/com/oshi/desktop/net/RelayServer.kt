package com.oshi.desktop.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * A working OSHI relay, in this process: a real mailbox and a real prekey server.
 *
 * [FakeRelay] records requests and answers whatever a test tells it to — right for
 * checking how a request is SHAPED. This one is the other half: it BEHAVES like the
 * server, so a test can drive two independent routers through a whole conversation —
 * publish, fetch, X3DH, send, pull, ack — and assert on what the second one reads out at
 * the far end. That is the only way to catch the failures that live between two clients
 * rather than inside one: a first message that establishes but cannot be opened, a
 * message acked before it was delivered, a one-time prekey handed to two people.
 *
 * It reproduces the two server behaviours the protocol depends on, because a lenient
 * fake would hide exactly the bugs worth finding:
 *
 *  - **`GET /v2/keys/:peer` POPS a one-time prekey.** Fetch twice, and the second caller
 *    gets a different one; fetch enough times and the pool drains and the bundle comes
 *    back without one. A client that fetches speculatively burns the peer's pool, and
 *    only a popping server shows it.
 *  - **`pull` is non-destructive and `ack` is what deletes.** Everything after a sequence
 *    number comes back on every pull until an ack passes it. A mailbox that deleted on
 *    read would make "never ack what you could not decrypt" untestable.
 */
class RelayServer : AutoCloseable {

    private class Bundle(
        val identityKey: String,
        val signingKey: String,
        val spkId: String,
        val spkKey: String,
        val spkSig: String,
        val opks: ArrayDeque<Pair<String, String>>,
    )

    private class Stored(val seq: Long, val envelope: JSONObject)

    private val bundles = ConcurrentHashMap<String, Bundle>()
    private val mailboxes = ConcurrentHashMap<String, MutableList<Stored>>()
    private val seqCounter = ConcurrentHashMap<String, AtomicLong>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** What `GET /v2/config` answers. Defaults to fully enabled. */
    var configJson: String = """{"v2_enabled":true,"rollout_percent":100,"min_build":0}"""

    /** Count of bundle fetches per peer — the one-time-prekey economics, observable. */
    val bundleFetches = ConcurrentHashMap<String, Int>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.executor = null
        server.start()
    }

    override fun close() = server.stop(0)

    fun mailboxSize(userKey: String): Int = mailboxes[userKey]?.size ?: 0
    fun remainingOpks(userKey: String): Int = bundles[userKey]?.opks?.size ?: 0
    fun hasBundle(userKey: String): Boolean = bundles.containsKey(userKey)

    /** Drop a message into a mailbox directly — for forging what a client would never send. */
    fun inject(toUserKey: String, envelope: JSONObject) {
        val seq = seqCounter.getOrPut(toUserKey) { AtomicLong(0) }.incrementAndGet()
        mailboxes.getOrPut(toUserKey) { mutableListOf() }.add(Stored(seq, envelope))
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        val path = ex.requestURI.rawPath
        val query = ex.requestURI.rawQuery
        val (code, response) = try {
            route(ex.requestMethod, path, query, body)
        } catch (e: Exception) {
            500 to """{"error":"${e.javaClass.simpleName}: ${e.message}"}"""
        }
        val bytes = response.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun route(method: String, path: String, query: String?, body: ByteArray): Pair<Int, String> {
        val segments = path.trim('/').split('/').map { URLDecoder.decode(it, "UTF-8") }
        return when {
            method == "GET" && path == "/v2/config" -> 200 to configJson

            method == "POST" && path == "/v2/keys/publish" -> publish(JSONObject(String(body)))

            method == "GET" && segments.size == 4 && segments[1] == "keys" && segments[3] == "count" ->
                200 to JSONObject().put("remaining", bundles[segments[2]]?.opks?.size ?: 0).toString()

            method == "GET" && segments.size == 3 && segments[1] == "keys" -> fetchBundle(segments[2])

            method == "POST" && path == "/v2/messages" -> send(JSONObject(String(body)))

            method == "POST" && segments.size == 4 && segments[1] == "messages" && segments[3] == "ack" ->
                ack(segments[2], JSONObject(String(body)).optLong("upToSeq", -1))

            method == "GET" && segments.size == 3 && segments[1] == "messages" ->
                pull(segments[2], (query ?: "").substringAfter("after=", "0").toLongOrNull() ?: 0)

            method == "DELETE" && path == "/v2/account" -> 200 to """{"complete":true,"erased":{"prekeys":true}}"""

            else -> 404 to """{"error":"no route for $method $path"}"""
        }
    }

    private fun publish(body: JSONObject): Pair<Int, String> {
        val userKey = body.getString("userKey")
        val spk = body.getJSONObject("signedPreKey")
        val incoming = body.optJSONArray("oneTimePreKeys") ?: JSONArray()

        // APPENDS one-time prekeys, REPLACES the long-lived material — the real server's
        // behaviour, and the reason a client that republishes in full grows the pool.
        val existing = bundles[userKey]
        val pool = existing?.opks ?: ArrayDeque()
        for (i in 0 until incoming.length()) {
            val o = incoming.getJSONObject(i)
            pool.addLast(o.getString("keyId") to o.getString("key"))
        }
        bundles[userKey] = Bundle(
            identityKey = body.getString("identityKey"),
            signingKey = body.getString("signingKey"),
            spkId = spk.getString("keyId"),
            spkKey = spk.getString("key"),
            spkSig = spk.getString("signature"),
            opks = pool,
        )
        return 200 to JSONObject().put("remaining", pool.size).toString()
    }

    private fun fetchBundle(userKey: String): Pair<Int, String> {
        val b = bundles[userKey] ?: return 404 to """{"error":"no bundle"}"""
        bundleFetches.merge(userKey, 1, Int::plus)
        val json = JSONObject()
            .put("identityKey", b.identityKey)
            .put("signingKey", b.signingKey)
            .put("signedPreKey", JSONObject()
                .put("keyId", b.spkId).put("key", b.spkKey).put("signature", b.spkSig))
        // POP one — oldest first, single use, gone for everyone else.
        b.opks.removeFirstOrNull()?.let { (id, key) ->
            json.put("oneTimePreKey", JSONObject().put("keyId", id).put("key", key))
        }
        return 200 to json.toString()
    }

    private fun send(envelope: JSONObject): Pair<Int, String> {
        val to = envelope.getString("to")
        val seq = seqCounter.getOrPut(to) { AtomicLong(0) }.incrementAndGet()
        mailboxes.getOrPut(to) { mutableListOf() }.add(Stored(seq, envelope))
        return 200 to JSONObject().put("accepted", JSONArray().put(envelope.getString("msgId"))).toString()
    }

    private fun pull(userKey: String, after: Long): Pair<Int, String> {
        val box = mailboxes[userKey] ?: mutableListOf()
        val due = box.filter { it.seq > after }
        val arr = JSONArray()
        for (m in due) arr.put(JSONObject(m.envelope.toString()).put("seq", m.seq))
        val maxSeq = due.maxOfOrNull { it.seq } ?: after
        return 200 to JSONObject().put("messages", arr).put("maxSeq", maxSeq).toString()
    }

    private fun ack(userKey: String, upTo: Long): Pair<Int, String> {
        mailboxes[userKey]?.removeAll { it.seq <= upTo }
        return 200 to "{}"
    }
}
