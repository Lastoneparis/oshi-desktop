package com.oshi.desktop.call.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARITY.md row 2.1 — the exact bytes of the ICE candidate TLV.
 *
 * ============================================================ WHERE THESE BYTES CAME FROM
 *
 * **Not from [IceCandidateCodec.encode].** Every hex string below was assembled outside
 * this codebase, in a Python session, from the field layout read off the two shipped
 * encoders — because an expectation produced by the code under test proves
 * self-consistency and nothing else, and this project has already paid for that shape
 * once (see `CallWireFormatTest`'s own note, and PARITY.md's "autotest qui FABRIQUE son
 * cas avec le code testé").
 *
 * The sources every value is justified from:
 *
 *   iOS      `OSHI/P2PTransport.swift:1728-1743`   encodeCandidates — the field order
 *            `:1745-1774`                          decodeCandidates — the stopping rules
 *            `:44-48`                              P2PCandidateType raw values 0/1/2
 *            `:56`                                 `family` is the literal 4 or 6
 *            `:1640-1648`                          priority(type:family:)
 *            `:1776-1788`                          parseIPToBytes — inet_pton, no DNS
 *   Android  `.../service/p2p/P2PTransport.kt:1072-1094`  encodeCandidates
 *            `:1096-1127`                          decodeCandidates
 *            `:70`                                 CANDIDATE_WIRE_VERSION = 0x01
 *            `:116-128`                            CandidateType wire 0/1/2
 *            `:105-109`                            defaultPriority — DIFFERENT from iOS
 *
 * The arithmetic behind the fixtures, so a reader can redo it without running anything:
 *
 *   port 50000 = 0xC350        192.168.1.7   = C0 A8 01 07
 *   port 51234 = 0xC822        203.0.113.42  = CB 00 71 2A
 *   port 60000 = 0xEA60        45.67.216.197 = 2D 43 D8 C5
 *   iOS priority = (base << 24) | (familyAdj << 8)
 *        host/v4  = (126 << 24) | (1 << 8) = 0x7E000100
 *        srflx/v4 = (100 << 24) | (1 << 8) = 0x64000100
 *        relay/v4 = (  0 << 24) | (1 << 8) = 0x00000100
 *        host/v6  = (126 << 24) | (0 << 8) = 0x7E000000
 */
class IceCandidateWireTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun unhex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    /** Three IPv4 candidates, one of each type. Computed in Python, not by [encode]. */
    private val threeV4 =
        "0103" +
            "000" + "4" + "c350" + "c0a80107" + "7e000100" +
            "01" + "04" + "c822" + "cb00712a" + "64000100" +
            "02" + "04" + "ea60" + "2d43d8c5" + "00000100"

    private val hostV4 = IceCandidate(IceCandidateType.HOST, "192.168.1.7", 50000, 0x7E000100)
    private val srflxV4 = IceCandidate(IceCandidateType.SRFLX, "203.0.113.42", 51234, 0x64000100)
    private val relayV4 = IceCandidate(IceCandidateType.RELAY, "45.67.216.197", 60000, 0x00000100)

    // ============================================================== encode, byte for byte

    @Test
    fun `three candidates encode to the shipped bytes`() {
        assertEquals(threeV4, hex(IceCandidateCodec.encode(listOf(hostV4, srflxV4, relayV4))))
    }

    /**
     * The version byte is `0x01` and the count is the SECOND byte, not a 16-bit field.
     * `swift:1730-1731` appends `0x01` then one byte; `kt:1076-1077` writes two bytes.
     */
    @Test
    fun `header is version then a single count byte`() {
        val out = IceCandidateCodec.encode(listOf(hostV4))
        assertEquals(0x01.toByte(), out[0])
        assertEquals(0x01.toByte(), out[1])
        assertEquals(2 + 12, out.size)
    }

    /**
     * Empty encodes to exactly `01 00`. Android returns that literal
     * (`kt:1073 byteArrayOf(CANDIDATE_WIRE_VERSION, 0)`); iOS falls out of its loop
     * with the same two bytes.
     */
    @Test
    fun `no candidates is two bytes`() {
        assertEquals("0100", hex(IceCandidateCodec.encode(emptyList())))
    }

    /**
     * An IPv6 candidate is 24 bytes, not 12: 4 prefix + 16 address + 4 priority.
     * `2001:db8::1` = 20 01 0d b8 followed by eleven zero bytes and a 01.
     */
    @Test
    fun `ipv6 candidate carries sixteen address bytes`() {
        val v6 = IceCandidate(IceCandidateType.HOST, "2001:db8::1", 50000, 0x7E000000)
        assertEquals(
            "0101" + "00" + "06" + "c350" + "20010db8000000000000000000000001" + "7e000000",
            hex(IceCandidateCodec.encode(listOf(v6))),
        )
    }

    // ============================================================== decode

    @Test
    fun `the shipped bytes decode to the three candidates`() {
        val got = IceCandidateCodec.decode(unhex(threeV4))
        assertEquals(listOf(hostV4, srflxV4, relayV4), got)
        assertEquals(4, got[0].family)
        assertEquals(50000, got[0].port)
    }

    /**
     * ============================================================ THE FAMILY BYTE
     *
     * `4` and `6`, never the STUN encoding `0x01`/`0x02` — and this is the byte most
     * likely to be got wrong, because [StunBinding]'s XOR-MAPPED-ADDRESS attribute in
     * the same call DOES use `0x01`/`0x02` (`StunClient.swift:231-232`).
     *
     * Both decoders reject anything else outright and STOP: `swift:1757 guard family ==
     * 4 || family == 6 else { break }`, `kt:1112 else return out`. So a TLV that writes
     * `0x01` for IPv4 does not decode to a candidate with a wrong family — it decodes to
     * NOTHING, and the peer signals, punches at no one, and the call is silent. This
     * test pins that, and the second half pins that the shipped value still works, so it
     * cannot pass vacuously by rejecting everything.
     */
    @Test
    fun `stun family encoding is rejected and the literal one is accepted`() {
        val stunStyle = "0101" + "00" + "01" + "c350" + "c0a80107" + "7e000100"
        assertEquals(emptyList<IceCandidate>(), IceCandidateCodec.decode(unhex(stunStyle)))

        val literal = "0101" + "00" + "04" + "c350" + "c0a80107" + "7e000100"
        assertEquals(listOf(hostV4), IceCandidateCodec.decode(unhex(literal)))
    }

    /** `swift:1749 guard version == 0x01 else { return [] }`, `kt:1099-1102`. */
    @Test
    fun `an unknown version yields nothing rather than a partial parse`() {
        val v2 = "02" + threeV4.substring(2)
        assertEquals(emptyList<IceCandidate>(), IceCandidateCodec.decode(unhex(v2)))
    }

    /**
     * An unknown TYPE byte stops the loop. `swift:1754 guard let type = ... else break`,
     * `kt:1108 ?: return out`. Candidate 0 survives, candidate 1 and everything after it
     * does not — skipping instead would resynchronise on garbage, because the entry
     * length depends on the family byte that follows.
     */
    @Test
    fun `an unknown candidate type stops the walk and keeps what came before`() {
        val withBadType = "0103" +
            "00" + "04" + "c350" + "c0a80107" + "7e000100" +
            "09" + "04" + "c822" + "cb00712a" + "64000100" +
            "02" + "04" + "ea60" + "2d43d8c5" + "00000100"
        assertEquals(listOf(hostV4), IceCandidateCodec.decode(unhex(withBadType)))
    }

    /**
     * A count that promises more than the buffer holds returns what parsed.
     * `swift:1752/1762`, `kt:1107/1113`. Both shipped ENCODERS can produce this: they
     * write the count before skipping candidates whose IP fails to parse
     * (`swift:1732`, `kt:1079-1082`), so an over-count is a shape a real phone emits.
     */
    @Test
    fun `an over-declared count returns only the entries that are present`() {
        val overCount = "0105" +
            "00" + "04" + "c350" + "c0a80107" + "7e000100" +
            "01" + "04" + "c822" + "cb00712a" + "64000100"
        assertEquals(listOf(hostV4, srflxV4), IceCandidateCodec.decode(unhex(overCount)))
    }

    /** A truncated final entry is dropped whole; nothing is invented for the missing bytes. */
    @Test
    fun `a truncated trailing entry is dropped`() {
        val truncated = "0102" +
            "00" + "04" + "c350" + "c0a80107" + "7e000100" +
            "01" + "04" + "c822" + "cb00"
        assertEquals(listOf(hostV4), IceCandidateCodec.decode(unhex(truncated)))
    }

    @Test
    fun `a payload shorter than the header is empty`() {
        assertEquals(emptyList<IceCandidate>(), IceCandidateCodec.decode(byteArrayOf(0x01)))
        assertEquals(emptyList<IceCandidate>(), IceCandidateCodec.decode(ByteArray(0)))
    }

    /**
     * The wire round-trips through the BYTES, not through the string form: Java prints
     * `2001:db8::1` uncompressed and iOS's `inet_ntop` compresses it
     * (`swift:1790-1802`), so the two shipped clients already disagree about the string
     * while agreeing exactly about the sixteen bytes. Asserting the string here would
     * pin the JDK's formatter rather than the protocol.
     */
    @Test
    fun `ipv6 survives a round trip at the byte level`() {
        val original = unhex("0101" + "0006" + "c350" + "20010db8000000000000000000000001" + "7e000000")
        val decoded = IceCandidateCodec.decode(original)
        assertEquals(1, decoded.size)
        assertEquals(6, decoded[0].family)
        assertArrayEquals(original, IceCandidateCodec.encode(decoded))
    }

    // ============================================================== priority

    /**
     * ============================================================ THE ONE REAL DISAGREEMENT
     *
     * `(base << 24) | (familyAdj << 8)` on iOS versus `base << 24` (with `base - 1` for
     * IPv6) on Android. A host IPv4 candidate is `0x7E000100` from an iPhone and
     * `0x7E000000` from an Android phone, for the SAME candidate.
     *
     * It is not a wire break — the field is four bytes either way, and nothing on either
     * side reads a REMOTE candidate's priority: pairs are ranked by measured RTT and
     * success rate with a 1.5× direct bias (`swift:17-21`, `kt:819-825`), and the field
     * is sorted only over LOCAL candidates before they are offered (`kt:620`).
     *
     * Both are pinned so neither is the "expected" one, and the last assertion is the
     * reason this client emits iOS's: Android hands its WORST candidate type the LARGEST
     * unsigned priority in the protocol.
     */
    @Test
    fun `the two platforms compute priority differently and both are pinned`() {
        assertEquals(0x7E000100, IcePriority.ios(IceCandidateType.HOST, 4))
        assertEquals(0x64000100, IcePriority.ios(IceCandidateType.SRFLX, 4))
        assertEquals(0x00000100, IcePriority.ios(IceCandidateType.RELAY, 4))
        assertEquals(0x7E000000, IcePriority.ios(IceCandidateType.HOST, 6))
        assertEquals(0x64000000, IcePriority.ios(IceCandidateType.SRFLX, 6))
        assertEquals(0x00000000, IcePriority.ios(IceCandidateType.RELAY, 6))

        assertEquals(0x7E000000, IcePriority.android(IceCandidateType.HOST, 4))
        assertEquals(0x7D000000, IcePriority.android(IceCandidateType.HOST, 6))
        assertEquals(0x64000000, IcePriority.android(IceCandidateType.SRFLX, 4))
        assertEquals(0x63000000, IcePriority.android(IceCandidateType.SRFLX, 6))
        assertEquals(0x00000000, IcePriority.android(IceCandidateType.RELAY, 4))

        // `kt:108` — RELAY over IPv6 is `-1`, i.e. 0xFFFFFFFF unsigned. Not copied.
        assertEquals(-1, IcePriority.android(IceCandidateType.RELAY, 6))
        assertTrue(
            "Android's relay/v6 priority outranks its host/v4 when read unsigned",
            IcePriority.android(IceCandidateType.RELAY, 6).toUInt() >
                IcePriority.android(IceCandidateType.HOST, 4).toUInt(),
        )
    }

    /** A negative priority survives the wire unchanged — it is four opaque bytes. */
    @Test
    fun `a negative priority round trips`() {
        val c = IceCandidate(IceCandidateType.RELAY, "45.67.216.197", 60000, -1)
        val encoded = IceCandidateCodec.encode(listOf(c))
        assertTrue(hex(encoded).endsWith("ffffffff"))
        assertEquals(-1, IceCandidateCodec.decode(encoded)[0].priority)
    }

    // ============================================================== no resolver, ever

    /**
     * Android's encoder calls `InetAddress.getByName(c.ip)` (`kt:1079`), which performs a
     * BLOCKING DNS lookup for anything that is not a literal, inside what is supposed to
     * be a pure byte builder on the call-setup path. iOS cannot do this — it uses
     * `inet_pton` (`swift:1777-1786`). These assertions pin the `inet_pton` behaviour:
     * a hostname is not an address, and neither is `"10"`, which the JVM's own
     * `getByName` happily turns into `0.0.0.10`.
     */
    @Test
    fun `only literals parse and nothing reaches the resolver`() {
        assertNotNull(IceCandidateCodec.parseIpLiteral("192.168.1.7"))
        assertNotNull(IceCandidateCodec.parseIpLiteral("2001:db8::1"))
        assertNull(IceCandidateCodec.parseIpLiteral("localhost"))
        assertNull(IceCandidateCodec.parseIpLiteral("example.com"))
        assertNull(IceCandidateCodec.parseIpLiteral("10"))
        assertNull(IceCandidateCodec.parseIpLiteral("1.2.3"))
        assertNull(IceCandidateCodec.parseIpLiteral("1.2.3.4.5"))
        assertNull(IceCandidateCodec.parseIpLiteral("256.1.1.1"))
        assertNull(IceCandidateCodec.parseIpLiteral("1.2.3.a"))
        assertNull(IceCandidateCodec.parseIpLiteral(""))
    }

    /** A zone id is a local fact and means nothing to the peer. `kt:1123` strips it too. */
    @Test
    fun `an ipv6 zone id is stripped before encoding`() {
        assertArrayEquals(
            IceCandidateCodec.parseIpLiteral("fe80::1"),
            IceCandidateCodec.parseIpLiteral("fe80::1%en0"),
        )
    }

    /** A candidate whose address will not parse is skipped AND not counted. */
    @Test
    fun `an unparseable candidate is skipped and the count follows`() {
        val bad = IceCandidate(IceCandidateType.HOST, "not-an-address", 50000, 0)
        val out = IceCandidateCodec.encode(listOf(hostV4, bad, srflxV4))
        assertEquals(2, out[1].toInt())
        assertEquals(listOf(hostV4, srflxV4), IceCandidateCodec.decode(out))
    }

    /**
     * ============================================================ A GUARD THAT WAS DEAD
     *
     * The family byte and the address length can never disagree, because
     * [IceCandidateCodec.parseIpLiteral] decides both from the same string: a colon means
     * IPv6 and only an `Inet6Address` is accepted, so Java's IPv4-mapped
     * `::ffff:192.168.1.7` — which `getByName` returns as an `Inet4Address` — is refused
     * outright rather than encoded with a `6` family byte in front of four bytes.
     *
     * This test replaces an `addr.size != expected` check in [IceCandidateCodec.encode]
     * that a mutation run proved DEAD: breaking it left the whole suite GREEN, because
     * `parseIpLiteral` had already rejected the only input that could reach it. The
     * surviving mutant was the signal; the guard was deleted and this pins the invariant
     * that made it unreachable. (Android's `addrBytes.size` check at `kt:1082` is the
     * same idea, and it is NOT dead there — `InetAddress.getByName` can hand its encoder
     * a 4-byte address for a string it independently classified as v6.)
     */
    @Test
    fun `the address length is decided by the same rule as the family byte`() {
        assertNull(IceCandidateCodec.parseIpLiteral("::ffff:192.168.1.7"))
        for (ip in listOf("192.168.1.7", "8.8.8.8", "0.0.0.0", "255.255.255.255")) {
            assertEquals("$ip", 4, IceCandidateCodec.parseIpLiteral(ip)!!.size)
        }
        for (ip in listOf("2001:db8::1", "::1", "fe80::1%en0")) {
            assertEquals("$ip", 16, IceCandidateCodec.parseIpLiteral(ip)!!.size)
        }
        // ...and the mapped form is skipped by encode rather than mis-framed.
        val mapped = IceCandidate(IceCandidateType.HOST, "::ffff:192.168.1.7", 50000, 0)
        val out = IceCandidateCodec.encode(listOf(mapped, hostV4))
        assertEquals(1, out[1].toInt())
        assertEquals(listOf(hostV4), IceCandidateCodec.decode(out))
    }

    /** The count field is one byte; more than 255 candidates cannot be expressed. */
    @Test
    fun `the candidate list is clipped at 255`() {
        val many = (0 until 300).map {
            IceCandidate(IceCandidateType.HOST, "10.0.${it / 256}.${it % 256}", 50000, 0)
        }
        val out = IceCandidateCodec.encode(many)
        assertEquals(255, out[1].toInt() and 0xFF)
        assertEquals(IceCandidateCodec.HEADER_SIZE + 255 * 12, out.size)
        assertEquals(255, IceCandidateCodec.decode(out).size)
    }
}
