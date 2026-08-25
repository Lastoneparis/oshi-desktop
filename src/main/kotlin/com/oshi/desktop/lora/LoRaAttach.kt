package com.oshi.desktop.lora

import java.io.ByteArrayOutputStream

/**
 * How a DESKTOP could reach a Meshtastic radio at all — and the honest state of it.
 *
 * Everything else in this package is a codec taken byte for byte from the shipped trees and
 * testable without hardware. This file is the one place that has to answer a question the
 * shipped trees do not answer, because neither shipped client is a desktop.
 *
 * ============================================================ WHAT THE PHONES DO: BLE,
 * AND ONLY BLE
 *
 * Both clients talk to the radio over Bluetooth Low Energy and nothing else. No USB serial,
 * no TCP. Verified by reading both transports and by grepping both LoRa trees for the
 * Meshtastic stream-framing magic (`0x94` / `0xc3`): **zero hits on either platform.**
 *
 * The GATT surface is identical on both (`OSHI/MeshtasticManager.swift:163-166`,
 * `service/lora/MeshtasticManager.kt:76-79`):
 *
 * ```
 * service    6BA1B218-15A8-461F-9FA8-5DCAE273EAFD
 * toRadio    F75C76D2-129E-4DAD-A1DD-7866124401E7   write, withResponse
 * fromRadio  2C55E69E-4993-11ED-B878-0242AC120002   read, drained in a loop until empty
 * fromNum    ED9DA18C-A800-4F66-A670-AA7547E34453   notify → triggers the drain
 * ```
 *
 * One GATT operation carries one whole `ToRadio` / `FromRadio` protobuf; there is **no
 * length framing at the BLE layer**, which is why [LoRaProto] has no stream parser.
 *
 * ============================================================ AND THE JDK HAS NO BLUETOOTH
 *
 * There is no Bluetooth API in the Java standard library — not in `java.*`, not in
 * `javax.*`, not in `jdk.*`. There is also no pure-Java BLE implementation, because BLE
 * lives behind an OS service on every platform this client targets (BlueZ/D-Bus on Linux,
 * WinRT on Windows, CoreBluetooth on macOS) and each needs its own native binding.
 *
 * So reproducing the phones' transport means **three platform-specific native dependencies**,
 * which PARITY.md working rule 4 asks to be justified in writing, and which would put the
 * desktop's LoRa support behind a per-OS binding that cannot be tested on CI without a
 * radio. That is not a decision this row should make quietly.
 *
 * ============================================================ THE WAY OUT: THE RADIO
 * SPEAKS TWO OTHER TRANSPORTS, AND ONE COSTS NOTHING
 *
 * The important observation is that **BLE is a PHONE constraint, not a protocol one.** A
 * Meshtastic node exposes the same `ToRadio` / `FromRadio` protobufs over three transports:
 * BLE, USB serial, and TCP on port 4403. The bytes [LoRaProto.toRadioPacket] produces are
 * identical on all three; only the wrapper that delimits one message from the next differs.
 *
 * | transport | JDK support | new dependency |
 * |---|---|---|
 * | BLE | none | a native binding per OS |
 * | USB serial | none (no `javax.comm` in a modern JDK) | a serial library, e.g. jSerialComm |
 * | **TCP :4403** | `java.net.Socket` | **none** |
 *
 * So the desktop's cheapest attachment is a TCP socket to a Meshtastic node — either a
 * WiFi-capable board directly, or a `meshtasticd` / phone acting as a bridge. Zero new
 * dependencies, and the radio relays the exact `"OM"` frames an iPhone would have sent.
 *
 * ============================================================ WHAT IS AND IS NOT VERIFIED
 *
 * **[StreamFraming] is the one thing in this whole package that is NOT taken from the
 * shipped OSHI source.** It cannot be: neither client implements it. It comes from
 * Meshtastic's own public stream protocol, and it is written here so the attachment is a
 * small, reviewable, testable piece rather than a gap.
 *
 * It is therefore recorded as **UNVERIFIED against a real radio**, in exactly the sense
 * PARITY.md uses the word: it is implemented and unit-tested, it has never moved a byte to
 * a device, and it must not be reported as parity until it has. Everything else in this
 * package is checked against the shipped trees; this is not, and the distinction is the
 * point of writing it down.
 *
 * No socket, no serial port and no reconnect loop is implemented here. A transport that
 * cannot be exercised on CI should not be written speculatively — the framing can be tested
 * today, a connection manager cannot, and the difference between "the codec is right" and
 * "the link works" is the difference this file exists to keep visible.
 */
object LoRaAttach {

    /** Meshtastic's TCP port. A plain `java.net.Socket` reaches it; no dependency needed. */
    const val TCP_PORT = 4403

    /** GATT service UUID (`MeshtasticManager.swift:163`, `MeshtasticManager.kt:76`). */
    const val BLE_SERVICE_UUID = "6BA1B218-15A8-461F-9FA8-5DCAE273EAFD"

    /** Write characteristic — one whole `ToRadio` per write, `withResponse`. */
    const val BLE_TO_RADIO_UUID = "F75C76D2-129E-4DAD-A1DD-7866124401E7"

    /** Read characteristic — drained in a loop until it returns empty. */
    const val BLE_FROM_RADIO_UUID = "2C55E69E-4993-11ED-B878-0242AC120002"

    /** Notify characteristic — a notification means "drain `fromRadio` now". */
    const val BLE_FROM_NUM_UUID = "ED9DA18C-A800-4F66-A670-AA7547E34453"

    /**
     * One BLE write every 2 000 ms (`MeshtasticManager.kt:102,861`, iOS
     * `MeshtasticManager.swift:822`).
     *
     * Not a BLE limitation — a LoRa one. LongFast airtime is ~1-2 s per frame, so writing
     * faster than this fills the radio's shallow TX queue (~16-32 packets) and the overflow
     * is dropped silently. A desktop attaching over TCP has the same radio underneath and
     * therefore the same pacing obligation: the constant belongs to the air, not to the
     * link, which is why it is here rather than in a BLE-specific file.
     */
    const val SEND_SPACING_MS = 2_000L

    /**
     * Meshtastic's stream framing, for the serial and TCP transports.
     *
     * ```
     * 0x94  0xC3  len_hi  len_lo  <protobuf bytes>
     * ```
     *
     * **UNVERIFIED — see the class doc.** This is the only construct in the `lora` package
     * with no line reference into `OSHI/` or `OSHI-Android/`, because neither shipped client
     * has a stream transport to take it from. It is Meshtastic's published framing, not
     * OSHI's, and it has never been exercised against a device from this codebase.
     *
     * The length is **big-endian**, which is worth stating out loud because it is the
     * opposite of every other multi-byte field in this package: the `"OM"` header's `msgId`
     * is little-endian ([LoRaFrame]) and protobuf `fixed32` is little-endian
     * ([ProtoWriter.fixed32]). Three fields, two orders, in one stack.
     */
    object StreamFraming {

        const val MAGIC_0: Byte = 0x94.toByte()
        const val MAGIC_1: Byte = 0xC3.toByte()
        const val HEADER_BYTES = 4

        /**
         * Meshtastic's maximum framed message size. A `len` above this is a desynchronised
         * stream, not a large message, and re-syncing beats trying to read it.
         */
        const val MAX_FRAME_BYTES = 512

        /** Wrap one `ToRadio` for a stream transport. */
        fun encode(protobuf: ByteArray): ByteArray {
            require(protobuf.size <= MAX_FRAME_BYTES) {
                "a stream frame is at most $MAX_FRAME_BYTES bytes, got ${protobuf.size}"
            }
            val o = ByteArrayOutputStream(HEADER_BYTES + protobuf.size)
            o.write(MAGIC_0.toInt())
            o.write(MAGIC_1.toInt())
            o.write((protobuf.size ushr 8) and 0xFF)   // BIG-endian — see the object doc
            o.write(protobuf.size and 0xFF)
            o.write(protobuf)
            return o.toByteArray()
        }

        /** One decoded frame, plus how many bytes of the buffer it consumed. */
        data class Decoded(val protobuf: ByteArray, val consumed: Int) {
            override fun equals(other: Any?): Boolean =
                other is Decoded && consumed == other.consumed &&
                    protobuf.contentEquals(other.protobuf)

            override fun hashCode(): Int = protobuf.contentHashCode() * 31 + consumed
        }

        /**
         * Read the first complete frame out of [buffer], **re-synchronising past garbage**.
         *
         * A stream transport has no message boundaries, so three things happen constantly
         * and all three are ordinary rather than exceptional:
         *
         *  - **debug text.** A Meshtastic node emits human-readable log lines on the same
         *    serial stream. Scanning forward to the next `0x94 0xC3` rather than failing is
         *    what makes the link usable at all.
         *  - **a partial frame.** Returns null; the caller keeps the buffer and appends more.
         *  - **an implausible length.** Treated as a desync: skip one byte past the magic
         *    and keep scanning, rather than waiting forever for bytes that will never come.
         *
         * @return the frame and how many leading bytes to drop, or null when no complete
         *   frame is present yet. `consumed` counts any garbage skipped before the magic, so
         *   the caller advances correctly in every case.
         */
        fun decode(buffer: ByteArray, offset: Int = 0): Decoded? {
            var i = offset
            while (i + HEADER_BYTES <= buffer.size) {
                if (buffer[i] != MAGIC_0 || buffer[i + 1] != MAGIC_1) {
                    i++
                    continue
                }
                val len = ((buffer[i + 2].toInt() and 0xFF) shl 8) or (buffer[i + 3].toInt() and 0xFF)
                // An implausible length is a DESYNC, not a big message: two garbage bytes
                // that happen to read as the magic give a length of up to 65535, and
                // waiting for it would hang the link on bytes that never come.
                if (len > MAX_FRAME_BYTES) { i++; continue }
                if (i + HEADER_BYTES + len > buffer.size) return null   // incomplete, wait
                val body = buffer.copyOfRange(i + HEADER_BYTES, i + HEADER_BYTES + len)
                return Decoded(body, (i - offset) + HEADER_BYTES + len)
            }
            return null
        }
    }
}
