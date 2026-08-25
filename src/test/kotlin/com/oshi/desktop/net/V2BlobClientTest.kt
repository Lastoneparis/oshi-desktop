package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/v2/blobs` against a real HTTP server that stores and returns raw bytes and checks our
 * signatures the way the relay does — PARITY.md row 0.15.
 *
 * [FakeRelay] (the shared test double for the rest of the transport) cannot be reused
 * as-is here: its responder returns a `String`, and a `String` round-trip of arbitrary
 * AES-GCM ciphertext is lossy (bytes that are not valid UTF-8 get mangled), which would
 * make a byte-exact round-trip test meaningless. [FakeBlobRelay] below is the same
 * pattern — `com.sun.net.httpserver.HttpServer`, and [FakeRelay.verifySignature] is
 * reused for every signature check — but stores chunks as raw `ByteArray` and answers
 * chunk GETs with the raw bytes on the wire.
 */
class V2BlobClientTest {

    private val dir: File = Files.createTempDirectory("oshi-blob-test").toFile()
    private val relay = FakeBlobRelay()
    private val identity = DesktopIdentity.generate()
    private val signer = DesktopV2Signer(identity)
    private val http = V2Http(signer, relay.baseUrl)
    private val client = V2BlobClient(http, identity)

    @After
    fun tearDown() {
        relay.close()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ round trip

    /**
     * encrypt -> upload (reserve/PUT/status/commit) -> download (streaming) -> decrypt,
     * and the bytes that come out the other end are identical to what went in. The
     * plaintext size is deliberately NOT a multiple of the chunk size, so the last,
     * short chunk is exercised too — a boundary Android's own `encryptFile` handles by
     * `minOf(off + chunkSize, bytes.size)`.
     */
    @Test
    fun `round trip - encrypt, upload, download, decrypt gives back identical bytes`() {
        val plaintext = ByteArray(200 * 1024 + 777) { (it % 251).toByte() }
        val enc = OSHICryptoV2.encryptFile(plaintext, "movie.mp4", "video/mp4", chunkSize = 64 * 1024)
        assertTrue("test is pointless with a single chunk", enc.chunks.size > 1)

        val blobId = client.uploadBlob("peer-recipient-key", enc.chunks)
        assertNotNull("uploadBlob failed", blobId)

        val st = client.status(blobId!!)
        assertNotNull(st)
        assertTrue("uploadBlob must leave the blob committed", st!!.committed)
        assertTrue("uploadBlob must leave nothing missing", st.missing.isEmpty())

        val out = File(dir, "out.bin")
        val written = client.downloadAndDecryptToFile(
            blobId, enc.chunks.size, enc.fileKey, enc.fileNonce, enc.manifest, out,
        )
        assertEquals(plaintext.size.toLong(), written)
        assertTrue("decrypted bytes do not match the original plaintext", out.readBytes().contentEquals(plaintext))
    }

    // ------------------------------------------------------------------ signing rules

    @Test
    fun `reserve hashes the exact JSON bytes sent and carries no x-oshi-user`() {
        val res = client.reserve("peer-key", totalSize = 10, chunkCount = 1)
        assertNotNull(res)
        val rec = relay.last()
        assertEquals("POST", rec.method)
        assertEquals("/v2/blobs", rec.path)
        assertNull("reserve must not carry x-oshi-user — the owner travels in the body", rec.userHeader)
        assertTrue("reserve's signature must cover the exact bytes sent",
            FakeRelay.verifySignature(rec, rec.body))
        val body = JSONObject(rec.bodyText)
        assertEquals(identity.userKey, body.getString("ownerKey"))
        assertEquals("peer-key", body.getString("recipientKey"))
    }

    @Test
    fun `uploadChunk hashes the two-byte empty JSON string, not the chunk bytes`() {
        val res = client.reserve("peer-key", totalSize = 5, chunkCount = 1)!!
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(client.uploadChunk(res.blobId, 0, data))

        val rec = relay.last()
        assertEquals("PUT", rec.method)
        assertTrue("the ciphertext must arrive unmodified", rec.body.contentEquals(data))
        assertTrue("upload must hash the 2-byte empty JSON string, not the chunk",
            FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY))
        assertFalse("upload must NOT hash the chunk bytes",
            FakeRelay.verifySignature(rec, data))
        assertEquals("x-oshi-user must ride on the chunk upload", identity.userKey, rec.userHeader)
    }

    @Test
    fun `status hashes the two-byte empty JSON string and reports the real gaps`() {
        val res = client.reserve("peer-key", totalSize = 30, chunkCount = 3)!!
        client.uploadChunk(res.blobId, 1, byteArrayOf(9))

        val st = client.status(res.blobId)
        assertNotNull(st)
        assertEquals(listOf(0, 2), st!!.missing)
        assertFalse(st.committed)

        val rec = relay.last()
        assertEquals("GET", rec.method)
        assertTrue(FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY))
        assertEquals(identity.userKey, rec.userHeader)
    }

    @Test
    fun `commit hashes zero bytes and refuses while a chunk is missing`() {
        val res = client.reserve("peer-key", totalSize = 20, chunkCount = 2)!!
        client.uploadChunk(res.blobId, 0, byteArrayOf(1))
        // chunk 1 is deliberately never uploaded.

        assertFalse("commit must refuse while a chunk is missing", client.commit(res.blobId))
        val rec = relay.last()
        assertEquals("POST", rec.method)
        assertTrue(rec.path.endsWith("/commit"))
        assertTrue("commit must hash the actual (empty) body — zero bytes",
            FakeRelay.verifySignature(rec, ByteArray(0)))
        assertFalse("committing must not have happened", relay.isCommitted(res.blobId) ?: false)

        client.uploadChunk(res.blobId, 1, byteArrayOf(2))
        assertTrue("commit must succeed once every chunk is present", client.commit(res.blobId))
        assertTrue((relay.isCommitted(res.blobId) ?: false))
    }

    @Test
    fun `downloadChunk hashes the two-byte empty JSON string and returns raw bytes`() {
        val res = client.reserve("peer-key", totalSize = 4, chunkCount = 1)!!
        val data = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x7F)   // includes bytes not valid as UTF-8
        client.uploadChunk(res.blobId, 0, data)

        val downloaded = client.downloadChunk(res.blobId, 0)
        assertNotNull(downloaded)
        assertTrue("bytes must round-trip exactly, including non-UTF-8 bytes",
            downloaded!!.contentEquals(data))

        val rec = relay.last()
        assertEquals("GET", rec.method)
        assertTrue(FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY))
        assertEquals(identity.userKey, rec.userHeader)
    }

    @Test
    fun `delete hashes the two-byte empty JSON string, and a 404 still counts as success`() {
        val res = client.reserve("peer-key", totalSize = 4, chunkCount = 1)!!
        assertTrue(client.delete(res.blobId))
        val rec = relay.last()
        assertEquals("DELETE", rec.method)
        assertTrue(FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY))
        assertEquals(identity.userKey, rec.userHeader)

        // The blob is already gone — deleting it again must still report success.
        assertTrue("a 404 on delete must be treated as already-gone, not a failure",
            client.delete(res.blobId))
    }

    @Test
    fun `reserve refuses over the ciphertext cap before any network call`() {
        val before = relay.requests().size
        val res = client.reserve("peer-key", totalSize = V2BlobClient.MAX_BLOB_BYTES + 1, chunkCount = 1)
        assertNull(res)
        assertEquals("must refuse locally, never even reach the relay", before, relay.requests().size)
    }

    // ------------------------------------------------------------------ resume

    /**
     * A chunk uploaded by a PREVIOUS attempt must not be re-sent when [V2BlobClient.uploadBlob]
     * is called again with `resumeBlobId`. This is the whole point of resume: an interrupted
     * transfer picks up where it left off instead of re-spending bandwidth (and, worse,
     * re-deriving the same AEAD key/nonce for a re-encrypted chunk).
     */
    @Test
    fun `uploadBlob with resumeBlobId only sends the chunks the server is still missing`() {
        val chunks = listOf(byteArrayOf(1, 1), byteArrayOf(2, 2), byteArrayOf(3, 3))
        val res = client.reserve("peer-key", totalSize = 6, chunkCount = 3)!!
        client.uploadChunk(res.blobId, 0, chunks[0])   // simulate a prior, partially-finished attempt
        relay.clear()

        val blobId = client.uploadBlob("peer-key", chunks, resumeBlobId = res.blobId)
        assertEquals(res.blobId, blobId)

        val puts = relay.requests().filter { it.method == "PUT" }
        assertEquals("only the two still-missing chunks should have been PUT", 2, puts.size)
        val putIndices = puts.map { it.path.substringAfterLast("/").toInt() }.sorted()
        assertEquals(listOf(1, 2), putIndices)
        assertTrue((relay.isCommitted(res.blobId) ?: false))
    }

    @Test
    fun `uploadBlob with no resumeBlobId does a fresh reserve and sends every chunk`() {
        val chunks = listOf(byteArrayOf(5, 5), byteArrayOf(6, 6))
        val blobId = client.uploadBlob("peer-key", chunks)
        assertNotNull(blobId)
        val puts = relay.requests().filter { it.method == "PUT" }
        assertEquals(2, puts.size)
        assertTrue((relay.isCommitted(blobId!!) ?: false))
    }

    // ------------------------------------------------------------------ fake blob relay

    /**
     * A minimal, real implementation of the `/v2/blobs` routes: reserve creates a record,
     * PUT stores raw bytes at an index, GET returns raw bytes, status reports real gaps,
     * commit refuses while any are missing. Every request is recorded as a [FakeRelay.Recorded]
     * so [FakeRelay.verifySignature] — the SAME check the rest of this project's transport
     * tests use — can be run against it without duplicating Ed25519 verification here.
     */
    private class FakeBlobRelay : AutoCloseable {
        private class BlobRecord(val chunkCount: Int) {
            val chunks = ConcurrentHashMap<Int, ByteArray>()
            @Volatile var committed = false
        }

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val blobs = ConcurrentHashMap<String, BlobRecord>()
        private val nextId = AtomicInteger(1)
        private val recorded = ConcurrentLinkedQueue<FakeRelay.Recorded>()

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/v2/blobs") { exchange -> handle(exchange) }
            server.executor = null
            server.start()
        }

        override fun close() = server.stop(0)

        fun requests(): List<FakeRelay.Recorded> = recorded.toList()
        fun last(): FakeRelay.Recorded = recorded.last()
        fun clear() = recorded.clear()

        /** Null means "no such blob" — distinct from a blob that exists but isn't committed. */
        fun isCommitted(id: String): Boolean? = blobs[id]?.committed

        private fun handle(exchange: HttpExchange) {
            try {
                val method = exchange.requestMethod
                val rawPath = exchange.requestURI.rawPath
                val body = exchange.requestBody.readBytes()
                val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
                val rec = FakeRelay.Recorded(method, rawPath, exchange.requestURI.rawQuery, headers, body)
                recorded.add(rec)

                val segments = rawPath.removePrefix("/v2/blobs").trim('/').split("/").filter { it.isNotEmpty() }
                when {
                    method == "POST" && segments.isEmpty() -> reserve(exchange, rec)
                    method == "PUT" && segments.size == 3 && segments[1] == "chunk" ->
                        putChunk(exchange, rec, segments[0], segments[2].toInt())
                    method == "GET" && segments.size == 3 && segments[1] == "chunk" ->
                        getChunk(exchange, rec, segments[0], segments[2].toInt())
                    method == "GET" && segments.size == 2 && segments[1] == "status" ->
                        status(exchange, rec, segments[0])
                    method == "POST" && segments.size == 2 && segments[1] == "commit" ->
                        commit(exchange, rec, segments[0])
                    method == "DELETE" && segments.size == 1 -> deleteBlob(exchange, rec, segments[0])
                    else -> respond(exchange, 404, "{}")
                }
            } catch (e: Exception) {
                respond(exchange, 500, "{\"error\":\"" + (e.message ?: "boom") + "\"}")
            }
        }

        private fun reserve(exchange: HttpExchange, rec: FakeRelay.Recorded) {
            if (rec.userHeader != null) { respond(exchange, 401, "{}"); return }
            if (!FakeRelay.verifySignature(rec, rec.body)) { respond(exchange, 401, "{}"); return }
            val json = JSONObject(rec.bodyText)
            val chunkCount = json.getInt("chunkCount")
            val id = "blob-" + nextId.getAndIncrement()
            blobs[id] = BlobRecord(chunkCount)
            val resp = JSONObject()
                .put("blobId", id)
                .put("chunkSize", OSHICryptoV2.DEFAULT_CHUNK_SIZE)
                .put("missing", JSONArray((0 until chunkCount).toList()))
            respond(exchange, 200, resp.toString())
        }

        private fun putChunk(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String, index: Int) {
            if (rec.userHeader == null) { respond(exchange, 401, "{}"); return }
            if (!FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY)) {
                respond(exchange, 401, "{}"); return
            }
            val record = blobs[id] ?: run { respond(exchange, 404, "{}"); return }
            if (index < 0 || index >= record.chunkCount) { respond(exchange, 400, "{}"); return }
            record.chunks[index] = rec.body
            respond(exchange, 200, "{}")
        }

        private fun getChunk(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String, index: Int) {
            if (!FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY)) {
                respond(exchange, 401, "{}"); return
            }
            val record = blobs[id] ?: run { respond(exchange, 404, "{}"); return }
            val data = record.chunks[index] ?: run { respond(exchange, 404, "{}"); return }
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }

        private fun status(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
            if (!FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY)) {
                respond(exchange, 401, "{}"); return
            }
            val record = blobs[id] ?: run { respond(exchange, 404, "{}"); return }
            val missing = (0 until record.chunkCount).filter { !record.chunks.containsKey(it) }
            val resp = JSONObject()
                .put("blobId", id)
                .put("missing", JSONArray(missing))
                .put("committed", record.committed)
                .put("expiresAt", 0.0)
            respond(exchange, 200, resp.toString())
        }

        private fun commit(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
            // Commit goes through the JSON body reader ⇒ hashes the actual (here empty) body.
            if (!FakeRelay.verifySignature(rec, ByteArray(0))) { respond(exchange, 401, "{}"); return }
            val record = blobs[id] ?: run { respond(exchange, 404, "{}"); return }
            val missing = (0 until record.chunkCount).filter { !record.chunks.containsKey(it) }
            if (missing.isNotEmpty()) {
                respond(exchange, 409, JSONObject().put("missing", JSONArray(missing)).toString())
                return
            }
            record.committed = true
            respond(exchange, 200, "{}")
        }

        private fun deleteBlob(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
            if (!FakeRelay.verifySignature(rec, DesktopV2Signer.EMPTY_JSON_STRING_BODY)) {
                respond(exchange, 401, "{}"); return
            }
            val existed = blobs.remove(id) != null
            respond(exchange, if (existed) 200 else 404, "{}")
        }

        private fun respond(exchange: HttpExchange, code: Int, json: String) {
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
