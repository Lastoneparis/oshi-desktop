package com.oshi.desktop.bot

import com.oshi.desktop.net.V2Http
import com.oshi.desktop.sync.SyncProtocol
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONArray

/**
 * The pending queue, as the BOT transport — PARITY.md row 0.26.
 *
 * This class exists because of one awkward fact: **the bot lane is not the V2 relay.** A
 * grep of `ServerVPS/v2/relay_v2.js` and `v2_server.js` for "bot" returns a single hit, and
 * it is the word "omit" inside a comment. Every bot message in the product travels as a
 * string in the hash slot of the LEGACY queue, `GET /api/pending/{key}`
 * (`ServerVPS/api/message_queue_server.js:609`), and both shipped clients intercept it there
 * — iOS `MessageManager.swift:2427-2431`, Android `VPSClient.kt:644-647`.
 *
 * So a desktop client that implements V2 perfectly still receives **zero** bot messages
 * unless it polls this endpoint. That is the entire justification for this file, and it is
 * why it carries only the four calls the bot lane needs:
 *
 * | verb + path | why |
 * |---|---|
 * | `GET /api/pending/{key}?deviceId=` | where a bot envelope appears |
 * | `POST /api/bot-received/{key}` `{"messageId","deviceId"}` | the SHORT ack — see [ackBot] |
 * | `DELETE /api/received/{key}/{hash}?deviceId=` | the ack for anything else in the queue |
 *
 * **What it deliberately does NOT carry**: the IPFS/pin surface that also lives on this
 * server — `GET /api/ipfs/proxy/{cid}`, `POST /api/pinata/pinning/…`, `POST /api/queue/…`.
 * That is the pre-V2 content-addressed road, and it is deliberately NOT ported: real
 * traffic goes over the V2 relay, the mesh and LoRa. This client therefore fetches nothing
 * by CID and pins nothing; a queue entry that is a content identifier is [QueueEntry.Cid]
 * and is recognised so it can be acked and ignored, never resolved.
 *
 * ============================================================ NO AUTH ON ANY OF IT
 *
 * Every route above takes a PUBLIC key in the path and checks nothing — no signature, no
 * bearer, no `x-oshi-*` header. Same property [com.oshi.desktop.sync.LegacySyncClient]
 * records for `/api/sync` on the same server, and it is worse here because the queue is a
 * delivery mechanism and its bot payloads are **not encrypted**:
 *
 *   - anyone holding a user's public key can **drain that user's queue** by polling it, and
 *     read every bot message in cleartext (see [BotEnvelope]'s ENCRYPTION section; the
 *     server says so in its own words at `message_queue_server.js:754-767`);
 *   - anyone can **ack** on their behalf, and since an entry is evicted after
 *     `MESSAGE_TTL_MS` from its FIRST receipt (`:369-395`), a third party can cause message
 *     loss.
 *
 * Which is why this is not built on [V2Http]: attaching a signature to a route with no
 * owner check spends a credential for nothing and would make the next reader assume an
 * owner check exists.
 *
 * ============================================================ THE PATH KEY
 *
 * base64url-with-padding-stripped, via [SyncProtocol.legacyPathKey] — a delegation, not a
 * copy. Same function iOS's `MessageManager.base64urlEncode` applies at all six of its call
 * sites (`:2182`, `:4147`, `:4801`, …). PARITY.md row 0.24's warning applies verbatim:
 * percent-encoding the key instead reaches a DIFFERENT slot with no error and no 404,
 * because the server normalises `-`→`+`, `_`→`/` and re-pads
 * (`message_queue_server.js:56-62`). Two devices then sync happily to two slots and never
 * see each other.
 *
 * iOS also polls a second, legacy percent-encoded spelling every 15th tick as a safety net
 * for bot messages queued under the old format (`MessageManager.swift:2192-2213`). Not
 * reproduced: its own comment calls it "~1 800 wasted HTTP requests per hour", and a
 * desktop with no history under that encoding has nothing to find there.
 *
 * ============================================================ A FAILED POLL IS NOT AN
 * EMPTY QUEUE
 *
 * [pending] returns `Result` and a transport error is a `failure`, never an empty list —
 * row 0.10's rule ("a failed pull is null, never an empty one"), and it matters more here:
 * the caller's action after an empty queue is to do nothing, so an error rendered as
 * "nothing waiting" is invisible forever.
 */
class BotQueueClient(
    private val ownerPublicKey: String,
    private val deviceId: String,
    private val baseUrl: String = V2Http.defaultBaseUrl(),
    connectTimeout: Duration = Duration.ofSeconds(15),
    private val readTimeout: Duration = Duration.ofSeconds(30),
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val pathKey: String get() = SyncProtocol.legacyPathKey(ownerPublicKey)

    /**
     * One entry off the queue, already classified.
     *
     * The queue is typed by STRING SHAPE, not by a field: an entry is either a content
     * identifier or an entire inlined bot message. Both shipped clients test the same way
     * and both do it BEFORE any fetch, so a bot envelope never reaches a gateway.
     * Classifying at the point of parsing rather than at each call site is what stops a
     * caller handing a 600 KB `bot:…:…` string to something expecting a CID.
     */
    sealed class QueueEntry {
        /** The raw string as the server gave it — what an ack has to echo back. */
        abstract val raw: String

        /**
         * A content identifier (`Qm…` CIDv0, `bafy…` CIDv1) — the old IPFS road. Recognised
         * so it can be told apart from a bot envelope and acked, **not** resolved: nothing
         * in this client fetches one.
         */
        data class Cid(override val raw: String) : QueueEntry()

        /** A `bot:<messageId>:<base64 json>` envelope. See [BotEnvelope]. */
        data class Bot(override val raw: String) : QueueEntry()

        /**
         * Neither. Kept rather than dropped, because an entry this client cannot classify
         * still occupies a slot that has to be acked or it re-streams on every poll — the
         * exact failure `/api/bot-received` was added to fix.
         */
        data class Unknown(override val raw: String) : QueueEntry()
    }

    /**
     * `GET /api/pending/{key}?deviceId=…`.
     *
     * The response is a bare JSON array of strings (`message_queue_server.js:305`:
     * `res.json(pending.map(m => m.hash))`). iOS accepts three other shapes for older
     * server builds (`MessageManager.swift:2280+`); this accepts the one the shipped server
     * emits and fails loudly otherwise, because a desktop client has no older server to be
     * compatible with and a lenient parser here would read an HTML error page as "zero
     * messages".
     */
    fun pending(): Result<List<QueueEntry>> {
        val url = "$baseUrl/api/pending/$pathKey?deviceId=${enc(deviceId)}"
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout).GET().build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                return Result.failure(RuntimeException("GET /api/pending HTTP ${resp.statusCode()}"))
            }
            val arr = JSONArray(resp.body())
            Result.success((0 until arr.length()).map { classify(arr.getString(it)) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * `bot:` is a bot envelope, `Qm…`/`bafy…` is a CID, anything else is unknown.
     *
     * The two CID prefixes are the server's own validation set — `ipfs_fetch_proxy.js:33-34`
     * rejects with HTTP 400 anything starting with neither — so this classifier and that
     * gateway agree on what a CID is even though this client never calls it.
     */
    fun classify(raw: String): QueueEntry = when {
        raw.startsWith(BotEnvelope.PREFIX) -> QueueEntry.Bot(raw)
        raw.startsWith("Qm") || raw.startsWith("bafy") -> QueueEntry.Cid(raw)
        else -> QueueEntry.Unknown(raw)
    }

    /**
     * `POST /api/bot-received/{key}` with `{"messageId":…,"deviceId":…}` — the SHORT ack.
     *
     * [messageId] is the MIDDLE field of `bot:<messageId>:<base64>`, available without
     * decoding the payload, which is the whole point. The standard `DELETE /api/received`
     * matches the entry in a URL PATH SEGMENT, and a bot envelope with inlined media is
     * hundreds of kilobytes: the request exceeds the URL length limit, fails silently, and
     * the envelope re-streams in every subsequent poll. That is not a hypothesis — it is why
     * this route exists, in the server's own words (`message_queue_server.js:417-425`, "8+
     * MB downloads"). Android still acks bot envelopes by URL path (`VPSClient.kt:725-747`)
     * and still has the bug; iOS uses this route with the DELETE only as a fallback
     * (`MessageManager.swift:2887-2937`).
     */
    fun ackBot(messageId: String): Result<Unit> {
        require(messageId.isNotEmpty()) { "empty bot messageId" }
        val body = buildString {
            append("{\"messageId\":\"").append(esc(messageId)).append('"')
            append(",\"deviceId\":\"").append(esc(deviceId)).append("\"}")
        }.toByteArray(Charsets.UTF_8)
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/bot-received/$pathKey"))
            .timeout(readTimeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return send(req, "POST /api/bot-received")
    }

    /**
     * `DELETE /api/received/{key}/{hash}?deviceId=…` — the per-device ack for a NON-bot
     * entry.
     *
     * Per-device on purpose: the entry stays queued for the account's other devices and is
     * only evicted once it has aged past the server's TTL from first receipt
     * (`message_queue_server.js:369-395`). So this is "I have it", not "delete it".
     *
     * **Typed to refuse a bot envelope.** It takes a [QueueEntry] that is not
     * [QueueEntry.Bot] and rejects one at runtime, because putting a 600 KB envelope in a
     * URL path is the silent failure [ackBot] exists to avoid. A caller cannot reach that
     * mistake by passing the wrong string.
     */
    fun ack(entry: QueueEntry): Result<Unit> {
        require(entry !is QueueEntry.Bot) {
            "a bot envelope must be acked with ackBot(messageId) — the URL-path DELETE " +
                "silently fails on a large envelope and it re-streams on every poll " +
                "(message_queue_server.js:417-425)"
        }
        val url = "$baseUrl/api/received/$pathKey/${enc(entry.raw)}?deviceId=${enc(deviceId)}"
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout).DELETE().build()
        return send(req, "DELETE /api/received")
    }

    private fun send(req: HttpRequest, label: String): Result<Unit> = try {
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) Result.success(Unit)
        else Result.failure(RuntimeException("$label HTTP ${resp.statusCode()}"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun esc(s: String): String = com.oshi.messenger.network.v2.OSHICryptoV2.jsonEscape(s)
}
