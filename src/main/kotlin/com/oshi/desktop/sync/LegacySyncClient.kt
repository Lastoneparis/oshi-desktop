package com.oshi.desktop.sync

import com.oshi.desktop.net.V2Http
import com.oshi.desktop.sync.SyncProtocol.Archive
import com.oshi.desktop.sync.SyncProtocol.LegacyKind
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The `/api/sync/{kind}/{key}` blob archive — PARITY.md row 0.24, and the ONLY multi-device
 * sync that is live on a shipped phone today (see [SyncProtocol]).
 *
 * iOS `MultiDeviceSyncManager.swift:459-1001`, Android `MultiDeviceSyncManager.kt:1163-1369`.
 * Five reachable kinds ([LegacyKind]); the seven device-registry routes iOS also calls 404
 * on the live server and are not implemented here or on Android.
 *
 * ============================================================ WHY THIS IS NOT [V2Http]
 *
 * Because these routes are **unauthenticated**, and running them through the signer would
 * be worse than useless in two directions at once. It would attach `x-oshi-signature` and
 * `x-oshi-signing-pubkey` headers to a request the server does not verify — a signed
 * assertion of identity handed to a route with no owner check, which is a credential
 * spent for nothing. And it would make this file look like the rest of the V2 surface, so
 * the next reader would assume an owner check exists here. The honest shape of a route
 * with no auth is a client with no signer.
 *
 * That is the same reasoning [V2Http]'s `getBytes` doc gives in reverse: one route's
 * signing rule must not leak into another route's stack. Here the rule is "there is no
 * rule", and it still must not leak.
 *
 * ============================================================ THE SECURITY PROPERTY
 *
 * Stated plainly because this is a shipped property of a shipped messenger and softening
 * it in a doc comment would be the mistake PARITY.md row 0.16 refuses to make:
 *
 *   - The path segment is the identity's own **public** key, base64url-unpadded
 *     ([SyncProtocol.legacyPathKey]) — the same string a QR code hands out (row 0.22).
 *   - There is no signature, no bearer token and no `x-oshi-*` header on either verb.
 *     iOS: a bare `URLSession.dataTask` (`swift:1035-1041`). Android: a bare OkHttp
 *     `Request.Builder().url(...).post(body)` (`.kt:1169-1195`).
 *   - Therefore anyone holding a user's public key can **read** that user's archive blob
 *     and can **overwrite** it.
 *   - The blob is AES-256-GCM under a key only the identity's private key can derive
 *     ([SyncCrypto.legacyArchiveKey]), so confidentiality holds against that stranger.
 *     Integrity and availability do not: an overwritten blob decrypts on no device, and
 *     since each kind is a whole-blob overwrite rather than a log, there is no earlier
 *     version to fall back to.
 *   - This client therefore treats a blob that fails to decrypt as **hostile or corrupt,
 *     never as empty** ([pull] returns a failure, not a null-as-success), because on an
 *     unauthenticated route "I could not decrypt this" and "there is nothing here" have
 *     completely different causes and the second one is a legitimate answer the server
 *     gives in its own words: `{"success":true,"data":null}` (`.kt:1215-1218`).
 *
 * ============================================================ CONFLICT RESOLUTION
 *
 * There is none. Each kind is one slot, last upload wins wholesale, and the only thing
 * that stops that being destructive is that both platforms merge a PULL gap-fill-only —
 * they never delete a local row because it is absent from the archive
 * (`swift:634-640`, `.kt:1245-1250`). Reproduced in [ContactSyncRecord.applyLegacyAliasMap].
 */
class LegacySyncClient(
    private val ownerPublicKey: String,
    private val deviceId: String,
    private val archiveKey: ByteArray,
    private val baseUrl: String = V2Http.defaultBaseUrl(),
    connectTimeout: Duration = Duration.ofSeconds(15),
    private val readTimeout: Duration = Duration.ofSeconds(30),
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    val archive: Archive get() = Archive.LEGACY

    /**
     * `POST /api/sync/{kind}/{key}` with `{"deviceId":…,"data":<base64 AES-GCM>}`.
     *
     * Note the `deviceId` is NOT on the query string for the upload and IS for the
     * download — Android encodes that asymmetry in a single `withDeviceId` flag
     * (`.kt:1163-1167`) and both shipped clients do it the same way, so it is reproduced
     * rather than tidied.
     */
    fun push(kind: LegacyKind, plaintext: ByteArray): Result<Unit> {
        val ciphertext = SyncCrypto.seal(archiveKey, plaintext)
        val body = SyncProtocol.encodeLegacyUploadBody(deviceId, ciphertext)
        val req = HttpRequest.newBuilder(URI.create(url(kind, withDeviceId = false)))
            .timeout(readTimeout)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) Result.success(Unit)
            else Result.failure(RuntimeException("legacy sync upload ${kind.wire} HTTP ${resp.statusCode()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * `GET /api/sync/{kind}/{key}?deviceId=…`.
     *
     * Three outcomes, and they are three, not two:
     *   - `success(bytes)` — a blob that decrypted,
     *   - `success(null)` — the server said `data: null`, i.e. nothing stored yet. Android
     *     counts this as having reached the server (`.kt:1215-1218`) and so does this.
     *   - `failure` — transport error, non-2xx, unreadable body, **or a blob that did not
     *     decrypt**. See the class doc: on an unauthenticated route the last of those is a
     *     signal, not noise.
     */
    fun pull(kind: LegacyKind): Result<ByteArray?> {
        val req = HttpRequest.newBuilder(URI.create(url(kind, withDeviceId = true)))
            .timeout(readTimeout)
            .GET()
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (resp.statusCode() !in 200..299) {
            return Result.failure(RuntimeException("legacy sync pull ${kind.wire} HTTP ${resp.statusCode()}"))
        }
        val b64 = SyncProtocol.parseLegacyPullData(resp.body() ?: "")
            ?: return Result.success(null)
        return try {
            Result.success(SyncCrypto.open(archiveKey, b64))
        } catch (e: SyncCryptoException) {
            Result.failure(
                SyncCryptoException(
                    "legacy ${kind.wire} archive did not decrypt. This route has NO authentication " +
                        "(MultiDeviceSyncManager.kt:1169-1226): anyone holding this public key can " +
                        "overwrite the slot. Not reported as an empty archive.",
                    e,
                )
            )
        }
    }

    private fun url(kind: LegacyKind, withDeviceId: Boolean): String {
        val base = "$baseUrl/api/sync/${kind.wire}/${SyncProtocol.legacyPathKey(ownerPublicKey)}"
        return if (withDeviceId) "$base?deviceId=$deviceId" else base
    }
}
