package com.oshi.messenger.network.v2

import java.io.IOException
import java.io.OutputStream

/**
 * Streaming half of the `/v2/blobs` file AEAD — iOS `OSHICryptoV2.swift:693-760`
 * ("peak memory ≈ chunkSize, independent of file size"), consumed by
 * `MessageManager+V2.swift:715-722` (`downloadAndDecryptFileToDisk`).
 *
 * [OSHICryptoV2.decryptFile] takes EVERY ciphertext chunk as a `List<ByteArray>` and
 * returns the whole plaintext as one more `ByteArray`, so a 150 MB video needs ~300 MB
 * of heap on a device with no `android:largeHeap`. This opens ONE chunk at a time and
 * appends its plaintext to a sink.
 *
 * NOTHING ABOUT THE WIRE FORMAT CHANGES. Same per-chunk nonce `fileNonce ‖ u32be(i)`,
 * same manifest under [OSHICryptoV2.MANIFEST_COUNTER], same `chunkCount`/`size`
 * assertions — a truncated or reordered download still fails loudly instead of yielding
 * corrupt media. For any blob, [decryptToStream] writes exactly the bytes
 * [OSHICryptoV2.decryptFile] would have returned.
 */
object OSHICryptoV2Streaming {

    private val EMPTY_AAD = ByteArray(0)

    /** The sealed manifest, opened. Fields as written by [OSHICryptoV2.manifestJson]. */
    data class Manifest(
        val filename: String,
        val mime: String,
        val size: Long,
        val chunkCount: Int,
        val chunkSize: Int,
    )

    /** Supplies chunk [index]'s opaque `ct‖tag`, or null when it could not be fetched. */
    fun interface ChunkSource {
        fun chunk(index: Int): ByteArray?
    }

    /** Open the manifest alone — the caller learns the plaintext size BEFORE allocating. */
    fun openManifest(fileKey: ByteArray, fileNonce: ByteArray, manifest: ByteArray): Manifest {
        val infoBytes = OSHICryptoV2.aesGcmOpen(
            fileKey, OSHICryptoV2.chunkNonce(fileNonce, OSHICryptoV2.MANIFEST_COUNTER), manifest, EMPTY_AAD,
        )
        val o = org.json.JSONObject(String(infoBytes, Charsets.UTF_8))
        return Manifest(
            filename = o.optString("filename"),
            mime = o.optString("mime"),
            size = o.getLong("size"),
            chunkCount = o.getInt("chunkCount"),
            chunkSize = o.optInt("chunkSize", OSHICryptoV2.DEFAULT_CHUNK_SIZE),
        )
    }

    /** Open one chunk. Peak allocation is the chunk, not the file. */
    fun openChunk(fileKey: ByteArray, fileNonce: ByteArray, index: Int, ctAndTag: ByteArray): ByteArray =
        OSHICryptoV2.aesGcmOpen(fileKey, OSHICryptoV2.chunkNonce(fileNonce, index.toLong()), ctAndTag, EMPTY_AAD)

    /**
     * Fetch → open → append, chunk by chunk. Returns the number of plaintext bytes
     * written, which is asserted against the manifest's `size`.
     *
     * [expectedChunkCount] is the count the SENDER announced in the `v2file` payload;
     * when non-null it must agree with the manifest, reproducing the
     * `chunkCount == chunks.size` assertion [OSHICryptoV2.decryptFile] makes.
     *
     * @throws IOException when a chunk is unavailable — distinct from a decrypt failure,
     *   so the caller can hold the relay ack and retry the DOWNLOAD rather than treat
     *   the blob as corrupt.
     */
    fun decryptToStream(
        fileKey: ByteArray,
        fileNonce: ByteArray,
        manifest: ByteArray,
        out: OutputStream,
        expectedChunkCount: Int? = null,
        source: ChunkSource,
    ): Long {
        val info = openManifest(fileKey, fileNonce, manifest)
        require(info.chunkCount >= 0) { "manifest chunkCount=${info.chunkCount}" }
        if (expectedChunkCount != null) {
            require(info.chunkCount == expectedChunkCount) {
                "manifest chunkCount=${info.chunkCount} but the payload announced $expectedChunkCount"
            }
        }
        var written = 0L
        for (i in 0 until info.chunkCount) {
            val ct = source.chunk(i) ?: throw IOException("chunk $i unavailable")
            val pt = openChunk(fileKey, fileNonce, i, ct)
            out.write(pt)
            written += pt.size.toLong()
        }
        out.flush()
        require(written == info.size) { "manifest size=${info.size} but reassembled $written" }
        return written
    }
}
