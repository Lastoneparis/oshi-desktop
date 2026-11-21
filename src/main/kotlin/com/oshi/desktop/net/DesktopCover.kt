package com.oshi.desktop.net

import com.oshi.desktop.store.AtomicFile
import com.oshi.desktop.store.DesktopPaths
import com.oshi.messenger.network.v2.CoverPresentation
import com.oshi.messenger.service.CoverCodec
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * __PRIVACY_DISGUISE_2026_11_18__ "Disguise OSHI traffic" — the V2 cover transport, desktop half.
 *
 * Same wire format as Android's `CoverInterceptor` and iOS's `CoverTransport.swift`, built
 * from the SAME Kotlin: [CoverCodec] (padded frame inside an analytics-style JSON body) and
 * [CoverPresentation] (alias path, query, optional headers, Content-Type spelling) are
 * compiled out of the Android tree. What this file adds is only the JDK `HttpClient` plumbing
 * Android does with OkHttp, and it is deliberately a line-for-line reading of that interceptor:
 *
 *  * the signed V2 request — method, path + query, every `x-oshi-*` header and the
 *    content-type, body as base64 — becomes ONE record `{v:1,method,path,headers,body}`;
 *  * the record is framed into a shared padding bucket and POSTed to `/v2/cover`,
 *    `/v2/collect` or `/v2/metrics` on the same host;
 *  * the reply is decoded by the strict closed-schema decoder and handed back as the inner
 *    V2 response (status, content-type, retry-after, body);
 *  * `Accept-Encoding: identity` on every cover request (audit finding 5): a gzipped reply's
 *    size would separate an empty poll from a full one.
 *
 * FAIL-CLOSED, like Android: any failure here is a failed call ([exchange] throws), never a
 * direct send. [V2Http] maps the throw to code -1 ("never reached the server"), and the
 * 1:1 send path then WAITS ([com.oshi.desktop.app.CoverOutbox]) instead of trying anything else.
 * The desktop has no legacy relay path for 1:1 at all, so there is nothing to fall back to
 * even by accident — the outbox is what makes "wait" visible instead of "failed".
 *
 * Off by default; a corrupt settings file reads as OFF, the same value the switch shows.
 */
object DesktopCover {

    /** Android's DataStore key (`covert_channel_enabled`), so an export/import reads the same. */
    const val KEY = "covert_channel_enabled"
    const val FILE_NAME = "privacy-disguise.json"

    /** The only carrier every relay copy accepts. */
    private const val MODE = "event"

    @Volatile private var file: File? = null
    @Volatile private var cached: Boolean? = null

    /** Tests point this at a scratch file; the app uses the per-user data directory. */
    internal fun useFile(f: File?) {
        synchronized(this) { file = f; cached = null; hintCached = null }
    }

    private fun file(): File = file ?: synchronized(this) {
        file ?: DesktopPaths.file(FILE_NAME).also { file = it }
    }

    fun isEnabled(): Boolean = cached ?: load().also { cached = it }

    fun setEnabled(on: Boolean) {
        synchronized(this) {
            val f = file()
            val o = try { if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject() } catch (_: Exception) { JSONObject() }
            AtomicFile.write(f, o.put(KEY, on).toString().toByteArray(Charsets.UTF_8))
            cached = load()
        }
        listeners.forEach { runCatching { it(isEnabled()) } }
    }

    /**
     * __RESTRICTED_NETWORK_HINT_2026_11_18__ "Let contacts know I'm on a restricted network"
     * (RN-HINT v1). Default ON; only acts while the cover is on. Same settings file.
     */
    const val HINT_KEY = "restricted_network_hint_enabled"
    @Volatile private var hintCached: Boolean? = null

    fun isHintEnabled(): Boolean = hintCached ?: loadHint().also { hintCached = it }

    fun setHintEnabled(on: Boolean) {
        synchronized(this) {
            val f = file()
            val o = try { if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject() } catch (_: Exception) { JSONObject() }
            if (!o.has(KEY)) o.put(KEY, isEnabled())
            AtomicFile.write(f, o.put(HINT_KEY, on).toString().toByteArray(Charsets.UTF_8))
            hintCached = loadHint()
        }
    }

    /** Should a 1:1 text sent NOW carry the hint? Both switches, read at send time. */
    fun shouldMarkOutgoing(): Boolean = isEnabled() && isHintEnabled()

    private fun loadHint(): Boolean = try {
        val f = file()
        if (!f.isFile) true else JSONObject(f.readText(Charsets.UTF_8)).optBoolean(HINT_KEY, true)
    } catch (_: Exception) {
        true
    }

    private fun load(): Boolean = try {
        val f = file()
        if (!f.isFile) false else JSONObject(f.readText(Charsets.UTF_8)).optBoolean(KEY, false)
    } catch (_: Exception) {
        false
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** Called (on the caller's thread) whenever the switch changes — the outbox listens. */
    fun addListener(l: (Boolean) -> Unit) { listeners += l }

    /** Every `/v2/` call to the configured relay is carried when the switch is on. */
    fun isEligible(uri: URI): Boolean = uri.rawPath?.startsWith("/v2/") == true

    /** The decoded inner V2 response. */
    class Inner(
        val status: Int, val contentType: String, val retryAfter: String?, val body: ByteArray,
        /** __BLOB_RANGE_2026_11_19__ a ranged chunk read (206) is only usable with its range. */
        val contentRange: String? = null,
    )

    /** Requests that left inside the cover carrier — for tests and the settings screen. */
    internal val coverSends = java.util.concurrent.atomic.AtomicInteger()

    /**
     * One V2 request, carried. Throws [IOException] on ANY failure — the caller must treat
     * that as "not sent" and must not try the request undisguised.
     *
     * @param headers the signed headers, exactly as a direct send would carry them.
     */
    fun exchange(
        http: HttpClient,
        method: String,
        uri: URI,
        headers: Map<String, String>,
        body: ByteArray?,
        timeout: Duration,
    ): Inner {
        try {
            val inner = JSONObject()
            for ((k, v) in headers) {
                if (k.startsWith("x-oshi-", ignoreCase = true) || k.equals("content-type", ignoreCase = true)) {
                    inner.put(k.lowercase(), v)
                }
            }
            val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            val record = JSONObject().put("v", 1).put("method", method).put("path", path)
                .put("headers", inner).put("body", Base64.getEncoder().encodeToString(body ?: ByteArray(0)))
            val carrier = CoverCodec.encode(record, MODE)
            val look = CoverPresentation.next()
            val outerUri = URI(uri.scheme, uri.rawAuthority, look.path, null, null).toString() +
                (look.query?.let { "?$it" } ?: "")
            // __COVER_SLOW_LINK_2026_11_18__ Android's CoverInterceptor rule: padding makes the
            // outer request up to ~3x the inner one and the relay answers only once it has all
            // of it, so a fixed timeout expired first on a 64 kbit/s link (netem "edge").
            // 15 s + 1 s per 4 KiB sent, max 10 min, never below the caller's own timeout.
            val budget = Duration.ofMillis(coverTimeoutMs(carrier.body.size))
            val builder = HttpRequest.newBuilder(URI.create(outerUri))
                .timeout(if (budget > timeout) budget else timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(carrier.body))
            for ((name, value) in look.headers) builder.header(name, value)
            builder.header("Content-Type", look.contentType)
            // __COVER_NO_GZIP_2026_11_18__ the client half of the compressed-size defence.
            builder.header("Accept-Encoding", "identity")
            carrier.cookie?.let { builder.header("Cookie", it) }
            if (uri.scheme == "http") builder.version(HttpClient.Version.HTTP_1_1)

            coverSends.incrementAndGet()
            // __COVER_SHORT_CONNECTIONS_2026_11_19__ see [ShortConnections].
            val fresh = ShortConnections.active()
            val client = if (fresh) ShortConnections.freshClient(http) else http
            if (fresh) builder.version(HttpClient.Version.HTTP_1_1)
            try {
            val whole = if (budget > timeout) budget else timeout
            val resp = try { NetClients.sendBounded(client, builder.build(), CoverCodec.MAX * 4, whole, "Cover response too large") }
                catch (e: IOException) { ShortConnections.onFailure(e); throw e }
            if (resp.statusCode() !in 200..299) {
                throw IOException("Obfuscated transport HTTP ${resp.statusCode()}")
            }
            // __COVER_MIDDLEBOX_2026_11_18__ The relay never compresses a cover reply and always
            // labels it application/json, but a middlebox in front of it may do either (netem
            // "gzip" / "rewrite": every Android send failed until this was fixed there). We asked
            // for `identity`, so nothing inflates for us: do it here, bounded. The outer
            // Content-Type is not trusted either way — CoverCodec.decode's closed JSON schema is
            // the check — so a relabelled reply is read as JSON. A portal page or broken gzip is
            // still a failed attempt, never a message.
            val gzipped = resp.headers().firstValue("Content-Encoding").orElse("").trim().equals("gzip", ignoreCase = true)
            val bytes = if (!gzipped) resp.body() else try {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(resp.body())).use { it.readBounded(CoverCodec.MAX * 4) }
            } catch (e: IOException) { throw IOException("Invalid obfuscated response: ${e.message}", e) }
            val decoded = CoverCodec.decode(bytes, "application/json")
            val status = decoded.getInt("status")
            if (status !in 200..599) throw IOException("Invalid obfuscated status $status")
            val innerHeaders = decoded.getJSONObject("headers")
            return Inner(
                status = status,
                contentType = innerHeaders.optString("content-type", "application/octet-stream"),
                retryAfter = innerHeaders.optString("retry-after").takeIf { it.isNotEmpty() },
                body = Base64.getDecoder().decode(decoded.getString("body")),
                contentRange = innerHeaders.optString("content-range").takeIf { it.isNotEmpty() },
            )
            } finally { if (fresh) ShortConnections.discard(client) }
        } catch (e: IOException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Obfuscated transport interrupted", e)
        } catch (e: Exception) {
            throw IOException("Invalid obfuscated response: ${e.message}", e)
        }
    }

    /**
     * __COVER_SHORT_CONNECTIONS_2026_11_19__ Survive a per-connection volume cap — the desktop
     * twin of Android's `CoverConnectionPolicy`.
     *
     * Since June 2025 Russia's TSPU silently freezes TCP connections to foreign hosting ranges
     * (Contabo AS51167, where the relay lives, is in the reported set) after ~16 KB / ~25
     * packets server->client: no RST, the reply just stops (net4people/bbs#490). The counter is
     * per connection. The JDK client multiplexes every call on ONE HTTP/2 connection, so on such
     * a network the desktop stalls after two or three padded exchanges (~5.5 KB each).
     *
     * After a transport-level failure (timeout, reset, EOF — not an HTTP status the relay chose
     * to send, not a reply that failed to decode) every cover exchange for [WINDOW_MS] uses its
     * own short-lived HTTP/1.1 client, i.e. its own TCP+TLS connection, closed afterwards.
     * Each further transport failure extends the window. Normal sharing otherwise.
     * Media chunks above the cap still fail inside one request (documented limit).
     */
    /**
     * __PULL_BUDGET_2026_11_19__ While [ShortConnections] is active, ask the relay for a pull
     * page that fits the 4 KiB cover bucket (~5.5 KB on the wire): a page in the 16 KiB bucket
     * (~22 KB) never gets through a 16 KB-per-connection freeze. Ignored by older relays.
     */
    fun pullBudgetQuery(): String =
        if (ShortConnections.active()) "&budget=${ShortConnections.SMALL_PULL_BUDGET}" else ""

    object ShortConnections {
        const val WINDOW_MS: Long = 15 * 60_000L
        /** Bytes of inner pull JSON that stay in the 4 KiB padding bucket (Android's value). */
        const val SMALL_PULL_BUDGET = 2400
        @Volatile private var until: Long = 0L
        @Volatile internal var clock: () -> Long = System::currentTimeMillis
        /** Exchanges that used a connection of their own — for tests and diagnostics. */
        internal val freshExchanges = java.util.concurrent.atomic.AtomicInteger()

        fun active(): Boolean = clock() < until
        fun onFailure(e: Throwable) { if (isTransportFailure(e)) until = clock() + WINDOW_MS }
        internal fun reset() { until = 0L; freshExchanges.set(0) }

        internal fun isTransportFailure(e: Throwable): Boolean {
            if (e !is IOException) return false
            val m = e.message.orEmpty()
            if (e.javaClass.simpleName == "KeyMismatchException") return false
            // An answer that came back but was wrong is not a stalled link (cover or OHTTP relay).
            return !(m.startsWith("Obfuscated transport HTTP") || m.startsWith("Invalid obfuscated") ||
                m.startsWith("Cover response too large") || m.startsWith("Privacy relay HTTP") ||
                m.startsWith("Privacy relay: ") || m.startsWith("Privacy relay response too large") ||
                m.startsWith("Privacy relay exchange failed"))
        }

        internal fun freshClient(like: HttpClient): HttpClient {
            freshExchanges.incrementAndGet()
            return NetClients.builderLike(like)
                .connectTimeout(like.connectTimeout().orElse(Duration.ofSeconds(15)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
        }

        /**
         * JDK 21+: `shutdownNow()` drops its connection now (NOT `close()`, which waits for a
         * frozen exchange to finish — i.e. forever on a capped link). Older JDKs: left to GC.
         */
        internal fun discard(client: HttpClient) {
            runCatching { HttpClient::class.java.getMethod("shutdownNow").invoke(client) }
        }
    }

    /**
     * Timeout for a cover request whose outer body is [outerBytes]: 15 s plus one second per
     * 4 KiB (still finishing on a ~32 kbit/s link), at most 10 min. Android's `coverTimeoutMs`.
     */
    internal fun coverTimeoutMs(outerBytes: Int): Long = minOf(600_000L, 15_000L + outerBytes.toLong() * 1000 / 4096)

    // ------------------------------------------------------------------ timing

    private val random = SecureRandom()

    /**
     * __COVER_JITTER_2026_11_18__ The delay before the next receive poll. With the cover on, a
     * fixed 3 s cadence would be the most recognisable thing about the traffic, so each wait
     * is drawn from 2.5–7.5 s — iOS's `CoverPullGate` window, and ±50 % around Android's 5 s.
     * Off, the caller's own interval is returned unchanged.
     */
    fun nextPollDelayMs(baseMs: Long, coverOn: Boolean = isEnabled()): Long =
        if (!coverOn) baseMs else PULL_MIN_MS + random.nextInt((PULL_MAX_MS - PULL_MIN_MS).toInt() + 1)

    const val PULL_MIN_MS = 2_500L
    const val PULL_MAX_MS = 7_500L

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("Cover response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
