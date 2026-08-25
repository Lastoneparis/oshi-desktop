package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A real HTTP server, in this process, standing in for the OSHI relay.
 *
 * Not a mock of the client: a mock would assert that we called the method we wrote, which
 * proves nothing about a transport whose entire content is *what goes on the wire*. This
 * records the actual request — verb, path, query, headers, body bytes — and can VERIFY
 * THE SIGNATURE the way the real server does, by rebuilding the canonical string and
 * checking the Ed25519 signature against the public key the request carried.
 *
 * That last part is what makes these tests worth having. Every one of the four ways this
 * project has earned a 401 (query included in the signed path, upper-case body hash,
 * hashing the wrong bytes, the wrong identity in the wrong header) is a difference
 * between what the client signs and what a server would reconstruct — and only a
 * reconstruction can catch it.
 */
class FakeRelay : AutoCloseable {

    data class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val bodyText: String get() = String(body, Charsets.UTF_8)
        val signingPubKey: String? get() = headers["x-oshi-signing-pubkey"]
        val signature: String? get() = headers["x-oshi-signature"]
        val timestamp: String? get() = headers["x-oshi-timestamp"]
        val userHeader: String? get() = headers["x-oshi-user"]
    }

    /** What to answer, per request. Replace per test; default is 200 `{}`. */
    var responder: (Recorded) -> Pair<Int, String> = { 200 to "{}" }

    private val recorded = ConcurrentLinkedQueue<Recorded>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange: HttpExchange ->
            val body = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
            val rec = Recorded(
                method = exchange.requestMethod,
                // getRawPath() keeps the percent-encoding — getPath() would decode it and
                // this whole file would then be unable to see an encoding bug.
                path = exchange.requestURI.rawPath,
                query = exchange.requestURI.rawQuery,
                headers = headers,
                body = body,
            )
            recorded.add(rec)
            val (code, text) = try { responder(rec) } catch (e: Exception) { 500 to (e.message ?: "boom") }
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
    }

    fun requests(): List<Recorded> = recorded.toList()
    fun last(): Recorded = recorded.last()
    fun clear() = recorded.clear()

    override fun close() = server.stop(0)

    companion object {
        /**
         * Rebuild the canonical string the way the server does and verify the signature.
         *
         * @param bodyToHash what THIS route hashes — the sent bytes for a JSON POST, zero
         *        bytes for a GET, the two-byte `""` for the verify-first routes. Passing
         *        the wrong one here is how the test itself would go wrong, so every call
         *        site names it explicitly rather than inferring it.
         */
        fun verifySignature(rec: Recorded, bodyToHash: ByteArray): Boolean {
            val sig = rec.signature ?: return false
            val pub = rec.signingPubKey ?: return false
            val ts = rec.timestamp ?: return false
            val canonical = "${rec.method}\n${rec.path}\n${DesktopV2Signer.sha256Hex(bodyToHash)}\n$ts"
            return DesktopIdentity.verify(
                canonical.toByteArray(Charsets.UTF_8),
                Base64.getDecoder().decode(sig),
                Base64.getDecoder().decode(pub),
            )
        }
    }
}
