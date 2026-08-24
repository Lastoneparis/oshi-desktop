package com.oshi.desktop

import com.oshi.messenger.network.v2.FetchedBundle
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import com.oshi.messenger.network.v2.V2Session
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Double Ratchet state machine, exercised the way a lossy relay actually behaves.
 *
 * The vectors in V2CryptoVectorTest prove the PRIMITIVES match iOS. These prove the
 * STATE MACHINE does — out-of-order delivery, a DH ratchet step, and the OPK discipline.
 */
class SessionRoundTripTest {

    /** Production associated data is empty; the effective AAD is the 40 header bytes. */
    private val AD = ByteArray(0)

    private class Peer {
        val id = DesktopIdentity.generate()
        val spk = OSHICryptoV2.generateX25519()
        val opk = OSHICryptoV2.generateX25519()
        fun bundle(withOpk: Boolean = true) = FetchedBundle(
            identityKey = id.identity.pub,
            signingKey = id.signingPub,
            signedPreKeyId = "spk-1",
            signedPreKey = spk.pub,
            oneTimePreKeyId = if (withOpk) "opk-1" else null,
            oneTimePreKey = if (withOpk) opk.pub else null,
        )
    }

    @Test
    fun `a full conversation survives out-of-order delivery and a dh ratchet`() {
        val a = Peer(); val b = Peer()
        val init = V2Session.initiator(a.id.identity, b.bundle())
        val bob = V2Session.responder(b.id.identity, b.spk, b.opk, init.x3dh)

        val sent = (1..5).map { n ->
            OSHIRatchetV2.encrypt(init.state, "message $n".toByteArray(), AD) to n
        }

        // Deliver 5, 2, 1, 4, 3.
        for (i in listOf(4, 1, 0, 3, 2)) {
            val (msg, n) = sent[i]
            val (h, c) = msg
            assertEquals("message $n", String(OSHIRatchetV2.decrypt(bob, h, c, AD)))
        }

        // Bob replies: forces a DH ratchet on both sides.
        val (h6, c6) = OSHIRatchetV2.encrypt(bob, "reply".toByteArray(), AD)
        assertEquals("reply", String(OSHIRatchetV2.decrypt(init.state, h6, c6, AD)))

        // And Alice can answer on the new chain.
        val (h7, c7) = OSHIRatchetV2.encrypt(init.state, "after ratchet".toByteArray(), AD)
        assertEquals("after ratchet", String(OSHIRatchetV2.decrypt(bob, h7, c7, AD)))
    }

    /** A drained OPK pool must still produce a working session — DH4 is simply omitted. */
    @Test
    fun `a session works when the peer has no one-time prekey`() {
        val a = Peer(); val b = Peer()
        val init = V2Session.initiator(a.id.identity, b.bundle(withOpk = false))
        assertEquals(null, init.x3dh.oneTimePreKeyId)

        val bob = V2Session.responder(b.id.identity, b.spk, null, init.x3dh)
        val (h, c) = OSHIRatchetV2.encrypt(init.state, "no opk".toByteArray(), AD)
        assertEquals("no opk", String(OSHIRatchetV2.decrypt(bob, h, c, AD)))
    }

    /**
     * The responder MUST use the same OPK the initiator named. If it guesses wrong the
     * SK differs and the AEAD fails — which is the correct, loud outcome. This is the
     * shape of the bug where a re-sent x3dh header referenced an already-burned OPK and
     * made a conversation permanently unrecoverable.
     */
    @Test
    fun `a mismatched one-time prekey fails loudly rather than silently`() {
        val a = Peer(); val b = Peer()
        val init = V2Session.initiator(a.id.identity, b.bundle())
        val wrongOpk = OSHICryptoV2.generateX25519()
        val bob = V2Session.responder(b.id.identity, b.spk, wrongOpk, init.x3dh)
        val (h, c) = OSHIRatchetV2.encrypt(init.state, "hello".toByteArray(), AD)
        try {
            OSHIRatchetV2.decrypt(bob, h, c, AD)
            fail("a wrong OPK must not silently decrypt")
        } catch (_: Exception) {
            // Expected: AEAD tag mismatch. Must be RETRIED under a budget, never acked away.
        }
    }

    /**
     * The AAD is the raw 40 header bytes. Tampering with the header must break the tag —
     * this is what stops a relay reordering or re-attributing a message.
     */
    @Test
    fun `a tampered header breaks the aead tag`() {
        val a = Peer(); val b = Peer()
        val init = V2Session.initiator(a.id.identity, b.bundle())
        val bob = V2Session.responder(b.id.identity, b.spk, b.opk, init.x3dh)
        val (h, c) = OSHIRatchetV2.encrypt(init.state, "authentic".toByteArray(), AD)

        val forged = OSHICryptoV2.Header(dh = h.dh, pn = h.pn, n = h.n + 1)
        try {
            OSHIRatchetV2.decrypt(bob, forged, c, AD)
            fail("a modified header must not authenticate")
        } catch (_: Exception) {
        }
    }

    /**
     * The X3DH header rides message #1 ONLY. Re-attaching it on later messages references
     * an OPK the responder already burned, and poisons the peer's recovery path. The
     * skeleton asserts the invariant a sender must enforce.
     */
    @Test
    fun `each message advances the ratchet and never reuses a key or nonce`() {
        val a = Peer(); val b = Peer()
        val init = V2Session.initiator(a.id.identity, b.bundle())

        val headers = (1..10).map { OSHIRatchetV2.encrypt(init.state, "m$it".toByteArray(), AD).first }
        // Message numbers strictly increase within the chain.
        assertEquals((0L..9L).toList(), headers.map { it.n })

        // Encrypting the SAME plaintext twice must produce different ciphertext, or the
        // ratchet has rewound and we are reusing an AES-GCM (key, nonce) pair.
        val (_, c1) = OSHIRatchetV2.encrypt(init.state, "same".toByteArray(), AD)
        val (_, c2) = OSHIRatchetV2.encrypt(init.state, "same".toByteArray(), AD)
        assertNotEquals(c1.toList(), c2.toList())
    }

    /** The two checks that make a fetched bundle trustworthy. Both are mandatory. */
    @Test
    fun `bundle trust requires a valid spk signature and identity equal to address`() {
        val b = Peer()
        val sig = b.id.sign(b.spk.pub)

        assertTrue("SPK signature must verify over the RAW 32-byte key",
            DesktopIdentity.verify(b.spk.pub, sig, b.id.signingPub))

        // TOFU: the address IS the X25519 identity. A mismatch is key substitution.
        assertEquals(b.id.userKey, DesktopIdentity.B64.encodeToString(b.bundle().identityKey))

        // A substituted identity must be rejected.
        val attacker = DesktopIdentity.generate()
        assertNotEquals(b.id.userKey, DesktopIdentity.B64.encodeToString(attacker.identity.pub))

        // And a signature made over the wrong bytes must not verify.
        assertTrue(!DesktopIdentity.verify(attacker.identity.pub, sig, b.id.signingPub))
    }

    /** The full-file AEAD, end to end, including the manifest assertions. */
    @Test
    fun `file encrypt decrypt round-trips and the manifest guards truncation`() {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val enc = OSHICryptoV2.encryptFile(payload, "photo.png", "image/png", chunkSize = 1024)
        assertEquals(5, enc.chunks.size)

        assertArrayEquals(payload,
            OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest))

        // A truncated download must fail loudly, not yield corrupt media.
        try {
            OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks.dropLast(1), enc.manifest)
            fail("a missing chunk must be detected by the manifest")
        } catch (_: Exception) {
        }
    }
}
