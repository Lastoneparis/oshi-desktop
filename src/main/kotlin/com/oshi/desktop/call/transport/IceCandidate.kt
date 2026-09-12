package com.oshi.desktop.call.transport

import java.net.Inet6Address
import java.net.InetAddress

/**
 * The ICE candidate wire format — PARITY.md row 2.1's bespoke binary TLV.
 *
 * ```
 * [version 0x01][count u8]  then `count` × :
 *   [type u8][family u8][port u16 BE][addr 4 or 16][priority u32 BE]
 * ```
 *
 * `OSHI/P2PTransport.swift:1728-1743` encodes it, `:1745-1774` decodes it;
 * `OSHI-Android/.../service/p2p/P2PTransport.kt:1072-1094` and `:1096-1127` produce and
 * consume the SAME bytes. The payload rides packet type `0x30`
 * (`iceCandidateExchange`, `VoiceCallManager.swift:2040`,
 * `P2PTransport.kt:66 PACKET_TYPE_ICE_EXCHANGE`) inside the ordinary encrypted call
 * signalling envelope — `VoiceCallManager.swift:13939-13940` is the one line that turns
 * candidates into a signal.
 *
 * ============================================================ THERE IS NO SDP HERE
 *
 * This is the whole of OSHI's "session description". No `a=candidate:` lines, no `m=`
 * section, no fingerprint, no ice-ufrag/ice-pwd — the connectivity check is not
 * RFC 5245 STUN with USERNAME, it is the bespoke 29-byte ping in [HolePunch]. A WebRTC
 * `PeerConnection` fed an SDP built from these candidates would emit STUN binding
 * requests carrying a USERNAME attribute that no OSHI phone parses, and would expect
 * DTLS-SRTP on the media path where OSHI puts raw AES-256-GCM. See
 * [com.oshi.desktop.call.media.CallMediaFrame]'s WHY THERE IS NO WEBRTC HERE.
 *
 * ============================================================ THE FAMILY BYTE IS 4 OR 6
 *
 * Not `0x01`/`0x02`. This is the single easiest byte in the protocol to get wrong,
 * because the family byte in the STUN attribute five files over IS `0x01`/`0x02`
 * (`StunClient.swift:231-232`, RFC 5389 §15.1) and the two live in the same call.
 * Both shipped clients write the literal integers:
 *
 *   > `var family: UInt8 { ip.contains(":") ? 6 : 4 }` — `P2PTransport.swift:56`
 *   > `val family = if (addr is Inet6Address) 6 else 4` — `P2PTransport.kt:1081`
 *
 * and both decoders REJECT anything else outright — `guard family == 4 || family == 6
 * else { break }` (`swift:1757`), `else return out` (`kt:1112`). Emit `0x01` for IPv4
 * and the peer stops parsing at candidate zero: a call that signals, exchanges an
 * empty candidate set, never punches, and falls back to the relay if there is one and
 * to silence if there is not.
 *
 * ============================================================ WHAT THE COUNT BYTE MEANS
 *
 * Both encoders write `count` BEFORE skipping candidates whose address fails to parse
 * (`swift:1733 guard let addrBytes = parseIPToBytes(c.ip) else { continue }`,
 * `kt:1079-1082 catch { continue }`), so a shipped client can emit a header claiming
 * N entries with fewer than N behind it. Both decoders survive that by treating the
 * count as an UPPER BOUND and stopping at the first truncated entry — so this encoder
 * writes the count of entries it ACTUALLY emitted, which every peer already handles
 * and which is strictly more honest.
 *
 * iOS additionally caps only the count byte and not the loop
 * (`swift:1731 UInt8(min(cands.count, 255))` with no `prefix(255)`), so 300 candidates
 * become a header saying 255 and 300 bodies. Harmless — the peer reads 255 and stops —
 * but not reproduced: [encode] takes the first [MAX_CANDIDATES] and writes that number.
 *
 * ============================================================ NO DNS IN A WIRE ENCODER
 *
 * Android's encoder calls `InetAddress.getByName(c.ip)` (`kt:1079`), which will perform
 * a **blocking DNS lookup** if the string is not a literal. On the JVM that is a
 * network round trip inside what is supposed to be a pure byte builder, on the call
 * setup path, holding whatever thread called it. iOS uses `inet_pton` and cannot do
 * this (`swift:1776-1788`). [parseIpLiteral] below is the `inet_pton` side of that
 * disagreement: IPv4 is parsed by hand as a strict dotted quad, and IPv6 goes through
 * `InetAddress.getByName` ONLY when the string contains a colon — which no hostname
 * may — so the resolver is never reachable from here.
 */
enum class IceCandidateType(val wire: Byte) {
    /** `0x00` — a local interface address. `P2PTransport.swift:45`, `kt:117`. */
    HOST(0x00),

    /** `0x01` — server-reflexive: what STUN says our public mapping is. */
    SRFLX(0x01),

    /** `0x02` — a TURN relay allocation. */
    RELAY(0x02);

    companion object {
        /** Null for an unknown byte — both decoders STOP rather than skip. */
        fun fromWire(b: Byte): IceCandidateType? = when (b.toInt() and 0xFF) {
            0x00 -> HOST
            0x01 -> SRFLX
            0x02 -> RELAY
            else -> null
        }
    }
}

/**
 * One ICE candidate.
 *
 * [ip] is always a literal — see the NO DNS note on [IceCandidateType]. [priority] is
 * carried as a signed [Int] because that is what the wire holds (4 bytes) and what
 * Android holds (`kt:135 val priority: Int`); iOS holds it as `UInt32`. The two agree
 * bit for bit and disagree only about how they print it.
 */
data class IceCandidate(
    val type: IceCandidateType,
    val ip: String,
    val port: Int,
    val priority: Int,
) {
    init {
        require(port in 0..0xFFFF) { "port out of range: $port" }
    }

    /** `4` or `6` — the literal integers, NOT the STUN 0x01/0x02. `swift:56`, `kt:137`. */
    val family: Int get() = if (ip.contains(':')) 6 else 4

    /** True for a path that does not traverse a TURN server. Used by the DIRECT_BIAS. */
    val isDirect: Boolean get() = type != IceCandidateType.RELAY

    /** `"ip:port"` — the pair key both clients use (`swift:1626-1628`, `kt` `relayKey`). */
    val key: String get() = "$ip:$port"
}

/**
 * Encode and decode the candidate TLV, byte-exactly.
 *
 * Every expectation in `IceCandidateWireTest` was written from the two shipped encoders
 * and computed outside this file; none of it was produced by [encode].
 */
object IceCandidateCodec {

    /** `CANDIDATE_WIRE_VERSION` — `P2PTransport.kt:70`, `swift:1730` writes `0x01`. */
    const val WIRE_VERSION: Byte = 0x01

    /** The count field is one byte. `kt:1074` clips; `swift:1730` clips the header only. */
    const val MAX_CANDIDATES = 255

    /** version + count. The shortest legal payload is exactly this, meaning "none". */
    const val HEADER_SIZE = 2

    /** type + family + port(2). Read before the address length is known. */
    const val ENTRY_PREFIX = 4

    /**
     * `[0x01][count]` and then one 12-byte (v4) or 24-byte (v6) entry each.
     *
     * A candidate whose [IceCandidate.ip] is not a literal of its own family is SKIPPED
     * and not counted — see WHAT THE COUNT BYTE MEANS. An empty list encodes to the two
     * bytes `01 00`, which is what both clients emit for "nothing gathered"
     * (`kt:1073` returns `byteArrayOf(CANDIDATE_WIRE_VERSION, 0)` explicitly).
     */
    fun encode(candidates: List<IceCandidate>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        var written = 0
        for (c in candidates) {
            if (written >= MAX_CANDIDATES) break
            // [parseIpLiteral] IS the family guard, which is why there is no length
            // check here. It decides family and length from the same string: a colon
            // means IPv6 and ONLY an `Inet6Address` is accepted, so Java's IPv4-mapped
            // `::ffff:1.2.3.4` — which `getByName` returns as an `Inet4Address` — is
            // refused outright rather than written as a `6` family byte in front of four
            // address bytes, which would put the peer's decoder twelve bytes out of step
            // and corrupt every candidate after it.
            //
            // A separate `addr.size != expected` check stood here until a mutation run
            // showed it was DEAD: breaking it left the whole suite green, because no
            // input can reach it. A guard no fixture can exercise is not a guard, so it
            // was removed rather than left as decoration. `IceCandidateWireTest`'s
            // `the address length is decided by the same rule as the family byte` pins
            // the invariant that makes it unnecessary.
            val addr = parseIpLiteral(c.ip) ?: continue
            body.write(c.type.wire.toInt() and 0xFF)
            body.write(c.family)
            body.write((c.port ushr 8) and 0xFF)
            body.write(c.port and 0xFF)
            body.write(addr)
            body.write((c.priority ushr 24) and 0xFF)
            body.write((c.priority ushr 16) and 0xFF)
            body.write((c.priority ushr 8) and 0xFF)
            body.write(c.priority and 0xFF)
            written++
        }
        val bodyBytes = body.toByteArray()
        val out = ByteArray(HEADER_SIZE + bodyBytes.size)
        out[0] = WIRE_VERSION
        out[1] = (written and 0xFF).toByte()
        bodyBytes.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * Parse a candidate payload. Never throws; returns what it could read.
     *
     * The stopping rules are copied from both decoders and they are STOP, not SKIP:
     * an unknown type byte (`swift:1754`, `kt:1108`), a family that is not 4 or 6
     * (`swift:1757`, `kt:1112`), or a truncated entry (`swift:1762`, `kt:1113`) ends
     * the loop and returns the candidates gathered so far. Skipping instead would
     * resynchronise on garbage: the entry length depends on the family byte, so one
     * bad byte means every subsequent offset is wrong.
     *
     * A wrong version byte returns EMPTY, not partial — `swift:1749 guard version ==
     * 0x01 else { return [] }`, `kt:1099-1102` logs and returns `emptyList()`.
     */
    fun decode(data: ByteArray): List<IceCandidate> {
        if (data.size < HEADER_SIZE) return emptyList()
        if (data[0] != WIRE_VERSION) return emptyList()
        val declared = data[1].toInt() and 0xFF
        val out = ArrayList<IceCandidate>(declared)
        var i = HEADER_SIZE
        repeat(declared) {
            if (i + ENTRY_PREFIX > data.size) return out
            val type = IceCandidateType.fromWire(data[i]) ?: return out
            val family = data[i + 1].toInt() and 0xFF
            val port = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += ENTRY_PREFIX
            val addrLen = when (family) {
                4 -> 4
                6 -> 16
                else -> return out
            }
            if (i + addrLen + 4 > data.size) return out
            val ip = formatIp(data, i, addrLen) ?: return out
            i += addrLen
            val priority = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            i += 4
            out.add(IceCandidate(type, ip, port, priority))
        }
        return out
    }

    /**
     * Bytes of an IP LITERAL, or null. Never resolves a name — see the NO DNS note.
     */
    fun parseIpLiteral(ip: String): ByteArray? {
        if (ip.isEmpty()) return null
        if (ip.contains(':')) {
            // A hostname cannot contain ':', so getByName cannot reach the resolver
            // here. Strip a scope id the way Android does (`kt:1123`) — `%en0` is a
            // local fact and means nothing to the peer.
            val bare = ip.substringBefore('%')
            return runCatching {
                val a = InetAddress.getByName(bare)
                if (a is Inet6Address) a.address else null
            }.getOrNull()
        }
        // Strict dotted quad. `InetAddress.getByName("10")` is a valid host on the JVM
        // and resolves to 0.0.0.10; nothing on this wire may be that forgiving.
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (k in 0 until 4) {
            val p = parts[k]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            out[k] = v.toByte()
        }
        return out
    }

    /**
     * Bytes → string.
     *
     * IPv4 is formatted by hand as the shipped clients do (`swift:1791-1794` builds
     * `"\(b[0]).\(b[1])..."`). IPv6 goes through [InetAddress], which prints the
     * UNCOMPRESSED form — `2001:db8:0:0:0:0:0:1` where iOS's `inet_ntop` prints
     * `2001:db8::1` (`swift:1799`). **The two shipped clients already disagree about
     * this string**, and it is not a wire difference: both re-encode to the same 16
     * bytes, and every equality that matters in this package is on those bytes or on
     * [IceCandidate.key], which is derived from the same normalised string on both
     * sides of a single process. Matching Android is the cheaper of two arbitrary
     * choices, because it is what the JDK gives.
     */
    private fun formatIp(data: ByteArray, off: Int, len: Int): String? {
        if (len == 4) {
            return "${data[off].toInt() and 0xFF}.${data[off + 1].toInt() and 0xFF}." +
                "${data[off + 2].toInt() and 0xFF}.${data[off + 3].toInt() and 0xFF}"
        }
        return runCatching {
            InetAddress.getByAddress(data.copyOfRange(off, off + len)).hostAddress?.substringBefore('%')
        }.getOrNull()
    }
}

/**
 * The priority field, and the fact that the two phones compute it DIFFERENTLY.
 *
 * ```
 * iOS      (base << 24) | (familyAdj << 8)     base host=126 srflx=100 relay=0
 *          familyAdj = 1 for IPv4, 0 for IPv6         — P2PTransport.swift:1640-1648
 * Android  family==4 ? base<<24 : (base-1)<<24  host=126 srflx=100 relay=0/-1
 *                                                     — P2PTransport.kt:105-109
 * ```
 *
 * So a host IPv4 candidate is `0x7E000100` from an iPhone and `0x7E000000` from an
 * Android phone. **This is not an interop break**, because nothing on either side reads
 * a REMOTE candidate's priority: iOS ranks pairs by `(1 / (RTT+1)) × successRate` with
 * a 1.5× direct bias (`P2PTransport.swift:17-21`) and Android does the same
 * (`kt:819-825`); the field is sorted only over LOCAL candidates before they are
 * offered (`kt:620`). It is decoration that both sides faithfully carry and neither
 * consults.
 *
 * Both are pinned here so a test can assert either shape without one of them being the
 * "expected" one, and [ios] is what this client emits: the row's named reference is the
 * iOS source, and Android's relay/IPv6 value is `-1` — `0xFFFFFFFF`, the LARGEST
 * unsigned priority in the protocol, handed to the worst possible candidate. Reading it
 * as unsigned (which is what iOS's `UInt32` would do) inverts the ordering exactly where
 * it matters least and exactly where it is most obviously wrong. It is copied nowhere.
 */
object IcePriority {

    /** `P2PTransport.swift:1640-1648`. What this client emits. */
    fun ios(type: IceCandidateType, family: Int): Int {
        val base = when (type) {
            IceCandidateType.HOST -> 126
            IceCandidateType.SRFLX -> 100
            IceCandidateType.RELAY -> 0
        }
        val familyAdj = if (family == 4) 1 else 0
        return (base shl 24) or (familyAdj shl 8)
    }

    /** `P2PTransport.kt:105-109`. Recorded for comparison; never emitted. */
    fun android(type: IceCandidateType, family: Int): Int = when (type) {
        IceCandidateType.HOST -> if (family == 4) 126 shl 24 else 125 shl 24
        IceCandidateType.SRFLX -> if (family == 4) 100 shl 24 else 99 shl 24
        IceCandidateType.RELAY -> if (family == 4) 0 shl 24 else -1
    }
}
