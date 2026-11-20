package com.oshi.desktop.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * __COVER_DNS_FALLBACK_2026_11_19__ How [CoverDns] reaches a JDK `HttpClient`.
 *
 * `java.net.http.HttpClient` (JVM 11 target) has no resolver hook — no OkHttp `Dns`, and
 * `InetAddressResolverProvider` needs JDK 18 and is process-wide. What it does have is a
 * [ProxySelector], asked once per request. So:
 *
 *  * [select]: an `https` request whose host the system resolves fine (or not at all a
 *    public name) goes exactly as before (the default selector — usually DIRECT). Only when
 *    [CoverDns.systemIsUnusable] says the answer is missing or non-routable is it routed to
 *    the in-process [Tunnel];
 *  * the [Tunnel] is a loopback-only HTTP CONNECT endpoint. It dials the address
 *    [CoverDns.lookup] gives (DoH) and then only copies bytes. TLS stays end to end between
 *    the client and `host:443`: SNI, Host, certificate and hostname checks are the client's
 *    own, unchanged. A lying resolver therefore cannot redirect OSHI anywhere that would be
 *    accepted.
 *
 * The tunnel only accepts `CONNECT` to a host [select] routed to it in this process, port
 * 443, so another local program cannot use it as an open proxy.
 */
class DnsFallbackRoute(
    private val dns: CoverDns = CoverDns.DEFAULT,
    private val delegate: () -> ProxySelector? = { ProxySelector.getDefault() },
) : ProxySelector() {

    private val routed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var tunnel: Tunnel? = null

    /** Requests sent through the tunnel — for tests and diagnostics. */
    internal val tunnelled = java.util.concurrent.atomic.AtomicInteger()

    override fun select(uri: URI): List<Proxy> {
        val host = uri.host
        if (uri.scheme.equals("https", ignoreCase = true) && host != null && dns.systemIsUnusable(host)) {
            routed += host.lowercase()
            tunnelled.incrementAndGet()
            return listOf(Proxy(Proxy.Type.HTTP, tunnel().address))
        }
        return delegate()?.select(uri) ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegate()?.let { d -> if (uri != null && sa != null && ioe != null) runCatching { d.connectFailed(uri, sa, ioe) } }
    }

    private fun tunnel(): Tunnel = tunnel ?: synchronized(this) {
        tunnel ?: Tunnel({ h, p -> p == 443 && h.lowercase() in routed }, dns::lookup).also { tunnel = it }
    }

    /**
     * Loopback HTTP CONNECT endpoint. One daemon thread accepts; each tunnel uses two
     * daemon threads copying bytes until either side closes.
     */
    class Tunnel(
        private val allowed: (host: String, port: Int) -> Boolean,
        private val resolve: (String) -> List<InetAddress>,
        private val connectTimeoutMs: Int = 15_000,
    ) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val address: InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)

        init {
            Thread({ acceptLoop() }, "oshi-dns-fallback-tunnel").apply { isDaemon = true }.start()
        }

        fun close() = runCatching { server.close() }

        private fun acceptLoop() {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: IOException) { return }
                Thread({ serve(c) }, "oshi-dns-fallback-conn").apply { isDaemon = true }.start()
            }
        }

        private fun serve(client: Socket) {
            var upstream: Socket? = null
            try {
                client.soTimeout = 30_000
                val input = client.getInputStream()
                val head = readHead(input) ?: return client.close()
                val first = head.lineSequence().first().trim().split(' ')
                if (first.size < 2 || first[0] != "CONNECT") return reply(client, "405 Method Not Allowed")
                val target = first[1]
                val colon = target.lastIndexOf(':')
                val host = target.substring(0, maxOf(colon, 0)).removePrefix("[").removeSuffix("]")
                val port = target.substring(colon + 1).toIntOrNull() ?: -1
                if (colon <= 0 || !allowed(host, port)) return reply(client, "403 Forbidden")
                val addrs = try { resolve(host) } catch (_: Exception) { emptyList() }
                for (a in addrs) {
                    val s = Socket()
                    try { s.connect(InetSocketAddress(a, port), connectTimeoutMs); upstream = s; break }
                    catch (_: IOException) { runCatching { s.close() } }
                }
                val up = upstream ?: return reply(client, "502 Bad Gateway")
                client.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
                client.soTimeout = 0
                val t = Thread({ pump(up.getInputStream(), client.getOutputStream(), client, up) }, "oshi-dns-fallback-down")
                t.isDaemon = true; t.start()
                pump(input, up.getOutputStream(), client, up)
            } catch (_: Exception) {
                runCatching { client.close() }; runCatching { upstream?.close() }
            }
        }

        private fun reply(c: Socket, status: String) {
            runCatching { c.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)) }
            runCatching { c.close() }
        }

        /** The request head (up to the blank line), bounded at 8 KiB, read byte by byte so no tunnel byte is consumed. */
        private fun readHead(input: InputStream): String? {
            val out = java.io.ByteArrayOutputStream()
            var last4 = 0
            while (out.size() < 8192) {
                val b = input.read()
                if (b < 0) return null
                out.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) return String(out.toByteArray(), Charsets.ISO_8859_1)
            }
            return null
        }

        private fun pump(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n); to.flush()
                }
            } catch (_: IOException) {
            } finally {
                runCatching { a.close() }; runCatching { b.close() }
            }
        }
    }

    companion object {
        /** The one route every signed V2 client shares (a single tunnel per process). */
        val DEFAULT: DnsFallbackRoute by lazy { DnsFallbackRoute() }
    }
}
