package com.oshi.desktop.call

import com.oshi.desktop.DesktopIdentity
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/**
 * A working OSHI CALL server, in this process — the same idea as
 * [com.oshi.desktop.net.RelayServer], for the other server.
 *
 * The call server is NOT the V2 relay. It is a separate Express app on port 8083 with its
 * own routes, its own key spelling, its own queue semantics and its own signing rule, and
 * the only way to know a desktop client can ring a phone is to drive two independent clients
 * through a server that behaves the way `ServerVPS/call_server.js` behaves.
 *
 * Four behaviours are reproduced because a lenient fake would hide exactly the bugs worth
 * finding, and each of them has a line number:
 *
 *  1. **nginx strips the prefix.** `location /api/call/ { proxy_pass http://127.0.0.1:8083/; }`
 *     — the trailing slash on the target replaces the matched prefix with `/`, so the
 *     Express app sees `/signal`, never `/api/call/signal`. This server does the same, and
 *     it is what makes the signing question in [CallSignalClient] a testable one rather than
 *     a paragraph.
 *  2. **`normalizeKey` folds every key to base64url**, `+`→`-`, `/`→`_`, `=` dropped
 *     (`call_server.js:56-59`). Its maps are keyed on nothing else, so a client that posts a
 *     standard-base64 recipient and polls a base64url one still finds its own queue — and a
 *     client that got the fold backwards would find an empty one.
 *  3. **Delivery is non-destructive and PER DEVICE** (`:846-921`). A signal is never
 *     re-delivered to a `deviceId` that has seen it, and stays readable by other devices for
 *     `POLL_GRACE_MS`. Without a `deviceId` the legacy branch consumes the queue.
 *  4. **The two staleness filters differ on purpose** (`:855-880`): an OFFER older than 30 s
 *     is never delivered (the caller has already given up), a TERMINAL older than 25 s is
 *     not either, and everything else rides the 60 s sweeper.
 *
 * ============================================================ ONE DELIBERATE DIFFERENCE
 *
 * **This server VERIFIES SIGNATURES; production does not.** `SIGNATURE_REQUIRED = false`
 * (`call_server.js:65`) makes every failure branch return `{valid:true}` — including the one
 * that catches a cryptographically invalid signature. A fake that copied that would be a
 * fake that cannot tell a correctly signed request from an unsigned one, and the canonical
 * string this client builds would be pinned by nothing at all. So [requireSignature]
 * defaults to true and answers 403 the way the server WOULD if the flag were flipped, which
 * is the stricter reading PLAN.md's rule asks for. Set it false to reproduce production.
 */
class FakeCallServer(
    /** What nginx matches and strips. */
    private val apiPrefix: String = CallSignalClient.API_PREFIX,
    /** See ONE DELIBERATE DIFFERENCE. */
    @Volatile var requireSignature: Boolean = true,
) : AutoCloseable {

    private class Signal(
        val sender: String,
        val signal: String,
        val callId: String,
        val type: String?,
        val senderDeviceId: String,
        val timestamp: Long,
    ) {
        val seenBy = HashSet<String>()
        var deliveredAtMs: Long? = null
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val queues = ConcurrentHashMap<String, MutableList<Signal>>()
    private val answeredElsewhere = ConcurrentHashMap<String, MutableList<Signal>>()
    private val activeCalls = ConcurrentHashMap<String, Pair<String, String>>()
    private val firstOfferAt = ConcurrentHashMap<String, Long>()
    private val signalCounts = ConcurrentHashMap<String, MutableList<Long>>()

    /** Every raw `POST /signal` body, in order. A wire-shape test asserts on these bytes. */
    val postedBodies = CopyOnWriteArrayList<String>()

    /** Every `(method, pathTheServerSaw)` pair — the SIGNED path, after the nginx strip. */
    val seenPaths = CopyOnWriteArrayList<Pair<String, String>>()

    /** Signature verdicts, in order: `verified`, `unsigned`, or a refusal reason. */
    val signatureVerdicts = CopyOnWriteArrayList<String>()

    /**
     * __CALL_PUSH_AUTH_DESKTOP_2026_09_23__ The server's TOFU table (`/var/oshi/auth_bindings.json`):
     * normalized X25519 identity → the base64 Ed25519 key published with it on `/v2`.
     * A VALID signature by a key bound to ANOTHER identity is a forgery (`mismatch` → 403).
     */
    val bindings = ConcurrentHashMap<String, String>()

    /** Per call-server request: `bound` · `unbound` · `mismatch` · `unsigned` · `invalid`. */
    val bindingVerdicts = CopyOnWriteArrayList<String>()

    /** Per push request, the same outcomes. */
    val pushVerdicts = CopyOnWriteArrayList<String>()

    /** `(method, path push_service saw)`. */
    val seenPushPaths = CopyOnWriteArrayList<Pair<String, String>>()

    /** Raw `/push/signal-answered` bodies. */
    val signalAnsweredBodies = CopyOnWriteArrayList<String>()

    /**
     * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ A relay token issued by `POST /relay-token`
     * (`docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md` §2), keyed by tokenId hex — what a fake
     * `:8089` needs to verify a trailer.
     */
    class IssuedToken(val identity: String, val peer: String, val callId: String, val tokenId: ByteArray, val macKey: ByteArray, val expiresAt: Long)

    val relayTokens = ConcurrentHashMap<String, IssuedToken>()

    /** Every `/relay-token` request's outcome: `issued`, or the 4xx reason. */
    val relayTokenVerdicts = CopyOnWriteArrayList<String>()

    /** Bind [identity] to its own signing key, as a `/v2` key publication would. */
    fun bind(identity: com.oshi.desktop.DesktopIdentity) {
        bindings[normalizeKey(identity.userKey)] = Base64.getEncoder().encodeToString(identity.signingPub)
    }

    /** `call_server.js:213` — 30 signals per 60 s per sender. Lowered by a test. */
    @Volatile var signalsPerWindow: Int = 30

    /** `RING_LIFETIME_MS` — `call_server.js:36`. */
    @Volatile var ringLifetimeMs: Long = 40_000

    /** The server's clock, injectable so a TTL can be crossed without sleeping. */
    @Volatile var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Force every route to answer this instead, e.g. to simulate the box being down. */
    @Volatile var failWith: Int? = null

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** How many signals are queued for this address, in any spelling. */
    fun queueDepth(address: String): Int = queues[normalizeKey(address)]?.size ?: 0

    /** Has `/register` ever been called? It must not be — see [CallSignalClient]. */
    fun registeredCalls(): Set<String> = activeCalls.keys.toSet()

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.executor = null
        server.start()
    }

    override fun close() = server.stop(0)

    // ------------------------------------------------------------------ plumbing

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        val (code, response) = try {
            dispatch(ex, body)
        } catch (e: Exception) {
            500 to """{"error":"${e.javaClass.simpleName}: ${e.message}"}"""
        }
        val bytes = response.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun dispatch(ex: HttpExchange, body: ByteArray): Pair<Int, String> {
        failWith?.let { return it to """{"error":"forced"}""" }

        val rawPath = ex.requestURI.rawPath
        val method = ex.requestMethod
        // nginx `location /push/ { proxy_pass http://127.0.0.1:8084/; }` — push_service.
        if (rawPath.startsWith("${CallSignalClient.PUSH_PREFIX}/")) {
            val path = rawPath.removePrefix(CallSignalClient.PUSH_PREFIX)
            seenPushPaths += method to path
            val sig = verifySignature(ex, method, path, body, listOf(CallSignalClient.PUSH_PREFIX))
            val verdict = bindingVerdict(sig, ex, claimedIdentity(path, body))
            pushVerdicts += verdict
            if (verdict == "mismatch") return 403 to """{"error":"forbidden","reason":"binding-mismatch"}"""
            return when {
                method == "POST" && path == "/signal-answered" -> {
                    signalAnsweredBodies += String(body, Charsets.UTF_8)
                    200 to """{"success":true}"""
                }
                else -> 404 to """{"error":"no route for $method $path"}"""
            }
        }
        // nginx. A request that does not carry the prefix never reaches this app at all.
        if (apiPrefix.isNotEmpty() && !rawPath.startsWith("$apiPrefix/")) {
            return 404 to """{"error":"nginx has no location for $rawPath"}"""
        }
        val path = if (apiPrefix.isEmpty()) rawPath else rawPath.removePrefix(apiPrefix)
        seenPaths += method to path

        val sig = verifySignature(ex, method, path, body, listOf("/api/call", "/voip"))
        signatureVerdicts += sig
        val verdict = bindingVerdict(sig, ex, claimedIdentity(path, body))
        bindingVerdicts += verdict
        // The token route never accepts unsigned requests, in any mode (contract §2).
        if (method == "POST" && path == "/relay-token") return relayToken(body, sig, verdict)
        if (requireSignature && sig != "verified" || verdict == "mismatch") {
            return 403 to JSONObject().put("error", "Invalid signature")
                .put("reason", if (verdict == "mismatch") "mismatch" else sig).toString()
        }

        val query = ex.requestURI.rawQuery.orEmpty()
        return when {
            method == "POST" && path == "/signal" -> postSignal(String(body, Charsets.UTF_8))
            method == "GET" && path.startsWith("/signals/") ->
                getSignals(URLDecoder.decode(path.removePrefix("/signals/"), "UTF-8"), deviceIdOf(query))
            method == "POST" && path == "/register" || method == "POST" && path == "/api/call/register" -> {
                val o = JSONObject(String(body, Charsets.UTF_8))
                val id = o.optString("callId")
                if (id.isEmpty()) 400 to """{"error":"Missing fields"}"""
                else {
                    activeCalls[id] = normalizeKey(o.optString("participant1")) to
                        normalizeKey(o.optString("participant2"))
                    200 to """{"success":true}"""
                }
            }
            method == "POST" && path == "/end" -> {
                activeCalls.remove(JSONObject(String(body, Charsets.UTF_8)).optString("callId"))
                200 to """{"success":true}"""
            }
            method == "POST" && path == "/ping" ->
                200 to JSONObject().put("pong", true).put("serverTime", nowMs()).toString()
            method == "GET" && path == "/health" ->
                200 to JSONObject().put("status", "ok").put("activeCalls", activeCalls.size)
                    .put("signatureRequired", requireSignature).toString()
            else -> 404 to """{"error":"no route for $method $path"}"""
        }
    }

    /** `call_server.js` `app.post('/relay-token')`, patched `__CALL_MEDIA_AUTH_2026_09_23__`. */
    private fun relayToken(body: ByteArray, sig: String, verdict: String): Pair<Int, String> {
        val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        val me = o?.optString("identity").orEmpty()
        val peer = o?.optString("peer").orEmpty()
        val callId = o?.optString("callId").orEmpty()
        val key32 = { k: String ->
            runCatching { Base64.getUrlDecoder().decode(normalizeKey(k)).size == 32 }.getOrDefault(false)
        }
        if (!key32(me) || !key32(peer) || normalizeKey(me) == normalizeKey(peer) ||
            !Regex("^[A-Za-z0-9._:-]{1,64}$").matches(callId)
        ) {
            relayTokenVerdicts += "bad-body"
            return 400 to JSONObject().put("error", "Bad identity, peer or callId").toString()
        }
        if (sig != "verified" || verdict == "mismatch") {
            val reason = when {
                verdict == "mismatch" -> "mismatch"
                sig == "unsigned" -> "unsigned"
                sig == "timestamp-expired" -> "expired"
                else -> "invalid"
            }
            relayTokenVerdicts += reason
            return 403 to JSONObject().put("error", "Signature required").put("reason", reason).toString()
        }
        val rnd = java.security.SecureRandom()
        val id = ByteArray(16).also(rnd::nextBytes)
        val key = ByteArray(32).also(rnd::nextBytes)
        val expiresAt = nowMs() + 4 * 3_600_000L
        relayTokens[id.joinToString("") { "%02x".format(it.toInt() and 0xFF) }] =
            IssuedToken(normalizeKey(me), normalizeKey(peer), callId, id, key, expiresAt)
        relayTokenVerdicts += "issued"
        val b64u = { b: ByteArray -> Base64.getUrlEncoder().withoutPadding().encodeToString(b) }
        return 200 to JSONObject().put("tokenId", b64u(id)).put("macKey", b64u(key)).put("expiresAt", expiresAt)
            .put("ttlMs", 14_400_000L).put("udpPort", 8089).put("mode", "dual").put("v", 1).toString()
    }

    private fun deviceIdOf(query: String): String =
        query.split('&').firstOrNull { it.startsWith("deviceId=") }
            ?.removePrefix("deviceId=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            .orEmpty()

    /**
     * The canonical string is the V2 one — `METHOD\nPATH\nSHA256hex(body)\nTIMESTAMP`
     * (`call_server.js:98-106`) — and `PATH` is `req.originalUrl.split('?')[0]` evaluated
     * HERE, i.e. after the nginx strip. That is the whole point of this function.
     */
    /** Contract §3: where each route's CLAIMED identity is read from. */
    private fun claimedIdentity(path: String, body: ByteArray): String? {
        val o = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
        return when {
            path == "/signal" || path == "/end" -> o?.optString("sender")
            path.startsWith("/signals/") -> URLDecoder.decode(path.removePrefix("/signals/"), "UTF-8")
            path == "/signal-answered" || path == "/relay-token" -> o?.optString("identity")
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private fun bindingVerdict(sig: String, ex: HttpExchange, claimed: String?): String {
        if (sig == "unsigned") return "unsigned"
        if (sig != "verified") return "invalid"
        if (claimed == null) return "unbound"
        val pub = normalizeKey(ex.requestHeaders.getFirst("x-oshi-signing-pubkey"))
        val bound = bindings[normalizeKey(claimed)] ?: return "unbound"
        return if (normalizeKey(bound) == pub) "bound" else "mismatch"
    }

    /**
     * Verifies over the stripped path OR any known prefix + stripped path — exactly what the
     * patched server does (contract §2). The client signs the full path it requested.
     */
    private fun verifySignature(
        ex: HttpExchange,
        method: String,
        path: String,
        body: ByteArray,
        prefixes: List<String>,
    ): String {
        val candidates = listOf(path) + prefixes.map { it + path }
        val first = verifySignatureOver(ex, method, candidates.first(), body)
        if (first != "invalid-signature") return first
        for (c in candidates.drop(1)) if (verifySignatureOver(ex, method, c, body) == "verified") return "verified"
        return first
    }

    private fun verifySignatureOver(ex: HttpExchange, method: String, path: String, body: ByteArray): String {
        val sig = ex.requestHeaders.getFirst("x-oshi-signature")
        val ts = ex.requestHeaders.getFirst("x-oshi-timestamp")
        val pub = ex.requestHeaders.getFirst("x-oshi-signing-pubkey")
        if (sig == null && ts == null && pub == null) return "unsigned"
        if (sig == null || ts == null || pub == null) return "missing-signature-headers"
        val at = ts.toLongOrNull() ?: return "timestamp-expired"
        // REAL time, not [nowMs]. The injectable clock exists so a test can cross a queue
        // TTL without sleeping; the freshness window is a property of the signer's clock and
        // hanging it off the same knob would make every TTL test fail as a 403 instead.
        if (Math.abs(System.currentTimeMillis() - at) > 30_000) return "timestamp-expired"
        val key = runCatching { Base64.getDecoder().decode(pub) }.getOrNull()
            ?: return "invalid-pubkey-length"
        if (key.size != 32) return "invalid-pubkey-length"
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val canonical = "$method\n$path\n$hash\n$ts"
        val ok = DesktopIdentity.verify(
            canonical.toByteArray(Charsets.UTF_8),
            runCatching { Base64.getDecoder().decode(sig) }.getOrNull() ?: return "invalid-signature",
            key,
        )
        return if (ok) "verified" else "invalid-signature"
    }

    // ------------------------------------------------------------------ the routes

    private fun postSignal(raw: String): Pair<Int, String> {
        postedBodies += raw
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return 400 to """{"error":"Missing fields"}"""
        val recipient = o.optString("recipient")
        val sender = o.optString("sender")
        val signal = o.optString("signal")
        // `if (!recipient || !sender || !signal)` — `call_server.js:634`. The `payload`
        // spelling is NOT accepted here, which is why the envelope must carry both.
        if (recipient.isEmpty() || sender.isEmpty() || signal.isEmpty()) {
            return 400 to """{"error":"Missing fields"}"""
        }
        val nr = normalizeKey(recipient)
        val ns = normalizeKey(sender)
        val now = nowMs()

        if (!rateLimit(ns, now)) {
            return 429 to JSONObject().put("error", "Rate limit exceeded").put("retryAfter", 60).toString()
        }

        val type = o.optString("type").takeIf { it.isNotEmpty() }
        val callId = o.optString("callId")
        val entry = Signal(ns, signal, callId, type, o.optString("senderDeviceId"), now)

        if (type == "callAnsweredElsewhere") {
            answeredElsewhere.getOrPut(nr) { mutableListOf() }.add(entry)
            return 200 to """{"success":true,"delivery":"multidevice"}"""
        }

        val queue = queues.getOrPut(nr) { mutableListOf() }
        synchronized(queue) {
            // __STALE_TERMINAL_DROP__ (`:668-683`): a terminal for a callId already
            // superseded by a newer offer to this recipient is not queued at all.
            val terminal = type in TERMINAL_TYPES
            val skip = terminal && callId.isNotEmpty() &&
                queue.any { isOffer(it.type) && it.callId.isNotEmpty() && it.callId != callId }
            if (!skip) queue.add(entry)

            // __NEWCALL_PURGE__ (`:706-724`): a new OFFER drops every queued signal from a
            // previous call to this recipient.
            if (isOffer(type) && callId.isNotEmpty()) {
                queue.retainAll { it.callId == callId }
            }
        }

        // __OFFER_LIFETIME_CAP__ (`:744-757`). Reproduced including its consequence: the
        // real server has ALREADY sent `delivery:"queued"` by the time it purges, so a
        // caller is told the offer was queued for offers that are being deleted. This fake
        // answers the word the real one puts on the wire, not the one in its source.
        if (isOffer(type) && callId.isNotEmpty()) {
            val first = firstOfferAt.putIfAbsent(callId, now)
            if (first != null && now - first > ringLifetimeMs) {
                synchronized(queue) { queue.retainAll { it.callId != callId } }
                return 200 to """{"success":true,"delivery":"queued"}"""
            }
        }
        return 200 to """{"success":true,"delivery":"queued"}"""
    }

    private fun getSignals(rawKey: String, deviceId: String): Pair<Int, String> {
        val key = normalizeKey(rawKey)
        val now = nowMs()
        val out = JSONArray()

        val queue = queues[key]
        if (queue != null) {
            synchronized(queue) {
                // The two staleness filters, `:855-880`.
                queue.retainAll {
                    val age = now - it.timestamp
                    when {
                        isOffer(it.type) -> age <= OFFER_MAX_AGE_MS
                        it.type in TERMINAL_TYPES -> age <= TERMINAL_MAX_AGE_MS
                        else -> age <= 60_000
                    }
                }
                val live = { s: Signal -> s.deliveredAtMs?.let { now - it < POLL_GRACE_MS } ?: true }
                val fresh = queue.filter {
                    live(it) && !(deviceId.isNotEmpty() && deviceId in it.seenBy)
                }
                for (s in fresh) {
                    if (deviceId.isNotEmpty()) s.seenBy += deviceId
                    if (s.deliveredAtMs == null) s.deliveredAtMs = now
                    out.put(render(s))
                }
                // Deliver and retain share ONE predicate, `:900-903` — otherwise a signal
                // goes out once more on the very poll that drops it.
                queue.retainAll(live)
                // Without a deviceId the server takes its legacy consume-on-read branch.
                if (deviceId.isEmpty()) queue.clear()
            }
        }

        val answered = answeredElsewhere[key]
        if (answered != null) {
            synchronized(answered) {
                answered.retainAll { now - it.timestamp < 30_000 }
                val live = { s: Signal -> s.deliveredAtMs?.let { now - it < POLL_GRACE_MS } ?: true }
                val fresh = answered.filter {
                    live(it) &&
                        !(deviceId.isNotEmpty() && it.senderDeviceId == deviceId) &&
                        !(deviceId.isNotEmpty() && deviceId in it.seenBy)
                }
                for (s in fresh) {
                    if (deviceId.isNotEmpty()) s.seenBy += deviceId
                    if (s.deliveredAtMs == null) s.deliveredAtMs = now
                    out.put(render(s))
                }
                answered.retainAll(live)
            }
        }
        // A BARE ARRAY. Not `{"signals":[…]}` — `res.json(allSignals)`, `:921`.
        return 200 to out.toString()
    }

    /** Exactly the five fields the server queues and re-emits (`:694`, `:920`). */
    private fun render(s: Signal): JSONObject = JSONObject()
        .put("sender", s.sender)
        .put("signal", s.signal)
        .put("callId", s.callId)
        .put("type", s.type ?: JSONObject.NULL)
        .put("timestamp", s.timestamp)
        .also { if (s.senderDeviceId.isNotEmpty()) it.put("senderDeviceId", s.senderDeviceId) }

    private fun rateLimit(sender: String, now: Long): Boolean {
        val hits = signalCounts.getOrPut(sender) { mutableListOf() }
        synchronized(hits) {
            hits.retainAll { now - it < 60_000 }
            hits.add(now)
            return hits.size <= signalsPerWindow
        }
    }

    companion object {
        /** `call_server.js:56-59`. */
        fun normalizeKey(key: String): String =
            key.replace('+', '-').replace('/', '_').trimEnd('=')

        private val TERMINAL_TYPES =
            setOf("callEnd", "callDeclined", "callMissed", "callTimeout", "callCancelled")

        private fun isOffer(type: String?): Boolean =
            type == null || type == "callRequest" || type == "callOffer" ||
                type == "offer" || type == "videoCallRequest"

        /** `OFFER_MAX_AGE_MS` — `call_server.js:858`. */
        const val OFFER_MAX_AGE_MS = 30_000L

        /** `TERMINAL_MAX_AGE_MS` — `call_server.js:875`. */
        const val TERMINAL_MAX_AGE_MS = 25_000L

        /** `POLL_GRACE_MS` — `call_server.js:846`. */
        const val POLL_GRACE_MS = 8_000L
    }
}
