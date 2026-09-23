package com.oshi.desktop.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The frozen cross-platform test vector for OSHI Mail's zero-access sealing.
 *
 * Produced by the REAL server (`crypto_mail.js seal()`), not by this codebase — so this is
 * the one test in the suite that can catch a desktop [MailCrypto] which encrypts and
 * decrypts happily with ITSELF while being incompatible with iOS, Android and the server.
 * There is no server-side plaintext copy of any mail: if this ever goes red, decrypting is
 * broken for every message already sitting in every mailbox, not just new ones.
 */
class MailCryptoTest {

    // Fixed vector — DO NOT regenerate from this codebase. See class note.
    private val privB64 = "0BcexBZW4k4XN+deE4U2FiPcH6qvShf1ki25zLG1TVk="
    private val pubB64 = "yXY5YvOc+hMjk5xTEoAa7n2lfc7EZgD4vjf/6mSfmBk="
    private val blobB64 =
        "OrgjhOlYUdrTPgGg3pG8k31LUwNglZjM2KFzcEO4h1nNgnKQwQ26dzzH0lxt9Awf3Ac7tUomVRT/L2p6ZUYN0JHdScJJvO1gY+HdhXGEmxEtowhz6kwZ8Mo22+5KlbFxSe49bVL8hftWrk09qPcDmBsJr8SAV4Bnwi0rjMZ6++ajL4tZFmdzUQekqTwt"
    private val plainB64 =
        "RnJvbTogSsOpcsO0bWUgPGpAeC5jb20+DQpTdWJqZWN0OiBDYWbDqSDigJQgYWNjZW50dcOpIOKckw0KDQppbnRlcm9wIGNhbmFyeSA0NzEx"

    private val priv = Base64.getDecoder().decode(privB64)
    private val pub = Base64.getDecoder().decode(pubB64)
    private val blob = Base64.getDecoder().decode(blobB64)
    private val plain = Base64.getDecoder().decode(plainB64)

    @Test
    fun `frozen vector opens to the exact server-sealed plaintext`() {
        val opened = MailCrypto.open(blob, priv)
        assertArrayEquals(plain, opened)
        // And human-readable, so a failure here shows the actual mismatch rather than hex.
        assertEquals(
            "From: Jérôme <j@x.com>\r\nSubject: Café — accentué ✓\r\n\r\ninterop canary 4711",
            String(opened, Charsets.UTF_8),
        )
    }

    @Test
    fun `the private key derives the same public key the vector was sealed to`() {
        assertArrayEquals(pub, MailCrypto.publicKeyFor(priv))
    }

    @Test
    fun `a different private key cannot open the frozen vector`() {
        val (otherPriv, _) = MailCrypto.generateMailKeyPair()
        val ex = try {
            MailCrypto.open(blob, otherPriv)
            null
        } catch (e: MailCrypto.SealedBlobError) {
            e
        }
        assertNotNull("expected SealedBlobError for the wrong key", ex)
    }

    @Test
    fun `a tampered frozen vector fails to open`() {
        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        val ex = try {
            MailCrypto.open(tampered, priv)
            null
        } catch (e: MailCrypto.SealedBlobError) {
            e
        }
        assertNotNull("expected SealedBlobError for a tampered blob", ex)
    }

    @Test
    fun `seal then open round-trips for this device's own key`() {
        val (myPriv, myPub) = MailCrypto.generateMailKeyPair()
        val message = "round-trip canary — éèê, emoji 📧".toByteArray(Charsets.UTF_8)
        val sealed = MailCrypto.seal(message, myPub)
        assertTrue(sealed.size > MailCrypto.SEAL_OVERHEAD)
        assertArrayEquals(message, MailCrypto.open(sealed, myPriv))
    }

    @Test
    fun `seal is non-deterministic (fresh ephemeral key and iv each time)`() {
        val (myPriv, myPub) = MailCrypto.generateMailKeyPair()
        val message = "same plaintext".toByteArray(Charsets.UTF_8)
        val a = MailCrypto.seal(message, myPub)
        val b = MailCrypto.seal(message, myPub)
        assertTrue(!a.contentEquals(b))
        assertArrayEquals(message, MailCrypto.open(a, myPriv))
        assertArrayEquals(message, MailCrypto.open(b, myPriv))
    }

    @Test
    fun `hex round-trips through MailCrypto helpers`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7f.toByte(), 0xff.toByte(), 0xa5.toByte())
        with(MailCrypto) {
            val hex = bytes.toHex()
            assertEquals("00017fffa5", hex)
            assertArrayEquals(bytes, hexToBytes(hex))
        }
    }
}
