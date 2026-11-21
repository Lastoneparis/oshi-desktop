package com.oshi.desktop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException

/** __COVER_DNS_FALLBACK_2026_11_19__ desktop twin of Android's CoverDnsTest, plus the tunnel. */
class CoverDnsTest {
    private val real = InetAddress.getByName("45.67.216.197")
    private val other = InetAddress.getByName("93.184.216.34")
    private val block = InetAddress.getByName("10.10.34.34")

    @Test fun `an honest answer is used as is and DoH is never asked`() {
        var asked = 0
        val d = CoverDns(system = { listOf(real) }, doh = { asked++; listOf(other) })
        assertEquals(listOf(real), d.lookup("oshi-messenger.com"))
        assertEquals(false, d.systemIsUnusable("oshi-messenger.com"))
        assertEquals(0, asked)
    }

    @Test fun `a block-page answer (10_10_34_34) falls back to DoH`() {
        val d = CoverDns(system = { listOf(block) }, doh = { listOf(real) })
        assertTrue(d.systemIsUnusable("oshi-messenger.com"))
        assertEquals(listOf(real), d.lookup("oshi-messenger.com"))
    }

    @Test fun `NXDOMAIN falls back to DoH`() {
        val d = CoverDns(system = { throw UnknownHostException(it) }, doh = { listOf(real) })
        assertTrue(d.systemIsUnusable("oshi-messenger.com"))
        assertEquals(listOf(real), d.lookup("oshi-messenger.com"))
    }

    @Test fun `a mixed answer keeps only the routable addresses, no DoH`() {
        var asked = 0
        val d = CoverDns(system = { listOf(block, real) }, doh = { asked++; emptyList() })
        assertEquals(listOf(real), d.lookup("oshi-messenger.com"))
        assertEquals(0, asked)
    }

    @Test fun `dead DoH is retried at most once a minute and the system error is kept`() {
        var now = 1_000_000L; var asked = 0
        val d = CoverDns(system = { throw UnknownHostException(it) }, doh = { asked++; throw java.io.IOException("down") }, clock = { now })
        repeat(5) { try { d.lookup("oshi-messenger.com"); fail() } catch (_: UnknownHostException) { } }
        assertEquals(1, asked)
        now += CoverDns.NEGATIVE_TTL_MS + 1
        try { d.lookup("oshi-messenger.com"); fail() } catch (_: UnknownHostException) { }
        assertEquals(2, asked)
    }

    @Test fun `a DoH answer is cached five minutes`() {
        var now = 0L; var asked = 0
        val d = CoverDns(system = { listOf(block) }, doh = { asked++; listOf(real) }, clock = { now })
        repeat(4) { d.lookup("oshi-messenger.com") }
        assertEquals(1, asked)
        now += CoverDns.POSITIVE_TTL_MS + 1
        d.lookup("oshi-messenger.com")
        assertEquals(2, asked)
    }

    @Test fun `DoH answers that are themselves non-routable are refused`() {
        val d = CoverDns(system = { throw UnknownHostException(it) }, doh = { listOf(block) })
        try { d.lookup("oshi-messenger.com"); fail() } catch (_: UnknownHostException) { }
    }

    @Test fun `local names and literals are never second-guessed`() {
        var asked = 0
        val d = CoverDns(system = { listOf(InetAddress.getLoopbackAddress()) }, doh = { asked++; listOf(real) })
        for (h in listOf("localhost", "relay.local", "box.lan", "printer", "127.0.0.1", "::1")) {
            assertEquals(false, d.systemIsUnusable(h)); d.lookup(h)
        }
        assertEquals(0, asked)
    }

    @Test fun `DoH JSON is parsed strictly`() {
        val ok = """{"Status":0,"Answer":[{"name":"oshi-messenger.com","type":5,"data":"x.example."},{"type":1,"data":"45.67.216.197"},{"type":1,"data":"<script>"}]}"""
        assertEquals(listOf("45.67.216.197"), CoverDns.parseDnsJson("oshi-messenger.com", ok).map { it.hostAddress })
        assertEquals("oshi-messenger.com", CoverDns.parseDnsJson("oshi-messenger.com", ok)[0].hostName)
        assertEquals(emptyList<InetAddress>(), CoverDns.parseDnsJson("x", """{"Status":3}"""))
        val v6 = """{"Status":0,"Answer":[{"type":28,"data":"2a02:c207:2284:3891::1"}]}"""
        assertEquals(1, CoverDns.parseDnsJson("x", v6).size)
    }

    @Test fun `the route leaves an honest host alone and tunnels a poisoned one`() {
        val honest = DnsFallbackRoute(CoverDns(system = { listOf(real) }, doh = { emptyList() }), delegate = { null })
        assertEquals(listOf(Proxy.NO_PROXY), honest.select(URI("https://oshi-messenger.com/v2/collect")))
        val poisoned = DnsFallbackRoute(CoverDns(system = { listOf(block) }, doh = { listOf(real) }), delegate = { null })
        val p = poisoned.select(URI("https://oshi-messenger.com/v2/collect")).single()
        assertEquals(Proxy.Type.HTTP, p.type())
        assertTrue((p.address() as InetSocketAddress).address.isLoopbackAddress)
        // Plain http (a loopback test relay) is never re-routed.
        assertEquals(listOf(Proxy.NO_PROXY), poisoned.select(URI("http://oshi-messenger.com/v2/collect")))
    }

    @Test fun `the tunnel dials the DoH address, copies bytes both ways, and refuses other targets`() {
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { upstream ->
            Thread {
                upstream.accept().use { s ->
                    val b = ByteArray(5); var n = 0
                    while (n < 5) n += s.getInputStream().read(b, n, 5 - n)
                    s.getOutputStream().write(b.reversedArray()); s.getOutputStream().flush()
                }
            }.apply { isDaemon = true }.start()
            val t = DnsFallbackRoute.Tunnel({ h, p -> h == "oshi-messenger.com" && p == 443 },
                { listOf(InetAddress.getLoopbackAddress()) }, connectTimeoutMs = 2000)
            // The test cannot listen on 443: map the allowed port onto the upstream's.
            val t2 = DnsFallbackRoute.Tunnel({ h, p -> h == "oshi-messenger.com" && p == upstream.localPort },
                { listOf(InetAddress.getLoopbackAddress()) }, connectTimeoutMs = 2000)
            Socket(t2.address.address, t2.address.port).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("CONNECT oshi-messenger.com:${upstream.localPort} HTTP/1.1\r\nHost: oshi-messenger.com\r\n\r\n".toByteArray())
                val head = readLine(c)
                assertTrue(head, head.startsWith("HTTP/1.1 200"))
                readLine(c) // blank
                c.getOutputStream().write("abcde".toByteArray())
                val back = ByteArray(5); var n = 0
                while (n < 5) n += c.getInputStream().read(back, n, 5 - n)
                assertEquals("edcba", String(back))
            }
            Socket(t.address.address, t.address.port).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("CONNECT evil.example:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(readLine(c).startsWith("HTTP/1.1 403"))
            }
            Socket(t.address.address, t.address.port).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("GET http://oshi-messenger.com/ HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(readLine(c).startsWith("HTTP/1.1 405"))
            }
            t.close(); t2.close()
        }
    }

    private fun readLine(s: Socket): String {
        val sb = StringBuilder()
        while (true) {
            val b = s.getInputStream().read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
