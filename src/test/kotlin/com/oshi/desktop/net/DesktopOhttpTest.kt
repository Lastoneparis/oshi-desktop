package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.ohttp.Bhttp
import com.oshi.messenger.network.ohttp.Ohttp
import com.oshi.messenger.network.ohttp.Ohttp.hex
import com.oshi.messenger.network.ohttp.Ohttp.toHex
import com.oshi.messenger.network.ohttp.OhttpConfig
import com.oshi.desktop.store.PrivacyRelayPreferences
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * __OHTTP_DESKTOP_2026_11_10__ The desktop's Oblivious HTTP client.
 *
 *  1. RFC 9458 Appendix A, byte for byte, through the SHARED `Ohttp.kt` (the same vector
 *     Android's `OhttpTest` runs; `BhttpTest` itself is compiled here unchanged).
 *  2. A /v2/blobs round trip (reserve → PUT chunk → status → commit → GET chunk → DELETE)
 *     through a local fake relay+gateway that opens each request with the gateway's private
 *     key, VERIFIES the x-oshi signature over the inner request, and serves it.
 *  3. OFF = direct; ON + relay failure = the call fails and nothing goes direct.
 */
class DesktopOhttpTest {

    // ---- RFC 9458 Appendix A ---------------------------------------------------------
    private val skR = hex("3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a")
    private val pkR = hex("31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155")
    private val keyConfigBytes = hex(
        "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155" + "0008" + "00010001" + "00010003",
    )
    private val bhttpRequest = hex("00034745540568747470730b6578616d706c652e636f6d012f")
    private val skE = hex("bc51d5e930bda26589890ac7032f70ad12e4ecb37abb1b65b1256c9c48999c73")
    private val pkE = hex("4b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472")
    private val encapsulatedRequest = hex(
        "010020000100014b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472" +
            "6374e469135906992e1268c594d2a10c695d858c40a026e7965e7d86b83dd440b2c0185204b4d63525",
    )
    private val bhttpResponse = hex("0140c8")
    private val encapsulatedResponse = hex("c789e7151fcba46158ca84b04464910d86f9013e404feea014e7be4a441f234f857fbd")

    @Test fun keyConfigParsesAndSelectsTheAes128Suite() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        assertEquals(1, cfg.keyId)
        assertArrayEquals(pkR, cfg.publicKey)
        assertEquals("01002000010001", toHex(cfg.header()))
    }

    @Test fun requestEncapsulationMatchesRfc9458AppendixA() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val ctx = Ohttp.encapsulateRequest(cfg, bhttpRequest, Ohttp.hpke().deserializePrivateKey(skE, pkE))
        assertEquals(toHex(encapsulatedRequest), toHex(ctx.encapsulatedRequest))
    }

    @Test fun responseDecapsulationMatchesRfc9458AppendixA() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val ctx = Ohttp.encapsulateRequest(cfg, bhttpRequest, Ohttp.hpke().deserializePrivateKey(skE, pkE))
        assertArrayEquals(bhttpResponse, Ohttp.decapsulateResponse(ctx, encapsulatedResponse))
        val bad = encapsulatedResponse.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { Ohttp.decapsulateResponse(ctx, bad); fail("tampered response accepted") } catch (_: IOException) {}
    }

    @Test fun pinnedProductionKeyIsKeyId1AndRelayIsStillUnset() {
        val cfg = Ohttp.selectFromKeysMedia(hex(OhttpConfig.PINNED_KEYS_HEX))!!
        assertEquals(1, cfg.keyId)
        assertEquals("c93f9075d86e2e7dca66b2e3309d584486ef53f7d6dca5cbff3d4ccf87cda019", toHex(cfg.publicKey))
        // Today no independent relay is contracted: the feature is unavailable and nothing routes.
        assertEquals("", OhttpConfig.RELAY_URL)
        assertFalse(DesktopOhttp.isAvailable())
        assertNull(DesktopOhttp.transport())
        assertNotNull(DesktopOhttp.build("https://relay.example/ohttp", OhttpConfig.PINNED_KEYS_HEX))
        assertNull(DesktopOhttp.build("https://relay.example/ohttp", "zz"))
    }

    @Test fun eligibilityIncludesBlobsAndNothingOutsideV2() {
        val t = DesktopOhttp.build("https://relay.example/ohttp", OhttpConfig.PINNED_KEYS_HEX)!!
        fun e(u: String) = DesktopOhttp.isEligible(URI.create(u), t)
        assertTrue(e("https://oshi-messenger.com/v2/messages/abc?after=3"))
        assertTrue(e("https://oshi-messenger.com/v2/blobs"))
        assertTrue(e("https://oshi-messenger.com/v2/blobs/x/chunk/0"))
        assertTrue(e("https://oshi-messenger.com/v2/config"))
        assertFalse(e("https://oshi-messenger.com/api/pending/x"))
        assertFalse(e("https://oshi-messenger.com/push/send-push"))
        assertFalse(e("https://evil.example/v2/messages"))
        assertFalse(e("http://oshi-messenger.com/v2/messages"))
        assertFalse(e("https://oshi-messenger.com:8443/v2/messages"))
    }

    @Test fun preferenceIsOffByDefaultAndPersists() {
        val dir = Files.createTempDirectory("oshi-ohttp-pref").toFile()
        try {
            val f = File(dir, PrivacyRelayPreferences.FILE_NAME)
            assertFalse(PrivacyRelayPreferences(f).load())
            PrivacyRelayPreferences(f).save(true)
            assertTrue(PrivacyRelayPreferences(f).load())
            f.writeText("{not json")
            assertFalse("corrupt reads as OFF", PrivacyRelayPreferences(f).load())
        } finally { dir.deleteRecursively() }
    }

    // ---- end to end through a fake relay + gateway -------------------------------------

    private val gateway = FakeOhttpGateway(Ohttp.hpke().deserializePrivateKey(skR, pkR))
    private val identity = DesktopIdentity.generate()
    private val signer = DesktopV2Signer(identity)
    private val transport = DesktopOhttpTransport(URI.create(gateway.relayUrl), Ohttp.parseKeyConfig(keyConfigBytes)!!)

    @After fun tearDown() = gateway.close()

    @Test fun blobChunkPutAndGetRoundTripThroughEncapsulation() {
        val http = V2Http(signer, "https://oshi-messenger.com", ohttp = { transport })
        val blobs = V2BlobClient(http, identity)
        val chunk = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) } // not valid UTF-8: must survive byte-exact

        val r = blobs.reserve(DesktopIdentity.generate().userKey, chunk.size, 1)
        assertNotNull("reserve via OHTTP", r)
        assertTrue("PUT chunk via OHTTP", blobs.uploadChunk(r!!.blobId, 0, chunk))
        assertEquals(emptyList<Int>(), blobs.status(r.blobId)!!.missing)
        assertTrue("commit via OHTTP", blobs.commit(r.blobId))
        assertArrayEquals("GET chunk via OHTTP is byte-exact", chunk, blobs.downloadChunk(r.blobId, 0))
        assertTrue(blobs.delete(r.blobId))

        assertEquals("nothing left direct", 0, http.directSends.get())
        val inner = gateway.inner.toList()
        assertEquals(
            listOf("POST /v2/blobs", "PUT /v2/blobs/${r.blobId}/chunk/0", "GET /v2/blobs/${r.blobId}/status",
                "POST /v2/blobs/${r.blobId}/commit", "GET /v2/blobs/${r.blobId}/chunk/0", "DELETE /v2/blobs/${r.blobId}"),
            inner.map { "${it.method} ${it.path}" },
        )
        assertTrue("every inner request carried a signature that VERIFIED", inner.all { it.signatureVerified })
        assertTrue(inner.all { it.authority == "oshi-messenger.com" && it.scheme == "https" })
        // What the relay saw: only POST message/ohttp-req, nothing identifying.
        val outer = gateway.outer.toList()
        assertEquals(6, outer.size)
        assertTrue(outer.all { it.method == "POST" && it.contentType == Ohttp.REQUEST_MEDIA_TYPE })
        assertTrue(outer.none { h -> h.headerNames.any { it.startsWith("x-oshi") } })
    }

    @Test fun signedJsonCallKeepsQueryAndBodyInsideTheInnerRequest() {
        val http = V2Http(signer, "https://oshi-messenger.com", ohttp = { transport })
        val resp = http.get("/v2/messages/abc", query = "after=5")
        assertEquals(200, resp.code)
        val seen = gateway.inner.single()
        assertEquals("/v2/messages/abc?after=5", seen.pathWithQuery)
        assertTrue(seen.signatureVerified)
        assertEquals(0, http.directSends.get())
    }

    @Test fun configGateGoesThroughTheRelayToo() {
        val gate = V2ConfigGate("https://oshi-messenger.com", buildNumber = 1_000_000, ohttp = { transport })
        assertTrue(gate.isEnabled(identity.userKey))
        assertEquals("GET /v2/config", gateway.inner.single().let { "${it.method} ${it.path}" })
    }

    @Test fun offMeansDirect() {
        FakeRelay().use { direct ->
            direct.responder = { 200 to "{}" }
            val http = V2Http(signer, direct.baseUrl, ohttp = { null })
            assertEquals(200, http.get("/v2/keys/abc").code)
            assertEquals(1, direct.requests().size)
            assertEquals(1, http.directSends.get())
            assertTrue("relay untouched when OFF", gateway.outer.isEmpty())
        }
    }

    @Test fun onButNotOshiOriginStaysDirect() {
        FakeRelay().use { direct ->
            val http = V2Http(signer, direct.baseUrl, ohttp = { transport }) // self-hosted / loopback relay
            assertEquals(200, http.get("/v2/keys/abc").code)
            assertEquals(1, direct.requests().size)
            assertTrue(gateway.outer.isEmpty())
        }
    }

    @Test fun onAndRelayFailingFailsTheCallAndNeverGoesDirect() {
        gateway.failWith = 502
        val http = V2Http(signer, "https://oshi-messenger.com", ohttp = { transport })
        val blobs = V2BlobClient(http, identity)
        assertFalse(blobs.uploadChunk("blob-1", 0, ByteArray(16)))
        assertNull(blobs.downloadChunk("blob-1", 0))
        val resp = http.get("/v2/keys/abc")
        assertEquals(-1, resp.code)
        assertTrue(resp.body, resp.body.contains("502"))
        assertEquals(3, gateway.outer.size)
        assertEquals("not one direct send", 0, http.directSends.get())
    }

    @Test fun onAndWrongMediaTypeOrKeyMismatchFails() {
        gateway.failWith = -415
        val http = V2Http(signer, "https://oshi-messenger.com", ohttp = { transport })
        assertEquals(-1, http.get("/v2/keys/abc").code)
        gateway.failWith = -422
        val r = http.get("/v2/keys/abc")
        assertEquals(-1, r.code)
        assertTrue(r.body, r.body.contains("KeyMismatch"))
        assertEquals(0, http.directSends.get())
    }

    @Test fun misconfiguredTransportFailsInsteadOfGoingDirect() {
        FakeRelay().use { direct ->
            val http = V2Http(signer, direct.baseUrl, ohttp = { throw IllegalStateException("bad pinned key") })
            assertEquals(-1, http.get("/v2/keys/abc").code)
            assertEquals(0, direct.requests().size)
        }
    }

    // ---- fake relay + gateway ----------------------------------------------------------

    /**
     * Relay and gateway in one: `POST /relay` opens the RFC 9458 request with the gateway's
     * private key, decodes BHTTP, checks the x-oshi signature over the INNER request exactly
     * like the v2 server, serves a tiny in-memory blob store / config / echo, and seals the answer.
     */
    private class FakeOhttpGateway(private val kpR: org.bouncycastle.crypto.AsymmetricCipherKeyPair) : AutoCloseable {
        class Outer(val method: String, val contentType: String?, val headerNames: Set<String>)
        class Inner(val method: String, val scheme: String, val authority: String, val pathWithQuery: String, val signatureVerified: Boolean) {
            val path: String get() = pathWithQuery.substringBefore('?')
        }

        val outer = ConcurrentLinkedQueue<Outer>()
        val inner = ConcurrentLinkedQueue<Inner>()
        /** >0: relay answers that HTTP status. -415: wrong content type. -422: RFC 9458 key-mismatch problem. */
        @Volatile var failWith = 0

        private val chunks = ConcurrentHashMap<String, ByteArray>()
        private val committed = ConcurrentHashMap.newKeySet<String>()
        private val nextId = AtomicInteger(1)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val relayUrl: String get() = "http://127.0.0.1:${server.address.port}/relay"

        init {
            server.createContext("/relay") { ex -> handle(ex) }
            server.start()
        }

        override fun close() = server.stop(0)

        private fun handle(ex: HttpExchange) {
            val body = ex.requestBody.readBytes()
            outer += Outer(ex.requestMethod, ex.requestHeaders.getFirst("Content-Type"),
                ex.requestHeaders.keys.map { it.lowercase() }.toSet())
            when {
                failWith > 0 -> return send(ex, failWith, "text/plain", "bad gateway".toByteArray())
                failWith == -415 -> return send(ex, 200, "text/html", "<html>".toByteArray())
                failWith == -422 -> return send(ex, 422, "application/problem+json",
                    """{"type":"https://iana.org/assignments/http-problem-types#ohttp-key"}""".toByteArray())
            }
            val header = body.copyOfRange(0, 7)
            val enc = body.copyOfRange(7, 7 + Ohttp.NPK)
            val ctx = Ohttp.hpke().setupBaseR(enc, kpR, Ohttp.requestInfo(header))
            val pt = ctx.open(ByteArray(0), body.copyOfRange(7 + Ohttp.NPK, body.size))
            val secret = ctx.export("message/bhttp response".toByteArray(), Ohttp.RESPONSE_NONCE_LEN)
            val req = Bhttp.decodeRequest(pt)
            val (status, ctype, content) = serve(req)
            val sealed = Ohttp.encapsulateResponse(enc, secret,
                Bhttp.encodeResponse(Bhttp.Response(status, listOf("content-type" to ctype), content)))
            send(ex, 200, Ohttp.RESPONSE_MEDIA_TYPE, sealed)
        }

        private fun serve(req: Bhttp.Request): Triple<Int, String, ByteArray> {
            val path = req.path.substringBefore('?')
            val headers = req.headers.toMap()
            val segs = path.removePrefix("/v2/blobs").trim('/').split('/').filter { it.isNotEmpty() }
            val isBlob = path == "/v2/blobs" || path.startsWith("/v2/blobs/")
            // Which bytes this route hashes: JSON POST = the body; commit/plain GET = 0 bytes;
            // verify-first blob routes (PUT chunk, GET chunk, status, DELETE) = `""`.
            val bodyToHash = when {
                req.method == "POST" && path == "/v2/blobs" -> req.content
                req.method == "POST" -> req.content
                isBlob -> DesktopV2Signer.EMPTY_JSON_STRING_BODY
                else -> ByteArray(0)
            }
            val verified = FakeRelay.verifySignature(
                FakeRelay.Recorded(req.method, path, req.path.substringAfter('?', "").ifEmpty { null }, headers, req.content),
                bodyToHash,
            )
            inner += Inner(req.method, req.scheme, req.authority, req.path, verified)
            fun json(code: Int, s: String) = Triple(code, "application/json", s.toByteArray())
            if (path == "/v2/config") return json(200, """{"v2_enabled":true,"rollout_percent":100,"min_build":0}""")
            if (!verified) return json(401, "{}")
            if (!isBlob) return json(200, "{}")
            return when {
                req.method == "POST" && segs.isEmpty() -> {
                    val id = "blob-" + nextId.getAndIncrement()
                    json(200, JSONObject().put("blobId", id).put("chunkSize", 2 * 1024 * 1024)
                        .put("missing", JSONArray(listOf(0))).toString())
                }
                req.method == "PUT" && segs.size == 3 -> { chunks["${segs[0]}/${segs[2]}"] = req.content; json(200, "{}") }
                req.method == "GET" && segs.size == 2 && segs[1] == "status" -> json(200,
                    JSONObject().put("blobId", segs[0]).put("committed", segs[0] in committed)
                        .put("missing", JSONArray(if (chunks.containsKey("${segs[0]}/0")) emptyList() else listOf(0))).toString())
                req.method == "POST" && segs.size == 2 && segs[1] == "commit" -> { committed += segs[0]; json(200, "{}") }
                req.method == "GET" && segs.size == 3 ->
                    chunks["${segs[0]}/${segs[2]}"]?.let { Triple(200, "application/octet-stream", it) } ?: json(404, "{}")
                req.method == "DELETE" && segs.size == 1 -> json(200, "{}")
                else -> json(404, "{}")
            }
        }

        private fun send(ex: HttpExchange, code: Int, type: String, bytes: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }
}
