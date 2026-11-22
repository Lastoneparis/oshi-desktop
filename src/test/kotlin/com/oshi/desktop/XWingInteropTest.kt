package com.oshi.desktop

import com.oshi.messenger.network.encryption.PostQuantumKEM
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The desktop half of the X-Wing interop proof — ANSSI audit Finding 3.
 *
 * WHY THIS FILE EXISTS. Until today this client shipped no post-quantum layer at all,
 * while iOS and Android both shipped X-Wing (ML-KEM-768 + X25519). The fix was to compile
 * the phone's `PostQuantumKEM.kt` here rather than write a second one — but "we compile
 * the same file" is a claim about the build, not about the bytes. BouncyCastle is resolved
 * separately here (it was pinned at 1.76, which has no `pqc.crypto.xwing` at all), the JDK
 * differs from Android's, and `SecureRandom` is a different provider. Any of those could
 * change the output.
 *
 * So the desktop replays the SAME vectors Apple produced, rather than trusting that
 * sharing a source file is sufficient. If this build ever derives a public key one byte
 * different from the phone's, nothing would fail to compile: both sides would derive
 * different roots and every message would fail to decrypt, silently, in both directions.
 * This test is what turns that into a red build instead.
 *
 * The vectors come from `OSHITests/XWingVectorDumpTests.swift` against CryptoKit on iOS 26,
 * and reach this source set through the `test { resources.srcDir(...) }` declaration in
 * build.gradle.kts.
 */
class XWingInteropTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun doc(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("xwing_ios_vectors.json")
        assertNotNull(
            "iOS vectors missing — regenerate with OSHITests/XWingVectorDumpTests, " +
                "and check the test resources srcDir in build.gradle.kts still resolves",
            stream,
        )
        return JSONObject(stream!!.bufferedReader().readText())
    }

    private fun vectors(d: JSONObject): JSONArray = d.getJSONArray("vectors")

    /**
     * The sizes are read FROM the file, never from our own constants: comparing our
     * constants to themselves would pass no matter what Apple actually emits.
     */
    @Test
    fun `algorithm identifier and sizes match iOS`() {
        val d = doc()
        assertEquals(PostQuantumKEM.ALGORITHM_IDENTIFIER, d.getString("algorithm"))
        assertEquals(PostQuantumKEM.PUBLIC_KEY_BYTE_COUNT, d.getInt("publicKeyByteCount"))
        assertEquals(PostQuantumKEM.CIPHERTEXT_BYTE_COUNT, d.getInt("ciphertextByteCount"))
        assertEquals(PostQuantumKEM.SHARED_SECRET_BYTE_COUNT, d.getInt("sharedSecretByteCount"))
    }

    /**
     * seed → public key must be deterministic AND identical to Apple's.
     *
     * This is the half that catches a seed-expansion mistake: BouncyCastle wants the raw
     * 32 seed bytes and does the SHAKE-256 expansion itself, so anyone "helpfully"
     * expanding first gets plausible keys that match nothing.
     */
    @Test
    fun `public keys derived from iOS seeds match iOS`() {
        val v = vectors(doc())
        assertTrue("no vectors in file", v.length() > 0)
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val seed = b64(o.getString("seed"))
            val expected = b64(o.getString("publicKey"))
            assertArrayEquals(
                "public key #$i differs from iOS — the desktop would publish a key " +
                    "whose private half nobody holds",
                expected,
                PostQuantumKEM.publicKey(seed),
            )
        }
    }

    /**
     * Decapsulating Apple's ciphertext must yield Apple's shared secret. This is the
     * direction that actually carries a session: iOS encapsulates, desktop opens.
     */
    @Test
    fun `decapsulating iOS ciphertexts yields the iOS shared secret`() {
        val v = vectors(doc())
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val ss = PostQuantumKEM.decapsulate(
                ciphertext = b64(o.getString("ciphertext")),
                seed = b64(o.getString("seed")),
            )
            assertArrayEquals(
                "shared secret #$i differs from iOS — sessions would silently fail to decrypt",
                b64(o.getString("sharedSecret")),
                ss,
            )
        }
    }

    /**
     * The other direction: desktop encapsulates to an iOS public key. We cannot compare the
     * ciphertext to a fixture (encapsulation is randomised), so we check the shape and then
     * close the loop — decapsulating with the matching seed must recover what we sent.
     */
    @Test
    fun `encapsulating to an iOS public key round-trips`() {
        val v = vectors(doc())
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            val enc = PostQuantumKEM.encapsulate(b64(o.getString("publicKey")))
            assertEquals(PostQuantumKEM.CIPHERTEXT_BYTE_COUNT, enc.ciphertext.size)
            assertEquals(PostQuantumKEM.SHARED_SECRET_BYTE_COUNT, enc.sharedSecret.size)
            assertArrayEquals(
                "round-trip #$i failed: what the desktop sealed, it cannot open",
                enc.sharedSecret,
                PostQuantumKEM.decapsulate(enc.ciphertext, b64(o.getString("seed"))),
            )
        }
    }

    /**
     * Malformed peer material must be refused by length before it reaches BouncyCastle, and
     * must fail closed. A KEM that accepted a short key would be a downgrade oracle.
     */
    @Test
    fun `malformed material is refused, never silently accepted`() {
        val v = vectors(doc())
        val o = v.getJSONObject(0)
        val goodPk = b64(o.getString("publicKey"))
        val goodSeed = b64(o.getString("seed"))

        var refused = false
        try {
            PostQuantumKEM.encapsulate(goodPk.copyOf(goodPk.size - 1))
        } catch (_: PostQuantumKEM.MalformedKeyMaterial) {
            refused = true
        }
        assertTrue("a truncated public key was accepted", refused)

        var decapRefused = false
        try {
            PostQuantumKEM.decapsulate(ByteArray(PostQuantumKEM.CIPHERTEXT_BYTE_COUNT), goodSeed)
        } catch (_: PostQuantumKEM.DecapsulationFailed) {
            decapRefused = true
        }
        assertTrue("a bogus ciphertext did not fail closed", decapRefused)
    }
}
