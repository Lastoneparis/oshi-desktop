package com.oshi.desktop.net

import com.oshi.desktop.DesktopV2Signer
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

    private val http: HttpClient = HttpClient.newBuilder()
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
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofByteArray(body)

        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout)
        for ((k, v) in headers) builder.header(k, v)
        contentType?.let { builder.header("Content-Type", it) }
        builder.method(method, publisher)

        return try {
            val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            Response(resp.statusCode(), resp.body() ?: "")
        } catch (e: Exception) {
            // A transport failure and an HTTP error must not look the same to a caller
            // that decides whether to fall back to another path — but neither may throw
            // out of a send loop. -1 is "never reached the server".
            Response(-1, "${e.javaClass.simpleName}: ${e.message}")
        }
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
        val headers = signer.sign("GET", path, bodyToHash, withUserHeader)
        val url = baseUrl + path + if (query.isNullOrEmpty()) "" else "?$query"
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET()
        for ((k, v) in headers) builder.header(k, v)
        return try {
            val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            resp.statusCode() to (resp.body() ?: ByteArray(0))
        } catch (e: Exception) {
            -1 to ByteArray(0)
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)

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
