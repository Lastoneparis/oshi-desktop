package com.oshi.desktop.net

import com.oshi.desktop.DesktopV2Signer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Every authenticated call to the OSHI relay goes through here.
 *
 * One class rather than a client per endpoint, because the four ways to get a 401 out of
 * this server are all in the plumbing rather than in any one route (PLAN.md §4.7), and a
 * rule enforced in one place is a rule that cannot be half-applied:
 *
 *  1. **The signature covers the PATH ONLY — the query string is excluded**, while the
 *     URL obviously carries it. `/v2/messages/{id}` is signed; `?after=123` is not.
 *     [request] takes them as separate parameters so a caller cannot accidentally sign
 *     one string and fetch another.
 *  2. **The body-to-hash is not always the body.** JSON POSTs hash the exact bytes sent;
 *     GETs and commit routes hash ZERO bytes; the verify-first blob/account routes hash
 *     the two-byte string `""`. The caller states which, explicitly.
 *  3. **Serialize once, hash that array, send that array.** [postJson] takes a
 *     `ByteArray`, never an object to be re-serialized — re-serializing between hashing
 *     and sending can reorder keys, and the 401 that follows looks like clock skew.
 *  4. **Two identities, never conflated.** `x-oshi-signing-pubkey` is Ed25519 and always
 *     present; `x-oshi-user` is the X25519 address and rides only on the blob, account
 *     and sync routes.
 *
 * Uses the JDK's own HTTP client — Android uses OkHttp, which is not a dependency worth
 * adding for four verbs. Everything is synchronous and blocking: the desktop client's
 * concurrency lives in its threads, not in a callback tree, and a suspending API here
 * would drag a coroutines dependency into a module that has no other use for one.
 */
class V2Http(
    private val signer: DesktopV2Signer,
    val baseUrl: String = defaultBaseUrl(),
    connectTimeout: Duration = Duration.ofSeconds(15),
    private val readTimeout: Duration = Duration.ofSeconds(30),
    /**
     * __OHTTP_DESKTOP_2026_11_10__ "Privacy relay (beta)". Asked on EVERY request; non-null
     * means an eligible `https://oshi-messenger.com/v2/...` call (blobs included) MUST be
     * sent as Oblivious HTTP through the relay — see [DesktopOhttp]. A failure there is a
     * failed call (code -1), never a direct send.
     */
    private val ohttp: () -> DesktopOhttpTransport? = DesktopOhttp::transport,
    /**
     * __PRIVACY_DISGUISE_2026_11_18__ "Disguise OSHI traffic". Asked on EVERY request, AFTER
     * the privacy relay: when OHTTP carries a call it is already unlinkable from the IP and
     * the cover would add nothing the gateway could see. Otherwise, true means every `/v2/`
     * call goes inside the cover carrier ([DesktopCover]) and a failure there is a failed
     * call (code -1), NEVER a direct send.
     */
    private val cover: () -> Boolean = DesktopCover::isEnabled,
    /**
     * __RELAY_FALLBACK_2026_11_19__ OSHI's blocked-host fallback relay ([RelayFallback]), used
     * only while the privacy relay is OFF, tried in order (__RELAY_LIST_CH1_2026_11_19__).
     * Empty = feature off.
     */
    private val fallback: () -> List<DesktopOhttpTransport> = RelayFallback::transports,
) {

    data class Response(val code: Int, val body: String) {
        val isSuccess: Boolean get() = code in 200..299

        /**
         * The account route's acceptance window: 200..207, and note that this is NARROWER
         * than [isSuccess], not wider. 207 means "erased some stores, could not reach
         * others" and carries a receipt naming which; anything above it is not an answer
         * this client knows how to read. The range is Android's
         * (`V2AccountClient.kt`: `if (resp.code !in 200..207)`), kept identical so the two
         * clients agree on what a completed deletion is.
         *
         * The name reads like a widening, which is exactly how a mutation probe of it came
         * back green: swapping it for [isSuccess] changes nothing for 207 because 207 is
         * already inside 200..299. Read it as "the account window", not as "success plus a
         * bit more".
         */
        val isSuccessOrPartial: Boolean get() = code in 200..207
    }

    /**
     * __PER_DEVICE_MAILBOX_2026_09_23__ Signs the per-device request string for calls made
     * with `device = true` (CLIENT_SPEC.md §2). Null = no device identity: such a call is
     * sent with the account headers only, which the server answers in its LEGACY view — the
     * caller decides whether that is acceptable, this class never invents a device.
     */
    fun interface DeviceRequestSigner {
        /** @return (deviceId, signature) over `OSHI-DEVICE/1\n<id>\n<method>\n<path>\n<bodyHash>\n<ts>`. */
        fun signDeviceRequest(method: String, path: String, bodySha256Hex: String, timestamp: String): Pair<String, String>
    }

    @Volatile var deviceSigner: DeviceRequestSigner? = null

    /** Is the cover carrier switched on for this client (Android: `coverSettings.mode() != null`). */
    fun coverOn(): Boolean = runCatching { cover() }.getOrDefault(false)

    private val http: HttpClient = NetClients.builder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)   // a signed request must not be replayed elsewhere
        // Cleartext (a self-hosted relay, a loopback test server): never attempt the h2c
        // upgrade. The JDK sends `Upgrade: h2c` on plain http, and the v2 server listens for
        // `upgrade` (the devsync WebSocket) — it answers any other upgrade with a 404, so every
        // request failed. TLS negotiates HTTP/2 by ALPN and is untouched.
        .apply { if (baseUrl.startsWith("http://")) version(HttpClient.Version.HTTP_1_1) }
        .build()

    fun get(path: String, query: String? = null, withUserHeader: Boolean = false, device: Boolean = false): Response =
        request("GET", path, query, bodyToHash = EMPTY, body = null, withUserHeader = withUserHeader, device = device)

    /**
     * __GROUP_INFO_SETTINGS_2026_11_20__ An UNSIGNED GET (GROUP_INFO_SETTINGS_SPEC §4.2: the public
     * handle lookup). Signing it would hand the server the identity of whoever looks a group up;
     * the route is public and needs none. Same routing (privacy relay, cover, fallback) as the rest.
     */
    fun unsignedGet(path: String): Response {
        val r = send("GET", baseUrl + path, emptyMap(), null, null, readTimeout)
        return Response(r.code, if (r.failure != null) r.why else String(r.body, Charsets.UTF_8))
    }

    /** @param json the EXACT bytes to send; they are also the bytes hashed. */
    fun postJson(path: String, json: ByteArray, withUserHeader: Boolean = false, device: Boolean = false): Response =
        request("POST", path, null, bodyToHash = json, body = json,
            contentType = "application/json; charset=utf-8", withUserHeader = withUserHeader, device = device)

    /** A POST whose body is empty but which still hashes zero bytes (the commit routes). */
    fun postEmpty(path: String, withUserHeader: Boolean = false): Response =
        request("POST", path, null, bodyToHash = EMPTY, body = EMPTY, withUserHeader = withUserHeader)

    /** DELETE with the verify-first two-byte body-to-hash. */
    fun delete(path: String, bodyToHash: ByteArray = DesktopV2Signer.EMPTY_JSON_STRING_BODY,
               withUserHeader: Boolean = true): Response =
        request("DELETE", path, null, bodyToHash = bodyToHash, body = null, withUserHeader = withUserHeader)

    fun put(path: String, body: ByteArray, bodyToHash: ByteArray, contentType: String = "application/octet-stream",
            withUserHeader: Boolean = true): Response =
        request("PUT", path, null, bodyToHash = bodyToHash, body = body,
            contentType = contentType, withUserHeader = withUserHeader)

    /**
     * @param path  signed AND sent, percent-encoded, no query.
     * @param query sent, never signed. Without the leading '?'.
     */
    fun request(
        method: String,
        path: String,
        query: String? = null,
        bodyToHash: ByteArray,
        body: ByteArray?,
        contentType: String? = null,
        withUserHeader: Boolean = false,
        device: Boolean = false,
    ): Response {
        val headers = signer.sign(method, path, bodyToHash, withUserHeader).toMutableMap()
        if (device) {
            // The device signature reuses the account signature's timestamp: the server
            // reads ONE `x-oshi-timestamp` for both, so two clocks here would be a 401.
            val ds = deviceSigner ?: return Response(-2, "no device identity for a device-mode request")
            val (id, sig) = ds.signDeviceRequest(
                method, path, DesktopV2Signer.sha256Hex(bodyToHash), headers.getValue("x-oshi-timestamp"),
            )
            headers["x-oshi-device"] = id
            headers["x-oshi-device-signature"] = sig
        }
        val url = baseUrl + path + if (query.isNullOrEmpty()) "" else "?$query"
        // __OHTTP_DESKTOP_2026_11_10__ THE choke point. Headers above were signed over the
        // inner method/path/body exactly as for a direct send; they ride inside the BHTTP.
        val r = send(method, url, headers, contentType, body, readTimeout)
        return Response(r.code, if (r.failure != null) r.why else String(r.body, Charsets.UTF_8))
    }

    /**
     * A GET whose response is BYTES, not text — blob chunks.
     *
     * Separate from [get] for one reason that is not stylistic: a chunk body is
     * AES-GCM ciphertext, and decoding arbitrary bytes as UTF-8 and back is lossy — every
     * byte sequence that is not valid UTF-8 comes back as a replacement character and the
     * tag check then fails on data that arrived intact.
     *
     * [bodyToHash] is a parameter, not a constant, because the blob store's chunk GET is a
     * VERIFY-FIRST route: it hashes the two-byte string `""`, while an ordinary GET hashes
     * zero bytes. Hard-coding the ordinary convention here forced the first caller to build
     * its own HTTP client to get a 401-free download — one route's signing rule leaking into
     * a second HTTP stack is exactly how the two drift apart later.
     */
    fun getBytes(
        path: String,
        query: String? = null,
        withUserHeader: Boolean = true,
        bodyToHash: ByteArray = EMPTY,
        timeout: Duration = readTimeout,
    ): Pair<Int, ByteArray> {
        val r = getRaw(path, query, withUserHeader, bodyToHash, timeout)
        return r.code to (if (r.failure != null) ByteArray(0) else r.body)
    }

    /**
     * __BLOB_RANGE_2026_11_19__ One signed GET whose reply is bytes, with what a ranged
     * chunk read needs besides the body: the inner `Content-Range` and, for code -1, the
     * transport failure itself (a stall opens the capped-network window).
     */
    fun getRaw(
        path: String,
        query: String? = null,
        withUserHeader: Boolean = true,
        bodyToHash: ByteArray = EMPTY,
        timeout: Duration = readTimeout,
    ): Raw {
        val headers = signer.sign("GET", path, bodyToHash, withUserHeader)
        val url = baseUrl + path + if (query.isNullOrEmpty()) "" else "?$query"
        return send("GET", url, headers, null, null, timeout)
    }

    /** One answered (or failed: code -1, [failure] set) exchange, whatever path carried it. */
    class Raw(val code: Int, val body: ByteArray, val contentRange: String?, val failure: IOException? = null, val why: String = "") {
        val isSuccess: Boolean get() = code in 200..299
    }

    /**
     * The routing, in order:
     *  1. the user's privacy relay (OHTTP) when ON: required, never falls back;
     *  2. otherwise __RELAY_FALLBACK_2026_11_19__: while [RelayFallback] says the OSHI host is
     *     blocked, through OSHI's own fallback relay; a direct/cover transport failure that puts
     *     it in force is retried there once (same bytes, same signature: the relay's
     *     idempotency cache answers a duplicate whose direct copy did arrive);
     *  3. the cover carrier when ON (fail-closed);
     *  4. direct.
     */
    private fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        body: ByteArray?,
        timeout: Duration,
    ): Raw {
        val uri = URI.create(url)
        val t = try { DesktopOhttp.resolve(ohttp) } catch (e: IOException) {
            return Raw(-1, ByteArray(0), null, e, "${e.javaClass.simpleName}: ${e.message}")
        }
        if (t != null) {
            if (DesktopOhttp.isEligible(uri, t)) {
                return try { viaOhttp(t, method, uri, headers, contentType, body, timeout, fresh = false) }
                catch (e: IOException) { Raw(-1, ByteArray(0), null, e, "${e.javaClass.simpleName}: ${e.message}") }
            }
            return coverOrDirectRaw(method, uri, headers, contentType, body, timeout)
        }
        val relays = try { fallback() } catch (_: Exception) { emptyList() }
        if (relays.isEmpty() || !DesktopOhttp.isEligible(uri, relays[0])) return coverOrDirectRaw(method, uri, headers, contentType, body, timeout)
        if (RelayFallback.active()) return viaRelays(relays, method, uri, headers, contentType, body, timeout, null)
        val direct = coverOrDirectRaw(method, uri, headers, contentType, body, timeout)
        val failure = direct.failure ?: run { RelayFallback.onDirectSuccess(); return direct }
        if (!RelayFallback.onDirectFailure(failure)) return direct
        return viaRelays(relays, method, uri, headers, contentType, body, timeout, failure)
    }

    /**
     * __RELAY_LIST_CH1_2026_11_19__ Try [relays] in order, each once; the first that answers
     * becomes preferred. A gateway key mismatch is the same behind every relay: not retried.
     */
    private fun viaRelays(
        relays: List<DesktopOhttpTransport>, method: String, uri: URI, headers: Map<String, String>,
        contentType: String?, body: ByteArray?, timeout: Duration, directFailure: Throwable?,
    ): Raw {
        var last: IOException? = null
        for (t in relays) {
            try {
                val r = viaOhttp(t, method, uri, headers, contentType, body, timeout, fresh = true)
                RelayFallback.onRelaySuccess(t)
                return r
            } catch (e: IOException) {
                last?.let { e.addSuppressed(it) }
                last = e
                if (e.javaClass.simpleName == "KeyMismatchException") break
            }
        }
        val e = last!!
        RelayFallback.onRelayFailure(e)
        directFailure?.let { e.addSuppressed(it) }
        return Raw(-1, ByteArray(0), null, e, "relay fallback: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun viaOhttp(
        t: DesktopOhttpTransport, method: String, uri: URI, headers: Map<String, String>,
        contentType: String?, body: ByteArray?, timeout: Duration, fresh: Boolean,
    ): Raw {
        val all = headers + listOfNotNull(contentType?.let { "Content-Type" to it })
        // The fallback relay sits in the same kind of hosting as the main server: on a
        // per-connection-capped network it gets the same one-connection-per-exchange policy.
        val short = fresh && DesktopCover.ShortConnections.active()
        val client = if (short) DesktopCover.ShortConnections.freshClient(http) else http
        try {
            val inner = DesktopOhttp.exchange(client, t, method, uri, all, body, timeout)
            return Raw(inner.status, inner.body, inner.header("content-range"))
        } catch (e: IOException) {
            if (fresh) DesktopCover.ShortConnections.onFailure(e)
            throw e
        } finally { if (short) DesktopCover.ShortConnections.discard(client) }
    }

    /** The cover carrier when ON, else direct. Never throws: a failure is code -1 with [Raw.failure]. */
    private fun coverOrDirectRaw(
        method: String, uri: URI, headers: Map<String, String>,
        contentType: String?, body: ByteArray?, timeout: Duration,
    ): Raw {
        if (cover() && DesktopCover.isEligible(uri)) {
            return try {
                val c = DesktopCover.exchange(http, method, uri, headers + listOfNotNull(contentType?.let { "content-type" to it }), body, timeout)
                Raw(c.status, c.body, c.contentRange)
            } catch (e: IOException) {
                Raw(-1, ByteArray(0), null, e, "cover: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: Exception) {
                val io = IOException(e.message, e)
                Raw(-1, ByteArray(0), null, io, "cover: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)
        val builder = HttpRequest.newBuilder(uri).timeout(timeout)
        for ((k, v) in headers) builder.header(k, v)
        contentType?.let { builder.header("Content-Type", it) }
        builder.method(method, publisher)
        directSends.incrementAndGet()
        // __COVER_SHORT_CONNECTIONS_2026_11_19__ the cover's rule applies to direct sends too:
        // after a stall, one connection per exchange (a per-connection cap does not care
        // whether the bytes are disguised).
        val short = DesktopCover.ShortConnections.active()
        val client = if (short) DesktopCover.ShortConnections.freshClient(http) else http
        if (short) builder.version(HttpClient.Version.HTTP_1_1)
        return try {
            val resp = NetClients.sendBounded(client, builder.build(), MAX_DIRECT_RESPONSE, timeout, "Response too large")
            Raw(resp.statusCode(), resp.body() ?: ByteArray(0), resp.headers().firstValue("Content-Range").orElse(null))
        } catch (e: IOException) {
            // A transport failure and an HTTP error must not look the same to a caller
            // that decides whether to fall back to another path — but neither may throw
            // out of a send loop. -1 is "never reached the server".
            DesktopCover.ShortConnections.onFailure(e)
            Raw(-1, ByteArray(0), null, e, "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Raw(-1, ByteArray(0), null, IOException("interrupted", e), "InterruptedException: ${e.message}")
        } catch (e: Exception) {
            Raw(-1, ByteArray(0), null, IOException(e.message, e), "${e.javaClass.simpleName}: ${e.message}")
        } finally { if (short) DesktopCover.ShortConnections.discard(client) }
    }

    /** __OHTTP_DESKTOP_2026_11_10__ Requests that left DIRECT (not via OHTTP). For tests: proves "no fallback". */
    internal val directSends = java.util.concurrent.atomic.AtomicInteger()

    companion object {
        private val EMPTY = ByteArray(0)
        /** A blob chunk is <= 2 MiB; a pull page <= 2 MiB. Generous, but bounded. */
        private const val MAX_DIRECT_RESPONSE = 32 * 1024 * 1024

        /**
         * The relay. `OSHI_SERVER` overrides it, which is how the tests point this at a
         * loopback server and how a self-hosted relay would be configured — the URL is
         * NOT compiled in the way the mobile clients compile theirs
         * (`backend-url-compiled-into-the-binary-blocks-any-migration`: on iOS and
         * Android, changing relay means shipping a release).
         */
        fun defaultBaseUrl(): String =
            System.getenv("OSHI_SERVER")?.trimEnd('/')?.takeIf { it.isNotBlank() }
                ?: "https://oshi-messenger.com"
    }
}
