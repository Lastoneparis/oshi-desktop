package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.ohttp.Bhttp
import com.oshi.messenger.network.ohttp.Ohttp
import com.oshi.messenger.network.ohttp.Ohttp.hex
import com.oshi.messenger.network.ohttp.OhttpConfig
import com.oshi.messenger.service.CoverCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * __RELAY_FALLBACK_2026_11_19__ + __BLOB_RANGE_2026_11_19__ (docs/CENSORSHIP_2026_THREAT_MODEL.md
 * §7, §9). Desktop twins of Android's RelayFallbackTest and BlobRangeTest, plus the ranged
 * chunk download end to end against a server that implements §7.2.
 */
class RelayFallbackAndRangeTest {
    private val skR = hex("3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a")
    private val pkR = hex("31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155")
    private val keyConfigBytes = hex("01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155" + "0008" + "00010001" + "00010003")
    private var now = 1_000_000L
    private val identity = DesktopIdentity.generate()
    private val signer = DesktopV2Signer(identity)

    @Before fun setUp() {
        RelayFallback.clock = { now }; DesktopCover.ShortConnections.clock = { now }
        RelayFallback.reset(); DesktopCover.ShortConnections.reset()
    }

    @After fun tearDown() {
        RelayFallback.overrideForTests(null); RelayFallback.clock = System::currentTimeMillis
        DesktopCover.ShortConnections.reset(); DesktopCover.ShortConnections.clock = System::currentTimeMillis
    }

    // ------------------------------------------------------------------ BlobRange

    @Test fun `parts, old server and failures are judged like Android`() {
        assertEquals(BlobRange.Result.Part(2688, false), BlobRange.accept(206, "bytes 0-2687/12000", 0, 2688))
        assertEquals(BlobRange.Result.Part(12000, true), BlobRange.accept(206, "bytes 10752-11999/12000", 10752, 1248))
        assertEquals(BlobRange.Result.Whole, BlobRange.accept(200, null, 0, 12000))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(206, "bytes 5-10/12000", 0, 6))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(206, "bytes 0-9/12000", 0, 9))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(206, null, 0, 10))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(416, "bytes */12000", 12000, 0))
        assertEquals(BlobRange.Result.Fail, BlobRange.accept(404, null, 0, 0))
        assertEquals(BlobRange.Result.Part(10, true), BlobRange.accept(206, "BYTES 0-9/10", 0, 10))
    }

    @Test fun `a cover part fits the smallest bucket`() {
        val body = Base64.getEncoder().encodeToString(ByteArray(BlobRange.COVER_PART))
        val record = JSONObject().put("v", 1).put("status", 206).put("headers", JSONObject()
            .put("content-type", "application/octet-stream").put("content-length", BlobRange.COVER_PART)
            .put("content-range", "bytes 2097152-2099839/2097168")).put("body", body)
        val size = record.toString().toByteArray().size
        assertTrue("record $size B", size + 4 <= 4096)
        assertEquals(4096, CoverCodec.minimalBucket(size))
    }

    @Test fun `on a capped network a chunk arrives in signed parts, byte exact`() {
        RangeServer(ByteArray(30_000).also { SecureRandom().nextBytes(it) }).use { s ->
            val blobs = V2BlobClient(V2Http(signer, s.base, ohttp = { null }, cover = { false }, fallback = { null }), identity)
            DesktopCover.ShortConnections.onFailure(SocketTimeoutException("read timed out"))   // capped window open
            assertArrayEquals(s.chunk, blobs.downloadChunk("b1", 0))
            val q = s.queries.toList()
            assertEquals(listOf("offset=0&length=12288", "offset=12288&length=12288", "offset=24576&length=12288"), q)
            assertEquals("every part carried a signature over the bare path", 3, s.signedPaths.count { it == "/v2/blobs/b1/chunk/0" })
        }
    }

    @Test fun `a whole-chunk read that stalls is retried in parts at once`() {
        RangeServer(ByteArray(40_000).also { SecureRandom().nextBytes(it) }, cutWholeAfter = 16_384).use { s ->
            val blobs = V2BlobClient(V2Http(signer, s.base, ohttp = { null }, cover = { false }, fallback = { null }), identity)
            assertArrayEquals(s.chunk, blobs.downloadChunk("b1", 0))
            assertTrue(DesktopCover.ShortConnections.active())
            assertEquals("", s.queries.first())
            assertEquals(5, s.queries.size)   // whole (cut) + 4 parts of 12288
        }
    }

    @Test fun `a part lost on the way is retried, not the whole chunk`() {
        RangeServer(ByteArray(120_000).also { SecureRandom().nextBytes(it) }, dropEvery = 4).use { s ->
            val blobs = V2BlobClient(V2Http(signer, s.base, ohttp = { null }, cover = { false }, fallback = { null }), identity)
            DesktopCover.ShortConnections.onFailure(SocketTimeoutException("read timed out"))
            assertArrayEquals(s.chunk, blobs.downloadChunk("b1", 0))
            assertTrue("10 parts + retries: ${s.queries.size}", s.queries.size in 11..14)
        }
    }

    @Test fun `an old server answering 200 to a ranged read is used as the whole chunk`() {
        RangeServer(ByteArray(20_000).also { SecureRandom().nextBytes(it) }, supportsRanges = false).use { s ->
            val blobs = V2BlobClient(V2Http(signer, s.base, ohttp = { null }, cover = { false }, fallback = { null }), identity)
            DesktopCover.ShortConnections.onFailure(SocketTimeoutException("read timed out"))
            assertArrayEquals(s.chunk, blobs.downloadChunk("b1", 0))
            assertEquals(1, s.queries.size)
        }
    }

    @Test fun `a part whose range does not match the request fails the chunk`() {
        RangeServer(ByteArray(20_000), lie = true).use { s ->
            val blobs = V2BlobClient(V2Http(signer, s.base, ohttp = { null }, cover = { false }, fallback = { null }), identity)
            DesktopCover.ShortConnections.onFailure(SocketTimeoutException("read timed out"))
            assertNull(blobs.downloadChunk("b1", 0))
        }
    }

    // ------------------------------------------------------------------ RelayFallback

    @Test fun `the built-in fallback is the OSHI-operated relay, not the privacy relay default`() {
        assertEquals("https://foodies.agency/api/v1/batch", RelayFallback.RELAY_URL)
        assertEquals("", OhttpConfig.RELAY_URL)
        val t = RelayFallback.transport()!!
        assertEquals("foodies.agency", t.relayUrl.host)
        assertEquals(1, t.keyConfig.keyId)
        assertTrue(DesktopOhttp.isEligible(URI("https://oshi-messenger.com/v2/config"), t))
        assertFalse(DesktopOhttp.isEligible(URI("https://oshi-messenger.com/api/x"), t))
    }

    @Test fun `connect-phase failures activate at once, statuses never`() {
        assertTrue(RelayFallback.isConnectPhase(ConnectException("refused")))
        assertTrue(RelayFallback.isConnectPhase(IOException("x", javax.net.ssl.SSLHandshakeException("reset"))))
        assertTrue(RelayFallback.isConnectPhase(java.net.http.HttpConnectTimeoutException("HTTP connect timed out")))
        assertFalse(RelayFallback.isConnectPhase(SocketTimeoutException("timeout")))
        assertFalse(RelayFallback.onDirectFailure(IOException("Obfuscated transport HTTP 403")))
        assertFalse(RelayFallback.onDirectFailure(IOException("Obfuscated transport HTTP 403")))
        assertFalse(RelayFallback.active())
        assertTrue(RelayFallback.onDirectFailure(ConnectException("refused")))
        assertTrue(RelayFallback.active())
    }

    @Test fun `a blocked host moves to the relay at once, retries that call through it, and probes direct after the window`() {
        FakeRelay(skR, pkR).use { relay ->
            val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).let { val p = it.localPort; it.close(); p }
            val origin = "http://127.0.0.1:$dead"
            val t = RelayFallback.localTransport(relay.url, Ohttp.toHex(byteArrayOf(0, keyConfigBytes.size.toByte()) + keyConfigBytes), origin)!!
            val http = V2Http(signer, origin, ohttp = { null }, cover = { false }, fallback = { t })
            val r = http.get("/v2/messages/abc", query = "after=0")
            assertEquals("refused -> same call answered via the relay", 200, r.code)
            assertTrue(r.body.contains("relay"))
            assertTrue(RelayFallback.active())
            val inner = relay.inner.single()
            assertEquals("oshi-messenger.com", inner.first)
            assertEquals("/v2/messages/abc?after=0", inner.second)
            val directBefore = http.directSends.get()
            assertEquals(200, http.get("/v2/keys/x").code)
            assertEquals("while active, direct is not tried", directBefore, http.directSends.get())
            now += RelayFallback.WINDOW_MS + 1
            assertFalse(RelayFallback.active())
            assertEquals(200, http.get("/v2/keys/y").code)   // direct refused again -> relay again
            assertEquals(directBefore + 1, http.directSends.get())
        }
    }

    @Test fun `one transient reset does not move traffic, two in a row do`() {
        FakeRelay(skR, pkR).use { relay ->
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { ss ->
                Thread { while (!ss.isClosed) runCatching { ss.accept().use { s -> s.getInputStream().read(ByteArray(4096)) } } }.apply { isDaemon = true }.start()
                val origin = "http://127.0.0.1:${ss.localPort}"
                val t = RelayFallback.localTransport(relay.url, Ohttp.toHex(byteArrayOf(0, keyConfigBytes.size.toByte()) + keyConfigBytes), origin)!!
                val http = V2Http(signer, origin, ohttp = { null }, cover = { false }, fallback = { t })
                assertEquals(-1, http.get("/v2/keys/a").code)
                assertEquals(0, relay.inner.size)
                assertFalse(RelayFallback.active())
                assertEquals("2nd reset in a row: answered by the relay", 200, http.get("/v2/keys/b").code)
                assertTrue(RelayFallback.active())
            }
        }
    }

    @Test fun `the user's own privacy relay keeps precedence and never uses the fallback`() {
        var asked = 0
        FakeRelay(skR, pkR).use { relay ->
            val user = DesktopOhttpTransport(URI.create(relay.url), Ohttp.parseKeyConfig(keyConfigBytes)!!)
            val http = V2Http(signer, "https://oshi-messenger.com", ohttp = { user }, cover = { false }, fallback = { asked++; null })
            assertEquals(200, http.get("/v2/config").code)
            assertEquals(0, asked)
        }
    }

    // ------------------------------------------------------------------ frozen body

    /**
     * netem `tspu16k`, 19/11: the cap froze a reply after its headers; the JDK request timeout
     * stops at the headers, so the desktop's poll thread blocked forever (1/8 texts received).
     */
    @Test fun `a reply frozen mid-body fails at the deadline, direct and through the cover`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { ss ->
            Thread {
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    Thread {
                        runCatching {
                            val inp = s.getInputStream(); val head = StringBuilder()
                            while (!head.endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) return@runCatching; head.append(c.toChar()) }
                            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10000\r\n\r\n{\"v\":".toByteArray())
                            s.getOutputStream().flush()
                            Thread.sleep(120_000)
                        }
                        runCatching { s.close() }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            val base = "http://127.0.0.1:${ss.localPort}"
            val direct = V2Http(signer, base, readTimeout = java.time.Duration.ofSeconds(2), ohttp = { null }, cover = { false }, fallback = { null })
            var t0 = System.currentTimeMillis()
            assertEquals(-1, direct.get("/v2/keys/a").code)
            assertTrue("direct freed in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 10_000)
            assertTrue(DesktopCover.ShortConnections.active())
            DesktopCover.ShortConnections.reset()
            val covered = V2Http(signer, base, readTimeout = java.time.Duration.ofSeconds(2), ohttp = { null }, cover = { true }, fallback = { null })
            t0 = System.currentTimeMillis()
            assertEquals(-1, covered.get("/v2/keys/a").code)
            assertTrue("cover freed in ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 30_000)
            assertTrue(DesktopCover.ShortConnections.active())
        }
    }

    // ------------------------------------------------------------------ fakes

    /** Serves one chunk at /v2/blobs/b1/chunk/0 with §7.2 ranges; can cut a whole read mid-way. */
    private class RangeServer(
        val chunk: ByteArray,
        private val supportsRanges: Boolean = true,
        private val cutWholeAfter: Int = -1,
        private val lie: Boolean = false,
        /** Reset every Nth ranged request before answering (netem loss stand-in); 0 = never. */
        private val dropEvery: Int = 0,
    ) : AutoCloseable {
        private val ranged = AtomicInteger()
        val queries = ConcurrentLinkedQueue<String>()
        val signedPaths = ConcurrentLinkedQueue<String>()
        private val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val base = "http://127.0.0.1:${ss.localPort}"
        init {
            Thread {
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    Thread { runCatching { s.use { serve(it) } } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        private fun serve(s: java.net.Socket) {
            val inp = java.io.BufferedInputStream(s.getInputStream()); val out = s.getOutputStream()
            while (true) {
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val c = inp.read(); if (c < 0) return; head.append(c.toChar()) }
                val target = head.lineSequence().first().split(' ')[1]
                val path = target.substringBefore('?'); val q = if ('?' in target) target.substringAfter('?') else ""
                queries += q
                if (Regex("(?i)x-oshi-signature:").containsMatchIn(head)) signedPaths += path
                val p = q.split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toLong() }
                if (q.isEmpty() || !supportsRanges) {
                    val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${chunk.size}\r\n\r\n".toByteArray()
                    if (q.isEmpty() && cutWholeAfter > 0) { out.write(hdr); out.write(chunk, 0, cutWholeAfter); out.flush(); return }
                    out.write(hdr + chunk); out.flush(); continue
                }
                if (dropEvery > 0 && ranged.incrementAndGet() % dropEvery == 0) return
                val o = p.getValue("offset").toInt(); val n = p.getValue("length").toInt()
                val e = minOf(o + n, chunk.size) - 1
                val start = if (lie) o + 1 else o
                val part = chunk.copyOfRange(o, e + 1)
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\nContent-Length: ${part.size}\r\n" +
                    "Content-Range: bytes $start-${e + (if (lie) 1 else 0)}/${chunk.size}\r\n\r\n").toByteArray() + part)
                out.flush()
            }
        }
        override fun close() { ss.close() }
    }

    /** Relay + gateway in one: opens the RFC 9458 request with the test key, answers 200. */
    private class FakeRelay(skR: ByteArray, pkR: ByteArray) : AutoCloseable {
        val inner = ConcurrentLinkedQueue<Pair<String, String>>()
        private val kp = Ohttp.hpke().deserializePrivateKey(skR, pkR)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String get() = "http://127.0.0.1:${server.address.port}/api/v1/batch"
        val hits = AtomicInteger()
        init { server.createContext("/api/v1/batch") { ex -> handle(ex) }; server.start() }
        private fun handle(ex: HttpExchange) {
            hits.incrementAndGet()
            val req = ex.requestBody.readBytes()
            val header = req.copyOfRange(0, 7); val enc = req.copyOfRange(7, 7 + Ohttp.NPK)
            val ctx = Ohttp.hpke().setupBaseR(enc, kp, Ohttp.requestInfo(header))
            val r = Bhttp.decodeRequest(ctx.open(ByteArray(0), req.copyOfRange(7 + Ohttp.NPK, req.size)))
            inner += r.authority to r.path
            val secret = ctx.export("message/bhttp response".toByteArray(), Ohttp.RESPONSE_NONCE_LEN)
            val out = Bhttp.encodeResponse(Bhttp.Response(200, listOf("content-type" to "application/json"), """{"via":"relay"}""".toByteArray()))
            val bytes = Ohttp.encapsulateResponse(enc, secret, out)
            ex.responseHeaders.add("Content-Type", "message/ohttp-res")
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        override fun close() = server.stop(0)
    }
}
