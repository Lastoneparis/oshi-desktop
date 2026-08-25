package com.oshi.desktop.lora

import java.io.ByteArrayOutputStream

/**
 * The minimal protobuf codec that wraps an `"OM"` frame for the radio — and the reason
 * there is no protobuf dependency.
 *
 * Byte-for-byte port of the hand-rolled writer/parser both phones use: iOS `ProtoWriter` /
 * `ProtoField` / `parseProtoFields` (`OSHI/MeshtasticManager.swift:25-84`), Android
 * `service/lora/LoRaProto.kt`.
 *
 * **PARITY.md working rule 4** — no new dependency without a reason that survives being
 * written down — is satisfied by copying the argument the phones already made
 * (`LoRaProto.kt:9-11`): *"the Meshtastic surface OSHI touches is a handful of fields, and
 * a generated stub would be a multi-megabyte dependency plus a schema to keep in sync."*
 * Neither `OSHI-Android/app/build.gradle.kts` nor the iOS project links protobuf. Three
 * message shapes and four field numbers do not justify a code generator, and a generated
 * stub would ALSO have to be kept byte-compatible with two hand-rolled encoders, which is
 * strictly more work than one more file.
 *
 * ============================================================ THE THREE MESSAGES
 *
 * ```
 * Data       : field 1  varint   portnum
 *              field 2  bytes    payload        ← the "OM" frame
 * MeshPacket : field 2  fixed32  to             (little-endian)
 *              field 4  bytes    decoded        (= Data)
 *              field 6  fixed32  id             (random packet id)
 *              field 10 varint   want_ack = 1   ← WRITTEN ONLY WHEN TRUE
 * ToRadio    : field 1  bytes    packet         (= MeshPacket)
 * ```
 *
 * `MeshPacket.from` is **never written by OSHI** — the radio fills it in. That is exactly
 * why a node number is not an authenticator: the sender does not choose it here, but any
 * radio firmware can, and [LoRaNodeBindings] is built on that fact.
 *
 * ============================================================ PROTOBUF OMITS DEFAULTS,
 * AND THAT HAS BRICKED A RADIO
 *
 * `want_ack` is written only when true, because on the wire `want_ack: false` and "no field
 * at all" are the same bytes (`MeshtasticManager.swift:776`). Harmless here — but the same
 * rule is dangerous one message over, and the shipped comment records the incident
 * (`LoRaProto.kt:14-19`, `MeshtasticManager.swift:587-591`): a `Config.lora` write that
 * omits `use_preset` sends `false`, which switches the radio to MANUAL bandwidth and spread
 * factor of **zero** — *"a node that is configured, on the air, and unable to talk to
 * anyone"*.
 *
 * This writer emits exactly what it is told and nothing else. The omit-defaults discipline
 * lives at the call sites, as it does on both phones — and this client deliberately
 * implements **no config-write path at all** (see [LoRaAttach]), so the dangerous call site
 * does not exist here.
 *
 * ============================================================ FAILURE BEHAVIOUR IS PART
 * OF THE CONTRACT
 *
 * [parseFields] reproduces iOS's `parseProtoFields` failure modes rather than improving on
 * them, and they are deliberate (`swift:48-84`):
 *
 *  - a **truncated field ends the parse and returns what was read so far** — not an
 *    exception, not an empty list. A radio that hands over half a frame must not take the
 *    connection down with it, and the fields that DID arrive are still usable.
 *  - an **unknown wire type stops the parse dead** (`:80`). Wire types 3 and 4 are the
 *    deprecated group markers and have no length, so there is no way to skip past one; the
 *    only safe answer is to stop.
 *  - a varint whose shift exceeds 63 bits returns null and ends the parse (`:56`).
 *
 * All varints are UNSIGNED 64-bit carried in a `Long`, and every shift is logical (`ushr`)
 * so the sign bit is never propagated.
 */
class ProtoWriter {

    private val out = ByteArrayOutputStream()

    /** The bytes written so far. */
    val data: ByteArray get() = out.toByteArray()

    /** Wire type 0 (`MeshtasticManager.swift:34-36`). */
    fun varint(field: Int, value: Long): ProtoWriter {
        out.write(vint(key(field, 0)))
        out.write(vint(value))
        return this
    }

    /** Wire type 5, little-endian (`swift:37-40`). */
    fun fixed32(field: Int, value: Int): ProtoWriter {
        out.write(vint(key(field, 5)))
        out.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte(),
            )
        )
        return this
    }

    /** Wire type 5 taking an unsigned value. */
    fun fixed32(field: Int, value: UInt): ProtoWriter = fixed32(field, value.toInt())

    /** Wire type 2, length-delimited (`swift:41-43`). */
    fun bytes(field: Int, d: ByteArray): ProtoWriter {
        out.write(vint(key(field, 2)))
        out.write(vint(d.size.toLong()))
        out.write(d)
        return this
    }

    companion object {
        private fun key(field: Int, wire: Int): Long = ((field.toLong() shl 3) or wire.toLong())

        /**
         * Base-128 varint (`swift:29-33`).
         *
         * Note the `do…while`, not a `while`: **zero encodes as a single `0x00` byte rather
         * than as nothing at all.** A `while` loop here emits an empty byte string for 0,
         * which shifts every following field and corrupts the whole message — and it is the
         * kind of thing that works in every test that never encodes a zero.
         */
        fun vint(value: Long): ByteArray {
            var v = value
            val o = ByteArrayOutputStream()
            do {
                var b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) b = b or 0x80
                o.write(b)
            } while (v != 0L)
            return o.toByteArray()
        }
    }
}

/** One decoded field, whatever its wire type (`MeshtasticManager.swift:46`). */
data class ProtoField(
    val number: Int,
    val wire: Int,
    /** Wire 0 and wire 5 both land here; wire 5 is the little-endian value, zero-extended. */
    val varintValue: Long,
    /** Wire 1, 2 and 5 carry their raw bytes here. */
    val payload: ByteArray,
) {
    /** A fixed32 read as an unsigned 32-bit value — node numbers and packet ids. */
    fun asUInt32(): UInt = varintValue.toUInt()

    /** A fixed32 read as a signed 32-bit value (`latitude_i` / `longitude_i`). */
    fun asInt32(): Int = varintValue.toInt()

    /** A float carried as a fixed32 bit pattern (SNR, `swift:928,959`). */
    fun asFloat(): Float = Float.fromBits(varintValue.toInt())

    override fun equals(other: Any?): Boolean =
        other is ProtoField && number == other.number && wire == other.wire &&
            varintValue == other.varintValue && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        (((number * 31 + wire) * 31 + varintValue.hashCode()) * 31) + payload.contentHashCode()
}

/** The parser and the two encoders this client needs. */
object LoRaProto {

    /** `PortNum.PRIVATE_APP` — OSHI↔OSHI envelopes (`MeshtasticManager.swift:169`). */
    const val PORT_OSHI = 256L

    /** `PortNum.TEXT_MESSAGE_APP` — interop with the stock Meshtastic app (`:170`). */
    const val PORT_TEXT = 1L

    /** `PortNum.ROUTING_APP` — what became of a `want_ack` packet (`:558`). */
    const val PORT_ROUTING = 5L

    /** `PortNum.ADMIN_APP` — config writes. **This client never encodes one.** (`:575`) */
    const val PORT_ADMIN = 6L

    /** `0xFFFFFFFF` (`:168`). */
    val BROADCAST_ADDR: UInt = 0xFFFFFFFFu

    /**
     * A whole `ToRadio` carrying one `MeshPacket` (`MeshtasticManager.swift:766-782`).
     *
     * The field order is the shipped order and is kept even though protobuf does not
     * require it, for the same diffability reason [LoRaSecureMessage] keeps its key order:
     * a captured phone frame and a desktop frame should differ in their random ids and
     * nowhere else.
     *
     * `wantAck` is meaningful only for a UNICAST packet. Meshtastic does not acknowledge
     * broadcasts, so setting it on one raises a flag nobody will ever answer
     * (`swift:528-534`) — and OSHI broadcasts by default, so the honest default here is
     * false. [toRadioPacket] does not enforce that, because a caller sending to a specific
     * node legitimately wants it; the rule is recorded so the caller can apply it.
     */
    fun toRadioPacket(
        to: UInt,
        portnum: Long,
        payload: ByteArray,
        wantAck: Boolean,
        packetId: UInt,
    ): ByteArray {
        val data = ProtoWriter()
            .varint(1, portnum)          // Data.portnum
            .bytes(2, payload)           // Data.payload
            .data

        val pkt = ProtoWriter()
            .fixed32(2, to)              // MeshPacket.to
            .bytes(4, data)              // MeshPacket.decoded
            .fixed32(6, packetId)        // MeshPacket.id
        if (wantAck) pkt.varint(10, 1)   // MeshPacket.want_ack — ONLY when true

        return ProtoWriter().bytes(1, pkt.data).data   // ToRadio.packet
    }

    /** `ToRadio.want_config_id`, field 3 varint (`MeshtasticManager.swift:410-412`). */
    fun wantConfig(configId: UInt): ByteArray = ProtoWriter().varint(3, configId.toLong()).data

    /**
     * Parse a protobuf message into flat fields (`swift:48-84`). See the class doc for the
     * three failure modes, all of which return the fields read so far rather than throwing.
     */
    fun parseFields(d: ByteArray): List<ProtoField> {
        val out = ArrayList<ProtoField>()
        var i = 0

        fun readVarint(): Long? {
            var v = 0L
            var shift = 0
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                i += 1
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                if (shift > 63) return null
            }
            return null
        }

        while (i < d.size) {
            val key = readVarint() ?: break
            val field = (key ushr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val v = readVarint() ?: return out
                    out.add(ProtoField(field, 0, v, ByteArray(0)))
                }
                1 -> {
                    if (d.size - i < 8) return out
                    val p = d.copyOfRange(i, i + 8); i += 8
                    out.add(ProtoField(field, 1, 0L, p))
                }
                2 -> {
                    val len = readVarint() ?: return out
                    if (len < 0 || d.size - i < len) return out
                    val p = d.copyOfRange(i, i + len.toInt()); i += len.toInt()
                    out.add(ProtoField(field, 2, 0L, p))
                }
                5 -> {
                    if (d.size - i < 4) return out
                    val p = d.copyOfRange(i, i + 4); i += 4
                    val v = ((p[0].toLong() and 0xFF)) or
                        ((p[1].toLong() and 0xFF) shl 8) or
                        ((p[2].toLong() and 0xFF) shl 16) or
                        ((p[3].toLong() and 0xFF) shl 24)
                    out.add(ProtoField(field, 5, v, p))
                }
                // Wire types 3 and 4 are the deprecated group markers and carry no length,
                // so there is no way to skip one. Stopping is the only safe answer.
                else -> return out
            }
        }
        return out
    }

    /** First field with this number (and optionally wire type), or null. */
    fun field(d: ByteArray, number: Int, wire: Int? = null): ProtoField? =
        parseFields(d).firstOrNull { it.number == number && (wire == null || it.wire == wire) }

    /**
     * The inverse of [toRadioPacket] for the ONE shape this client receives: a `FromRadio`
     * carrying a `MeshPacket` whose `decoded` is a `Data`.
     *
     * Returns null unless every layer is present, because a partial packet is not a packet.
     * `from` is field 1 fixed32 on `MeshPacket` — filled in by the radio, never by the
     * sender, and therefore **not an authenticator**: see [LoRaNodeBindings].
     */
    data class Incoming(val from: UInt, val portnum: Long, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Incoming && from == other.from && portnum == other.portnum &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (from.hashCode() * 31 + portnum.hashCode()) * 31 + payload.contentHashCode()
    }

    /**
     * Pull `(from, portnum, payload)` out of a `MeshPacket`'s bytes.
     *
     * Takes the MeshPacket, not the FromRadio, so the caller decides which `FromRadio`
     * variant it is looking at — a `FromRadio` also carries `my_info`, `node_info` and
     * `config`, and a parser that guessed would silently treat a config frame as a packet.
     */
    fun parseMeshPacket(meshPacket: ByteArray): Incoming? {
        val from = field(meshPacket, 1, wire = 5)?.asUInt32() ?: return null
        val decoded = field(meshPacket, 4, wire = 2)?.payload ?: return null
        val portnum = field(decoded, 1, wire = 0)?.varintValue ?: return null
        val payload = field(decoded, 2, wire = 2)?.payload ?: return null
        return Incoming(from, portnum, payload)
    }
}
