package com.oshi.desktop.store

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/**
 * __LOCAL_DATA_AT_REST_2026_09_22__ "OSHIMED1": streaming chunked AES-GCM for attachments.
 */
class MediaVaultTest {

    private val dir: File = Files.createTempDirectory("oshi-media-vault").toFile()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val chunk = 64 // small, so a few hundred bytes is many chunks
    private val vault = MediaVault(File(dir, "media"), File(dir, "media-tmp"), key, chunkSize = chunk)

    @After
    fun tearDown() {
        MediaVault.uninstall(vault)
        dir.deleteRecursively()
    }

    private fun seal(plain: ByteArray, k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        MediaCipher.encrypting(k, out, chunk).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, k: ByteArray = key): ByteArray =
        MediaCipher.decrypting(k, ByteArrayInputStream(sealed)).use { it.readBytes() }

    private fun assertRejected(sealed: ByteArray, why: String) {
        try {
            open(sealed)
            fail("$why was accepted")
        } catch (_: IOException) {
        }
    }

    private val record = chunk + MediaCipher.TAG_BYTES

    // ------------------------------------------------------------------ round trip

    @Test
    fun `round trips at every boundary size`() {
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 5 * chunk + 17)) {
            val plain = Random(n).nextBytes(n)
            val sealed = seal(plain)
            assertArrayEquals("size $n", plain, open(sealed))
            assertEquals("length from header, size $n", n.toLong(), MediaCipher.plaintextLength(sealed.size.toLong(), chunk))
        }
    }

    @Test
    fun `multi-chunk output streams one byte at a time and in odd slices`() {
        val plain = Random(7).nextBytes(10 * chunk + 3)
        val out = ByteArrayOutputStream()
        MediaCipher.encrypting(key, out, chunk).use { s ->
            var i = 0
            while (i < plain.size) {
                val n = minOf(1 + i % 37, plain.size - i)
                if (n == 1) s.write(plain[i].toInt()) else s.write(plain, i, n)
                i += n
            }
        }
        val sealed = out.toByteArray()
        assertEquals(MediaCipher.HEADER_BYTES + 11 * MediaCipher.TAG_BYTES + plain.size, sealed.size)
        val back = ByteArrayOutputStream()
        MediaCipher.decrypting(key, ByteArrayInputStream(sealed)).use { s ->
            while (true) { val b = s.read(); if (b < 0) break; back.write(b) }
        }
        assertArrayEquals(plain, back.toByteArray())
    }

    @Test
    fun `two seals of the same bytes differ (random nonce prefix)`() {
        val plain = ByteArray(100)
        assertFalse(seal(plain).contentEquals(seal(plain)))
    }

    // ------------------------------------------------------------------ rejection

    @Test
    fun `truncation at a chunk boundary is rejected (final flag)`() {
        val sealed = seal(Random(1).nextBytes(3 * chunk + 5))
        assertRejected(sealed.copyOf(MediaCipher.HEADER_BYTES + 2 * record), "dropping the final chunk")
        assertRejected(sealed.copyOf(MediaCipher.HEADER_BYTES + record), "dropping two chunks")
    }

    @Test
    fun `truncation mid-chunk, a bare header and appended bytes are rejected`() {
        val sealed = seal(Random(2).nextBytes(3 * chunk))
        assertRejected(sealed.copyOf(sealed.size - 1), "one byte short")
        assertRejected(sealed.copyOf(MediaCipher.HEADER_BYTES), "header only")
        assertRejected(sealed.copyOf(MediaCipher.HEADER_BYTES - 1), "short header")
        assertRejected(sealed + ByteArray(record), "an extra chunk-sized tail")
        assertRejected(sealed + byteArrayOf(0), "one appended byte")
    }

    @Test
    fun `swapped chunks are rejected (index in nonce and AAD)`() {
        val sealed = seal(Random(3).nextBytes(3 * chunk + 9))
        val h = MediaCipher.HEADER_BYTES
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, h, swapped, h + record, record)
        System.arraycopy(sealed, h + record, swapped, h, record)
        assertRejected(swapped, "chunks 0 and 1 swapped")
    }

    @Test
    fun `chunks spliced from another file under the same key are rejected (per-file prefix)`() {
        val a = seal(Random(4).nextBytes(2 * chunk + 1))
        val b = seal(Random(5).nextBytes(2 * chunk + 1))
        val h = MediaCipher.HEADER_BYTES
        val spliced = a.copyOf()
        System.arraycopy(b, h, spliced, h, record)
        assertRejected(spliced, "a chunk from another file")
    }

    @Test
    fun `a flipped bit anywhere is rejected, header included`() {
        val sealed = seal(Random(6).nextBytes(2 * chunk + 3))
        for (i in listOf(9, 14, MediaCipher.HEADER_BYTES, MediaCipher.HEADER_BYTES + record + 3, sealed.size - 1)) {
            val t = sealed.copyOf()
            t[i] = (t[i].toInt() xor 0x01).toByte()
            assertRejected(t, "bit flip at $i")
        }
    }

    @Test
    fun `the wrong key is rejected`() {
        assertRejected(seal(ByteArray(10), ByteArray(32)), "another key")
    }

    // ------------------------------------------------------------------ vault

    @Test
    fun `sealingStream writes a sealed file that openStream, lengthOf and readBytes read`() {
        val f = File(vault.mediaDir, "abc-photo.jpg")
        val plain = Random(8).nextBytes(1000)
        vault.sealingStream(f).use { it.write(plain) }

        assertTrue(MediaVault.isSealed(f))
        assertEquals(1000L, MediaVault.lengthOf(f))
        assertArrayEquals(plain, vault.openStream(f).use { it.readBytes() })
        assertArrayEquals(plain, vault.readBytes(f, 1000))
        assertTrue(runCatching { vault.readBytes(f, 999) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `readAny needs the installed vault for a sealed file and passes plaintext through`() {
        val f = File(vault.mediaDir, "x.bin")
        vault.sealingStream(f).use { it.write(byteArrayOf(1, 2, 3)) }
        MediaVault.uninstall(vault)
        assertTrue(runCatching { MediaVault.readAny(f, 10) }.exceptionOrNull() is IOException)
        MediaVault.install(vault)
        assertArrayEquals(byteArrayOf(1, 2, 3), MediaVault.readAny(f, 10))

        val own = File(dir, "users-own-file.txt").apply { writeBytes(byteArrayOf(9, 9)) }
        assertFalse(MediaVault.isSealed(own))
        assertArrayEquals(byteArrayOf(9, 9), MediaVault.readAny(own, 10))
        assertNull(MediaVault.lengthOf(File(dir, "missing")))
    }

    @Test
    fun `decryptTo deletes its output when the source is tampered`() {
        val f = File(vault.mediaDir, "v.mp4")
        vault.sealingStream(f).use { it.write(Random(9).nextBytes(5 * chunk)) }
        val bytes = f.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        val dest = File(dir, "out.mp4")
        assertTrue(runCatching { vault.decryptTo(f, dest) }.exceptionOrNull() is IOException)
        assertFalse("no partial plaintext may survive", dest.exists())
    }

    @Test
    fun `scratch copies keep the extension and are swept`() {
        val f = File(vault.mediaDir, "1234-note.m4a")
        vault.sealingStream(f).use { it.write(byteArrayOf(5, 6, 7)) }
        val copy = vault.decryptToScratch(f)
        assertEquals(vault.scratchDir, copy.parentFile)
        assertTrue(copy.name.endsWith("-1234-note.m4a"))
        assertArrayEquals(byteArrayOf(5, 6, 7), copy.readBytes())
        assertEquals(1, vault.sweepScratch())
        assertFalse(copy.exists())
    }

    // ------------------------------------------------------------------ legacy migration

    @Test
    fun `legacy plaintext media is sealed in place under the same name`() {
        vault.mediaDir.mkdirs()
        val plain = Random(10).nextBytes(7 * chunk + 11)
        val f = File(vault.mediaDir, "deadbeef-holiday.jpg").apply { writeBytes(plain) }
        val already = File(vault.mediaDir, "sealed.bin")
        vault.sealingStream(already).use { it.write(byteArrayOf(1)) }
        File(vault.mediaDir, ".stale.jpg.x${MediaVault.MIGRATION_SUFFIX}").writeBytes(byteArrayOf(0))
        val old = System.currentTimeMillis() - 120_000
        f.setLastModified(old); already.setLastModified(old)

        val candidates = vault.legacyCandidates()
        assertEquals(listOf(f), candidates)
        assertEquals(MediaVault.MigrationReport(1, 0, 0), vault.migratePlaintext(candidates))

        assertTrue(MediaVault.isSealed(f))
        assertArrayEquals("the journal's mediaRef still opens the same bytes", plain, vault.readBytes(f, Long.MAX_VALUE))
        assertEquals(setOf("deadbeef-holiday.jpg", "sealed.bin"), vault.mediaDir.list()!!.toSet())
        assertTrue(vault.legacyCandidates().isEmpty())
    }

    @Test
    fun `a file written in the last moments is not picked up`() {
        vault.mediaDir.mkdirs()
        val fresh = File(vault.mediaDir, "fresh.jpg").apply { writeBytes(ByteArray(10)) }
        assertTrue("possibly still being written by another process", vault.legacyCandidates().isEmpty())
        fresh.setLastModified(System.currentTimeMillis() - 120_000)
        assertEquals(listOf(fresh), vault.legacyCandidates())
    }

    @Test
    fun `a failed migration leaves the plaintext untouched`() {
        vault.mediaDir.mkdirs()
        val plain = Random(11).nextBytes(300)
        val f = File(vault.mediaDir, "keep.png").apply { writeBytes(plain) }
        // A vault whose chunk size the format refuses cannot seal: the move must not happen.
        val broken = MediaVault(vault.mediaDir, vault.scratchDir, key, chunkSize = 1)
        assertEquals(false, broken.migrateOne(f))
        assertArrayEquals(plain, f.readBytes())
        assertFalse(MediaVault.isSealed(f))
        assertEquals(listOf("keep.png"), vault.mediaDir.list()!!.toList())
    }
}
