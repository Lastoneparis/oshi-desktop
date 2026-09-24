package com.oshi.desktop.mail

import com.oshi.desktop.net.FakeRelay
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal, real implementation of the `/mail/v1` routes described in the task brief, in
 * this process — the mail counterpart of [com.oshi.desktop.net.FakeRelay] /
 * `V2BlobClientTest.FakeBlobRelay`.
 *
 * Every signed request is recorded as a [FakeRelay.Recorded] so [FakeRelay.verifySignature]
 * — the same check the rest of this project's transport tests use — can be run against it
 * without a second Ed25519 verifier. Two routes are answered WITHOUT checking a signature at
 * all (`availability`, `web/pair/inspect`), matching the brief ("unauthenticated").
 *
 * State is a single mailbox: one localpart, one set of messages, one drive. That is enough
 * to exercise [MailClient] end to end without building a second server per test.
 */
class FakeMailRelay : AutoCloseable {

    class StoredMessage(
        val id: String,
        var folder: String,
        var seen: Boolean,
        val sealedEnvelope: String?,
        val sealedBody: ByteArray,
        val deliveredTo: String = "you@mail.oshi-messenger.com",
    )

    class StoredFile(val id: String, val bytes: ByteArray, val sealedMeta: String, val createdAt: String)
    class StoredWebSession(val id: String, val label: String, val createdAt: String, val lastSeenAt: String)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val recorded = ConcurrentLinkedQueue<FakeRelay.Recorded>()

    var localpart: String? = null
    var mailPubKeyHex: String? = null
    val messages = ConcurrentHashMap<String, StoredMessage>()
    val driveFiles = ConcurrentHashMap<String, StoredFile>()
    val webSessions = ConcurrentHashMap<String, StoredWebSession>()
    private val uploads = ConcurrentHashMap<String, ByteArray>()
    private val uploadOffsets = ConcurrentHashMap<String, Int>()
    private val uploadMeta = ConcurrentHashMap<String, String>()
    private val nextId = AtomicInteger(1)

    /** A pairing request this relay will answer `inspect`/`claim` for. */
    var pairingCode: String? = null
    var pairingBrowserPubHex: String? = null
    var claimedSealedMailKey: String? = null

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = null
        server.start()
    }

    override fun close() = server.stop(0)

    fun requests(): List<FakeRelay.Recorded> = recorded.toList()
    fun last(): FakeRelay.Recorded = recorded.last()

    fun addMessage(msg: StoredMessage) { messages[msg.id] = msg }

    private fun handle(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod
            val rawPath = exchange.requestURI.rawPath
            val body = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
            val rec = FakeRelay.Recorded(method, rawPath, exchange.requestURI.rawQuery, headers, body)
            recorded.add(rec)

            // The signature covers the RAW (percent-encoded) path — DesktopV2Signer.encodeIdentity
            // percent-encodes every non-alphanumeric byte of an id, including '-' and '.', so a
            // dash-bearing uuid or upload id arrives here as "%2D". A real server url-decodes path
            // segments before routing; this fake does the same so lookups against un-encoded keys
            // ("up-1", "kate.alt") succeed, while `rec.path` above keeps the RAW string so signature
            // verification checks exactly what was signed.
            val segs = rawPath.removePrefix("/mail/v1").trim('/').split("/").filter { it.isNotEmpty() }
                .map { java.net.URLDecoder.decode(it, "UTF-8") }
            when {
                method == "GET" && segs == listOf("availability") -> availability(exchange)
                method == "GET" && segs == listOf("web", "pair", "inspect") -> pairInspect(exchange)
                method == "POST" && segs == listOf("web", "pair", "claim") -> pairClaim(exchange, rec)
                method == "GET" && segs == listOf("web", "sessions") -> sessions(exchange, rec)
                method == "DELETE" && segs.size == 3 && segs[0] == "web" && segs[1] == "sessions" -> revokeSession(exchange, rec, segs[2])
                method == "POST" && segs == listOf("register") -> register(exchange, rec)
                method == "GET" && segs == listOf("account") -> account(exchange, rec)
                method == "GET" && segs == listOf("inbox") -> inbox(exchange, rec)
                method == "GET" && segs.size == 2 && segs[0] == "message" -> messageBytes(exchange, rec, segs[1])
                method == "POST" && segs.size == 3 && segs[0] == "message" && segs[2] == "seen" ->
                    seen(exchange, rec, segs[1])
                method == "POST" && segs.size == 3 && segs[0] == "message" && segs[2] == "move" ->
                    move(exchange, rec, segs[1])
                method == "DELETE" && segs.size == 2 && segs[0] == "message" -> deleteMessage(exchange, rec, segs[1])
                method == "POST" && segs == listOf("send") -> send(exchange, rec)
                method == "POST" && segs == listOf("draft") -> draft(exchange, rec)
                method == "POST" && segs == listOf("alias") -> addAlias(exchange, rec)
                method == "DELETE" && segs.size == 2 && segs[0] == "alias" -> removeAlias(exchange, rec, segs[1])
                method == "GET" && segs == listOf("drive") -> driveList(exchange, rec)
                method == "POST" && segs == listOf("drive", "upload") -> driveInit(exchange, rec)
                method == "PUT" && segs.size == 3 && segs[0] == "drive" && segs[1] == "upload" ->
                    driveChunk(exchange, rec, segs[2])
                method == "POST" && segs.size == 4 && segs[0] == "drive" && segs[1] == "upload" && segs[3] == "complete" ->
                    driveComplete(exchange, rec, segs[2])
                method == "GET" && segs.size == 3 && segs[0] == "drive" && segs[1] == "file" ->
                    driveDownload(exchange, rec, segs[2])
                method == "DELETE" && segs.size == 3 && segs[0] == "drive" && segs[1] == "file" ->
                    driveDelete(exchange, rec, segs[2])
                method == "PATCH" && segs.size == 4 && segs[0] == "drive" && segs[1] == "file" && segs[3] == "meta" ->
                    driveMeta(exchange, rec, segs[2])
                else -> respondJson(exchange, 404, JSONObject().put("error", "not_found"))
            }
        } catch (e: Exception) {
            respondJson(exchange, 500, JSONObject().put("error", e.message ?: "boom"))
        }
    }

    private fun requireSigned(rec: FakeRelay.Recorded, exchange: HttpExchange, bodyToHash: ByteArray): Boolean {
        if (!FakeRelay.verifySignature(rec, bodyToHash)) {
            respondJson(exchange, 401, JSONObject().put("error", "bad_signature"))
            return false
        }
        return true
    }

    // ---- account -----------------------------------------------------------------------

    private fun availability(exchange: HttpExchange) {
        val q = exchange.requestURI.rawQuery ?: ""
        val requested = Regex("localpart=([^&]*)").find(q)?.groupValues?.get(1)
        val available = requested != null && requested != localpart
        respondJson(exchange, 200, JSONObject().put("available", available))
    }

    private fun register(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val o = JSONObject(rec.bodyText)
        localpart = o.getString("localpart")
        mailPubKeyHex = o.getString("mailPubKey")
        respondJson(exchange, 200, accountJson())
    }

    private fun account(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, ByteArray(0))) return
        // A fresh identity has no mailbox. Returning a made-up account here hid the setup
        // flow and disagreed with the real API's 404, which MailModel treats as normal.
        if (localpart == null) return respondJson(exchange, 404, JSONObject().put("error", "no_mailbox"))
        respondJson(exchange, 200, accountJson())
    }

    private fun accountJson(): JSONObject = JSONObject().put(
        "account",
        JSONObject()
            .put("address", "${localpart ?: "nobody"}@${MailClient.MAIL_DOMAIN}")
            .put("localpart", localpart ?: "nobody")
            // Account refresh follows alias mutations in the real service. Keeping this
            // snapshot honest matters because the desktop composer sources its From menu
            // from it, not from a stale mutation response.
            .put("aliases", JSONArray(aliases))
            .put("aliasLimit", 10)
            .put("storage", JSONObject().put("used", 1024).put("quota", 150L * 1024 * 1024).put("messages", messages.size))
            .put("sending", JSONObject().put("sent", 0).put("quota", 150).put("remaining", 150))
            .put("drive", JSONObject().put("used", 0).put("quota", 1024L * 1024 * 1024).put("files", driveFiles.size)),
    )

    // ---- inbox -------------------------------------------------------------------------

    private fun inbox(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, ByteArray(0))) return
        val arr = JSONArray()
        for (m in messages.values) {
            val o = JSONObject()
                .put("id", m.id)
                .put("receivedAt", "2026-09-13T00:00:00Z")
                .put("bytes", m.sealedBody.size)
                .put("seen", m.seen)
                .put("folder", m.folder)
                .put("deliveredTo", m.deliveredTo)
            m.sealedEnvelope?.let { o.put("sealedEnvelope", it) }
            arr.put(o)
        }
        respondJson(exchange, 200, JSONObject().put("messages", arr).put("storage", JSONObject().put("used", 1024).put("quota", 1000)))
    }

    private fun messageBytes(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        if (!requireSigned(rec, exchange, ByteArray(0))) return
        val m = messages[id] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        respondBytes(exchange, 200, m.sealedBody)
    }

    private fun seen(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val m = messages[id] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        m.seen = JSONObject(rec.bodyText).optBoolean("seen", true)
        respondJson(exchange, 200, JSONObject())
    }

    private fun move(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val m = messages[id] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        m.folder = JSONObject(rec.bodyText).getString("folder")
        respondJson(exchange, 200, JSONObject())
    }

    private fun deleteMessage(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        // Verify-first: the two-byte JSON empty string, exactly like /v2/account.
        if (!requireSigned(rec, exchange, "\"\"".toByteArray(Charsets.UTF_8))) return
        val m = messages[id] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        val permanent = (rec.query ?: "").contains("permanent=1")
        if (permanent || m.folder == "trash") {
            messages.remove(id)
        } else {
            m.folder = "trash"
        }
        respondJson(exchange, 200, JSONObject())
    }

    // ---- sending / drafts / aliases -----------------------------------------------------

    private fun send(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val id = "sent-" + nextId.getAndIncrement()
        respondJson(exchange, 200, JSONObject().put("messageId", id))
    }

    private fun draft(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val o = JSONObject(rec.bodyText)
        val id = o.optString("id").ifEmpty { "draft-" + nextId.getAndIncrement() }
        messages[id] = StoredMessage(id, "draft", true, o.getString("sealedEnvelope"), Base64.getDecoder().decode(o.getString("sealedBody")))
        respondJson(exchange, 200, JSONObject().put("id", id))
    }

    private val aliases = mutableListOf<String>()

    private fun addAlias(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, rec.body)) return
        aliases += JSONObject(rec.bodyText).getString("alias")
        respondJson(exchange, 200, JSONObject().put("aliases", JSONArray(aliases)))
    }

    private fun removeAlias(exchange: HttpExchange, rec: FakeRelay.Recorded, localpartArg: String) {
        if (!requireSigned(rec, exchange, "\"\"".toByteArray(Charsets.UTF_8))) return
        aliases.removeAll { it.substringBefore('@') == localpartArg }
        respondJson(exchange, 200, JSONObject().put("aliases", JSONArray(aliases)))
    }

    // ---- drive --------------------------------------------------------------------------

    private fun driveList(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, ByteArray(0))) return
        val arr = JSONArray()
        for (f in driveFiles.values) {
            arr.put(
                JSONObject().put("id", f.id).put("bytes", f.bytes.size)
                    .put("createdAt", f.createdAt).put("sealedMeta", f.sealedMeta),
            )
        }
        respondJson(exchange, 200, JSONObject().put("files", arr))
    }

    private fun driveInit(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val o = JSONObject(rec.bodyText)
        val id = "up-" + nextId.getAndIncrement()
        uploads[id] = ByteArray(o.getInt("bytes"))
        uploadOffsets[id] = 0
        uploadMeta[id] = o.getString("sealedMeta")
        respondJson(exchange, 200, JSONObject().put("uploadId", id).put("chunkSize", 64 * 1024))
    }

    private fun driveChunk(exchange: HttpExchange, rec: FakeRelay.Recorded, uploadId: String) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val offset = Regex("offset=(\\d+)").find(rec.query ?: "")?.groupValues?.get(1)?.toInt() ?: 0
        val expected = uploadOffsets[uploadId] ?: 0
        if (offset != expected) {
            respondJson(exchange, 409, JSONObject().put("error", "wrong_offset").put("expected", expected))
            return
        }
        val buf = uploads[uploadId] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        System.arraycopy(rec.body, 0, buf, offset, rec.body.size)
        val newOffset = offset + rec.body.size
        uploadOffsets[uploadId] = newOffset
        respondJson(exchange, 200, JSONObject().put("received", newOffset))
    }

    private fun driveComplete(exchange: HttpExchange, rec: FakeRelay.Recorded, uploadId: String) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val bytes = uploads[uploadId] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        val id = "file-" + nextId.getAndIncrement()
        driveFiles[id] = StoredFile(id, bytes, uploadMeta[uploadId] ?: "", "2026-09-13T00:00:00Z")
        respondJson(exchange, 200, JSONObject().put("file", JSONObject().put("id", id)))
    }

    private fun driveDownload(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        if (!requireSigned(rec, exchange, ByteArray(0))) return
        val f = driveFiles[id] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        respondBytes(exchange, 200, f.bytes)
    }

    private fun driveDelete(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        if (!requireSigned(rec, exchange, "\"\"".toByteArray(Charsets.UTF_8))) return
        driveFiles.remove(id)
        respondJson(exchange, 200, JSONObject())
    }

    private fun driveMeta(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val f = driveFiles[id] ?: return respondJson(exchange, 404, JSONObject().put("error", "not_found"))
        val newSealed = JSONObject(rec.bodyText).getString("sealedMeta")
        driveFiles[id] = StoredFile(f.id, f.bytes, newSealed, f.createdAt)
        respondJson(exchange, 200, JSONObject())
    }

    // ---- pairing (unauthenticated inspect, signed claim) --------------------------------

    private fun pairInspect(exchange: HttpExchange) {
        val q = exchange.requestURI.rawQuery ?: ""
        val code = Regex("code=([^&]*)").find(q)?.groupValues?.get(1)
        if (code == null || code != pairingCode) {
            respondJson(exchange, 404, JSONObject().put("error", "not_found"))
            return
        }
        respondJson(exchange, 200, JSONObject().put("browserPubKey", pairingBrowserPubHex).put("ageMs", 1000))
    }

    private fun pairClaim(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, rec.body)) return
        val o = JSONObject(rec.bodyText)
        claimedSealedMailKey = o.getString("sealedMailKey")
        val id = "web-${nextId.getAndIncrement()}"
        webSessions[id] = StoredWebSession(id, o.optString("label", "Browser"), "2026-09-22T00:00:00Z", "2026-09-22T00:00:00Z")
        respondJson(exchange, 200, JSONObject().put("address", "${localpart}@${MailClient.MAIL_DOMAIN}"))
    }

    private fun sessions(exchange: HttpExchange, rec: FakeRelay.Recorded) {
        if (!requireSigned(rec, exchange, ByteArray(0))) return
        val list = JSONArray()
        webSessions.values.sortedBy { it.id }.forEach { session -> list.put(JSONObject()
            .put("id", session.id).put("label", session.label)
            .put("createdAt", session.createdAt).put("lastSeenAt", session.lastSeenAt)) }
        respondJson(exchange, 200, JSONObject().put("sessions", list))
    }

    private fun revokeSession(exchange: HttpExchange, rec: FakeRelay.Recorded, id: String) {
        // Session revoke is a signed DELETE, therefore follows the mail server's
        // verify-first convention: hash the two-byte JSON empty string, send no body.
        if (!requireSigned(rec, exchange, "\"\"".toByteArray(Charsets.UTF_8))) return
        val revoked = if (id == "all") {
            val count = webSessions.size
            webSessions.clear()
            count
        } else if (webSessions.remove(id) != null) 1 else 0
        respondJson(exchange, 200, JSONObject().put("revoked", revoked))
    }

    // ---- wire helpers ---------------------------------------------------------------------

    private fun respondJson(exchange: HttpExchange, code: Int, body: JSONObject) =
        respondBytes(exchange, code, body.toString().toByteArray(Charsets.UTF_8), "application/json")

    private fun respondBytes(
        exchange: HttpExchange, code: Int, bytes: ByteArray, contentType: String = "application/octet-stream",
    ) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

/** Test-only helper: base64 wire encoding used throughout the mail API. */
internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
