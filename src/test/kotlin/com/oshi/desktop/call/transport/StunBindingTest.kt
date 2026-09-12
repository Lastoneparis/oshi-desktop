package com.oshi.desktop.call.transport

import com.oshi.desktop.call.media.CallMediaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1 — RFC 5389 Binding, byte for byte against both shipped clients.
 *
 * ============================================================ WHERE THESE BYTES CAME FROM
 *
 * Assembled in a Python session from RFC 5389 §6 and §15.1-15.2 and cross-read against
 * the two hand-rolled clients — **not** produced by [StunBinding]. A codec checked
 * against its own output is checked against nothing.
 *
 *   iOS      `OSHI/StunClient.swift:148-169`  buildBindingRequest — the 20-byte header
 *            `:175-219`                       parseBindingResponse — order of the checks
 *            `:213-215`                       the 4-byte attribute padding
 *            `:228-271`                       XOR-MAPPED-ADDRESS, v4 and v6
 *            `:274-286`                       MAPPED-ADDRESS, v4 only
 *            `:121-135`                       isBindingSuccessResponse AND the ⚠️ warning
 *   Android  `.../service/p2p/StunClient.kt:79-97`   buildBindingRequest
 *            `:103-144`                       parseBindingResponse
 *            `:139-141`                       the padding
 *            `:146-178`                       XOR-MAPPED-ADDRESS
 *            `:180-193`                       MAPPED-ADDRESS
 *
 * The arithmetic, redoable by hand:
 *
 *   cookie  = 0x2112A442
 *   x-port  = 51234 ⊕ 0x2112 = 0xC822 ⊕ 0x2112 = 0xE930
 *   x-addr  = 203.0.113.42 ⊕ cookie
 *           = CB 00 71 2A ⊕ 21 12 A4 42 = EA 12 D5 68
 */
class StunBindingTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    /** `00 01 02 ... 0b` — a fixed transaction id so the request is a pure function. */
    private val txId = ByteArray(12) { it.toByte() }

    private val otherTx = ByteArray(12) { (0xF0 + it).toByte() }

    // ============================================================== the request

    /**
     * `[0001][0000][2112A442][txid]`. Type first, THEN a zero length (no attributes),
     * THEN the cookie. `swift:151-167` appends in exactly that order; `kt:82-95` writes
     * the same five fields into a 20-byte array.
     */
    @Test
    fun `the binding request is twenty bytes in the shipped order`() {
        assertEquals(
            "0001" + "0000" + "2112a442" + "000102030405060708090a0b",
            hex(StunBinding.buildBindingRequest(txId)),
        )
        assertEquals(20, StunBinding.buildBindingRequest(txId).size)
    }

    /** A request carries no attributes, so the length field is zero, not 20. */
    @Test
    fun `the length field counts attributes and there are none`() {
        val req = StunBinding.buildBindingRequest(txId)
        assertEquals(0, ((req[2].toInt() and 0xFF) shl 8) or (req[3].toInt() and 0xFF))
    }

    @Test
    fun `two fresh transaction ids differ and are twelve bytes`() {
        val a = StunBinding.newTransactionId()
        val b = StunBinding.newTransactionId()
        assertEquals(12, a.size)
        assertFalse(a.contentEquals(b))
    }

    // ============================================================== the response

    /** Success response carrying XOR-MAPPED-ADDRESS for 203.0.113.42:51234. */
    private val xorV4 = "0101" + "000c" + "2112a442" + "000102030405060708090a0b" +
        "0020" + "0008" + "0001" + "e930" + "ea12d568"

    @Test
    fun `xor mapped address decodes to the public mapping`() {
        val m = StunBinding.parseBindingResponse(unhex(xorV4), txId)
        assertEquals(StunBinding.Mapped("203.0.113.42", 51234), m)
    }

    /**
     * The legacy attribute, unXORed. Some servers still answer with it and both clients
     * accept it (`swift:207-210`, `kt:134-136`), because a NAT that rewrites payloads
     * mangles the plain address and leaves the XORed one intact — which is why the XOR
     * exists and why the fallback is still worth having.
     */
    @Test
    fun `legacy mapped address decodes too`() {
        val legacy = "0101" + "000c" + "2112a442" + "000102030405060708090a0b" +
            "0001" + "0008" + "0001" + "c822" + "cb00712a"
        assertEquals(
            StunBinding.Mapped("203.0.113.42", 51234),
            StunBinding.parseBindingResponse(unhex(legacy), txId),
        )
    }

    /**
     * IPv6, XORed against `cookie ‖ transaction id` (RFC 5389 §15.2). This branch is not
     * decoration: an IPv6-only cellular peer has no IPv4 srflx at all, and iOS added it
     * for exactly that reason (`swift:223-224`, "cellular-IPv6 peers (T-Mobile etc.) get
     * a srflx candidate").
     *
     * `2001:db8::1 ⊕ (2112a442 ‖ 000102030405060708090a0b)` = `0113a9fa000102030405060708090a0a`.
     */
    @Test
    fun `xor mapped address handles ipv6`() {
        val v6 = "0101" + "0018" + "2112a442" + "000102030405060708090a0b" +
            "0020" + "0014" + "0002" + "e930" + "0113a9fa000102030405060708090a0a"
        val m = StunBinding.parseBindingResponse(unhex(v6), txId)
        assertNotNull(m)
        assertEquals(51234, m!!.port)
        assertEquals(
            java.net.InetAddress.getByName("2001:db8::1").hostAddress!!.substringBefore('%'),
            m.ip,
        )
    }

    /**
     * A response is not read for a transaction we did not send. This is not pedantry:
     * the reply lands on the MEDIA socket, so without it anyone who can guess the
     * five-tuple can inject a mapped address of their choosing and steer our srflx
     * candidate at a host they control. `swift:190-191`, `kt:117-119`.
     */
    @Test
    fun `a foreign transaction id is refused`() {
        assertNull(StunBinding.parseBindingResponse(unhex(xorV4), otherTx))
        // ...and the same bytes still parse under the right id, so this is not vacuous.
        assertNotNull(StunBinding.parseBindingResponse(unhex(xorV4), txId))
    }

    /** Wrong cookie: not a STUN message at all, whatever else it looks like. */
    @Test
    fun `a wrong magic cookie is refused`() {
        val bad = "0101" + "000c" + "deadbeef" + "000102030405060708090a0b" +
            "0020" + "0008" + "0001" + "e930" + "ea12d568"
        assertFalse(StunBinding.isBindingSuccess(unhex(bad)))
        assertNull(StunBinding.parseBindingResponse(unhex(bad), txId))
    }

    /** A Binding REQUEST (0x0001) is not a success response (0x0101). */
    @Test
    fun `a request is not mistaken for a response`() {
        assertFalse(StunBinding.isBindingSuccess(StunBinding.buildBindingRequest(txId)))
    }

    // ==================================================== THE DEMUX TRAP, ASSERTED

    /**
     * ============================================================ 0x15 AND 0xC0
     *
     * iOS's warning at `StunClient.swift:121-124` is the single most load-bearing comment
     * in this area:
     *
     *   > `Do NOT use (firstByte & 0xC0) == 0 as the discriminator: OSHI's own packet`
     *   > `types (0x05 AAC-ELD, 0x14/0x15/0x16/0x17 audio, 0x0E/0x0F video upgrade) all`
     *   > `satisfy it.`
     *
     * The first half of this test proves the trap is REAL — every audio type this
     * protocol defines passes the textbook check. The second half proves this
     * implementation does not fall into it: a sealed 48 kHz PCM frame, the exact bytes
     * this client puts on the wire fifty times a second, is not a Binding Success
     * Response. Without the first half the second would pass for the wrong reason.
     */
    @Test
    fun `every oshi media type passes the naive stun check and none is stun`() {
        val types = listOf(
            CallMediaFrame.TYPE_AAC_ELD, CallMediaFrame.TYPE_PCM_48K,
            CallMediaFrame.TYPE_OSHI_CODEC, CallMediaFrame.TYPE_PCM_16K,
        )
        for (t in types) {
            assertEquals("type 0x%02x defeats the (first & 0xC0) == 0 test".format(t), 0, t and 0xC0)
        }

        val sealed = CallMediaFrame.encode(
            ByteArray(32) { 0x11 }, byteArrayOf(1, 2, 3, 4), true, 1,
            CallMediaFrame.TYPE_PCM_48K, ByteArray(1920),
        )
        assertEquals(0, (sealed[0].toInt() and 0xFF) and 0xC0)
        assertFalse(
            "a sealed PCM frame must never be demuxed as a STUN response",
            StunBinding.isBindingSuccess(sealed),
        )
    }

    /** A hole-punch ping is not STUN either, though it lives on the same socket. */
    @Test
    fun `a hole punch ping is not a stun response`() {
        val ping = HolePunch.buildPing(0x0123456789ABCDEFL, ByteArray(12), 1L)
        assertFalse(StunBinding.isBindingSuccess(ping))
    }

    // ============================================================== the attribute walk

    /**
     * Attribute values are padded to four bytes and the padding is NOT in the length
     * field (`swift:213-215`, `kt:139-141`). Here a five-byte SOFTWARE attribute
     * (`0x8022`, value `"cotur"`) sits in front of XOR-MAPPED-ADDRESS: without the
     * `(len + 3) & ~3` step the walk lands three bytes early, reads garbage as the next
     * attribute type, and returns null — which looks exactly like a STUN server that did
     * not answer.
     */
    @Test
    fun `an odd length attribute is padded before the next one is read`() {
        val padded = "0101" + "0018" + "2112a442" + "000102030405060708090a0b" +
            "8022" + "0005" + "636f747572" + "000000" +
            "0020" + "0008" + "0001" + "e930" + "ea12d568"
        assertEquals(
            StunBinding.Mapped("203.0.113.42", 51234),
            StunBinding.parseBindingResponse(unhex(padded), txId),
        )
    }

    /**
     * A message length longer than the datagram is a truncated or lying message. Both
     * clients clamp with `min(20 + msgLen, count)` (`swift:195`, `kt:122`); without the
     * clamp the walk reads past the datagram into whatever the previous packet left in
     * a reused receive buffer.
     */
    @Test
    fun `a length field past the end of the datagram reads nothing`() {
        val lying = "0101" + "0fff" + "2112a442" + "000102030405060708090a0b" +
            "0020" + "0008" + "0001" + "e930" + "ea12d5"
        assertNull(StunBinding.parseBindingResponse(unhex(lying), txId))
    }

    /** An attribute whose own length runs past the message end ends the walk. */
    @Test
    fun `an over-long attribute length ends the walk`() {
        val overlong = "0101" + "000c" + "2112a442" + "000102030405060708090a0b" +
            "0020" + "00ff" + "0001" + "e930" + "ea12d568"
        assertNull(StunBinding.parseBindingResponse(unhex(overlong), txId))
    }

    /** Shorter than a header is not a STUN message. */
    @Test
    fun `a runt datagram is refused`() {
        assertFalse(StunBinding.isBindingSuccess(ByteArray(19)))
        assertNull(StunBinding.transactionId(ByteArray(19)))
        assertNull(StunBinding.parseBindingResponse(ByteArray(19), txId))
    }

    /** A success response with no address attribute at all is a null mapping, not a crash. */
    @Test
    fun `a response with no mapped address yields null`() {
        val empty = "0101" + "0000" + "2112a442" + "000102030405060708090a0b"
        assertTrue(StunBinding.isBindingSuccess(unhex(empty)))
        assertNull(StunBinding.parseBindingResponse(unhex(empty), txId))
    }

    /**
     * A legacy MAPPED-ADDRESS over IPv6 is refused rather than guessed — both clients
     * return null (`swift:285`, `kt:190-191`) and a wrong guess here would advertise a
     * candidate that cannot receive.
     */
    @Test
    fun `legacy mapped address refuses ipv6`() {
        val v6legacy = "0101" + "0018" + "2112a442" + "000102030405060708090a0b" +
            "0001" + "0014" + "0002" + "c822" + "20010db8000000000000000000000001"
        assertNull(StunBinding.parseBindingResponse(unhex(v6legacy), txId))
    }

    @Test
    fun `the transaction id is bytes eight through twenty`() {
        assertArrayEqualsHex("000102030405060708090a0b", StunBinding.transactionId(unhex(xorV4))!!)
    }

    private fun assertArrayEqualsHex(expected: String, actual: ByteArray) =
        assertEquals(expected, hex(actual))

    /**
     * The coturn both phones point at. Recorded, and deliberately NOT a default anywhere
     * in this package — [MediaSocket.requestSrflx] takes the server as a parameter, so
     * no test needs the internet and no redeploy needs a rebuild.
     * `StunClient.swift:27-28`, `StunClient.kt:27-28`, `VoiceCallManager.swift:2399-2402`.
     */
    @Test
    fun `the shipped stun endpoint is recorded`() {
        assertEquals("45.67.216.197", StunBinding.DEFAULT_SERVER.hostString)
        assertEquals(3478, StunBinding.DEFAULT_SERVER.port)
    }
}
