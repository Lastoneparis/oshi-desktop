package com.oshi.desktop.call.transport

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI

/**
 * Ephemeral TURN credentials from the same endpoint iOS uses — PARITY.md row 2.1-t.
 *
 * `GET https://oshi-messenger.com/voip/turn-creds` →
 * `{"username":"<expiry>:oshi","password":"…","ttl":3600,"uris":["turn:45.67.216.197:3478?transport=udp", …]}`
 * (`VoiceCallManager.swift:14207`; Android's `VPSClient.getTurnCreds` hits the same route
 * on its call-server base). coturn runs `use-auth-secret`, so these are the TURN REST
 * credentials and the static `oshi:…` pair still compiled into both phones is answered
 * 401 — which is why this client ships NO static fallback at all: a fallback coturn
 * rejects is a relay that silently never allocates, the exact iOS 2026-07-27 bug.
 *
 * Cached until [REFRESH_BEFORE_EXPIRY_MS] before expiry, like both phones (5 min).
 *
 * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ the GET is SIGNED when [get] is handed a signer
 * (`x-oshi-*` over `GET\n/voip/turn-creds\n<sha256 of nothing>\n<ts>`, the call/push contract
 * §1 scheme) — it used to go out unsigned. The server accepts both today.
 */
class TurnCredentials(
    private val url: String = DEFAULT_URL,
    /** `(url, headers) -> body or null`. Null = the real HTTPS GET. */
    private val fetcher: ((String, Map<String, String>) -> String?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Creds(val server: InetSocketAddress, val username: String, val password: String, val expiresAtMs: Long) {
        /** Never print the password. */
        override fun toString(): String = "Creds(${server.hostString}:${server.port}, expires=$expiresAtMs)"
    }

    @Volatile
    private var cached: Creds? = null

    /**
     * Fresh creds, or null when the endpoint cannot be reached or answers nonsense.
     * [sign] turns the request path into the `x-oshi-*` headers (null = unsigned).
     */
    @Synchronized
    fun get(sign: ((path: String) -> Map<String, String>)? = null): Creds? {
        val now = clock()
        cached?.let { if (it.expiresAtMs - now > REFRESH_BEFORE_EXPIRY_MS) return it }
        val headers = sign?.let { s -> runCatching { s(URI(url).rawPath) }.getOrNull() }.orEmpty()
        val body = runCatching { (fetcher ?: ::httpGet)(url, headers) }.getOrNull() ?: return cached?.takeIf { it.expiresAtMs > now }
        val parsed = parse(body, now) ?: return cached?.takeIf { it.expiresAtMs > now }
        cached = parsed
        return parsed
    }

    companion object {
        const val DEFAULT_URL = "https://oshi-messenger.com/voip/turn-creds"
        const val REFRESH_BEFORE_EXPIRY_MS = 300_000L

        /** The address both phones hard-code (`TURN_HOST`, `TURNConfig.server`). */
        val DEFAULT_SERVER: InetSocketAddress = InetSocketAddress.createUnresolved("45.67.216.197", 3478)

        /**
         * TURN over TLS: a HOSTNAME, because the certificate is `CN=oshi-messenger.com` and it
         * is validated (`VoiceCallManager.swift:14500-14501`, `TurnClient.swift:279-281`).
         */
        const val TLS_HOST = "oshi-messenger.com"
        const val TLS_PORT = 5349

        fun parse(body: String, nowMs: Long): Creds? = runCatching {
            val j = JSONObject(body)
            val user = j.getString("username")
            val pass = j.getString("password")
            if (user.isEmpty() || pass.isEmpty()) return null
            val ttl = j.optInt("ttl", 3600)
            var server = DEFAULT_SERVER
            val uris = j.optJSONArray("uris")
            if (uris != null) {
                for (i in 0 until uris.length()) {
                    val u = uris.optString(i)
                    // Only the UDP URI: this client allocates UDP relays, like the phones'
                    // default. TURN over TLS uses [TLS_HOST]:[TLS_PORT] instead (see TurnLink).
                    if (!u.startsWith("turn:") || u.contains("transport=tcp")) continue
                    val hostPort = u.removePrefix("turn:").substringBefore('?')
                    val host = hostPort.substringBeforeLast(':')
                    val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 3478
                    // Literal IPs only — never resolve a hostname handed to us by a server.
                    if (IceCandidateCodec.parseIpLiteral(host) != null) server = InetSocketAddress.createUnresolved(host, port)
                    break
                }
            }
            Creds(server, user, pass, nowMs + ttl * 1000L)
        }.getOrNull()

        private fun httpGet(url: String, headers: Map<String, String>): String? {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("User-Agent", "OSHI-Desktop/" + (TurnCredentials::class.java.`package`?.implementationVersion ?: "dev"))
            return try {
                if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
    }
}
