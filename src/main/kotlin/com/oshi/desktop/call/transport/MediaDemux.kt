package com.oshi.desktop.call.transport

/**
 * What one datagram off the media socket turns out to be.
 *
 * Four protocols share one UDP port for the life of a call — sealed audio, hole-punch
 * pings, STUN binding responses and (when TURN is in play) ChannelData — and the only
 * thing separating them is the first byte plus, for STUN, a transaction id we sent.
 * [MediaDemux] is that decision, extracted from the socket so it can be tested against
 * literal bytes with no network at all.
 */
sealed interface InboundPacket {

    /** A STUN Binding Success Response answering one of OUR transactions. */
    data class StunResponse(val txIdHex: String, val mapped: StunBinding.Mapped?) : InboundPacket

    /** A `0x31` hole-punch ping that passed the call-id check and is owed a pong. */
    data class Ping(val callId: Long, val nonceHex: String, val tsMs: Long, val pong: ByteArray) :
        InboundPacket {
        override fun equals(other: Any?): Boolean =
            other is Ping && callId == other.callId && nonceHex == other.nonceHex &&
                tsMs == other.tsMs && pong.contentEquals(other.pong)

        override fun hashCode(): Int = (callId.hashCode() * 31 + nonceHex.hashCode()) * 31 + tsMs.hashCode()
    }

    /** A `0x32` pong. Its call id is NOT checked — see [HolePunch]'s PONGS IGNORE THE CALL ID. */
    data class Pong(val nonceHex: String, val tsMs: Long) : InboundPacket

    /** Anything else: a sealed media frame, handed on for [com.oshi.desktop.call.media.CallMediaFrame]. */
    data class Media(val bytes: ByteArray) : InboundPacket {
        override fun equals(other: Any?): Boolean = other is Media && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Consumed and discarded, with a reason a caller can count. */
    data class Dropped(val reason: DropReason) : InboundPacket
}

/** Why a datagram was thrown away. Every one of these is worth a counter. */
enum class DropReason {
    /** Zero-length datagram. */
    EMPTY,

    /**
     * A `0x31` ping whose call id is neither zero nor ours — iOS's
     * `DIAG_ICE_PING_DROP reason=cid_mismatch` (`P2PTransport.swift:1391-1393`).
     * PARITY.md row 2.1 names this as the failure that makes a connected call silent.
     */
    CID_MISMATCH,

    /** A `0x31`/`0x32` shorter than the 29 bytes both clients require. */
    MALFORMED_PING,

    /**
     * A STUN Binding Success Response whose transaction id we never sent. Off-path
     * injection, a reply to a previous call, or a stray from the same coturn.
     */
    UNKNOWN_STUN_TRANSACTION,

    /**
     * First byte in `0x40..0x7F`: a TURN ChannelData frame (RFC 5766 §11.4, top two
     * bits `01`). **This client does not speak TURN** — see [MediaSocket]'s TURN note —
     * so the range is recognised and dropped rather than being fed to the media codec,
     * where it would fail to authenticate and be counted as a broken audio frame.
     */
    TURN_CHANNEL_DATA_UNSUPPORTED,
}

/**
 * The pure classifier. No socket, no clock, no state except what is passed in.
 *
 * ============================================================ THE ORDER IS THE PROTOCOL
 *
 * STUN is demuxed FIRST, exactly as `P2PTransport.swift:1377` does
 * (`if handleStunOnMediaSocket(data) { return }`), and it is matched on message type +
 * magic cookie + a transaction id we sent — never on the high bits of byte 0. iOS wrote
 * the reason down (`StunClient.swift:121-124`) and it is worth repeating because getting
 * it wrong is silent: **`0x15`, the raw-PCM-48 kHz audio type this client emits fifty
 * times a second, satisfies `(first & 0xC0) == 0`.** A `& 0xC0` discriminator eats every
 * audio frame of every call and the symptom is a call that connects and says nothing.
 *
 * Then `0x31`/`0x32`, then everything else is media. Nothing in OSHI's media type set
 * (`0x05` AAC-ELD, `0x15`/`0x17` PCM, `0x16` OshiCodec, `0xF1` video) collides with
 * `0x31`, `0x32`, `0x01` or the `0x40..0x7F` ChannelData range, which is what makes a
 * first-byte switch legitimate at all.
 */
object MediaDemux {

    /** RFC 5766 §11.4: ChannelData's channel number is `0x4000..0x7FFF`. */
    const val CHANNEL_DATA_FIRST_BYTE_MIN = 0x40
    const val CHANNEL_DATA_FIRST_BYTE_MAX = 0x7F

    /**
     * Classify one datagram.
     *
     * @param localCallId this call's [HolePunch.deriveP2PCallId]. Inbound pings carrying
     *   `0` are accepted as a wildcard; anything else that differs is [DropReason.CID_MISMATCH].
     * @param isOurStunTransaction asked only for datagrams that already look like a
     *   Binding Success Response. Returning false makes the packet a
     *   [DropReason.UNKNOWN_STUN_TRANSACTION] rather than letting it through as media —
     *   a STUN response is never a media frame regardless of whose it is.
     */
    fun classify(
        data: ByteArray,
        length: Int = data.size,
        localCallId: Long,
        isOurStunTransaction: (String) -> Boolean,
    ): InboundPacket {
        if (length <= 0) return InboundPacket.Dropped(DropReason.EMPTY)
        val packet = if (length == data.size) data else data.copyOfRange(0, length)

        if (StunBinding.isBindingSuccess(packet)) {
            val tx = StunBinding.transactionId(packet)
                ?: return InboundPacket.Dropped(DropReason.UNKNOWN_STUN_TRANSACTION)
            val hex = HolePunch.nonceHex(tx)
            if (!isOurStunTransaction(hex)) {
                return InboundPacket.Dropped(DropReason.UNKNOWN_STUN_TRANSACTION)
            }
            return InboundPacket.StunResponse(hex, StunBinding.parseBindingResponse(packet, tx))
        }

        val first = packet[0].toInt() and 0xFF
        if (first in CHANNEL_DATA_FIRST_BYTE_MIN..CHANNEL_DATA_FIRST_BYTE_MAX) {
            return InboundPacket.Dropped(DropReason.TURN_CHANNEL_DATA_UNSUPPORTED)
        }

        return when (packet[0]) {
            HolePunch.TYPE_PING -> {
                val pong = HolePunch.pongFor(packet)
                    ?: return InboundPacket.Dropped(DropReason.MALFORMED_PING)
                val incoming = HolePunch.readCallId(packet)
                    ?: return InboundPacket.Dropped(DropReason.MALFORMED_PING)
                if (!HolePunch.callIdAcceptable(incoming, localCallId)) {
                    return InboundPacket.Dropped(DropReason.CID_MISMATCH)
                }
                val nonce = HolePunch.readNonce(packet)
                    ?: return InboundPacket.Dropped(DropReason.MALFORMED_PING)
                val ts = HolePunch.readTimestamp(packet)
                    ?: return InboundPacket.Dropped(DropReason.MALFORMED_PING)
                InboundPacket.Ping(incoming, HolePunch.nonceHex(nonce), ts, pong)
            }
            HolePunch.TYPE_PONG -> {
                // No explicit length check: [HolePunch.readNonce] needs 21 bytes and
                // [HolePunch.readTimestamp] needs 29, so between them every short packet
                // is already refused and there is no length only a size test would
                // catch. One stood here until a mutation run came back GREEN with it
                // broken — a guard whose neighbours make it unreachable is decoration,
                // and decoration that looks like a security check is worse than none.
                // `MediaSocketTest.every length below twenty-nine is malformed` sweeps
                // the whole boundary instead.
                val nonce = HolePunch.readNonce(packet)
                    ?: return InboundPacket.Dropped(DropReason.MALFORMED_PING)
                val ts = HolePunch.readTimestamp(packet)
                    ?: return InboundPacket.Dropped(DropReason.MALFORMED_PING)
                InboundPacket.Pong(HolePunch.nonceHex(nonce), ts)
            }
            else -> InboundPacket.Media(packet)
        }
    }
}
