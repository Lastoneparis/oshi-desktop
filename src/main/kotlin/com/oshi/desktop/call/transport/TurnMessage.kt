package com.oshi.desktop.call.transport

import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * STUN/TURN message framing for the relay leg — PARITY.md row 2.1-t.
 *
 * The subset both phones speak to the coturn at `45.67.216.197:3478`
 * (`OSHI/TurnClient.swift:139-164`, `OSHI-Android/.../service/p2p/TurnClient.kt:80-95`):
 * Allocate, Refresh, ChannelBind, the Data indication, and ChannelData. Long-term
 * credentials (RFC 8489 §9.2): key = MD5(username ":" realm ":" password), every
 * authenticated request carries USERNAME, REALM, NONCE, MESSAGE-INTEGRITY
 * (HMAC-SHA1) and FINGERPRINT (CRC-32 XOR `0x5354554E`), in that order.
 *
 * Pure: no socket, no clock. [TurnClient] owns the socket.
 */
object TurnMessage {

    const val MAGIC_COOKIE = 0x2112A442
    const val HEADER_SIZE = 20

    // Methods, already merged with their class bits (RFC 8489 §5).
    const val ALLOCATE_REQUEST = 0x0003
    const val ALLOCATE_SUCCESS = 0x0103
    const val ALLOCATE_ERROR = 0x0113
    const val REFRESH_REQUEST = 0x0004
    const val REFRESH_SUCCESS = 0x0104
    const val REFRESH_ERROR = 0x0114
    const val CHANNEL_BIND_REQUEST = 0x0009
    const val CHANNEL_BIND_SUCCESS = 0x0109
    const val CHANNEL_BIND_ERROR = 0x0119
    const val DATA_INDICATION = 0x0017

    const val ATTR_MAPPED_ADDRESS = 0x0001
    const val ATTR_USERNAME = 0x0006
    const val ATTR_MESSAGE_INTEGRITY = 0x0008
    const val ATTR_ERROR_CODE = 0x0009
    const val ATTR_CHANNEL_NUMBER = 0x000C
    const val ATTR_LIFETIME = 0x000D
    const val ATTR_XOR_PEER_ADDRESS = 0x0012
    const val ATTR_DATA = 0x0013
    const val ATTR_REALM = 0x0014
    const val ATTR_NONCE = 0x0015
    const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
    const val ATTR_REQUESTED_TRANSPORT = 0x0019
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val ATTR_FINGERPRINT = 0x8028

    private const val FINGERPRINT_XOR = 0x5354554E

    /** UDP (17), as the first byte of REQUESTED-TRANSPORT. */
    const val TRANSPORT_UDP: Byte = 17

    /** RFC 8656 §12: channel numbers live in 0x4000..0x4FFF. */
    const val CHANNEL_MIN = 0x4000
    const val CHANNEL_MAX = 0x4FFF

    private val random = SecureRandom()

    fun newTransactionId(): ByteArray = ByteArray(12).also { random.nextBytes(it) }

    /** RFC 8489 §9.2.2: MD5(username:realm:password). */
    fun longTermKey(username: String, realm: String, password: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$username:$realm:$password".toByteArray(Charsets.UTF_8))

    class Attr(val type: Int, val value: ByteArray)

    /**
     * Build a message. With [integrityKey], MESSAGE-INTEGRITY is appended over everything
     * before it; FINGERPRINT is always appended last.
     */
    fun build(type: Int, txId: ByteArray, attrs: List<Attr>, integrityKey: ByteArray? = null): ByteArray {
        require(txId.size == 12) { "transaction id is 12 bytes" }
        var body = ByteArray(0)
        for (a in attrs) body += encodeAttr(a.type, a.value)
        if (integrityKey != null) {
            // The length field covers the body UP TO AND INCLUDING the integrity attribute.
            val header = header(type, body.size + 24, txId)
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(integrityKey, "HmacSHA1")) }
            body += encodeAttr(ATTR_MESSAGE_INTEGRITY, mac.doFinal(header + body))
        }
        val fpHeader = header(type, body.size + 8, txId)
        body += encodeAttr(ATTR_FINGERPRINT, u32(fingerprintOf(fpHeader + body)))
        return header(type, body.size, txId) + body
    }

    fun fingerprintOf(bytes: ByteArray): Int {
        val crc = CRC32().apply { update(bytes) }
        return (crc.value.toInt()) xor FINGERPRINT_XOR
    }

    /** A parsed STUN message. [raw] is kept so integrity can be verified after parsing. */
    class Parsed(val type: Int, val txId: ByteArray, val attrs: Map<Int, ByteArray>, val raw: ByteArray) {
        fun attr(t: Int): ByteArray? = attrs[t]

        /** ERROR-CODE as class*100 + number, or null. */
        val errorCode: Int?
            get() = attrs[ATTR_ERROR_CODE]?.takeIf { it.size >= 4 }?.let { (it[2].toInt() and 0x07) * 100 + (it[3].toInt() and 0xFF) }

        val realm: String? get() = attrs[ATTR_REALM]?.toString(Charsets.UTF_8)
        val nonce: ByteArray? get() = attrs[ATTR_NONCE]
        val lifetime: Int? get() = attrs[ATTR_LIFETIME]?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int }
        val relayed: Pair<String, Int>? get() = attrs[ATTR_XOR_RELAYED_ADDRESS]?.let { decodeXorAddress(it, txId) }
        val mapped: Pair<String, Int>? get() = attrs[ATTR_XOR_MAPPED_ADDRESS]?.let { decodeXorAddress(it, txId) }
        val peer: Pair<String, Int>? get() = attrs[ATTR_XOR_PEER_ADDRESS]?.let { decodeXorAddress(it, txId) }

        /**
         * True when MESSAGE-INTEGRITY is present and matches [key]. Responses from coturn
         * carry it; a response that fails it is dropped by [TurnClient], so a spoofed
         * Allocate success cannot plant a relay address we would then advertise.
         */
        fun integrityValid(key: ByteArray): Boolean {
            val off = attrOffset(raw, ATTR_MESSAGE_INTEGRITY) ?: return false
            val expected = raw.copyOfRange(off + 4, off + 24)
            val prefix = raw.copyOfRange(0, off)
            // Length field rewritten to end at the integrity attribute (RFC 8489 §14.5).
            val lenFix = off + 24 - HEADER_SIZE
            prefix[2] = (lenFix ushr 8).toByte(); prefix[3] = lenFix.toByte()
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            return MessageDigest.isEqual(mac.doFinal(prefix), expected)
        }

        /** True when FINGERPRINT is absent (optional) or correct. */
        fun fingerprintValid(): Boolean {
            val off = attrOffset(raw, ATTR_FINGERPRINT) ?: return true
            val want = ByteBuffer.wrap(raw, off + 4, 4).int
            return fingerprintOf(raw.copyOfRange(0, off)) == want
        }
    }

    /** True when [data] looks like a STUN message: top two bits 0 and the magic cookie. */
    fun isStun(data: ByteArray, length: Int = data.size): Boolean =
        length >= HEADER_SIZE && (data[0].toInt() and 0xC0) == 0 &&
            ByteBuffer.wrap(data, 4, 4).int == MAGIC_COOKIE

    fun parse(data: ByteArray, length: Int = data.size): Parsed? {
        if (!isStun(data, length)) return null
        val type = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val msgLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (HEADER_SIZE + msgLen > length || msgLen % 4 != 0) return null
        val txId = data.copyOfRange(8, 20)
        val attrs = LinkedHashMap<Int, ByteArray>()
        var i = HEADER_SIZE
        val end = HEADER_SIZE + msgLen
        while (i + 4 <= end) {
            val t = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val l = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (i + 4 + l > end) return null
            // First occurrence wins (RFC 8489 §14): later duplicates are ignored.
            attrs.putIfAbsent(t, data.copyOfRange(i + 4, i + 4 + l))
            i += 4 + l + ((4 - l % 4) % 4)
        }
        return Parsed(type, txId, attrs, data.copyOfRange(0, end))
    }

    // ================================================================ addresses

    fun encodeXorAddress(ip: String, port: Int, txId: ByteArray): ByteArray {
        val addr = IceCandidateCodec.parseIpLiteral(ip) ?: throw IllegalArgumentException("not an IP literal")
        val out = ByteArray(4 + addr.size)
        out[1] = if (addr.size == 4) 0x01 else 0x02
        val xport = port xor (MAGIC_COOKIE ushr 16)
        out[2] = (xport ushr 8).toByte(); out[3] = xport.toByte()
        val mask = u32(MAGIC_COOKIE) + txId
        for (k in addr.indices) out[4 + k] = (addr[k].toInt() xor mask[k].toInt()).toByte()
        return out
    }

    fun decodeXorAddress(v: ByteArray, txId: ByteArray): Pair<String, Int>? {
        if (v.size < 8) return null
        val family = v[1].toInt()
        val len = when (family) { 0x01 -> 4; 0x02 -> 16; else -> return null }
        if (v.size < 4 + len) return null
        val port = (((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)) xor (MAGIC_COOKIE ushr 16)
        val mask = u32(MAGIC_COOKIE) + txId
        val addr = ByteArray(len) { k -> (v[4 + k].toInt() xor mask[k].toInt()).toByte() }
        val ip = InetAddress.getByAddress(addr).hostAddress.substringBefore('%')
        return ip to (port and 0xFFFF)
    }

    // ================================================================ ChannelData

    /**
     * `[channel 2][length 2][data]`. No padding over UDP; over TCP/TLS it MUST be padded to
     * a multiple of 4 (RFC 8656 §12.5) and the length field still names the unpadded size.
     */
    fun channelData(channel: Int, data: ByteArray, pad: Boolean = false): ByteArray {
        val size = if (pad) (4 + data.size + 3) and 3.inv() else 4 + data.size
        val out = ByteArray(size)
        out[0] = (channel ushr 8).toByte(); out[1] = channel.toByte()
        out[2] = (data.size ushr 8).toByte(); out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        return out
    }

    /** True when the first byte marks ChannelData (top two bits `01`). */
    fun isChannelData(data: ByteArray, length: Int = data.size): Boolean =
        length >= 4 && (data[0].toInt() and 0xC0) == 0x40

    /** Returns (channel, payload), or null if the length field runs past the datagram. */
    fun parseChannelData(data: ByteArray, length: Int = data.size): Pair<Int, ByteArray>? {
        if (!isChannelData(data, length)) return null
        val ch = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val len = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (4 + len > length) return null
        return ch to data.copyOfRange(4, 4 + len)
    }

    // ================================================================ helpers

    private fun header(type: Int, len: Int, txId: ByteArray): ByteArray =
        byteArrayOf((type ushr 8).toByte(), type.toByte(), (len ushr 8).toByte(), len.toByte()) +
            u32(MAGIC_COOKIE) + txId

    private fun encodeAttr(type: Int, value: ByteArray): ByteArray {
        val pad = (4 - value.size % 4) % 4
        val out = ByteArray(4 + value.size + pad)
        out[0] = (type ushr 8).toByte(); out[1] = type.toByte()
        out[2] = (value.size ushr 8).toByte(); out[3] = value.size.toByte()
        System.arraycopy(value, 0, out, 4, value.size)
        return out
    }

    private fun u32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Offset of the first attribute of [type] in a raw message, or null. */
    private fun attrOffset(raw: ByteArray, type: Int): Int? {
        var i = HEADER_SIZE
        while (i + 4 <= raw.size) {
            val t = ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
            val l = ((raw[i + 2].toInt() and 0xFF) shl 8) or (raw[i + 3].toInt() and 0xFF)
            if (t == type) return i
            i += 4 + l + ((4 - l % 4) % 4)
        }
        return null
    }
}
