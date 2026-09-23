package com.oshi.desktop.store

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * __LOCAL_DATA_AT_REST_2026_09_22__ Attachments at rest: chunked, streaming AES-256-GCM.
 *
 * ============================================================ FORMAT ("OSHIMED1")
 *
 *     header (20 bytes) = "OSHIMED1" ‖ chunkSize u32 BE ‖ noncePrefix (8 random bytes)
 *     then chunks       = AES-GCM(plain_i) ‖ tag(16), each non-final chunk EXACTLY chunkSize
 *                         plaintext bytes; the final chunk 0..chunkSize bytes
 *     nonce_i           = noncePrefix ‖ i u32 BE
 *     AAD_i             = header ‖ i u32 BE ‖ final-flag (1 byte: 1 on the last chunk, else 0)
 *
 * What each piece defends against: the per-file random prefix keeps nonces unique across
 * files under one key; the index in the nonce and the AAD rejects reordered or swapped
 * chunks; the final flag rejects truncation at a chunk boundary (the new "last" chunk was
 * sealed with flag 0) and appended data (the real last chunk no longer verifies as
 * non-final); the header in the AAD rejects an edited chunk size or prefix.
 *
 * Streaming both ways: a writer holds one chunk, a reader one chunk, so a 200 MB video is
 * never in memory. A reader releases a chunk's bytes only after that chunk's tag verified —
 * but a consumer can have consumed EARLIER chunks of a file whose later chunk then fails.
 * Every consumer here treats the thrown [IOException] as "this file is bad" and discards
 * what it read (the image loader, the temp-file decrypt which deletes its output).
 *
 * The key is HKDF subkey [LocalDataKeys.MEDIA]; the file NAME is not authenticated, so two
 * sealed attachments swapped on disk by someone with write access open as each other.
 *
 * ============================================================ PLAINTEXT, AND WHERE IT MAY BE
 *
 * `media/` holds only sealed files once migration has run. Decrypted bytes exist in
 * exactly two places: the heap (thumbnails, the viewer), and [scratchDir] (`media-tmp/`) —
 * a temp file for playback or for "open in another app", deleted after use where this
 * process can know "after", deleted at JVM exit, and swept at every start ([sweepScratch]).
 * "Save a copy" writes plaintext where the USER chose; that is an export, not storage.
 */
class MediaVault(
    val mediaDir: File,
    val scratchDir: File,
    key: ByteArray,
    private val chunkSize: Int = MediaCipher.DEFAULT_CHUNK,
) {
    private val key = key.copyOf()

    init {
        require(key.size == 32) { "media key must be 32 bytes" }
    }

    /** A stream that seals everything written to it into [target]; fsync on close. */
    fun sealingStream(target: File): OutputStream {
        target.parentFile?.let(DesktopPaths::ensurePrivateDir)
        val fos = FileOutputStream(target)
        DesktopPaths.makePrivate(target)
        val synced = object : FilterOutputStream(fos) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { out.flush(); fos.fd.sync() } finally { out.close() }
            }
        }
        return try {
            MediaCipher.encrypting(key, synced, chunkSize)
        } catch (e: Throwable) {
            fos.close()
            throw e
        }
    }

    /** Decrypting stream for a sealed file; the file itself for a plaintext one (a user's own outbound file). */
    fun openStream(file: File): InputStream {
        val raw = FileInputStream(file)
        return if (isSealed(file)) {
            try { MediaCipher.decrypting(key, raw) } catch (e: Throwable) { raw.close(); throw e }
        } else raw
    }

    /** Every plaintext byte, refusing (before allocating) a file over [max]. */
    fun readBytes(file: File, max: Long): ByteArray {
        val len = lengthOf(file) ?: throw IOException("not a file: $file")
        if (len > max) throw IOException("$file is $len bytes, over $max")
        return openStream(file).use { it.readBytes() }
    }

    /** Decrypt [src] to [dest], streaming. A failure deletes [dest]: no partial plaintext survives. */
    fun decryptTo(src: File, dest: File): Long {
        try {
            dest.parentFile?.mkdirs()
            openStream(src).use { input -> FileOutputStream(dest).use { out -> return input.copyTo(out, COPY_BUFFER) } }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    /** A plaintext copy in [scratchDir], named `<random>-<original name>` so the extension survives. */
    fun decryptToScratch(src: File): File {
        DesktopPaths.ensurePrivateDir(scratchDir)
        val safe = src.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120).ifEmpty { "file" }
        val dest = File(scratchDir, "${UUID.randomUUID()}-$safe")
        decryptTo(src, dest)
        DesktopPaths.makePrivate(dest)
        dest.deleteOnExit()
        return dest
    }

    /** Delete everything in [scratchDir]. Called at start and at exit. Returns how many entries went. */
    fun sweepScratch(): Int {
        val entries = scratchDir.listFiles() ?: return 0
        var n = 0
        for (e in entries) if (e.deleteRecursively()) n++
        return n
    }

    data class MigrationReport(val sealed: Int, val failed: Int, val skipped: Int)

    /**
     * Plaintext files at the top of [mediaDir] that a previous build left there, older than
     * [quietMs] so a file some other process is still writing is not picked up mid-write.
     * Listed SYNCHRONOUSLY at start, before this process downloads anything.
     */
    fun legacyCandidates(nowMs: Long = System.currentTimeMillis(), quietMs: Long = 30_000): List<File> {
        val files = mediaDir.listFiles() ?: return emptyList()
        files.filter { it.isFile && it.name.endsWith(MIGRATION_SUFFIX) }.forEach { it.delete() }
        return files.filter {
            it.isFile && !it.name.endsWith(MIGRATION_SUFFIX) && it.lastModified() <= nowMs - quietMs &&
                !isSealed(it)
        }.sortedBy { it.name }
    }

    fun migratePlaintext(candidates: List<File>): MigrationReport {
        var ok = 0; var failed = 0; var skipped = 0
        for (f in candidates) {
            when (migrateOne(f)) {
                true -> ok++
                false -> failed++
                null -> skipped++
            }
        }
        return MigrationReport(ok, failed, skipped)
    }

    /**
     * WRITE NEW, VERIFY, THEN REPLACE — in place, under the SAME name, so every `mediaRef`
     * already in the (encrypted) message journals still points at the right file.
     *
     * Sealed to a temp beside the original and fsynced; the temp is then decrypted end to end
     * and its SHA-256 and length compared with the original's; the original must not have
     * changed meanwhile. Only then an atomic move. Any failure deletes the temp and leaves
     * the plaintext exactly where it was — the next start tries again.
     *
     * @return true sealed, false failed (plaintext kept), null nothing to do.
     */
    fun migrateOne(file: File): Boolean? {
        if (!file.isFile || isSealed(file)) return null
        val len0 = file.length()
        val mtime0 = file.lastModified()
        val tmp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}$MIGRATION_SUFFIX")
        return try {
            val want = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                sealingStream(tmp).use { out ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        want.update(buf, 0, n); out.write(buf, 0, n)
                    }
                }
            }
            val got = MessageDigest.getInstance("SHA-256")
            var count = 0L
            openStream(tmp).use { input ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    got.update(buf, 0, n); count += n
                }
            }
            if (count != len0 || !MessageDigest.isEqual(want.digest(), got.digest())) return false
            if (file.length() != len0 || file.lastModified() != mtime0) return false
            tmp.setLastModified(mtime0)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            DesktopPaths.makePrivate(file)
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        const val SCRATCH_DIR_NAME = "media-tmp"
        const val MIGRATION_SUFFIX = ".oshimig"
        private const val COPY_BUFFER = 64 * 1024

        @Volatile
        private var installed: MediaVault? = null

        /**
         * The window's composables open media by PATH (the `mediaRef` string), with no client
         * in reach; `OshiClient` installs its vault here so they can decrypt. The last client
         * constructed wins, which is the one the window holds.
         */
        fun install(vault: MediaVault) {
            installed = vault
        }

        fun uninstall(vault: MediaVault) {
            if (installed === vault) installed = null
        }

        fun current(): MediaVault? = installed

        /** True when [file] starts with the "OSHIMED1" magic. Reads 8 bytes. */
        fun isSealed(file: File): Boolean = try {
            if (!file.isFile || file.length() < MediaCipher.MAGIC.size) false
            else FileInputStream(file).use { input ->
                val head = ByteArray(MediaCipher.MAGIC.size)
                readFully(input, head) == head.size && head.contentEquals(MediaCipher.MAGIC)
            }
        } catch (_: IOException) {
            false
        }

        /** Plaintext size: computed from the header for a sealed file, the file size otherwise. Null if absent. */
        fun lengthOf(file: File): Long? {
            if (!file.isFile) return null
            if (!isSealed(file)) return file.length()
            val chunk = try {
                FileInputStream(file).use { input ->
                    val h = ByteArray(MediaCipher.HEADER_BYTES)
                    if (readFully(input, h) < h.size) return null
                    ByteBuffer.wrap(h, MediaCipher.MAGIC.size, 4).int
                }
            } catch (_: IOException) {
                return null
            }
            return MediaCipher.plaintextLength(file.length(), chunk)
        }

        /** Open through the installed vault; a sealed file with no vault installed is an [IOException]. */
        fun openAny(file: File): InputStream {
            if (!isSealed(file)) return FileInputStream(file)
            val v = installed ?: throw IOException("$file is encrypted and no media key is loaded in this process")
            return v.openStream(file)
        }

        fun readAny(file: File, max: Long): ByteArray {
            val len = lengthOf(file) ?: throw IOException("not a file: $file")
            if (len > max) throw IOException("$file is $len bytes, over $max")
            return openAny(file).use { it.readBytes() }
        }

        internal fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
            var got = 0
            while (got < len) {
                val n = input.read(buf, off + got, len - got)
                if (n < 0) break
                got += n
            }
            return got
        }
    }
}

/** The pure codec for [MediaVault]; streams in, streams out, no files. */
object MediaCipher {
    val MAGIC: ByteArray = "OSHIMED1".toByteArray(Charsets.US_ASCII)
    const val HEADER_BYTES = 8 + 4 + 8
    const val TAG_BYTES = 16
    const val DEFAULT_CHUNK = 64 * 1024
    const val MIN_CHUNK = 16
    const val MAX_CHUNK = 8 * 1024 * 1024
    private const val MAX_CHUNKS = 0xFFFF_FFFFL

    fun encrypting(key: ByteArray, out: OutputStream, chunkSize: Int = DEFAULT_CHUNK): OutputStream =
        SealingOutputStream(key, out, chunkSize)

    fun decrypting(key: ByteArray, input: InputStream): InputStream = OpeningInputStream(key, input)

    /** Null when the length cannot be a well-formed file of this chunk size. */
    fun plaintextLength(fileLength: Long, chunkSize: Int): Long? {
        if (chunkSize !in MIN_CHUNK..MAX_CHUNK) return null
        val body = fileLength - HEADER_BYTES
        if (body < TAG_BYTES) return null
        val record = chunkSize.toLong() + TAG_BYTES
        val full = body / record
        val rem = body % record
        return when {
            rem == 0L -> full * chunkSize
            rem < TAG_BYTES -> null
            else -> full * chunkSize + (rem - TAG_BYTES)
        }
    }

    private fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index.toInt()).put(if (last) 1 else 0).array()

    private fun nonce(prefix: ByteArray, index: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).array()

    private class SealingOutputStream(key: ByteArray, private val out: OutputStream, private val chunkSize: Int) : OutputStream() {
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
        private val header: ByteArray
        private val buf: ByteArray
        private var fill = 0
        private var index = 0L
        private var closed = false

        init {
            require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunk size $chunkSize out of range" }
            header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(chunkSize).put(prefix).array()
            buf = ByteArray(chunkSize)
            out.write(header)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stream closed")
            var o = off
            var r = len
            while (r > 0) {
                // A full buffer is emitted as NON-final only once more data proves it is not the last.
                if (fill == chunkSize) emit(last = false)
                val n = minOf(r, chunkSize - fill)
                System.arraycopy(b, o, buf, fill, n)
                fill += n; o += n; r -= n
            }
        }

        private fun emit(last: Boolean) {
            if (index > MAX_CHUNKS) throw IOException("media file too large for this format")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, index)))
            cipher.updateAAD(aad(header, index, last))
            out.write(cipher.doFinal(buf, 0, fill))
            index++
            fill = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                emit(last = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    private class OpeningInputStream(key: ByteArray, input: InputStream) : InputStream() {
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val src = PushbackInputStream(input, 1)
        private val header = ByteArray(HEADER_BYTES)
        private val prefix: ByteArray
        private val record: ByteArray
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var done = false

        init {
            if (MediaVault.readFully(src, header) < HEADER_BYTES) throw EOFException("media header truncated")
            if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IOException("not an OSHIMED1 file")
            val chunk = ByteBuffer.wrap(header, MAGIC.size, 4).int
            if (chunk !in MIN_CHUNK..MAX_CHUNK) throw IOException("bad media chunk size $chunk")
            prefix = header.copyOfRange(12, 20)
            record = ByteArray(chunk + TAG_BYTES)
        }

        private fun nextChunk(): Boolean {
            if (done) return false
            val n = MediaVault.readFully(src, record)
            if (n < TAG_BYTES) throw EOFException("media file truncated at chunk $index")
            val last = if (n < record.size) true else {
                val peek = src.read()
                if (peek < 0) true else { src.unread(peek); false }
            }
            if (index > MAX_CHUNKS) throw IOException("too many media chunks")
            plain = try {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, index)))
                cipher.updateAAD(aad(header, index, last))
                cipher.doFinal(record, 0, n)
            } catch (e: Exception) {
                throw IOException("media chunk $index failed authentication (tampered, truncated or wrong key)", e)
            }
            pos = 0
            index++
            if (last) done = true
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) {
                if (!nextChunk()) return -1
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = src.close()
    }
}
