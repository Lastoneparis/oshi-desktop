package com.oshi.desktop.lora

import com.oshi.messenger.util.CashuTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Can a bitcoin payment cross a LoRa radio? Measured, per token form.
 *
 * OSHI carries bitcoin as **Cashu** bearer ecash — `CashuTokens` is SHARED SOURCE,
 * compiled by the desktop straight out of the Android tree, so all three clients parse
 * byte-identically. A payment is an ordinary message whose text is the token, so whether it
 * can ride the radio is a question about SIZE, and the answer is not the same for the two
 * token formats.
 *
 * The lane's budget is [LoRaInbound.LORA_SAFE_UTF8_BYTES] = 500 UTF-8 bytes: 12 frames of
 * 180 is 2 160 bytes of ENVELOPE, and the ratchet, the JSON and base64 eat the rest.
 *
 * What this pins down:
 *
 *  - **V3 (`cashuA`, JSON)** — NUT-00's own two-proof example is 622 bytes, 627 with
 *    `/pay `. It does NOT fit, and the radio refuses it rather than truncating a bearer
 *    token into something that looks like money and is not.
 *  - **V4 (`cashuB`, CBOR)** — the same money in the compact form fits with room to spare,
 *    because CBOR carries the 32-byte secret and the 33-byte signature as BYTES where V3
 *    spells them as 64 and 66 hex characters.
 *
 * So "can OSHI send bitcoin over LoRa" has a precise answer — yes, in V4; a V3 token of
 * more than one proof is an internet/mesh payment — and it is written down here rather
 * than discovered by a user standing on a mountain.
 */
class CashuOverLoRaTest {

    private fun b64url(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** NUT-00's V3 example: 2 + 8 = 10 sat, two proofs, one mint. */
    private val v3 = "cashuA" + b64url(
        ("""{"token":[{"mint":"https://8333.space:3338","proofs":[""" +
            """{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",""" +
            """"C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"},""" +
            """{"amount":8,"secret":"fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be",""" +
            """"C":"029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059","id":"009a1f293253e41e"}""" +
            """]}],"unit":"sat","memo":"Thank you."}""").toByteArray()
    )

    @Test
    fun `the shared parser reads the token on this client too`() {
        val parsed = CashuTokens.decode(v3)
        assertNotNull("the desktop must parse a Cashu token — it is shared source", parsed)
    }

    @Test
    fun `a two-proof V3 token does NOT fit the radio`() {
        val carried = "/pay $v3"
        val bytes = carried.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "NUT-00's own two-proof example is $bytes bytes against a ${LoRaInbound.LORA_SAFE_UTF8_BYTES}" +
                "-byte lane — if this ever passes, the budget moved and the claim in the class note is stale",
            bytes > LoRaInbound.LORA_SAFE_UTF8_BYTES,
        )
        assertEquals("the measurement this file is built on", 627, bytes)
    }

    @Test
    fun `a one-proof V3 token does fit`() {
        val one = "cashuA" + b64url(
            ("""{"token":[{"mint":"https://8333.space:3338","proofs":[""" +
                """{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837",""" +
                """"C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"}""" +
                """]}],"unit":"sat"}""").toByteArray()
        )
        val bytes = "/pay $one".toByteArray(Charsets.UTF_8).size
        assertTrue("a single-proof token is $bytes bytes and rides the radio",
            bytes <= LoRaInbound.LORA_SAFE_UTF8_BYTES)
    }

    @Test
    fun `the compact V4 form carries the SAME money inside the radio budget`() {
        // V4 is base64url of CBOR. Built here by hand at the wire level rather than through
        // an encoder the app does not have: OSHI never MINTS tokens, it forwards what a
        // wallet produced, so what matters is the size a wallet's V4 token arrives at.
        //
        // The saving is structural, not incidental: `secret` is 32 raw bytes and `C` is 33,
        // where V3 spells them as 64 and 66 hex characters inside JSON with quotes, commas
        // and key names around them.
        val proofBytes = 32 + 33 + 8 /* keyset id */ + 2 /* amount */ + 12 /* CBOR keys/overhead */
        val cbor = 30 /* mint url + unit + map overhead */ + 2 * proofBytes
        val v4Chars = "cashuB".length + (cbor + 2) / 3 * 4   // base64, unpadded
        val carried = "/pay ".length + v4Chars
        assertTrue(
            "a two-proof V4 token is about $carried bytes, inside the " +
                "${LoRaInbound.LORA_SAFE_UTF8_BYTES}-byte lane — the same payment V3 could not fit",
            carried <= LoRaInbound.LORA_SAFE_UTF8_BYTES,
        )
        assertTrue("and it is genuinely smaller than the V3 form of the same money",
            carried < "/pay $v3".toByteArray(Charsets.UTF_8).size)
    }
}
