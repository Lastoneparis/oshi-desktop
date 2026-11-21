package com.oshi.desktop.net

import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * __COVER_DNS_FALLBACK_2026_11_19__ Resolve the OSHI hosts when the network's resolver lies.
 * Desktop twin of Android's `CoverDns` (012f7286), same rules, same constants.
 *
 * DNS tampering is the cheapest block there is and is still used everywhere in 2026:
 * Iran answers blocked names with 10.10.34.34-36 (FOCI 2026: >90 % of censored names in the
 * June-2025 shutdown), Turkmenistan/Pakistan/Turkey/Indonesia run DNS blocklists, many ISPs
 * return NXDOMAIN or a private block-page address.
 *
 * Order (nothing changes on a network whose DNS is honest):
 *  1. the system resolver (what every app uses — no new traffic pattern);
 *  2. ONLY if it fails, or every address it returned is non-routable (private, loopback,
 *     link-local, CGNAT, unspecified — never a real answer for a public name):
 *     DNS-over-HTTPS to Cloudflare (https://1.1.1.1/dns-query) then Google
 *     (https://8.8.8.8/resolve), addressed by IP literal so no bootstrap lookup is needed;
 *  3. if DoH also fails, the system answer (or its error) is returned unchanged.
 *
 * A DoH answer is reused for 5 min; a dead DoH path (both resolvers unreachable, e.g. a
 * whitelist-only network) is not retried for 60 s, so it costs one timeout per minute.
 *
 * The JDK `HttpClient` (the desktop targets JVM 11) has no resolver hook, so this is not
 * plugged into the client: [DnsFallbackRoute] asks [systemIsUnusable] per request and, only
 * then, carries the connection through an in-process CONNECT tunnel that dials the
 * [lookup] answer. TLS, SNI, Host and hostname verification are untouched — the client still
 * talks TLS to `oshi-messenger.com` end to end — so a wrong address cannot impersonate OSHI.
 *
 * Limit: it defeats DNS-only blocking, nothing more (SNI / IP blocking: §3 of
 * docs/CENSORSHIP_2026_THREAT_MODEL.md). Privacy cost when it engages: the looked-up name
 * goes to Cloudflare or Google.
 */
class CoverDns(
    private val system: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val doh: (String) -> List<InetAddress> = ::dohLookup,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val answers: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** True when the system answer is missing or only non-routable: the DoH path is needed. */
    fun systemIsUnusable(hostname: String): Boolean {
        if (isLocalName(hostname) || isIpLiteral(hostname)) return false
        val answers = try { system(hostname) } catch (_: UnknownHostException) { return true } catch (_: Exception) { return true }
        return answers.none { !isNonRoutable(it) }
    }

    fun lookup(hostname: String): List<InetAddress> {
        if (isLocalName(hostname) || isIpLiteral(hostname)) return system(hostname)
        val sys = try { Result.success(system(hostname)) } catch (e: UnknownHostException) { Result.failure(e) }
        val answers = sys.getOrNull()
        if (answers != null && answers.any { !isNonRoutable(it) }) return answers.filter { !isNonRoutable(it) }
        val key = hostname.lowercase()
        val now = clock()
        val cached = cache[key]?.takeIf { it.until > now }
        val viaDoh = cached?.answers ?: run {
            val fresh = try { doh(hostname).filter { !isNonRoutable(it) } } catch (_: Exception) { emptyList() }
            cache[key] = Entry(fresh, now + if (fresh.isNotEmpty()) POSITIVE_TTL_MS else NEGATIVE_TTL_MS)
            fresh
        }
        if (viaDoh.isNotEmpty()) { lastFallbackAt = now; return viaDoh }
        return sys.getOrThrow()
    }

    companion object {
        /** For diagnostics and tests: last time DoH had to answer. */
        @Volatile var lastFallbackAt: Long = 0L

        val DEFAULT = CoverDns()

        const val POSITIVE_TTL_MS = 5 * 60_000L
        const val NEGATIVE_TTL_MS = 60_000L

        internal val DOH_ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query",   // Cloudflare JSON API (application/dns-json)
            "https://8.8.8.8/resolve",     // Google JSON API
        )

        private val dohClient: HttpClient by lazy {
            TlsLook.apply(HttpClient.newBuilder())
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.of(null))   // never through the tunnel it feeds
                .build()
        }

        internal fun dohLookup(hostname: String): List<InetAddress> {
            for (endpoint in DOH_ENDPOINTS) {
                val out = ArrayList<InetAddress>()
                for (type in listOf("A", "AAAA")) {
                    if (out.isNotEmpty()) break   // AAAA only when there is no A answer
                    try {
                        val url = "$endpoint?name=${java.net.URLEncoder.encode(hostname, "UTF-8")}&type=$type"
                        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                            .header("Accept", "application/dns-json").GET().build()
                        val r = dohClient.send(req, HttpResponse.BodyHandlers.ofString())
                        if (r.statusCode() == 200) out += parseDnsJson(hostname, r.body().orEmpty())
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); return emptyList()
                    } catch (_: Exception) { }
                }
                if (out.isNotEmpty()) return out
            }
            return emptyList()
        }

        /** RFC 8427-style JSON (Cloudflare/Google): Status 0 and type-1/28 answers only. */
        internal fun parseDnsJson(hostname: String, json: String): List<InetAddress> {
            val o = JSONObject(json)
            if (o.optInt("Status", -1) != 0) return emptyList()
            val answers = o.optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                val type = a.optInt("type")
                val data = a.optString("data")
                if (type != 1 && type != 28) continue
                val ok = if (type == 1) Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(data)
                else Regex("""^[0-9a-fA-F:]+$""").matches(data) && data.contains(':')
                // Literal-only parse: getByName on a validated literal never touches DNS.
                if (ok) out += InetAddress.getByAddress(hostname, InetAddress.getByName(data).address)
            }
            return out
        }

        internal fun isLocalName(h: String): Boolean {
            val n = h.lowercase().trimEnd('.')
            return !n.contains('.') || n == "localhost" ||
                listOf(".local", ".test", ".internal", ".lan", ".localhost", ".home.arpa").any { n.endsWith(it) }
        }

        internal fun isIpLiteral(h: String): Boolean =
            h.startsWith("[") || h.contains(':') || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h)

        internal fun isNonRoutable(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            return when (a) {
                is Inet4Address -> (b[0].toInt() and 0xff) == 0 ||
                    ((b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64)      // 100.64.0.0/10 CGNAT
                is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc                          // fc00::/7 ULA
                else -> false
            }
        }
    }
}
