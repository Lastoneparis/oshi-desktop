package com.oshi.desktop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * __TLS_LOOK_2026_11_19__ The relay-facing clients send the Android (Conscrypt + OkHttp
 * cipher list) ClientHello, read off the wire by a local socket that never answers.
 */
class TlsLookTest {
    @Test fun `conscrypt is active on this host`() {
        assertTrue("fallback: ${TlsLook.unavailableReason}", TlsLook.active)
        assertNull(TlsLook.unavailableReason)
    }

    @Test fun `the hello carries OkHttp's 15 ciphers in order, Android's groups and extensions`() {
        val hello = captureHello { port -> NetClients.builder().connectTimeout(Duration.ofSeconds(3)).build() to "https://localhost:$port/" }
        val h = parseHello(hello)
        assertEquals(listOf(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02c, 0xcca9, 0xc02f, 0xc030, 0xcca8, 0xc013, 0xc014, 0x9c, 0x9d, 0x2f, 0x35), h.ciphers)
        assertEquals(listOf(0x1d, 0x17, 0x18), h.groups)
        // Android 15 OkHttp/Conscrypt order, minus BoringSSL's padding (21), see TlsLook.
        assertEquals(listOf(0, 23, 65281, 10, 11, 35, 16, 5, 13, 51, 45, 43), h.extensions)
        assertEquals(listOf("h2", "http/1.1"), h.alpn)
    }

    @Test fun `the fresh short-lived twin of a client keeps the look`() {
        val base = NetClients.builder().build()
        val hello = captureHello { port -> NetClients.builderLike(base).connectTimeout(Duration.ofSeconds(3)).build() to "https://localhost:$port/" }
        val h = parseHello(hello)
        assertEquals(15, h.ciphers.size)
        // JDK 20+: the twin's copied SSLParameters must still carry the groups, or Conscrypt
        // falls back to its default (X25519MLKEM768 first).
        assertEquals(listOf(0x1d, 0x17, 0x18), h.groups)
    }

    private fun captureHello(make: (Int) -> Pair<HttpClient, String>): ByteArray {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { ss ->
            val (client, url) = make(ss.localPort)
            val t = Thread { runCatching { client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding()) } }
            t.isDaemon = true; t.start()
            ss.accept().use { s ->
                s.soTimeout = 5000
                val inp = s.getInputStream()
                val head = ByteArray(5); var n = 0
                while (n < 5) n += inp.read(head, n, 5 - n)
                val len = ((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)
                val body = ByteArray(len); n = 0
                while (n < len) n += inp.read(body, n, len - n)
                return body
            }
        }
    }

    private class Hello(val ciphers: List<Int>, val extensions: List<Int>, val groups: List<Int>, val alpn: List<String>)

    private fun parseHello(b: ByteArray): Hello {
        fun u16(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        var p = 4 + 2 + 32
        p += 1 + (b[p].toInt() and 0xff)
        val cl = u16(p); p += 2
        val ciphers = (0 until cl step 2).map { u16(p + it) }; p += cl
        p += 1 + (b[p].toInt() and 0xff)
        val end = p + 2 + u16(p); p += 2
        val exts = ArrayList<Int>(); var groups = emptyList<Int>(); val alpn = ArrayList<String>()
        while (p < end) {
            val t = u16(p); val l = u16(p + 2); val d = p + 4
            exts += t
            if (t == 10) groups = (0 until u16(d) step 2).map { u16(d + 2 + it) }
            if (t == 16) { var q = d + 2; while (q < d + l) { val k = b[q].toInt() and 0xff; alpn += String(b, q + 1, k); q += 1 + k } }
            p = d + l
        }
        return Hello(ciphers, exts, groups, alpn)
    }
}
