package com.oshi.desktop.mail

import com.oshi.desktop.DesktopIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The mail key is DERIVED from the OSHI identity — HKDF-SHA256 over the identity's X25519
 * encryption private key — so the mailbox is recoverable from the identity seed alone, on
 * any device that restores the account. That derivation MUST be byte-identical to iOS
 * (`IdentityManager.deriveMailKeyMaterial`) and Android
 * (`CryptoManager.deriveMailKeyMaterial`): a drift of one byte in the salt, info or length
 * makes the desktop client compute a different key and read NONE of the mail those two
 * platforms sealed — with no server-side plaintext to fall back on.
 *
 * The expected value here is computed with an INDEPENDENT HKDF-SHA256 (RFC 5869) built
 * straight from `javax.crypto.Mac`, not from the BouncyCastle path
 * [DesktopIdentity.deriveMailKeyMaterial] uses, so a bug shared by both implementations
 * cannot hide. The params asserted (salt = "oshi-mail", info = "oshi-mail-x25519-v1",
 * length = 32) are exactly the ones the mobile clients pass.
 */
class MailKeyDerivationTest {

    /** RFC 5869 HKDF-SHA256, sufficient for the single-block (L = 32 = HashLen) case. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + counter.toByte())
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    @Test
    fun `derivation matches an independent RFC 5869 HKDF with the iOS-Android params`() {
        val identity = DesktopIdentity.generate()
        val expected = hkdfSha256(
            ikm = identity.identity.priv,
            salt = "oshi-mail".toByteArray(Charsets.UTF_8),
            info = "oshi-mail-x25519-v1".toByteArray(Charsets.UTF_8),
            length = 32,
        )
        val actual = identity.deriveMailKeyMaterial()
        assertEquals(32, actual.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `derivation is deterministic for a given identity`() {
        val identity = DesktopIdentity.generate()
        assertArrayEquals(identity.deriveMailKeyMaterial(), identity.deriveMailKeyMaterial())
    }

    @Test
    fun `distinct identities derive distinct mail keys`() {
        val a = DesktopIdentity.generate().deriveMailKeyMaterial()
        val b = DesktopIdentity.generate().deriveMailKeyMaterial()
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `the derived key is a usable X25519 private key and register would publish its public half`() {
        val identity = DesktopIdentity.generate()
        val priv = identity.deriveMailKeyMaterial()
        // Round-trips through MailCrypto exactly as a mailbox key must.
        val pub = MailCrypto.publicKeyFor(priv)
        val sealed = MailCrypto.seal("recover me".toByteArray(Charsets.UTF_8), pub)
        assertArrayEquals("recover me".toByteArray(Charsets.UTF_8), MailCrypto.open(sealed, priv))
    }
}
