package com.oshi.desktop.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The two archive keys and the sealed layout — PARITY.md row 0.24.
 *
 * **Nothing here is asserted against [SyncCrypto]'s own output.** The derivations are
 * recomputed in this file from the algorithm the shipped source spells out, using
 * `javax.crypto.Mac` and `MessageDigest` — a different implementation (JCE) of HKDF from
 * the one under test (Bouncy Castle, via the shared `OSHICryptoV2.hkdf`). Asserting a
 * derivation against itself is the `autotest-that-generates-with-the-code-under-test-is-vacuous`
 * shape, and the independent implementation is itself checked against RFC 5869's published
 * test vector A.1 before it is trusted to check anything else.
 */
class SyncCryptoTest {

    // ────────────────────────────────────────────── the independent HKDF, and its own check

    /** RFC 5869 §2.2/2.3, written from the RFC, not from the code under test. */
    private fun rfc5869(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
            return mac.doFinal(data)
        }
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /**
     * RFC 5869 A.1 — the published vector. If this fails, every other assertion in this
     * file is worthless, so it runs first in reading order for a reason.
     */
    @Test
    fun `the reference hkdf reproduces RFC 5869 test vector A1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte()
        )
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        assertEquals(expected, rfc5869(ikm, salt, info, 42).toHex())
    }

    // ────────────────────────────────────────────── V2 archive key

    /**
     * `V2SyncManager.swift:19-26,292-294`:
     *   `HKDF-SHA256(ikm = identityPrivateKeyBytes, salt = 0x00 × 32, info = "oshi-mds-v1", 32)`
     */
    @Test
    fun `v2 archive key is HKDF over the ed25519 private key with a 32 byte zero salt`() {
        val priv = ByteArray(32) { (it * 7 + 3).toByte() }
        val expected = rfc5869(priv, ByteArray(32), "oshi-mds-v1".toByteArray(), 32)
        assertArrayEquals(expected, SyncCrypto.v2ArchiveKey(priv))
        assertEquals(32, SyncCrypto.v2ArchiveKey(priv).size)
    }

    /** The info label is what binds the key to the scheme version (`swift:88-89`). */
    @Test
    fun `a different info label produces a different key`() {
        val priv = ByteArray(32) { it.toByte() }
        assertFalse(
            SyncCrypto.v2ArchiveKey(priv).contentEquals(SyncCrypto.v2ArchiveKey(priv, "oshi-mds-v2"))
        )
        assertEquals("oshi-mds-v1", SyncCrypto.V2_ARCHIVE_INFO)
    }

    /**
     * GUARD — an empty identity is refused, not expanded.
     *
     * HKDF will happily turn zero bytes of ikm into 32 plausible-looking bytes, so an
     * identity that failed to load would read as an archive key that decrypts nothing, and
     * the symptom is "every item skipped as corrupt". `V2SyncManager.swift:291` makes the
     * same check.
     */
    @Test
    fun `an empty identity key is refused rather than expanded`() {
        val e = runCatching { SyncCrypto.v2ArchiveKey(ByteArray(0)) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        // The thing being prevented: HKDF over nothing IS a valid 32-byte output.
        assertEquals(32, rfc5869(ByteArray(0), ByteArray(32), "oshi-mds-v1".toByteArray(), 32).size)
    }

    // ────────────────────────────────────────────── legacy archive key

    /**
     * `MultiDeviceSyncManager.swift:1004-1009`: `SHA256(privKeyData + "oshi-sync-key")`.
     * Android does the same with two `update` calls in that order (`.kt:1397-1403`).
     */
    @Test
    fun `legacy archive key is sha256 of the private key with the salt APPENDED`() {
        val priv = ByteArray(32) { (it * 3 + 1).toByte() }
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(priv + "oshi-sync-key".toByteArray(Charsets.UTF_8))
        assertArrayEquals(expected, SyncCrypto.legacyArchiveKey(priv))
    }

    /** Salt PREPENDED is a different 32 bytes — the reason the order is spelled out. */
    @Test
    fun `prepending the salt would produce a different key`() {
        val priv = ByteArray(32) { (it * 3 + 1).toByte() }
        val wrong = MessageDigest.getInstance("SHA-256")
            .digest("oshi-sync-key".toByteArray(Charsets.UTF_8) + priv)
        assertFalse(wrong.contentEquals(SyncCrypto.legacyArchiveKey(priv)))
    }

    /** The two archives derive from DIFFERENT key pairs and must never coincide. */
    @Test
    fun `the two archive keys are different even from the same bytes`() {
        val priv = ByteArray(32) { 0x42 }
        assertFalse(SyncCrypto.v2ArchiveKey(priv).contentEquals(SyncCrypto.legacyArchiveKey(priv)))
    }

    // ────────────────────────────────────────────── the sealed layout

    /**
     * `base64(nonce(12) ‖ ct ‖ tag(16))`, standard alphabet, empty AAD — Apple's
     * `SealedBox.combined` (`swift:1017`, `V2SyncManager.swift:306-313`, `.kt:1427-1449`).
     *
     * Checked structurally against the raw base64 rather than by round-tripping through
     * [SyncCrypto.open], which would pass for any self-consistent layout.
     */
    @Test
    fun `sealed layout is nonce then ciphertext then tag in standard base64`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (0xA0 + it).toByte() }
        val plaintext = "hello archive".toByteArray()
        val b64 = SyncCrypto.seal(key, plaintext, nonce)

        val blob = Base64.getDecoder().decode(b64)   // STANDARD alphabet, padded
        assertArrayEquals("first 12 bytes are the nonce", nonce, blob.copyOfRange(0, 12))
        assertEquals("nonce + ct + tag", 12 + plaintext.size + 16, blob.size)

        // And the base64 is standard, not base64url: this key/nonce pair produces a '+' or
        // '/' in the padded standard alphabet and would differ under base64url.
        val urlSafe = Base64.getUrlEncoder().encodeToString(blob)
        assertFalse("standard base64, not base64url (Data(base64Encoded:) / Base64.NO_WRAP)", b64 == urlSafe)
    }

    @Test
    fun `open reverses seal`() {
        val key = SyncCrypto.v2ArchiveKey(ByteArray(32) { 9 })
        val payload = """{"type":"contacts","contacts":[]}""".toByteArray()
        assertArrayEquals(payload, SyncCrypto.open(key, SyncCrypto.seal(key, payload)))
    }

    @Test
    fun `a different key does not open`() {
        val a = SyncCrypto.v2ArchiveKey(ByteArray(32) { 1 })
        val b = SyncCrypto.v2ArchiveKey(ByteArray(32) { 2 })
        val sealed = SyncCrypto.seal(a, "x".toByteArray())
        val e = runCatching { SyncCrypto.open(b, sealed) }.exceptionOrNull()
        assertTrue("expected SyncCryptoException, got $e", e is SyncCryptoException)
    }

    /**
     * GUARD — a short blob is REFUSED, not decoded.
     *
     * `V2SyncManager.swift:321` guards `blob.count > nonceLen + tagLen`. Kept because the
     * JCE's failure for a truncated input is not the tag exception a caller would expect,
     * and an escape from this refusal escapes [SyncEngine]'s per-item isolation and aborts
     * the whole drain. Note the shipped bound is strictly-greater, so exactly 28 bytes — a
     * valid seal of ZERO plaintext — is refused too, and this reproduces that.
     */
    @Test
    fun `a blob too short to hold a nonce and a tag is refused`() {
        val key = ByteArray(32)
        for (n in intArrayOf(0, 1, 11, 12, 27, 28)) {
            val b64 = Base64.getEncoder().encodeToString(ByteArray(n))
            val e = runCatching { SyncCrypto.open(key, b64) }.exceptionOrNull()
            assertTrue("$n bytes must be refused, got $e", e is SyncCryptoException)
        }
        // 28 bytes IS a structurally valid seal of zero plaintext, and iOS refuses it too.
        val realEmptySeal = SyncCrypto.seal(key, ByteArray(0))
        assertEquals(28, Base64.getDecoder().decode(realEmptySeal).size)
        val e28 = runCatching { SyncCrypto.open(key, realEmptySeal) }.exceptionOrNull()
        assertTrue(
            "iOS's bound is strictly-greater, so a 28-byte seal of zero plaintext is " +
                "refused there too (V2SyncManager.swift:321); got $e28",
            e28 is SyncCryptoException
        )
    }

    @Test
    fun `ciphertext that is not base64 is refused as malformed not as empty`() {
        val e = runCatching { SyncCrypto.open(ByteArray(32), "!!! not base64 !!!") }.exceptionOrNull()
        assertTrue("expected SyncCryptoException, got $e", e is SyncCryptoException)
        assertNotNull(e!!.message)
        assertTrue(e.message!!.contains("base64"))
    }

    /** Two seals of the same plaintext differ — a fresh nonce each time. */
    @Test
    fun `each seal uses a fresh nonce`() {
        val key = ByteArray(32) { 5 }
        val a = SyncCrypto.seal(key, "same".toByteArray())
        val b = SyncCrypto.seal(key, "same".toByteArray())
        assertFalse("a repeated nonce under a long-lived archive key is a GCM catastrophe", a == b)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
