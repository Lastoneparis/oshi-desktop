package com.oshi.desktop.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_OFFER_PEEL_WITHOUT_A_LIST_2026_11_20__ The capability peel once read its flags from
 * an ArrayList<Byte> built with add(0, …); twice in full suite runs a slot came back null (NPE
 * in decode). Decode the same offers until the JIT has compiled the peel, and require the same
 * answer every time.
 */
class CallOfferHotDecodeTest {
    @Test
    fun `a hot decode answers the same flags every time`() {
        val id = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
        val all = CallOffer(ByteArray(32) { 7 }, byteArrayOf(1, 2, 3, 4), id,
            supportsVideo = true, supportsWbAdpcm = true).encode()
        val legacy = all.copyOfRange(0, all.size - 4)   // fewer cap bytes than slots
        repeat(200_000) {
            val a = CallOffer.decode(all)!!
            assertTrue(a.supportsVideo && a.supportsWbAdpcm && a.callId == id)
            val b = CallOffer.decode(legacy)!!
            assertEquals(id, b.callId)
            assertTrue(!b.supportsVideo && !b.supportsWbAdpcm)
        }
    }
}
