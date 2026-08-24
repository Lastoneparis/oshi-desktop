package com.oshi.desktop

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHICryptoV2.X25519Pair
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-for-byte parity of the DESKTOP V2 crypto core against iOS.
 *
 * These are the SAME known-answer vectors iOS asserts in OSHICryptoV2Tests.swift and
 * Android asserts in V2CryptoVectorTest.kt. Ported here deliberately UNCHANGED so that
 * all three platforms fail on the same assertion if any of them drifts.
 *
 * If any assertion here fails, the desktop client's X3DH / Double-Ratchet / file-chunk
 * crypto is not interoperable with iOS or Android, and nothing else it does matters.
 */
class V2CryptoVectorTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hexStr(d: ByteArray): String = d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `X3DH shared secret matches the iOS reference vector`() {
        val aIk = X25519Pair(
            hex("9075dca881d3bf1d61bb3d8887500ae4ab7e07533c7f2c23e918f87c49dde973"),
            hex("ca548efd90c81b073386d49b0a46fd9fcd9793180fadc0ea3cbadcfd39962403"),
        )
        val aEk = X25519Pair(
            hex("40fdf08a94a157da384df3c5b985d12089c5195e41deef35aec01c0f64916a74"),
            hex("f88ae69adedcc56713189704c703c7a2253fed76643a2ff3750ac5ffd5014475"),
        )
        val sk = OSHICryptoV2.x3dhInitiator(
            aIk, aEk,
            hex("3854d658e7ee3f7e1e99569048a64ab6a4da2ebe8bed8f71b8190abba1ab5e05"),
            hex("2ff3c27cbe727972e6d594336c8611311fa14740afaffda1e950e034944bc906"),
            hex("417e827f8aa889a74248fa298f0bbed6d06a4616239297ea24266d4456613947"),
        )
        assertEquals("1e698eedf60da463caacead61efe258cc730a8556050c2841d24ebfc621c9223", hexStr(sk))
    }

    @Test
    fun `Double Ratchet KDFs and AEAD match the iOS reference vector`() {
        val sk = hex("591df6ebb3bbeed6e2d2db39a6e8197fe3b9b6bc1196abcf5e9b174d07a55e88")
        val aRatchetPriv = hex("f0d6f7b0631bbdf92528320af8673fee53bde46120ab3da0b36d6490ef245760")
        val aRatchetPub = hex("5206c6092f1d552164eb33ebe66c7d943737b5de5d9f238073e59cbb97b05e6e")
        val bRatchetPub = hex("cb6a5743bff9c4fb0239d57895c7f5214a40562da2e806728d7f3ff316c41518")
        val ad = hex("6f7368692d61642d7632")   // "oshi-ad-v2" — TEST VECTOR ONLY, prod AAD is empty
        val pt = hex("68656c6c6f206f73686920646f75626c652072617463686574")

        val (rk, cks) = OSHICryptoV2.kdfRK(sk, OSHICryptoV2.dh(aRatchetPriv, bRatchetPub))
        assertEquals("bfc6c4f0408338cf0368387a2e4493a5432b220a1039114ff52783389afaa67f", hexStr(rk))
        assertEquals("bbe73b2fd3d6a904b66539137da48d606da192491802cc6900b9a197f666aa7b", hexStr(cks))

        val (_, mk) = OSHICryptoV2.kdfCK(cks)
        assertEquals("3f87018c970f224b743b0c431894ba4774b7b4ec31412b728cd2fdd7a03f3cd1", hexStr(mk))

        val (key, nonce) = OSHICryptoV2.deriveMsgKey(mk)
        assertEquals("88e46d8088fa5014d18ee333b94c8def8840fd98bdf4e9f063751ba2897734f5", hexStr(key))
        assertEquals("8bd0f0abfa88825abc68fc14", hexStr(nonce))

        val header = OSHICryptoV2.Header(dh = aRatchetPub, pn = 0, n = 0)
        val ct = OSHICryptoV2.aesGcmSeal(key, nonce, pt, ad + OSHICryptoV2.encodeHeader(header))
        assertEquals(
            "804127cba25e2ec4e966150459b39a0a4f4e81c044c8d8defd83420006e3267838de6f4715d059c1e6",
            hexStr(ct),
        )
    }

    @Test
    fun `file chunk nonce and AEAD match the iOS reference vector`() {
        val fileKey = hex("4eb8b5d38eb2262b1893c228e3773473b4fa5a7a082e74de32d0fe99e033e1f5")
        val fileNonce = hex("9122e59770302611")
        val pt = hex("4f5348492066696c65206368756e6b207a65726f207061796c6f61642030313233343536373839")

        val nonce0 = OSHICryptoV2.chunkNonce(fileNonce, 0)
        assertEquals("9122e5977030261100000000", hexStr(nonce0))
        assertEquals(
            "60db9693ed70c16f04514ca550ebdbf48f71df2687cec9b89d1682238d42fd7ebe96f1db8f0e23ef26bdaa6b5e0f17b2ae923d7847cf9e",
            hexStr(OSHICryptoV2.aesGcmSeal(fileKey, nonce0, pt, ByteArray(0))),
        )
    }

    /**
     * The manifest is the one JSON blob whose KEY ORDER is load-bearing: it reproduces
     * Node `JSON.stringify` / Swift declaration order, and it is AEAD plaintext, so a
     * reordering changes the bytes a peer must reproduce to open the file.
     */
    @Test
    fun `file manifest JSON has the exact iOS key order and no whitespace`() {
        assertEquals(
            """{"filename":"a.png","mime":"image/png","size":10,"chunkCount":1,"chunkSize":2097152}""",
            String(OSHICryptoV2.manifestJson("a.png", "image/png", 10, 1, 2 * 1024 * 1024)),
        )
    }

    /** The manifest rides under a reserved counter, NOT a chunk index. */
    @Test
    fun `manifest counter is the reserved u32 max`() {
        assertEquals(0xffffffffL, OSHICryptoV2.MANIFEST_COUNTER)
        assertEquals(
            "9122e59770302611ffffffff",
            hexStr(OSHICryptoV2.chunkNonce(hex("9122e59770302611"), OSHICryptoV2.MANIFEST_COUNTER)),
        )
    }

    /** The ratchet header is exactly 40 bytes: dh(32) ‖ pn(u32be) ‖ n(u32be). */
    @Test
    fun `ratchet header is 40 bytes big-endian and round-trips`() {
        val dh = ByteArray(32) { it.toByte() }
        val encoded = OSHICryptoV2.encodeHeader(OSHICryptoV2.Header(dh, pn = 258, n = 1))
        assertEquals(40, encoded.size)
        assertEquals("0000010200000001", hexStr(encoded.copyOfRange(32, 40)))  // big-endian, not little
        val back = OSHICryptoV2.decodeHeader(encoded)
        assertEquals(258L, back.pn)
        assertEquals(1L, back.n)
    }
}
