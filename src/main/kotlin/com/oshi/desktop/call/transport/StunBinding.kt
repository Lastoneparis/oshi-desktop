package com.oshi.desktop.call.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * RFC 5389 STUN Binding, hand-rolled — the same twenty bytes both phones send.
 *
 * ```
 * [type u16 BE][length u16 BE][cookie 0x2112A442][transaction id 12]  then attributes:
 * [attr type u16 BE][attr length u16 BE][value, padded to 4]
 * ```
 *
 * `OSHI/StunClient.swift:148-169` builds the request and `:175-219` walks the response;
 * `OSHI-Android/.../service/p2p/StunClient.kt:79-97` and `:103-144` do the same. There is
 * no library on either side and there is none here: the whole of what OSHI needs from
 * STUN is one request with no attributes and one attribute read back out of the reply.
 *
 * ============================================================ WHAT IS DELIBERATELY ABSENT
 *
 * No USERNAME, no MESSAGE-INTEGRITY, no FINGERPRINT, no ICE-CONTROLLED/CONTROLLING, no
 * PRIORITY, no USE-CANDIDATE. A full RFC 5245 agent sends all of those on every
 * connectivity check; OSHI sends **none** of them, because OSHI's connectivity check is
 * not a STUN message at all — it is [HolePunch]'s 29-byte `0x31` ping. STUN is used here
 * for exactly one thing: asking the server what our public mapping is.
 *
 * The server is coturn on `45.67.216.197:3478`, the same instance and the same port that
 * serves TURN, answering unauthenticated Binding Requests
 * (`StunClient.swift:26-28`, `VoiceCallManager.swift:2399-2402`,
 * `StunClient.kt:27-28`). This client does not hard-code it — see [DEFAULT_SERVER] for
 * why the constant exists and why nothing in this package reaches for it on its own.
 *
 * ============================================================ THE DEMUX TRAP, WRITTEN DOWN
 *
 * The reply lands on the MEDIA socket (see [MediaSocket]), mixed in with sealed audio,
 * hole-punch pings and TURN ChannelData. iOS left the whole trap in a comment
 * (`StunClient.swift:121-124`):
 *
 *   > `⚠️ Do NOT use (firstByte & 0xC0) == 0 as the discriminator: OSHI's own packet`
 *   > `types (0x05 AAC-ELD, 0x14/0x15/0x16/0x17 audio, 0x0E/0x0F video upgrade) all`
 *   > `satisfy it. The message-type + magic-cookie pair does not collide, and callers`
 *   > `additionally match the 12-byte transaction id.`
 *
 * `0x15 & 0xC0 == 0`. That is the raw-PCM-48 kHz audio type this client emits fifty
 * times a second. The textbook STUN discriminator would therefore eat **every audio
 * frame of every call** and hand them to a STUN parser, which would reject them, which
 * would look exactly like a call that connects and stays silent. [isBindingSuccess]
 * matches the message type AND the cookie, and [MediaSocket] additionally requires the
 * transaction id to be one it sent — three independent checks for a demux that is
 * allowed to be wrong zero times.
 */
object StunBinding {

    /** RFC 5389 §6. Bytes 4..8 of every STUN message. `StunClient.swift:31`, `kt:30`. */
    const val MAGIC_COOKIE: Int = 0x2112A442

    /** `0x0001` — Binding Request. `swift:34`, `kt:31`. */
    const val TYPE_BINDING_REQUEST: Int = 0x0001

    /** `0x0101` — Binding Success Response. `swift:35`, `kt:32`. */
    const val TYPE_BINDING_SUCCESS: Int = 0x0101

    /** `0x0001` MAPPED-ADDRESS, the pre-XOR form. `swift:38`, `kt:34`. */
    const val ATTR_MAPPED_ADDRESS: Int = 0x0001

    /** `0x0020` XOR-MAPPED-ADDRESS. `swift:39`, `kt:35`. */
    const val ATTR_XOR_MAPPED_ADDRESS: Int = 0x0020

    /** 20 bytes: type, length, cookie, transaction id. `kt:38 STUN_HEADER_LEN`. */
    const val HEADER_SIZE = 20

    /** RFC 5389 §6: the transaction id is 96 bits. */
    const val TRANSACTION_ID_SIZE = 12

    /** `0x01` IPv4 / `0x02` IPv6 — the STUN family, NOT [IceCandidate]'s 4/6. */
    const val FAMILY_IPV4 = 0x01
    const val FAMILY_IPV6 = 0x02

    /** `swift:42` / `kt:37` both wait 1 500 ms for the reply. */
    const val TIMEOUT_MS = 1500

    /**
     * `45.67.216.197:3478` — the coturn both phones use
     * (`StunClient.swift:27-28`, `StunClient.kt:27-28`).
     *
     * It is a constant and NOT a default anywhere in this package: [MediaSocket] takes
     * the server as a parameter, so a desktop that is pointed at a different deployment
     * does not have to be rebuilt, and a test never has to be able to reach the
     * internet to run. The one place this address belongs is the integration the caller
     * writes, which is why it stops here.
     */
    val DEFAULT_SERVER = InetSocketAddress.createUnresolved("45.67.216.197", 3478)

    private val random = SecureRandom()

    /** 12 fresh random bytes. `swift:162-166` uses SecRandom; `kt:93-94` SecureRandom. */
    fun newTransactionId(): ByteArray = ByteArray(TRANSACTION_ID_SIZE).also { random.nextBytes(it) }

    /**
     * The 20-byte Binding Request. No attributes, so the length field is zero.
     *
     * The transaction id is a parameter rather than generated inside, for the same
     * reason [com.oshi.desktop.call.media.CallMediaFrame.encode] takes its sequence
     * number: a codec whose output depends on hidden randomness cannot be pinned to a
     * byte fixture, and this one has to be.
     */
    fun buildBindingRequest(txId: ByteArray): ByteArray {
        require(txId.size == TRANSACTION_ID_SIZE) { "transaction id must be 12 bytes" }
        val out = ByteArray(HEADER_SIZE)
        out[0] = ((TYPE_BINDING_REQUEST ushr 8) and 0xFF).toByte()
        out[1] = (TYPE_BINDING_REQUEST and 0xFF).toByte()
        out[2] = 0
        out[3] = 0
        out[4] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
        out[5] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
        out[6] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
        out[7] = (MAGIC_COOKIE and 0xFF).toByte()
        txId.copyInto(out, 8)
        return out
    }

    /**
     * True when [data] is a Binding Success Response carrying the magic cookie.
     *
     * Type AND cookie, never the high bits of byte 0 — see THE DEMUX TRAP.
     * `StunClient.swift:125-135`.
     */
    fun isBindingSuccess(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean {
        if (length < HEADER_SIZE) return false
        val msgType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        if (msgType != TYPE_BINDING_SUCCESS) return false
        val cookie = ((data[offset + 4].toInt() and 0xFF) shl 24) or
            ((data[offset + 5].toInt() and 0xFF) shl 16) or
            ((data[offset + 6].toInt() and 0xFF) shl 8) or
            (data[offset + 7].toInt() and 0xFF)
        return cookie == MAGIC_COOKIE
    }

    /** Bytes 8..20, or null when the datagram is too short. `StunClient.swift:138-142`. */
    fun transactionId(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray? {
        if (length < HEADER_SIZE) return null
        return data.copyOfRange(offset + 8, offset + 8 + TRANSACTION_ID_SIZE)
    }

    /** A public mapping as the server sees it. */
    data class Mapped(val ip: String, val port: Int)

    /**
     * Parse a Binding Success Response and return the mapped address, or null.
     *
     * Checks in the order both clients check them: length, message type, cookie,
     * transaction id, then a walk of the attributes. **The transaction id check is not
     * optional** — on the media socket it is what stops an off-path attacker who can
     * guess the five-tuple from injecting a mapped address of their choosing and
     * steering the srflx candidate at a host they control.
     *
     * XOR-MAPPED-ADDRESS is preferred and MAPPED-ADDRESS is accepted, which is what both
     * clients do (`swift:203-211`, `kt:130-137`); the legacy form exists because the
     * unXORed address gets rewritten in flight by NATs that rewrite payloads, and some
     * servers still answer with it.
     */
    fun parseBindingResponse(
        data: ByteArray,
        txId: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Mapped? {
        if (length < HEADER_SIZE) return null
        if (!isBindingSuccess(data, offset, length)) return null
        val msgLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        for (k in 0 until TRANSACTION_ID_SIZE) {
            if (data[offset + 8 + k] != txId[k]) return null
        }

        var i = HEADER_SIZE
        // `min(20 + msgLen, count)` — a length field longer than the datagram is a
        // truncated or lying message, and reading past it would read another packet's
        // bytes out of a reused receive buffer. `swift:195`, `kt:122`.
        val end = minOf(HEADER_SIZE + msgLen, length)
        while (i + 4 <= end) {
            val attrType = ((data[offset + i].toInt() and 0xFF) shl 8) or (data[offset + i + 1].toInt() and 0xFF)
            val attrLen = ((data[offset + i + 2].toInt() and 0xFF) shl 8) or (data[offset + i + 3].toInt() and 0xFF)
            val valueStart = i + 4
            if (valueStart + attrLen > end) break

            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS ->
                    parseXorMapped(data, offset + valueStart, attrLen, txId)?.let { return it }
                ATTR_MAPPED_ADDRESS ->
                    parseMapped(data, offset + valueStart, attrLen)?.let { return it }
            }

            // RFC 5389 §15: attribute values are padded to a 4-byte boundary and the
            // padding is NOT counted in the length. `swift:213-215`, `kt:139-141`.
            i = valueStart + ((attrLen + 3) and 3.inv())
        }
        return null
    }

    /**
     * XOR-MAPPED-ADDRESS, RFC 5389 §15.2.
     *
     * ```
     * [reserved u8][family u8][x-port u16][x-address 4 or 16]
     * x-port    = port    ⊕ (cookie >> 16)
     * x-addr v4 = addr    ⊕ cookie
     * x-addr v6 = addr    ⊕ (cookie ‖ transaction id)
     * ```
     *
     * IPv6 matters and is not decoration: a T-Mobile-style IPv6-only cellular peer has
     * no IPv4 srflx at all, and dropping the v6 branch means it gathers host candidates
     * that no off-LAN peer can reach and then relays every call.
     * `StunClient.swift:228-271`, `StunClient.kt:146-178`.
     */
    private fun parseXorMapped(data: ByteArray, start: Int, length: Int, txId: ByteArray): Mapped? {
        if (length < 8) return null
        if (start + length > data.size) return null
        val family = data[start + 1].toInt() and 0xFF
        val xPort = ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
        val port = xPort xor ((MAGIC_COOKIE ushr 16) and 0xFFFF)
        return when (family) {
            FAMILY_IPV4 -> {
                val b0 = (data[start + 4].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 24) and 0xFF)
                val b1 = (data[start + 5].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 16) and 0xFF)
                val b2 = (data[start + 6].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 8) and 0xFF)
                val b3 = (data[start + 7].toInt() and 0xFF) xor (MAGIC_COOKIE and 0xFF)
                Mapped("$b0.$b1.$b2.$b3", port)
            }
            FAMILY_IPV6 -> {
                if (length < 20) return null
                val mask = ByteArray(16)
                mask[0] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
                mask[1] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
                mask[2] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
                mask[3] = (MAGIC_COOKIE and 0xFF).toByte()
                txId.copyInto(mask, 4, 0, TRANSACTION_ID_SIZE)
                val raw = ByteArray(16)
                for (k in 0 until 16) raw[k] = (data[start + 4 + k].toInt() xor mask[k].toInt()).toByte()
                runCatching {
                    InetAddress.getByAddress(raw).hostAddress?.substringBefore('%')?.let { Mapped(it, port) }
                }.getOrNull()
            }
            else -> null
        }
    }

    /**
     * MAPPED-ADDRESS, RFC 5389 §15.1. IPv4 only — both clients return null for a
     * legacy IPv6 mapped address (`swift:285`, `kt:190-191`) rather than guessing, and a
     * server that only speaks the legacy attribute over IPv6 does not exist in the
     * deployment either client targets.
     */
    private fun parseMapped(data: ByteArray, start: Int, length: Int): Mapped? {
        if (length < 8) return null
        if (start + length > data.size) return null
        if ((data[start + 1].toInt() and 0xFF) != FAMILY_IPV4) return null
        val port = ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
        val b0 = data[start + 4].toInt() and 0xFF
        val b1 = data[start + 5].toInt() and 0xFF
        val b2 = data[start + 6].toInt() and 0xFF
        val b3 = data[start + 7].toInt() and 0xFF
        return Mapped("$b0.$b1.$b2.$b3", port)
    }

    /**
     * One blocking Binding exchange on a socket the caller owns.
     *
     * **The socket is the point.** The mapping a NAT hands out belongs to a
     * five-tuple, so the mapping this discovers is only the srflx candidate worth
     * advertising if it is discovered on the socket the AUDIO leaves from. iOS shipped
     * the other version first and left the post-mortem in the source
     * (`P2PTransport.swift:617-627`):
     *
     *   > `srflx used to come from StunClient.discoverReflexiveAddress, which opens a`
     *   > `BRAND-NEW NWConnection and cancels it as soon as the reply lands. The NAT`
     *   > `mapping it discovers therefore belongs to a THROWAWAY socket — but it was`
     *   > `advertised as our srflx candidate while media leaves from sockFd. The peer`
     *   > `punched at a port that was never the media port and was already closed.`
     *   > `Host candidates use the real media port, which is why direct P2P only ever`
     *   > `worked on the same LAN.`
     *
     * Android still has that bug: its `StunClient.discoverReflexiveAddress` opens its
     * own `DatagramSocket()` (`StunClient.kt:55`) and closes it in the `finally`
     * (`:71`). So this method takes the socket, and [MediaSocket.requestSrflx] is the
     * path this client actually uses — which does not block at all, because the reply
     * comes back through the normal demux.
     *
     * Restores the socket's previous `soTimeout` before returning; a socket handed in
     * mid-call must come back exactly as it was lent.
     */
    fun discoverOn(
        socket: DatagramSocket,
        server: InetSocketAddress,
        timeoutMs: Int = TIMEOUT_MS,
    ): Mapped? {
        val txId = newTransactionId()
        val request = buildBindingRequest(txId)
        val previousTimeout = runCatching { socket.soTimeout }.getOrDefault(0)
        return try {
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(request, request.size, server))
            val buf = ByteArray(RECV_BUFFER_SIZE)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                // Anything that is not OUR binding response is another packet sharing
                // this socket — keep reading rather than declaring failure.
                if (!isBindingSuccess(buf, pkt.offset, pkt.length)) continue
                val respTx = transactionId(buf, pkt.offset, pkt.length) ?: continue
                if (!respTx.contentEquals(txId)) continue
                return parseBindingResponse(buf, txId, pkt.offset, pkt.length)
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
    }

    /** `kt:62` reads into 512; `RECV_BUFFER_SIZE = 2048` is the media socket's own. */
    private const val RECV_BUFFER_SIZE = 2048
}
