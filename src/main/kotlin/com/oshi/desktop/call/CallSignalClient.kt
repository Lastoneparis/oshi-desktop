package com.oshi.desktop.call

import com.oshi.desktop.DesktopV2Signer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject

/**
 * The call server's SIGNALLING REST surface — PARITY.md row 2.1, the transport under it.
 *
 * Everything in this file was read out of `ServerVPS/call_server.js`, not inferred from the
 * two clients, because the two clients disagree about it in three places and the server is
 * the only thing that decides who is right.
 *
 * ============================================================ THE ROUTES THAT EXIST
 *
 * The call server is a separate Express app on **port 8083** — not the V2 relay, and none of
 * its routes live under `/v2`. nginx proxies `location /api/call/ → http://127.0.0.1:8083/`
 * (`ServerVPS/oshi-backup/nginx-sites-available/oshi.backup:13-16`), and the trailing slash
 * on the `proxy_pass` target is what makes the prefix DISAPPEAR before Express sees it. So
 * every path below is written twice, and the difference is load-bearing — see SIGNING.
 *
 * | client sends | server sees | route | answers |
 * |---|---|---|---|
 * | `POST /api/call/signal` | `/signal` | `:628` | 200 `{success:true,delivery:…}` · 400 `{error:"Missing fields"}` · 403 `{error:"Invalid signature",reason}` · 429 `{error:"Rate limit exceeded",retryAfter:60}` |
 * | `GET /api/call/signals/<b64url>?deviceId=…` | `/signals/<b64url>` | `:847` | 200, a **BARE JSON ARRAY** |
 * | `POST /api/call/register` | `/register` | `:1012`/`:1018` | 200 `{success:true}` |
 * | `POST /api/call/end` | `/end` | `:1024` | 200 `{success:true}` |
 * | `GET /api/call/turn-creds` | `/turn-creds` | `:598` | 200 creds · 503 when unconfigured |
 * | `GET /api/call/health` | `/health` | `:618` | 200 |
 * | `POST /api/call/ping` | `/ping` | `:627` | 200 `{pong:true,serverTime}` |
 *
 * `POST /audio`, `POST /video` and their GET drains exist too and are **deliberately not
 * here**: this is the signalling lane and there is no media path in this client at all
 * (see [CallLane.NO_AUDIO_WILL_FLOW]). Neither is `/turn-creds` — TURN/ICE is a separate
 * decision and a separate workstream, and a client that fetched a TURN credential it had no
 * socket for would be claiming a NAT traversal it does not have.
 *
 * **`/register` is not called, and that is a decision rather than an omission.** It stores
 * `activeCalls.set(callId, {participants:[p1,p2], createdAt})` (`:1012-1022`) — a literal
 * who-called-whom table, held up to an hour (`:1039-1041`). Nothing in the server ROUTES on
 * it: `activeCalls` is read in exactly two other places, the `/health` counter (`:620`) and
 * the hourly sweeper. So calling it would upload the call graph [CallSignalEnvelope]'s
 * privacy note is about, in exchange for a number on a health page. `POST /end` IS called,
 * because it deletes an entry rather than creating one.
 *
 * ============================================================ SIGNING, AND WHY IT CANNOT VERIFY
 *
 * The server implements the same Ed25519 scheme the V2 relay uses — canonical
 * `METHOD\nPATH\nSHA256hex(body)\nTIMESTAMP`, headers `x-oshi-signature`,
 * `x-oshi-timestamp`, `x-oshi-signing-pubkey` (`call_server.js:71-142`) — so
 * [DesktopV2Signer] produces exactly the right bytes with no second implementation.
 *
 * **But `PATH` is `req.originalUrl.split('?')[0]` (`:101`), evaluated INSIDE the upstream
 * Express app, after nginx has already stripped `/api/call`.** A client that signs the URL
 * it typed signs `/api/call/signal`; the server hashes `/signal`; the signature can never
 * verify. That is not speculation — the server's own grace branch names it:
 *
 *   > `console.log('[SIG-DEBUG] Invalid sig for ${method} ${path} — client may use different path prefix');`
 *   > — `call_server.js:132`
 *
 * So this client signs the **stripped** path ([apiPrefix] removed), which is the path the
 * process that verifies actually hashes. [signedPathFor] is the one place that decision
 * lives, and `FakeCallServer` verifies STRICTLY so the choice is pinned by a test rather
 * than by this paragraph. Point the client at `:8083` directly and set `apiPrefix = ""`;
 * the signed path is then the same string either way.
 *
 * __CALL_PUSH_AUTH_DESKTOP_2026_09_23__ SUPERSEDED by `docs/CALL_PUSH_AUTH_CONTRACT.md`
 * (STABLE 1.0): the server now verifies the full requested path too, binds the signing key
 * to the identity each route claims (TOFU from the `/v2` key publication — so the key MUST
 * be the [DesktopV2Signer] one), and in `dual` mode already answers 403 to a valid signature
 * by a key bound to someone else. This client therefore signs the FULL path it requests
 * ([signedPathFor]), sends `sender` on `/end`, signs `/push/signal-answered` through the
 * same helper, and announces itself as `OSHI-Desktop/<version>`.
 *
 * ============================================================ WHAT A 200 DOES NOT MEAN
 *
 * `POST /signal` answers `{success:true, delivery:…}` and the delivery word is worth
 * reading, because two of the five mean the signal is NOT on its way:
 *
 *  - `websocket+queued` — pushed to a live socket AND queued as backup (`:709`);
 *  - `websocket` — `callAnsweredElsewhere` only, pushed and not queued (`:709`);
 *  - `multidevice` — `callAnsweredElsewhere` stored in the non-destructive map (`:722`);
 *  - `queued` — the ordinary desktop case: the recipient has no live socket (`:733`);
 *  - `ring-capped` — the offer was **DELETED**, not queued (`:754`).
 *
 * **And `ring-capped` can never actually be observed**, which is worse than it sounds.
 * `res.json({delivery:'queued'})` has already been sent at `:733` by the time the ring-cap
 * block runs at `:744-757`; the second `res.json` throws `ERR_HTTP_HEADERS_SENT` into a
 * response that has already left. So a caller re-offering past `RING_LIFETIME_MS` (40 s,
 * `:36`) is told `queued` for offers the server is purging from the queue on the same tick.
 * [Post.Queued] therefore carries no promise of delivery, the word is surfaced verbatim,
 * and the only thing this client trusts about whether a call rang is
 * [CallTimeouts.CALLER_NO_ANSWER_MS] expiring.
 *
 * ============================================================ THE KEY SPELLING IS base64url
 *
 * Both clients fold the address before it touches this server — `+`→`-`, `/`→`_`, `=`
 * dropped (`VPSClient.kt:493-496`, `VoiceCallManager.swift:2771-2776`,
 * `VoIPPushManager.swift:282-287`) — and the server folds it again itself in `normalizeKey`
 * (`:56-59`), so its maps are keyed on that spelling and nothing else. [base64Url] is that
 * fold and it is applied to `sender`, `recipient` and the GET path segment.
 *
 * The path segment is sent RAW. `DesktopV2Signer.encodeIdentity` — which percent-encodes
 * `-` and `_` — is deliberately NOT used here: the V2 relay carries STANDARD base64 in its
 * paths and has to escape `+ / =`, this server carries base64url and neither shipped client
 * escapes anything (`"\(vpsURL)/signals/\(encodedKey)"`, `"/signals/$base64urlKey"`).
 * Escaping would change the bytes the signature covers AND the map key the server looks up.
 */
class CallSignalClient(
    baseUrl: String,
    /** Null disables signing entirely. See SIGNING — the server accepts unsigned today. */
    private val signer: DesktopV2Signer? = null,
    /**
     * The prefix nginx strips. Sent on the URL, removed before signing.
     * `""` for a direct connection to port 8083.
     */
    private val apiPrefix: String = API_PREFIX,
    connectTimeout: Duration = Duration.ofSeconds(5),
    private val readTimeout: Duration = Duration.ofSeconds(8),
    /**
     * The signing clock, epoch ms. Injectable ONLY so a test can reproduce the contract's
     * fixed-timestamp vectors byte for byte (docs/CALL_PUSH_AUTH_CONTRACT.md §6).
     */
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Contract §7: the server's enforce-readiness report counts traffic per UA. */
    private val userAgent: String = defaultUserAgent(),
) {

    private val base: String = baseUrl.trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** What `POST /signal` said. See WHAT A 200 DOES NOT MEAN. */
    sealed class Post {
        /**
         * The server took it. [delivery] is its own word for what it did with it, verbatim,
         * because "queued" and "websocket+queued" are different facts about a ring and
         * collapsing them to a boolean throws away the only diagnosis this lane has.
         */
        data class Queued(val delivery: String) : Post()

        /** 400/403/429/5xx, or the request never reached the server ([code] = -1). */
        data class Refused(val code: Int, val reason: String, val retryAfterSeconds: Int? = null) : Post()

        val accepted: Boolean get() = this is Queued
    }

    /**
     * `POST /api/call/signal`.
     *
     * [nowMs] is threaded rather than read here for the reason every instant in this package
     * is: the envelope's own `timestamp` field is derived from it, and a builder that reads
     * the clock cannot be asserted against a byte shape.
     */
    fun sendSignal(envelope: CallSignalEnvelope, nowMs: Long = System.currentTimeMillis()): Post {
        val body = envelope.encode(nowMs).toByteArray(Charsets.UTF_8)
        val (code, text) = request("POST", "/signal", null, body, "application/json")
        if (code == -1) return Post.Refused(-1, "the call server was not reachable: $text")
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (code !in 200..299) {
            val reason = json?.optString("reason")?.takeIf { it.isNotEmpty() }
                ?: json?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: text.take(200)
            // 429 carries `retryAfter` in SECONDS (`call_server.js:640`, `:213` limits
            // signals to 30 per 60 s per sender). Surfaced rather than swallowed: a call
            // that will not go through because we are rate limited is a different thing to
            // tell a person than a call nobody answered.
            return Post.Refused(code, reason, json?.optInt("retryAfter")?.takeIf { code == 429 && it > 0 })
        }
        // `success:false` has no emitter in the server's source, but a body that is not the
        // JSON we expect is not an acceptance either.
        if (json == null) return Post.Refused(code, "the server answered 200 with a body that is not JSON")
        return Post.Queued(json.optString("delivery", "unknown"))
    }

    /**
     * `GET /api/call/signals/<base64url>?deviceId=…` — the poll.
     *
     * @return the envelopes, or **null** when the server could not be reached or answered
     *   something unreadable. Null is not an empty list: an empty list means "no call is
     *   waiting", null means "we do not know", and a caller that treats an outage as silence
     *   is a caller that will drop a ring and never say why.
     *
     * **The `deviceId` is not optional.** Without it the server takes its legacy branch and
     * the FIRST device to poll consumes the account's whole queue (`:892-903` records the
     * bug it was added to fix: *"he answered but heard nothing, and my phone kept ringing"*).
     * With it, a signal is never re-delivered to the same device, which is also what makes
     * this client's own dedup a second belt rather than the only one.
     *
     * The body is a **bare JSON array**. Android wraps a bare array into `{"signals":[…]}`
     * before parsing (`VPSClient.kt:2260-2268`); both shapes are accepted here, the array
     * first, because the array is what the server actually emits (`:921`).
     */
    fun poll(myAddress: String, deviceId: String): List<CallSignalEnvelope>? {
        val key = base64Url(myAddress)
        val query = "deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8")
        val (code, text) = request("GET", "/signals/$key", query, null, null)
        if (code !in 200..299) return null
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: runCatching { JSONObject(text).optJSONArray("signals") }.getOrNull()
            ?: return null
        val out = ArrayList<CallSignalEnvelope>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            // One unreadable entry costs one signal, never the whole poll — the same rule
            // row 0.10 applies to a bad envelope in a message pull.
            CallSignalEnvelope.decode(o.toString())?.let { out += it }
        }
        return out
    }

    /**
     * `POST /api/call/end` — drop the server's `activeCalls` row for this call.
     *
     * Best effort and never load-bearing: the row only feeds a health counter, and the
     * signal that actually ends a call for the peer is the sealed `callEnd` packet.
     */
    fun endCall(callId: String, sender: String? = null): Boolean {
        // __CALL_PUSH_AUTH_DESKTOP_2026_09_23__ contract §3 row 3: the identity `/end`
        // proves is read from body `sender`, so it is added (base64url, as on `/signal`).
        val o = JSONObject().put("callId", callId)
        if (!sender.isNullOrEmpty()) o.put("sender", base64Url(sender))
        val body = o.toString().toByteArray(Charsets.UTF_8)
        return request("POST", "/end", null, body, "application/json").first in 200..299
    }

    /**
     * Where push_service `/signal-answered` lives for THIS transport: the same origin as the
     * call server, behind nginx `location /push/`. Derived rather than hard-coded so a lane
     * pointed at a local test server never reaches production push.
     */
    val signalAnsweredUrl: String get() = "$base$PUSH_PREFIX/signal-answered"

    /**
     * `POST /push/signal-answered` — make the account's OTHER devices stop ringing
     * (dismiss CallKit / the ringing notification on a suspended phone).
     *
     * Signed like every other call/push request (contract §3 row 6: the claimed identity is
     * body `identity`, which must be bound to our signing key). Returns the HTTP status, or
     * -1 when the push service was not reachable.
     */
    fun signalAnswered(
        identity: String,
        callId: String,
        exceptDeviceId: String,
        url: String = signalAnsweredUrl,
    ): Int {
        val body = JSONObject()
            .put("identity", identity)
            .put("callId", callId)
            .put("exceptDeviceId", exceptDeviceId)
            .toString().toByteArray(Charsets.UTF_8)
        return send("POST", URI.create(url), body, "application/json").first
    }

    /** What `POST /relay-token` said: the token, or the HTTP code (-1 = unreachable) and reason. */
    data class RelayTokenReply(val code: Int, val token: com.oshi.desktop.call.transport.RelayToken?, val reason: String)

    /**
     * __CALL_MEDIA_AUTH_DESKTOP_2026_09_23__ `POST /api/call/relay-token` —
     * `docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md` (STABLE 1.0) §2. Signed through [send] like
     * every call request (the route never accepts unsigned). Body exact bytes, key order
     * `identity`, `peer`, `callId`, keys base64url unpadded — the spelling of the UDP frame.
     */
    fun relayToken(identity: String, peer: String, callId: String): RelayTokenReply {
        val body = relayTokenBody(identity, peer, callId).toByteArray(Charsets.UTF_8)
        val (code, text) = request("POST", "/relay-token", null, body, "application/json")
        if (code !in 200..299) {
            val json = runCatching { JSONObject(text) }.getOrNull()
            val reason = json?.optString("reason")?.takeIf { it.isNotEmpty() }
                ?: json?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: text.take(120)
            return RelayTokenReply(code, null, reason)
        }
        val t = com.oshi.desktop.call.transport.RelayToken.parse(text)
            ?: return RelayTokenReply(code, null, "a 200 that is not a relay token")
        return RelayTokenReply(code, t, "ok")
    }

    /** `GET` headers for [path] (empty body) — `/voip/turn-creds`, the WS upgrade. Empty when unsigned. */
    fun signedGetHeaders(path: String): Map<String, String> = webSocketUpgradeHeaders(path)

    /** `POST /api/call/ping` — is the call server there at all. */
    fun ping(): Boolean = request("POST", "/ping", null, ByteArray(0), "application/json").first in 200..299

    // ------------------------------------------------------------------ internals

    private fun request(
        method: String,
        path: String,
        query: String?,
        body: ByteArray?,
        contentType: String?,
    ): Pair<Int, String> {
        val sentPath = apiPrefix + path
        val url = base + sentPath + if (query.isNullOrEmpty()) "" else "?$query"
        return send(method, URI.create(url), body, contentType)
    }

    /**
     * The ONE place a call/push request is built, signed and sent — contract §7 "one signing
     * helper shared by all call sites". The signature covers the exact [body] bytes that go
     * on the wire and the path of the URL as requested (query excluded).
     */
    private fun send(method: String, uri: URI, body: ByteArray?, contentType: String?): Pair<Int, String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofByteArray(body)
        val builder = HttpRequest.newBuilder(uri).timeout(readTimeout)
        signer?.sign(method, signedPathFor(uri.rawPath), body ?: ByteArray(0), timestampMs = clockMs())
            ?.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", userAgent)
        contentType?.let { builder.header("Content-Type", it) }
        builder.method(method, publisher)
        return try {
            val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            resp.statusCode() to (resp.body() ?: "")
        } catch (e: Exception) {
            // -1 is "never reached the server", kept distinct from every HTTP answer for
            // the same reason V2Http keeps it: a caller deciding whether to keep ringing
            // must not read a network outage as a refusal.
            -1 to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * The path we SIGN, given the path we SENT: the full request path, prefix INCLUDED.
     *
     * __CALL_PUSH_AUTH_DESKTOP_2026_09_23__ `docs/CALL_PUSH_AUTH_CONTRACT.md` §2 (STABLE 1.0):
     * "sign the path exactly as it appears in the request line you send"; the server
     * re-adds the prefixes nginx strips (`/api/call`, `/voip`, `/push`) when verifying.
     * Until that contract this client signed the STRIPPED path — which the server still
     * accepts — and the SIGNING section above explains why that used to be the only spelling
     * that verified. `FakeCallServer` verifies the way the patched server does.
     */
    fun signedPathFor(sentPath: String): String = sentPath

    /**
     * The three `x-oshi-*` headers for the WebSocket relay's upgrade GET — contract §4, what
     * iOS signs: `GET\n<path as sent>\n<sha256 of nothing>\n<ts>`. Empty when unsigned.
     */
    fun webSocketUpgradeHeaders(path: String): Map<String, String> =
        signer?.sign("GET", path, ByteArray(0), timestampMs = clockMs()) ?: emptyMap()

    companion object {
        /** nginx's `location /api/call/`. Both shipped clients hard-code it. */
        const val API_PREFIX = "/api/call"

        /** nginx's `location /push/` → push_service :8084 (prefix stripped). */
        const val PUSH_PREFIX = "/push"

        /** `OSHI-Desktop/<version>` — contract §7. The version is the jar manifest's, "dev" in a source run. */
        fun defaultUserAgent(): String =
            "OSHI-Desktop/" + (CallSignalClient::class.java.`package`?.implementationVersion ?: "dev")

        /** The call server's own port, for a direct connection with no proxy in front. */
        const val DIRECT_PORT = 8083

        /**
         * `+`→`-`, `/`→`_`, drop `=`. Byte-identical to `publicKeyToBase64url`
         * (`VPSClient.kt:493-496`) and to iOS's `base64urlEncode`, and to the server's own
         * `normalizeKey` (`call_server.js:56-59`), which is what its maps are keyed on.
         *
         * NOT `ContactQr`'s job and not [DesktopV2Signer.encodeIdentity]'s: this is the one
         * route family in the whole client that speaks base64url on the wire, and PLAN.md
         * §4.1's rule is that base64url appears only in URL path segments, QR payloads and
         * the legacy notify path. This is a URL path segment.
         */
        fun base64Url(address: String): String =
            address.trim().replace('+', '-').replace('/', '_').trimEnd('=')

        /** Contract §2/§7 T1: `{"identity":…,"peer":…,"callId":…}` in that order, no spaces. */
        fun relayTokenBody(identity: String, peer: String, callId: String): String =
            "{\"identity\":" + JSONObject.quote(base64Url(identity)) +
                ",\"peer\":" + JSONObject.quote(base64Url(peer)) +
                ",\"callId\":" + JSONObject.quote(callId) + "}"
    }
}
