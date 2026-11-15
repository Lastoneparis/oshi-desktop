package com.oshi.desktop.net

import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.PrivacyRelayPreferences
import com.oshi.messenger.network.ohttp.Bhttp
import com.oshi.messenger.network.ohttp.Ohttp
import com.oshi.messenger.network.ohttp.OhttpConfig
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * __OHTTP_DESKTOP_2026_11_10__ Everything one encapsulated exchange needs: where the
 * independent relay is, and the PINNED gateway key config. Stateless, safe across threads.
 * Desktop twin of Android's `OhttpTransport` (which is OkHttp-typed and so not shared).
 */
data class DesktopOhttpTransport(
    val relayUrl: URI,
    val keyConfig: Ohttp.KeyConfig,
    val innerAuthority: String = OhttpConfig.INNER_AUTHORITY,
)

/**
 * __OHTTP_DESKTOP_2026_11_10__ "Privacy relay (beta)" — Oblivious HTTP (RFC 9458) for the
 * desktop's `/v2/` calls, media blobs INCLUDED (docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md §4).
 *
 * Path: this client → [OhttpConfig.RELAY_URL] (third party: sees our IP, only ciphertext)
 *       → OSHI gateway (decrypts, sees only the relay's IP) → v2 API.
 *
 * The crypto is NOT re-implemented: `Bhttp.kt`, `Ohttp.kt` and `OhttpConfig.kt` are
 * compiled straight out of the Android tree (build.gradle.kts), so the pinned key
 * (key_id 1) and the relay URL are the phones' constants, byte for byte.
 *
 * Wired at the two places a desktop `/v2/...` request leaves the process: [V2Http] (every
 * signed route, blobs included) and [V2ConfigGate] (`GET /v2/config`). Signature headers
 * are computed by [com.oshi.desktop.DesktopV2Signer] over the INNER request exactly as
 * for a direct send and copied unchanged into the BHTTP message, which the gateway
 * replays — so the server verifies the same canonical string.
 *
 * NO SILENT FALLBACK: once [transport] is non-null, an eligible request that fails in
 * OHTTP (relay down, non-200, wrong media type, auth failure, key mismatch) FAILS — the
 * callers' own retry/queue handles it. It is never re-sent direct.
 *
 * Out of scope, direct BY DESIGN: calls (`/api/call`, WebRTC/WS relay), the devsync
 * WebSocket, legacy `/api/...`, push (`/push/...`), anything not `https://oshi-messenger.com/v2/`.
 */
object DesktopOhttp {
    const val MAX_RESPONSE = 16 * 1024 * 1024
    private val HOP_BY_HOP = setOf(
        "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "host", "content-length",
    )

    /**
     * __OHTTP_RELAY_ADDRESS_2026_11_10__ False while there is no relay: none entered by the
     * user and none built in ([OhttpConfig.RELAY_URL], still `""`) — the toggle is greyed.
     */
    fun isAvailable(): Boolean = OhttpConfig.isConfigured(relayUrl())

    @Volatile private var cachedRelayUrl: String? = null

    /** The relay used: the address the user entered, else the built-in one. */
    fun relayUrl(): String =
        (cachedRelayUrl ?: prefs().loadRelayUrl().also { cachedRelayUrl = it }).ifBlank { OhttpConfig.RELAY_URL }

    /** The address the user entered ("" when none). */
    fun customRelayUrl(): String = cachedRelayUrl ?: prefs().loadRelayUrl().also { cachedRelayUrl = it }

    /**
     * Save a relay address; false (nothing saved) when unacceptable. Blank clears it and,
     * with no built-in relay, turns the switch off — never ON while routing nowhere.
     */
    fun setRelayUrl(input: String): Boolean {
        val url = if (input.isBlank()) "" else normalizeRelayUrl(input) ?: return false
        synchronized(this) {
            prefs().saveRelayUrl(url)
            cachedRelayUrl = url
            if (url.isEmpty() && OhttpConfig.RELAY_URL.isBlank()) setEnabled(false)
        }
        return true
    }

    /**
     * https only (the relay hop must be encrypted too), a real dotted host, and NOT OSHI's
     * own domain — the party that decrypts cannot also be the one hiding the IP.
     * Same rule as Android's `OhttpSettings.normalizeRelayUrl`.
     */
    fun normalizeRelayUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        val uri = runCatching { URI(t) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        val inner = OhttpConfig.INNER_AUTHORITY
        if (host == inner || host.endsWith(".$inner")) return null
        if (!host.contains('.')) return null
        return if (uri.rawPath.isNullOrEmpty()) "$t/" else t
    }

    @Volatile private var prefs: PrivacyRelayPreferences? = null
    @Volatile private var cachedEnabled: Boolean? = null

    private fun prefs(): PrivacyRelayPreferences =
        prefs ?: synchronized(this) {
            prefs ?: PrivacyRelayPreferences(DesktopPaths.file(PrivacyRelayPreferences.FILE_NAME)).also { prefs = it }
        }

    /** The persisted user choice, OFF by default. Says nothing about availability. */
    fun isEnabled(): Boolean = cachedEnabled ?: prefs().load().also { cachedEnabled = it }

    fun setEnabled(on: Boolean) {
        synchronized(this) {
            prefs().save(on)
            cachedEnabled = prefs().load()
        }
    }

    /**
     * Non-null exactly when eligible `/v2/` calls MUST go through OHTTP. A configured but
     * unparsable pinned key throws — the call fails, it never quietly goes direct.
     */
    fun transport(): DesktopOhttpTransport? {
        if (!isEnabled()) return null
        val relay = relayUrl()
        if (!OhttpConfig.isConfigured(relay)) return null
        transportCache?.let { (url, t) -> if (url == relay) return t }
        val t = build(relay, OhttpConfig.PINNED_KEYS_HEX)
            ?: throw IOException("Privacy relay: relay address or pinned key config is invalid")
        transportCache = relay to t
        return t
    }

    @Volatile private var transportCache: Pair<String, DesktopOhttpTransport>? = null

    fun build(relayUrl: String, keysHex: String): DesktopOhttpTransport? = runCatching {
        val cfg = Ohttp.selectFromKeysMedia(Ohttp.hex(keysHex)) ?: return null
        val uri = URI.create(relayUrl)
        if (uri.scheme != "https" && uri.scheme != "http") return null
        DesktopOhttpTransport(uri, cfg)
    }.getOrNull()

    /**
     * Resolve the transport for one request, turning ANY misconfiguration into an
     * [IOException] — never into "null, so go direct".
     */
    fun resolve(supplier: () -> DesktopOhttpTransport?): DesktopOhttpTransport? = try {
        supplier()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Privacy relay misconfigured", e)
    }

    /** `https://<innerAuthority>/v2/...` only — media blobs INCLUDED (spec §4). */
    fun isEligible(uri: URI, t: DesktopOhttpTransport): Boolean {
        if (uri.scheme != "https") return false
        if (uri.host != t.innerAuthority) return false
        if (uri.port != -1 && uri.port != 443) return false
        val path = uri.rawPath ?: return false
        return path.startsWith("/v2/")
    }

    /** The inner answer, as the gateway relayed it from v2. */
    class Inner(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    }

    /** Build the BHTTP request the gateway will replay. Header names lower-cased, hop-by-hop dropped. */
    fun toBhttp(method: String, uri: URI, headers: Map<String, String>, body: ByteArray?): Bhttp.Request {
        val inner = ArrayList<Pair<String, String>>()
        for ((k, v) in headers) {
            val n = k.lowercase()
            if (n !in HOP_BY_HOP) inner += n to v
        }
        val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val authority = if (uri.port == -1 || uri.port == 443) uri.host else "${uri.host}:${uri.port}"
        return Bhttp.Request(method, "https", authority, path, inner, body ?: ByteArray(0))
    }

    /**
     * One RFC 9458 exchange: BHTTP-encode, HPKE-seal to the pinned key, POST
     * `message/ohttp-req` to the relay with NO other identifying header, open the
     * `message/ohttp-res`. Throws [IOException] on every failure — the caller maps that to
     * its own "never reached the server" code and does NOT go direct.
     */
    fun exchange(
        http: HttpClient,
        t: DesktopOhttpTransport,
        method: String,
        uri: URI,
        headers: Map<String, String>,
        body: ByteArray?,
        timeout: Duration,
    ): Inner {
        try {
            val ctx = Ohttp.encapsulateRequest(t.keyConfig, Bhttp.encodeRequest(toBhttp(method, uri, headers, body)))
            val outer = HttpRequest.newBuilder(t.relayUrl)
                .timeout(timeout)
                .header("Content-Type", Ohttp.REQUEST_MEDIA_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(ctx.encapsulatedRequest))
                // A cleartext relay (only ever a loopback test double) must not see an h2c upgrade.
                .apply { if (t.relayUrl.scheme == "http") version(HttpClient.Version.HTTP_1_1) }
                .build()
            val resp = http.send(outer, HttpResponse.BodyHandlers.ofInputStream())
            val bytes = resp.body().use { it.readBounded(MAX_RESPONSE) }
            val type = resp.headers().firstValue("Content-Type").orElse("")
            if (isKeyMismatch(resp.statusCode(), type, bytes)) {
                throw Ohttp.KeyMismatchException(
                    "Privacy relay: gateway rejected the pinned key config (key id ${t.keyConfig.keyId})",
                )
            }
            if (resp.statusCode() != 200) throw IOException("Privacy relay HTTP ${resp.statusCode()}")
            if (!type.trim().startsWith(Ohttp.RESPONSE_MEDIA_TYPE, ignoreCase = true)) {
                throw IOException("Privacy relay: unexpected content type '$type'")
            }
            val decoded = Bhttp.decodeResponse(Ohttp.decapsulateResponse(ctx, bytes))
            return Inner(decoded.status, decoded.headers, decoded.content)
        } catch (e: IOException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Privacy relay exchange interrupted", e)
        } catch (e: Exception) {
            throw IOException("Privacy relay exchange failed: ${e.message}", e)
        }
    }

    /** RFC 9458 §5.3: problem type `https://iana.org/assignments/http-problem-types#ohttp-key`. */
    fun isKeyMismatch(code: Int, contentType: String, body: ByteArray): Boolean {
        if (code !in 400..499) return false
        if (!contentType.contains("problem+json", ignoreCase = true)) return false
        return String(body, Charsets.UTF_8).contains("http-problem-types#ohttp-key")
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("Privacy relay response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
