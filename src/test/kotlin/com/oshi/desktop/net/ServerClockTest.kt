package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.call.CallSignalClient
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * __CALL_CLOCK_SKEW_DESKTOP_2026_11_21__ Offset learning, the 5 s threshold, and which
 * clock a signature carries.
 */
class ServerClockTest {

    private val identity: DesktopIdentity = run {
        val priv = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
        DesktopIdentity(
            OSHICryptoV2.X25519Pair(ByteArray(32), ByteArray(32) { 0xAB.toByte() }),
            priv.encoded, priv.generatePublicKey().encoded,
        )
    }

    @Before fun reset() = ServerClock.resetForTest()
    @After fun resetAfter() = ServerClock.resetForTest()

    // ------------------------------------------------------------ offset learning

    @Test fun `a sample centres the one-second Date on the round-trip midpoint`() {
        // Sent at 1_000, answered at 1_200 → local midpoint 1_100; server says 61_000 (+500).
        assertEquals(60_400L, ServerClock.sampleOffset(61_000, 1_000, 1_200))
    }

    @Test fun `unusable samples are rejected`() {
        assertNull(ServerClock.sampleOffset(0, 1_000, 1_200))
        assertNull(ServerClock.sampleOffset(61_000, 1_200, 1_000))
        assertNull(ServerClock.sampleOffset(61_000, 1_000, 1_000 + ServerClock.MAX_SAMPLE_RTT_MS + 1))
    }

    @Test fun `RFC 1123 Date parses, garbage does not`() {
        assertEquals(784_111_777_000L, ServerClock.parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertNull(ServerClock.parseHttpDate(null))
        assertNull(ServerClock.parseHttpDate("yesterday"))
    }

    @Test fun `observing a header records the offset, a bad header changes nothing`() {
        ServerClock.observeHeader("Sun, 06 Nov 1994 08:49:37 GMT", 784_111_777_000L - 120_000, 784_111_777_000L - 120_000)
        assertEquals(120_500L, ServerClock.rawOffsetMs())
        ServerClock.observeHeader("not a date", 1, 2)
        assertEquals(120_500L, ServerClock.rawOffsetMs())
    }

    // ------------------------------------------------------------ the 5 s threshold

    @Test fun `below 5 s the local clock is used untouched`() {
        assertEquals(0L, ServerClock.effectiveOffset(4_999))
        assertEquals(0L, ServerClock.effectiveOffset(-4_999))
        assertEquals(5_000L, ServerClock.effectiveOffset(5_000))
        assertEquals(-5_000L, ServerClock.effectiveOffset(-5_000))
    }

    @Test fun `nowMs is the local clock before any sample and for a healthy clock`() {
        assertEquals(1_000_000L, ServerClock.nowMs(1_000_000L))
        ServerClock.setOffsetForTest(3_000)
        assertEquals(1_000_000L, ServerClock.nowMs(1_000_000L))
        ServerClock.setOffsetForTest(-90_000)
        assertEquals(910_000L, ServerClock.nowMs(1_000_000L))
    }

    // ------------------------------------------------------------ signing choice

    @Test fun `the signer stamps server time only when this PC is 5 s or more off`() {
        val signer = DesktopV2Signer(identity)
        val healthy = signer.sign("GET", "/v2/x", ByteArray(0)).getValue("x-oshi-timestamp").toLong()
        assertTrue(abs(healthy - System.currentTimeMillis()) < 2_000)

        ServerClock.setOffsetForTest(4_000)
        val small = signer.sign("GET", "/v2/x", ByteArray(0)).getValue("x-oshi-timestamp").toLong()
        assertTrue(abs(small - System.currentTimeMillis()) < 2_000)

        ServerClock.setOffsetForTest(-120_000)
        val skewed = signer.sign("GET", "/v2/x", ByteArray(0)).getValue("x-oshi-timestamp").toLong()
        assertTrue(abs(skewed - (System.currentTimeMillis() - 120_000)) < 2_000)

        // An explicit instant (the contract vectors) is never rewritten.
        assertEquals("1756060000000", signer.sign("GET", "/v2/x", ByteArray(0), timestampMs = 1756060000000L)["x-oshi-timestamp"])
    }

    /**
     * End to end: a call server whose clock is 2 minutes ahead of this PC. The first
     * answer teaches the offset; the next signed request carries the server's time, and
     * the timestamp it would have carried before is outside the server's ±30 s.
     */
    @Test fun `the call client learns the offset from Date and then signs on server time`() {
        val ahead = 120_000L
        val stamps = CopyOnWriteArrayList<Long>()
        // A raw HTTP/1.1 server: the JDK HttpServer always writes its own (correct) Date.
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val acceptor = Thread {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                sock.use { so ->
                    val input = so.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val (k, v) = line.split(":", limit = 2).let { if (it.size == 2) it[0].trim() to it[1].trim() else "" to "" }
                        if (k.equals("x-oshi-timestamp", true)) v.toLongOrNull()?.let { stamps += it }
                        if (k.equals("content-length", true)) contentLength = v.toIntOrNull() ?: 0
                    }
                    repeat(contentLength) { input.read() }
                    val date = DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(Instant.ofEpochMilli(System.currentTimeMillis() + ahead).atOffset(ZoneOffset.UTC))
                    val b = "{\"pong\":true}"
                    so.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nDate: $date\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${b.length}\r\nConnection: close\r\n\r\n$b").toByteArray(Charsets.ISO_8859_1),
                    )
                    so.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val c = CallSignalClient("http://127.0.0.1:${ss.localPort}", DesktopV2Signer(identity), apiPrefix = "")
            assertTrue(c.ping())
            assertTrue("learned ≈ +120 s, got ${ServerClock.rawOffsetMs()}", abs(ServerClock.rawOffsetMs() - ahead) < 2_000)
            assertTrue(c.ping())
            assertEquals(2, stamps.size)
            val serverNow = System.currentTimeMillis() + ahead
            assertTrue("first request signed on the raw (wrong) clock", serverNow - stamps[0] > 30_000)
            assertTrue("second request signed on server time", abs(serverNow - stamps[1]) < 3_000)
        } finally {
            ss.close()
            acceptor.join(2_000)
        }
    }
}
