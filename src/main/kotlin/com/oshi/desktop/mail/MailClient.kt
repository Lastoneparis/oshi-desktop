package com.oshi.desktop.mail

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.desktop.mail.MailCrypto.toHex
import com.oshi.desktop.net.V2Http
import com.oshi.desktop.store.KeyVault
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * OSHI Mail on desktop — the counterpart of Android `OshiMailClient` and iOS
 * `OSHIMailClient`.
 *
 * There is no mail password: the mailbox is owned by this device's OSHI identity, and
 * [DesktopV2Signer] — the SAME Ed25519 request signer every other `/v2/` route on this
 * client already uses — proves it (SERVER API note in the task brief: "Auth is the SAME
 * Ed25519 scheme the desktop app already uses"). The mail X25519 key is a SEPARATE key
 * from that identity (see [MailCrypto]'s class note) and lives only here, in [KeyVault] —
 * the same encrypted-at-rest store [com.oshi.desktop.app.OshiClient] keeps every other
 * private key in. Losing it loses the mail: the server never holds a readable copy.
 *
 * Base URL is the APEX host (`https://oshi-messenger.com`), not the mail domain: the API
 * is served from the apex under its existing certificate, while addresses are
 * `@mail.oshi-messenger.com`.
 */
class MailClient(
    private val identity: DesktopIdentity,
    private val vault: KeyVault,
    baseUrl: String = V2Http.defaultBaseUrl(),
) {
    companion object {
        const val MAIL_DOMAIN = "mail.oshi-messenger.com"
        const val VAULT_KEY = "mail-x25519-private"

        /**
         * The server caps one message at 25 MB and base64 inflates by about a third, so
         * this is the real ceiling on raw attachment bytes. Checked before the upload
         * rather than after it — matches Android's `maxAttachmentTotalBytes`.
         */
        const val MAX_ATTACHMENT_TOTAL_BYTES = 18 * 1024 * 1024
    }

    class MailException(val code: String, val status: Int = 0, message: String? = null) :
        Exception(message ?: code)

    private val http = V2Http(DesktopV2Signer(identity), baseUrl)

    /** Unauthenticated calls only (availability check, pairing inspect) — no signer involved. */
    private val rawHttp: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val rawBaseUrl = baseUrl

    // ---- the mail key --------------------------------------------------------------

    /**
     * DERIVED from this device's OSHI identity — HKDF-SHA256 over the X25519 encryption
     * private key ([DesktopIdentity.deriveMailKeyMaterial]), the exact same derivation iOS
     * (`OSHIMailClient.mailPrivateKey`) and Android (`OshiMailClient.mailPrivateKey`) use.
     * This is what makes the mailbox and drive recoverable from the identity seed / key
     * backup alone: restore the account and the same mail key falls out, with nothing to
     * sync out of band. The server never holds a readable copy — zero-access is unchanged.
     *
     * The [vault] path is a FALLBACK only, kept for the case where no identity material is
     * available (it always is here, since the constructor requires one) — a device-local,
     * NON-recoverable random key, matching the mobile clients' own fallback branch.
     */
    private fun mailPrivateKey(): ByteArray =
        runCatching { identity.deriveMailKeyMaterial() }.getOrNull()
            ?: vault.getOrCreate(VAULT_KEY) { MailCrypto.generateMailKeyPair().first }

    fun mailPublicKey(): ByteArray = MailCrypto.publicKeyFor(mailPrivateKey())

    fun mailPublicKeyHex(): String = mailPublicKey().toHex()

    /**
     * A stable alias derived from the public mail key, matching iOS. Eighteen lowercase
     * hex characters fit the server's local-part rules while avoiding a chosen, linkable
     * name; it never reveals the private mail key.
     */
    val keyBasedLocalpart: String get() = mailPublicKeyHex().take(18).lowercase()

    /** Reverses [seal] using this device's own mail private key. */
    fun open(blob: ByteArray): ByteArray = MailCrypto.open(blob, mailPrivateKey())

    /** Seals to this device's OWN mail key — used for drafts and drive metadata. */
    fun sealForSelf(plaintext: ByteArray): ByteArray = MailCrypto.seal(plaintext, mailPublicKey())

    private fun openB64(sealedB64: String): ByteArray = open(Base64.getDecoder().decode(sealedB64))

    // ---- signed transport -----------------------------------------------------------

    private fun callJson(method: String, path: String, body: JSONObject? = null): JSONObject {
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        val resp = when (method) {
            "GET" -> http.get(path)
            "POST" -> if (bytes != null) http.postJson(path, bytes) else http.postEmpty(path)
            "DELETE" -> http.delete(path)
            else -> http.request(method, path, bodyToHash = bytes ?: ByteArray(0), body = bytes,
                contentType = if (bytes != null) "application/json; charset=utf-8" else null)
        }
        if (!resp.isSuccess) throw resp.toException()
        return if (resp.body.isBlank()) JSONObject() else JSONObject(resp.body)
    }

    private fun V2Http.Response.toException(): MailException {
        val errorCode = runCatching { JSONObject(body).optString("error", "unknown") }.getOrDefault("unknown")
        return MailException(errorCode, code, body.take(300))
    }

    // ---- account ----------------------------------------------------------------------

    data class Account(
        val address: String,
        val localpart: String,
        val aliases: List<String>,
        val aliasLimit: Int,
        val storageUsed: Long,
        val storageQuota: Long,
        val messages: Int,
        val sent: Int,
        val sendQuota: Int,
        val sendRemaining: Int,
        val driveUsed: Long,
        val driveQuota: Long,
        val driveFiles: Int,
    )

    private fun parseAccount(o: JSONObject): Account {
        val a = o.getJSONObject("account")
        val storage = a.optJSONObject("storage") ?: JSONObject()
        val sending = a.optJSONObject("sending") ?: JSONObject()
        val drive = a.optJSONObject("drive") ?: JSONObject()
        val aliases = a.optJSONArray("aliases") ?: JSONArray()
        return Account(
            address = a.getString("address"),
            localpart = a.getString("localpart"),
            aliases = (0 until aliases.length()).map { aliases.getString(it) },
            aliasLimit = a.optInt("aliasLimit", 10),
            storageUsed = storage.optLong("used", 0),
            storageQuota = storage.optLong("quota", 150L * 1024 * 1024),
            messages = storage.optInt("messages", 0),
            sent = sending.optInt("sent", 0),
            sendQuota = sending.optInt("quota", 150),
            sendRemaining = sending.optInt("remaining", 150),
            driveUsed = drive.optLong("used", 0),
            driveQuota = drive.optLong("quota", 1024L * 1024 * 1024),
            driveFiles = drive.optInt("files", 0),
        )
    }

    /** Unauthenticated and rate-limited server-side; safe to call while the user types. */
    fun isAvailable(localpart: String): Result<Boolean> = runCatching {
        val url = "$rawBaseUrl/mail/v1/availability?localpart=${localpart.lowercase()}"
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val resp = rawHttp.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) throw MailException("http_${resp.statusCode()}", resp.statusCode())
        JSONObject(resp.body()).optBoolean("available", false)
    }

    /** Claims an address, publishing only the PUBLIC half of the mail key. */
    fun register(localpart: String): Result<Account> = runCatching {
        parseAccount(
            callJson(
                "POST", "/mail/v1/register",
                JSONObject()
                    .put("localpart", localpart.lowercase())
                    .put("mailPubKey", mailPublicKeyHex())
                    // The X25519 messaging identity (standard-padded base64 of the public
                    // key) — the same key the push service already knows this device by —
                    // so the server can wake the device when mail is delivered. Not secret,
                    // not the mail key; harmless if unused. Matches iOS/Android register.
                    .put("identityPubKey", identity.userKey),
            )
        )
    }

    fun account(): Result<Account> = runCatching { parseAccount(callJson("GET", "/mail/v1/account")) }

    // ---- inbox ------------------------------------------------------------------------

    data class Envelope(val from: String, val to: String, val subject: String, val date: String, val cc: String = "", val inReplyTo: String? = null)

    data class MailSummary(
        val id: String,
        val receivedAt: String,
        val bytes: Long,
        val seen: Boolean,
        val folder: String,
        val deliveredTo: String,
        /** null when the envelope was sealed for a different device, or absent (drafts/sent may omit it). */
        val envelope: Envelope?,
    )

    data class InboxResult(val messages: List<MailSummary>, val storageUsed: Long?, val storageQuota: Long?)

    private fun parseEnvelope(sealedB64: String?): Envelope? {
        if (sealedB64.isNullOrEmpty()) return null
        return runCatching {
            val plain = JSONObject(String(openB64(sealedB64), Charsets.UTF_8))
            Envelope(
                from = plain.optString("from"),
                to = plain.optString("to"),
                subject = plain.optString("subject"),
                date = plain.optString("date"),
                cc = plain.optString("cc"),
                inReplyTo = plain.optString("inReplyTo").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun parseMessages(arr: JSONArray): List<MailSummary> = (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        MailSummary(
            id = m.getString("id"),
            receivedAt = m.optString("receivedAt"),
            bytes = m.optLong("bytes", 0),
            seen = m.optBoolean("seen"),
            folder = m.optString("folder", "inbox"),
            deliveredTo = m.optString("deliveredTo"),
            envelope = parseEnvelope(m.optString("sealedEnvelope", "").takeIf { it.isNotEmpty() }),
        )
    }

    /**
     * Every message this account can see, across every folder — folder filtering is a
     * client-side concern (see [MailModel]), because the server route itself is one flat
     * list.
     *
     * The query string (`?limit=N`) is sent but never SIGNED — [V2Http.get]'s `query`
     * parameter keeps the two apart so a caller cannot accidentally sign one string and
     * fetch another (see [V2Http]'s class note, trap 1).
     */
    fun inbox(limit: Int = 100): Result<InboxResult> = runCatching {
        val resp = http.get("/mail/v1/inbox", query = "limit=$limit")
        if (!resp.isSuccess) throw resp.toException()
        val o = if (resp.body.isBlank()) JSONObject() else JSONObject(resp.body)
        val messages = parseMessages(o.optJSONArray("messages") ?: JSONArray())
        val storage = o.optJSONObject("storage")
        InboxResult(
            messages,
            storage?.takeIf { it.has("used") }?.optLong("used"),
            storage?.takeIf { it.has("quota") }?.optLong("quota"),
        )
    }

    /** The full RFC822 message, decrypted on this device. Feed straight into [MailMime.parse]. */
    fun message(id: String): Result<ByteArray> = runCatching {
        val (code, bytes) = http.getBytes("/mail/v1/message/${encodeId(id)}")
        if (code !in 200..299) throw MailException("http_$code", code)
        open(bytes)
    }

    fun markSeen(id: String, seen: Boolean = true): Result<Unit> = runCatching {
        callJson("POST", "/mail/v1/message/${encodeId(id)}/seen", JSONObject().put("seen", seen))
        Unit
    }

    fun move(id: String, folder: String): Result<Unit> = runCatching {
        callJson("POST", "/mail/v1/message/${encodeId(id)}/move", JSONObject().put("folder", folder))
        Unit
    }

    /**
     * First call moves it to TRASH; a second call (or [permanent]) destroys it. `permanent`
     * rides the query string, never the signature, exactly like [inbox]'s `limit`.
     */
    fun delete(id: String, permanent: Boolean = false): Result<Unit> = runCatching {
        val path = "/mail/v1/message/${encodeId(id)}"
        val resp = http.request(
            "DELETE", path, query = if (permanent) "permanent=1" else null,
            bodyToHash = DesktopV2Signer.EMPTY_JSON_STRING_BODY, body = null,
        )
        if (!resp.isSuccess) throw resp.toException()
    }

    private fun encodeId(id: String): String = DesktopV2Signer.encodeIdentity(id)

    // ---- sending ------------------------------------------------------------------------

    data class OutgoingAttachment(val filename: String, val mimeType: String, val bytes: ByteArray)

    /**
     * Counted against the 150/month allowance. [from] may only be one of this account's own
     * aliases — the server answers 403 to anything else rather than letting a client forge
     * a sender. Plaintext on the wire to `/mail/v1/send` on purpose: the server has to read
     * subject/body/attachments to compose and deliver the RFC822 message (including to
     * recipients outside OSHI Mail entirely). Zero-access covers what the server STORES for
     * this mailbox to read back later, not the one-shot act of sending.
     */
    fun send(
        to: List<String>,
        subject: String,
        text: String,
        cc: List<String> = emptyList(),
        from: String? = null,
        inReplyTo: String? = null,
        attachments: List<OutgoingAttachment> = emptyList(),
    ): Result<String> = runCatching {
        require(attachments.sumOf { it.bytes.size } <= MAX_ATTACHMENT_TOTAL_BYTES) {
            "attachments exceed $MAX_ATTACHMENT_TOTAL_BYTES bytes"
        }
        val payload = JSONObject()
            .put("to", JSONArray(to))
            .put("subject", subject)
            .put("text", text)
        if (cc.isNotEmpty()) payload.put("cc", JSONArray(cc))
        from?.let { payload.put("from", it) }
        inReplyTo?.let { payload.put("inReplyTo", it) }
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            attachments.forEach {
                arr.put(
                    JSONObject()
                        .put("filename", it.filename)
                        .put("mimeType", it.mimeType)
                        .put("dataBase64", Base64.getEncoder().encodeToString(it.bytes)),
                )
            }
            payload.put("attachments", arr)
        }
        callJson("POST", "/mail/v1/send", payload).optString("messageId")
    }

    // ---- drafts (sealed to our own mail key) ---------------------------------------------

    /**
     * @param envelopeJson e.g. `{"to":[...],"subject":...,"date":...}`, sealed to OUR OWN
     *        mail key before it leaves the device — a draft is mail nobody but us should
     *        ever read.
     * @param bodyBytes the RFC822-ish body text, sealed the same way.
     * @return the draft id — pass it back on the next autosave to update in place.
     */
    fun saveDraft(id: String?, envelopeJson: ByteArray, bodyBytes: ByteArray): Result<String> = runCatching {
        val payload = JSONObject()
            .put("sealedEnvelope", Base64.getEncoder().encodeToString(sealForSelf(envelopeJson)))
            .put("sealedBody", Base64.getEncoder().encodeToString(sealForSelf(bodyBytes)))
        id?.let { payload.put("id", it) }
        callJson("POST", "/mail/v1/draft", payload).optString("id").ifEmpty { id.orEmpty() }
    }

    // ---- aliases ----------------------------------------------------------------------

    fun addAlias(alias: String): Result<List<String>> = runCatching {
        val out = callJson("POST", "/mail/v1/alias", JSONObject().put("alias", alias.lowercase()))
        val arr = out.optJSONArray("aliases") ?: JSONArray()
        (0 until arr.length()).map { arr.getString(it) }
    }

    fun removeAlias(alias: String): Result<List<String>> = runCatching {
        val localpart = alias.substringBefore('@')
        val out = callJson("DELETE", "/mail/v1/alias/${encodeId(localpart)}")
        val arr = out.optJSONArray("aliases") ?: JSONArray()
        (0 until arr.length()).map { arr.getString(it) }
    }

    // ---- drive (1 GB) -------------------------------------------------------------------

    data class DriveFile(val id: String, val bytes: Long, val createdAt: String, val name: String?)

    fun driveFiles(): Result<List<DriveFile>> = runCatching {
        val files = callJson("GET", "/mail/v1/drive").optJSONArray("files") ?: JSONArray()
        (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            val name = f.optString("sealedMeta", "").takeIf { it.isNotEmpty() }?.let { sealed ->
                runCatching { JSONObject(String(openB64(sealed), Charsets.UTF_8)).optString("name") }.getOrNull()
            }
            DriveFile(f.getString("id"), f.optLong("bytes", 0), f.optString("createdAt"), name)
        }
    }

    /**
     * [encrypted] must ALREADY be sealed by the caller — this method never sees plaintext.
     * Chunks are resumable: the server rejects an out-of-order offset and names the one it
     * expects, so a dropped connection resumes instead of restarting.
     */
    fun uploadToDrive(
        encrypted: ByteArray,
        name: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String> = runCatching {
        val sealedName = sealForSelf(JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8))
        val init = callJson(
            "POST", "/mail/v1/drive/upload",
            JSONObject().put("bytes", encrypted.size).put("sealedMeta", Base64.getEncoder().encodeToString(sealedName)),
        )
        val uploadId = init.getString("uploadId")
        val chunkSize = init.optInt("chunkSize", 8 * 1024 * 1024)

        var offset = 0
        while (offset < encrypted.size) {
            val end = minOf(offset + chunkSize, encrypted.size)
            val chunk = encrypted.copyOfRange(offset, end)
            // Not http.put(): that overload hardcodes query=null, and offset must ride the
            // (unsigned) query string exactly like inbox's limit and delete's permanent.
            val resp = http.request(
                "PUT", "/mail/v1/drive/upload/${encodeId(uploadId)}", query = "offset=$offset",
                bodyToHash = chunk, body = chunk, contentType = "application/octet-stream",
            )
            if (!resp.isSuccess) throw resp.toException()
            offset = runCatching { JSONObject(resp.body).optInt("received", end) }.getOrDefault(end)
            onProgress?.invoke(offset.toFloat() / encrypted.size)
        }

        val done = callJson("POST", "/mail/v1/drive/upload/${encodeId(uploadId)}/complete")
        done.optJSONObject("file")?.optString("id") ?: uploadId
    }

    fun downloadFromDrive(id: String): Result<ByteArray> = runCatching {
        val (code, bytes) = http.getBytes("/mail/v1/drive/file/${encodeId(id)}")
        if (code !in 200..299) throw MailException("http_$code", code)
        open(bytes)
    }

    fun renameDriveFile(id: String, newName: String): Result<Unit> = runCatching {
        val sealedName = sealForSelf(JSONObject().put("name", newName).toString().toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("sealedMeta", Base64.getEncoder().encodeToString(sealedName))
            .toString().toByteArray(Charsets.UTF_8)
        val resp = http.request(
            "PATCH", "/mail/v1/drive/file/${encodeId(id)}/meta",
            bodyToHash = json, body = json, contentType = "application/json; charset=utf-8",
        )
        if (!resp.isSuccess) throw resp.toException()
    }

    fun deleteFromDrive(id: String): Result<Unit> = runCatching {
        callJson("DELETE", "/mail/v1/drive/file/${encodeId(id)}")
        Unit
    }

    // ---- browser pairing (this desktop is a CLIENT of it, like a phone) -----------------

    data class PairingRequest(val code: String, val browserPublicKey: ByteArray, val ageSeconds: Int)

    /** Unauthenticated: reveals only an ephemeral public key and how old the request is. */
    fun inspectPairing(code: String): Result<PairingRequest> = runCatching {
        val clean = code.uppercase().filter { !it.isWhitespace() }
        val url = "$rawBaseUrl/mail/v1/web/pair/inspect?code=$clean"
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val resp = rawHttp.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            val err = runCatching { JSONObject(resp.body()).optString("error") }.getOrDefault("unknown")
            throw MailException(err, resp.statusCode())
        }
        val o = JSONObject(resp.body())
        PairingRequest(clean, MailCrypto.hexToBytes(o.getString("browserPubKey")), o.optInt("ageMs") / 1000)
    }

    /**
     * Hands this mailbox's PRIVATE key to that browser, sealed to the browser's own
     * ephemeral key. The server relays a blob it cannot open. This is the only action that
     * lets another device read the mailbox.
     */
    fun approvePairing(pairing: PairingRequest, label: String): Result<String> = runCatching {
        val sealedKey = MailCrypto.seal(mailPrivateKey(), pairing.browserPublicKey)
        callJson(
            "POST", "/mail/v1/web/pair/claim",
            JSONObject()
                .put("code", pairing.code)
                .put("sealedMailKey", Base64.getEncoder().encodeToString(sealedKey))
                .put("label", label),
        ).optString("address")
    }

    /** A browser which currently possesses a copy of this mailbox's sealed key. */
    data class WebSession(val id: String, val label: String, val createdAt: String, val lastSeenAt: String)

    fun webSessions(): Result<List<WebSession>> = runCatching {
        val sessions = callJson("GET", "/mail/v1/web/sessions").optJSONArray("sessions") ?: JSONArray()
        (0 until sessions.length()).map { index ->
            val session = sessions.getJSONObject(index)
            WebSession(
                id = session.getString("id"),
                label = session.optString("label").ifBlank { "Browser" },
                createdAt = session.optString("createdAt"),
                lastSeenAt = session.optString("lastSeenAt"),
            )
        }
    }

    /** [id] may be a session id, or `all` to sign every browser out at once. */
    fun revokeWebSession(id: String): Result<Int> = runCatching {
        callJson("DELETE", "/mail/v1/web/sessions/${encodeId(id)}").optInt("revoked", 0)
    }
}
