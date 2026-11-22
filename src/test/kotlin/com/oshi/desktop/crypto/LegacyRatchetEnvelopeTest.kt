package com.oshi.desktop.crypto

import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.DoubleRatchet
import com.oshi.messenger.network.encryption.RatchetBase64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * The legacy lane, end to end — PARITY.md row 0.16.
 *
 * ============================================================ WHAT IS AND IS NOT PROVEN
 * HERE
 *
 * The RATCHET is not under test: this build compiles the phones' own `DoubleRatchet.kt`,
 * so there is no second implementation to prove equal to the first. What IS under test is
 * everything this client had to supply around it, and that is exactly where a port fails
 * quietly:
 *
 *  - the five primitives in `com.oshi.messenger.network.encryption.CryptoManager`, which
 *    this tree reimplements because Android's copy is 701 lines of Keystore;
 *  - the outer `EncryptedMessage` envelope — HKDF over a static-static X25519 secret, then
 *    AES-GCM with NO additional data, then base64, then the ratchet JSON inside.
 *
 * The first is pinned against **iOS's own numbers**, not against Android's: the vector file
 * is `derivation_ios_vectors.json`, dumped from the Swift implementation, and this build
 * already reads the Android test resources that hold it. A primitive that is subtly
 * different does not fail to compile — it fails to decrypt, once, on someone's radio.
 */
class LegacyRatchetEnvelopeTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun vectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("derivation_ios_vectors.json")
        assertNotNull("iOS derivation vectors missing from the Android test resources", stream)
        return JSONObject(stream!!.bufferedReader().readText())
    }

    // ================================================================ the primitives

    /**
     * THE LOAD-BEARING TEST for this client's own crypto.
     *
     * `hkdfDerive` is reimplemented here, and the ratchet uses it for every message key and
     * every chain advance. These are the bytes **iOS** produced from a known shared secret.
     * Empty salt must mean 32 zero bytes (RFC 5869), which is what CryptoKit's
     * `hkdfDerivedSymmetricKey(salt: Data())` does — get that wrong and every key differs.
     */
    @Test
    fun `the HKDF this client reimplemented produces iOS's own root and chain`() {
        val v = vectors()
        val sharedSecret = b64(v.getString("sharedSecret"))
        val crypto = CryptoManager()

        assertArrayEquals(
            "root key diverges from iOS",
            b64(v.getString("rootClassical")),
            crypto.hkdfDerive(sharedSecret, ByteArray(0), v.getString("infoRootClassical").toByteArray(), 32),
        )
        assertArrayEquals(
            "initial chain diverges from iOS",
            b64(v.getString("chainClassical")),
            crypto.hkdfDerive(sharedSecret, ByteArray(0), v.getString("infoChainClassical").toByteArray(), 32),
        )
    }

    /** An explicit all-zero salt and an empty salt must be the same thing, or RFC 5869 is not. */
    @Test
    fun `an empty salt is thirty-two zero bytes`() {
        val crypto = CryptoManager()
        val ikm = ByteArray(32) { it.toByte() }
        assertArrayEquals(
            crypto.hkdfDerive(ikm, ByteArray(32), "RootKey".toByteArray(), 32),
            crypto.hkdfDerive(ikm, ByteArray(0), "RootKey".toByteArray(), 32),
        )
    }

    /** And the desktop's build of the SHARED ratchet still lands on iOS's numbers. */
    @Test
    fun `the shared ratchet derives iOS's root and chain on this platform too`() {
        val v = vectors()
        val (root, chain) = DoubleRatchet.deriveRootAndChain(b64(v.getString("sharedSecret")), null)
        assertArrayEquals(b64(v.getString("rootClassical")), root)
        assertArrayEquals(b64(v.getString("chainClassical")), chain)
    }

    /**
     * nonce(12) || ciphertext || tag(16) — Swift's `AES.GCM.SealedBox.combined` layout.
     * A different arrangement decrypts to garbage on a phone rather than failing here.
     */
    @Test
    fun `AES-GCM is nonce then ciphertext then tag`() {
        val crypto = CryptoManager()
        val key = ByteArray(32) { 7 }
        val plaintext = "hello".toByteArray()
        val box = crypto.encryptAESGCMWithAAD(plaintext, key, ByteArray(0))

        assertEquals("12-byte nonce + 5-byte body + 16-byte tag", 12 + 5 + 16, box.size)
        assertArrayEquals(plaintext, crypto.decryptAESGCMWithAAD(box, key, ByteArray(0)))
    }

    @Test
    fun `AES-GCM authenticates its additional data`() {
        val crypto = CryptoManager()
        val key = ByteArray(32) { 3 }
        val box = crypto.encryptAESGCMWithAAD("x".toByteArray(), key, "aad".toByteArray())
        try {
            crypto.decryptAESGCMWithAAD(box, key, "different".toByteArray())
            fail("a changed AAD must not authenticate — that is what makes the header unforgeable")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `X25519 agreement is symmetric and uses raw 32-byte keys`() {
        val crypto = CryptoManager()
        val (aPriv, aPub) = crypto.generateEphemeralKeyPair()
        val (bPriv, bPub) = crypto.generateEphemeralKeyPair()
        assertEquals(32, aPriv.size)
        assertEquals(32, aPub.size)
        assertArrayEquals(
            crypto.deriveSharedSecretWithKeys(aPriv, bPub),
            crypto.deriveSharedSecretWithKeys(bPriv, aPub),
        )
    }

    // ================================================================ the envelope

    private class Party(val priv: ByteArray, val pub: ByteArray) {
        val address: String = Base64.getEncoder().encodeToString(pub)
        val envelope = LegacyRatchetEnvelope(priv, address)
    }

    private fun party(): Party {
        val (priv, pub) = CryptoManager().generateEphemeralKeyPair()
        return Party(priv, pub)
    }

    @Test
    fun `a sealed envelope opens on the other side`() {
        val alice = party()
        val bob = party()

        val sealed = alice.envelope.seal("the radio works", bob.address)
        assertEquals("opened by the recipient", "the radio works", bob.envelope.open(sealed, alice.address))
    }

    /**
     * Two messages in a row, which is where a chain that does not advance shows up. A
     * ratchet that reused its message key would pass the single-message test above.
     */
    @Test
    fun `consecutive messages each decrypt, and no two share a ciphertext`() {
        val alice = party()
        val bob = party()

        val first = alice.envelope.seal("one", bob.address)
        val second = alice.envelope.seal("two", bob.address)
        assertNotEquals(
            "a reused message key would show here",
            first.getString("ciphertext"), second.getString("ciphertext"),
        )
        assertEquals("one", bob.envelope.open(first, alice.address))
        assertEquals("two", bob.envelope.open(second, alice.address))
    }

    @Test
    fun `a reply decrypts too, so the ratchet turns around`() {
        val alice = party()
        val bob = party()

        assertEquals("hi", bob.envelope.open(alice.envelope.seal("hi", bob.address), alice.address))
        assertEquals("hi back", alice.envelope.open(bob.envelope.seal("hi back", alice.address), bob.address))
    }

    /**
     * 🔴 IMPERSONATION. Decrypting with one identity and FILING under another is the whole
     * bug Android fixed at `MessageRepository.kt:5384`: an attacker seals to you under
     * their OWN keypair, sets the inner sender field to a contact's, and the message renders
     * as that contact's. The inner field must agree with the routed one or the envelope is
     * refused LOUDLY — not ignored, because a forgery must never fall through to something
     * that happens to work.
     */
    @Test
    fun `an envelope whose inner sender disagrees with the routed sender is refused`() {
        val alice = party()
        val bob = party()
        val mallory = party()

        val sealed = mallory.envelope.seal("trust me", bob.address)
        // Mallory sealed it under her own key but claims to be Alice on the wire.
        try {
            bob.envelope.open(sealed, alice.address)
            fail("an envelope routed from Alice but sealed by Mallory must be refused")
        } catch (e: LegacyRatchetEnvelope.ImpersonationException) {
            assertTrue(e.message!!, e.message!!.contains("but was routed from"))
        }
    }

    /** base64url and padding differences are spelling, not identity. */
    @Test
    fun `a base64url spelling of the same key is not an impersonation`() {
        val alice = party()
        val bob = party()
        val sealed = alice.envelope.seal("same key, other spelling", bob.address)
        val urlSpelling = alice.address.replace('+', '-').replace('/', '_').trimEnd('=')
        assertEquals("same key, other spelling", bob.envelope.open(sealed, urlSpelling))
    }

    // ================================================================ the wire shape

    /**
     * The outer layer, opened WITHOUT [LegacyRatchetEnvelope] — by hand, from the spec —
     * so this cannot pass merely because seal and open share a mistake.
     */
    @Test
    fun `the outer layer is HKDF over the static secret then AES-GCM with no AAD`() {
        val alice = party()
        val bob = party()
        val crypto = CryptoManager()

        val sealed = alice.envelope.seal("by hand", bob.address)

        val sharedSecret = crypto.deriveSharedSecretWithKeys(bob.priv, alice.pub)
        val symmetricKey = crypto.hkdfDerive(sharedSecret, ByteArray(0), ByteArray(0), 32)
        val inner = String(
            crypto.decryptAESGCMWithAAD(
                RatchetBase64.decode(sealed.getString("ciphertext")), symmetricKey, ByteArray(0),
            ),
            Charsets.UTF_8,
        )
        // …and what is inside is base64 of the ratchet message JSON, nothing else.
        val ratchetJson = JSONObject(String(RatchetBase64.decode(inner), Charsets.UTF_8))
        assertTrue("header missing", ratchetJson.has("header"))
        assertTrue("ciphertext missing", ratchetJson.has("ciphertext"))
        assertTrue("sessionId missing", ratchetJson.getJSONObject("header").has("sessionId"))
    }

    @Test
    fun `the envelope carries the fields the phones read`() {
        val alice = party()
        val bob = party()
        val sealed = alice.envelope.seal("fields", bob.address)

        assertEquals(alice.address, sealed.getString("senderPublicKey"))
        // iOS does not verify it and Android sends it empty; inventing one would be a new
        // wire fact neither phone checks.
        assertEquals("", sealed.getString("signature"))
    }

    /**
     * Swift's `JSONDecoder` uses `.deferredToDate`, i.e. seconds since 2001-01-01. Unix
     * seconds here decode on iOS as a year-2055 date — cosmetic, and still wrong.
     */
    @Test
    fun `the timestamp is Apple-epoch seconds, not Unix`() {
        val alice = party()
        val bob = party()
        val sealed = alice.envelope.seal("when", bob.address)

        val unixNow = System.currentTimeMillis() / 1000.0
        val stamped = sealed.getDouble("timestamp")
        assertEquals(unixNow - LegacyRatchetEnvelope.APPLE_EPOCH_OFFSET, stamped, 60.0)
        assertTrue("a Unix-epoch value would be ~978 million larger", stamped < unixNow - 900_000_000)
    }
}
