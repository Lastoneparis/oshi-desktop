package com.oshi.desktop.net

import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHICryptoV2Streaming
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/** `POST /v2/blobs` response — iOS `V2.ReserveResult`, Android `V2Reservation`. */
data class V2Reservation(val blobId: String, val chunkSize: Int, val missing: List<Int>)

/** `GET /v2/blobs/{id}/status` response — iOS `V2.BlobStatus`, Android `V2BlobStatus`. */
data class V2BlobStatus(val blobId: String, val missing: List<Int>, val committed: Boolean, val expiresAt: Double)

/**
 * OSHI V2 blob store (`/v2/blobs`) — desktop port of Android `V2BlobClient.kt`, byte- and
 * route-compatible with both shipped clients (iOS `V2Client.swift:197-360,444-495,589-624`).
 *
 * BODY-HASH DISCIPLINE (the part that silently 401s if you get it wrong) — the blob
 * handlers verify the signature BEFORE reading the streamed body, so `oshi_auth` hashes
 * the literal two bytes `""` for every route except reserve (a plain JSON POST) and
 * commit (which goes through the JSON body reader and therefore hashes the actual,
 * empty, body):
 *
 *   reserve    POST   /v2/blobs                   → hash the exact JSON bytes sent, NO x-oshi-user
 *   upload     PUT    /v2/blobs/{id}/chunk/{i}    → hash `""`     (V2Http.put, default bodyToHash arg)
 *   status     GET    /v2/blobs/{id}/status       → hash `""`
 *   commit     POST   /v2/blobs/{id}/commit       → hash 0 bytes  (V2Http.postEmpty)
 *   download   GET    /v2/blobs/{id}/chunk/{i}    → hash `""`
 *   delete     DELETE /v2/blobs/{id}              → hash `""`     (V2Http.delete's own default)
 *
 * Every route but reserve carries `x-oshi-user` (the server authorises owner-or-recipient);
 * `blobId` is NOT percent-encoded — the blob store does not decode its path segments and a
 * blob id is always server-generated hex/UUID, never user input (PLAN.md §4.1 is about
 * *identities* in a path, which blob ids are not).
 *
 * THE CHUNK DOWNLOAD IS THE AWKWARD ONE, and it was fixed at the root rather than worked
 * around here. Its response is AES-GCM ciphertext, so it cannot come back through a
 * `String` (bytes that are not valid UTF-8 return as replacement characters and the tag
 * check then fails on data that arrived intact), AND it is a verify-first route, so it
 * hashes the two-byte `""` where an ordinary GET hashes zero bytes. `V2Http.getBytes` now
 * takes the body-to-hash as a parameter, so this client owns no HTTP or signing code of
 * its own — one route's signing rule living in a second HTTP stack is precisely how two
 * implementations of the same protocol start to drift.
 */
class V2BlobClient(
    private val http: V2Http,
    private val identity: DesktopIdentity,
) {
    // ---- Routes ---------------------------------------------------------------------

    /** Reserve a blob for one recipient. Returns null on any non-2xx / transport error. */
    fun reserve(recipientKey: String, totalSize: Int, chunkCount: Int): V2Reservation? {
        if (totalSize <= 0 || chunkCount < 1) return null
        if (totalSize > MAX_BLOB_BYTES) return null   // refuse before the round-trip, not after a 413
        val body = JSONObject().apply {
            put("ownerKey", identity.userKey)
            put("recipientKey", recipientKey)
            put("totalSize", totalSize)
            put("chunkCount", chunkCount)
        }.toString().toByteArray(Charsets.UTF_8)
        // reserve is the ONE blob route that does NOT carry x-oshi-user: the owner travels
        // in the body as `ownerKey` (iOS V2Client.swift:214-227 — no withUserHeader; Android
        // V2BlobClient.kt:70 — same). postJson's default withUserHeader is already false;
        // named here so the omission reads as a decision, not an oversight.
        val resp = http.postJson("/v2/blobs", body, withUserHeader = false)
        if (!resp.isSuccess) return null
        return try {
            val o = JSONObject(resp.body)
            V2Reservation(
                blobId = o.getString("blobId"),
                chunkSize = o.optInt("chunkSize", OSHICryptoV2.DEFAULT_CHUNK_SIZE),
                missing = o.optJSONArray("missing").toIntList(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** PUT one chunk. Idempotent server-side, so a retry after an ambiguous drop is safe. */
    fun uploadChunk(blobId: String, index: Int, data: ByteArray): Boolean {
        val path = "/v2/blobs/$blobId/chunk/$index"
        // Verified BEFORE the streamed body is read ⇒ sign over `""`, not the chunk bytes —
        // V2Http.put's default bodyToHash arg is exactly that two-byte string, so it is not
        // overridden here; only the actual ciphertext travels as the sent body.
        return http.put(path, data, DesktopV2Signer.EMPTY_JSON_STRING_BODY).isSuccess
    }

    /** Non-destructive status: which chunk indices the server is still missing. */
    fun status(blobId: String): V2BlobStatus? {
        val path = "/v2/blobs/$blobId/status"
        val resp = http.request(
            "GET", path,
            bodyToHash = DesktopV2Signer.EMPTY_JSON_STRING_BODY,
            body = null,
            withUserHeader = true,
        )
        if (!resp.isSuccess) return null
        return try {
            val o = JSONObject(resp.body)
            V2BlobStatus(
                blobId = o.optString("blobId", blobId),
                missing = o.optJSONArray("missing").toIntList(),
                committed = o.optBoolean("committed", false),
                expiresAt = o.optDouble("expiresAt", 0.0),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finalize. False means the server still has gaps (a 409) or the request otherwise
     * failed — Android's port returns a plain Boolean here rather than iOS's typed
     * `chunkStillMissing(missing)`; [uploadBlob] already re-checks `status` and tops up
     * immediately before calling this, so a 409 reaching a caller is the rare case, not
     * the steered one.
     */
    fun commit(blobId: String): Boolean {
        val path = "/v2/blobs/$blobId/commit"
        // Commit DOES go through the server's JSON body reader ⇒ hash the actual (empty)
        // body, i.e. zero bytes — V2Http.postEmpty exists for exactly this shape.
        return http.postEmpty(path, withUserHeader = true).isSuccess
    }

    /** Download one chunk's opaque ciphertext-plus-tag. Null on any non-2xx / transport error. */
    fun downloadChunk(blobId: String, index: Int): ByteArray? {
        if (index < 0) return null
        val (code, bytes) = http.getBytes(
            path = "/v2/blobs/$blobId/chunk/$index",
            withUserHeader = true,
            // Verify-first route: the two-byte string `""`, not zero bytes.
            bodyToHash = DesktopV2Signer.EMPTY_JSON_STRING_BODY,
            timeout = CHUNK_TIMEOUT,
        )
        return if (code in 200..299) bytes else null
    }

    /** Delete-after-delivery. 404 counts as success — the blob is already gone. */
    fun delete(blobId: String): Boolean {
        // V2Http.delete's own defaults are already this route's rules: hash `""`, carry
        // x-oshi-user. Nothing to override.
        val resp = http.delete("/v2/blobs/$blobId")
        return resp.isSuccess || resp.code == 404
    }

    // ---- Orchestration ----------------------------------------------------------------

    /**
     * reserve → PUT every missing chunk → re-check status and top up → commit. Mirrors
     * Android `V2BlobClient.uploadBlob` (network/v2/V2BlobClient.kt:206-254), including the
     * status top-up before commit so a dropped PUT surfaces here instead of as a 409 (or,
     * worse, a committed-but-incomplete file). Sequential, not bounded-parallel like the
     * mobile clients — this API is deliberately synchronous (no coroutines), and chunk PUTs
     * are idempotent and order-independent, so the only cost of going serial is wall-clock
     * time, never correctness. Returns the blobId, or null if any step failed.
     *
     * @param resumeBlobId a reservation a PREVIOUS attempt made for these exact bytes. Only
     *   the indices `/status` still reports missing are sent. Null → a fresh reserve.
     * @param onReserved invoked with a FRESH blobId the instant `reserve` returns — before
     *   the first chunk goes out — so a caller can persist it before a crash mid-transfer.
     */
    fun uploadBlob(
        recipientKey: String,
        chunks: List<ByteArray>,
        resumeBlobId: String? = null,
        onReserved: ((String) -> Unit)? = null,
    ): String? {
        if (chunks.isEmpty()) return null
        val totalSize = chunks.sumOf { it.size }
        // RESUME. A prior attempt's reservation is reused when the server still holds it
        // and its chunk count still matches; the caller has already proved the plaintext is
        // unchanged (whatever binds the resume decision lives above this class), so
        // re-encryption is byte-identical and the gaps can simply be topped up. Anything
        // unexpected falls through to a fresh reserve — "no resume" is always a valid
        // answer, a wrong resume is not.
        val reservation = resumeBlobId?.let { id ->
            val st = status(id)
            when {
                st == null -> null
                st.committed -> V2Reservation(id, OSHICryptoV2.DEFAULT_CHUNK_SIZE, emptyList())
                st.missing.any { it < 0 || it >= chunks.size } -> null
                else -> V2Reservation(id, OSHICryptoV2.DEFAULT_CHUNK_SIZE, st.missing)
            }
        } ?: reserve(recipientKey, totalSize, chunks.size)?.also { onReserved?.invoke(it.blobId) }
            ?: return null
        // A resumed reservation with NOTHING missing must not be re-uploaded whole.
        val resumed = resumeBlobId != null && reservation.blobId == resumeBlobId

        fun put(indices: List<Int>): Boolean {
            for (n in indices) {
                if (n < 0 || n >= chunks.size) return false
                if (!uploadChunk(reservation.blobId, n, chunks[n])) return false
            }
            return true
        }

        // A fresh reserve lists every index as missing; a resumed one lists the gaps — and
        // an empty gap list on a RESUMED blob means "all present", not "send it all".
        val first = when {
            reservation.missing.isNotEmpty() -> reservation.missing
            resumed -> emptyList()
            else -> chunks.indices.toList()
        }
        if (!put(first)) return null
        // [status top-up] Don't trust the single reserve/resume snapshot: re-check the live
        // missing set before committing. A dropped PUT that slipped past its own retry would
        // otherwise surface as a 409 at commit — or worse, an incomplete file if the server
        // ever raced.
        val st = status(reservation.blobId)
        if (st != null && st.missing.isNotEmpty() && !put(st.missing)) return null
        return if (commit(reservation.blobId)) reservation.blobId else null
    }

    /**
     * Fetch every chunk IN ORDER — iOS `downloadBlob` (V2Client.swift:589-624), Android
     * `downloadBlob`. Order is load-bearing: the per-chunk AEAD nonce is `fileNonce ‖
     * u32be(i)`, so a scrambled reassembly reads as "corrupt media" rather than as a bug
     * here. Returns null if any chunk is missing, so the caller can hold the relay ack.
     */
    fun downloadBlob(blobId: String, chunkCount: Int): List<ByteArray>? {
        if (chunkCount < 1) return null
        val out = ArrayList<ByteArray>(chunkCount)
        for (i in 0 until chunkCount) {
            out.add(downloadChunk(blobId, i) ?: return null)
        }
        return out
    }

    /**
     * STREAMING download — the memory-bounded counterpart of [downloadBlob]. Android
     * `downloadAndDecryptToFile` on top of the streaming crypt in
     * [OSHICryptoV2Streaming.decryptToStream] (peak memory ≈ one chunk, independent of file
     * size, instead of [downloadBlob] + [OSHICryptoV2.decryptFile]'s ~2× the file size).
     *
     * The bytes written are byte-identical to what [OSHICryptoV2.decryptFile] would return —
     * this is an internal restructuring, not a protocol change. [out] is deleted on any
     * failure so a partial file can never be mistaken for media.
     *
     * @return the plaintext size, or null when a chunk was unavailable or the blob failed to
     *   open (the caller then holds the relay ack, exactly as before).
     */
    fun downloadAndDecryptToFile(
        blobId: String,
        chunkCount: Int,
        fileKey: ByteArray,
        fileNonce: ByteArray,
        manifest: ByteArray,
        out: File,
    ): Long? {
        if (chunkCount < 1) return null
        return try {
            out.parentFile?.mkdirs()
            var written = 0L
            BufferedOutputStream(FileOutputStream(out)).use { sink ->
                written = OSHICryptoV2Streaming.decryptToStream(
                    fileKey = fileKey,
                    fileNonce = fileNonce,
                    manifest = manifest,
                    out = sink,
                    expectedChunkCount = chunkCount,
                ) { i -> downloadChunk(blobId, i) }
            }
            written
        } catch (e: Throwable) {
            out.delete()
            null
        }
    }

    companion object {
        /** iOS `V2.maxPlaintextBytes` / Android `MAX_PLAINTEXT_BYTES` (V2ClientModels.swift:248). */
        const val MAX_PLAINTEXT_BYTES = 200 * 1024 * 1024

        /** iOS `V2.maxBlobBytes` / Android `MAX_BLOB_BYTES` = 200 MB + 1 MiB AEAD slack (V2ClientModels.swift:255). */
        const val MAX_BLOB_BYTES = 200 * 1024 * 1024 + 1024 * 1024

        /**
         * Per-CHUNK read timeout. A chunk is at most 2 MiB, but a phone uploading over a
         * weak uplink can take a while to have it available — and this is one chunk of a
         * transfer that resumes, so a timeout here costs a retry of 2 MiB, never the file.
         */
        private val CHUNK_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}

/** Null-safe: a missing/absent `missing` array reads as "nothing reported", not a crash. */
private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return (0 until length()).map { optInt(it) }
}
