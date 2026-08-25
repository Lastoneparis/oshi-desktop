package com.oshi.desktop.lora

import java.io.ByteArrayOutputStream

/**
 * The `"OM"` frame — the eight bytes an iPhone and an Android phone agree on over LoRa.
 *
 * OSHI rides **Meshtastic PRIVATE_APP, portnum 256** (`OSHI/MeshtasticManager.swift:521-525`,
 * `MeshtasticManager.kt`), and inside that port it defines its own fragmentation header
 * because a Meshtastic packet cannot carry a whole message. This file is that header and
 * nothing else: no radio, no BLE, no serial, no coroutines. That is deliberate — the
 * framing is the only part two platforms MUST agree on byte for byte, and a framing that
 * cannot be tested without hardware is a framing that quietly stops agreeing.
 *
 * ============================================================ THE LAYOUT
 *
 * ```
 * offset  size  field
 *   0      2    magic  0x4F 0x4D  ("OM")
 *   2      4    msgId  uint32, LITTLE-ENDIAN
 *   6      1    seq    uint8, 0-based
 *   7      1    total  uint8, number of frames in this message
 *   8      N    chunk  1..180 bytes of the payload
 * ```
 *
 * Transcribed from `MeshtasticManager.swift:520-525`, which builds it explicitly:
 * `Data([0x4F, 0x4D])`, then `msgId.littleEndian` appended through `withUnsafeBytes`, then
 * `UInt8(i)`, then `UInt8(chunks.count)`, then the chunk. Android's port states the same
 * layout with the same line references (`service/lora/LoRaEnvelope.kt:12-22`).
 *
 * **Little-endian is load-bearing and is the one field a reimplementation gets wrong.**
 * Swift's `.littleEndian` on a `UInt32` written through raw bytes is LE on every Apple
 * platform; a JVM reading it with `ByteBuffer`'s default order would read BIG-endian and
 * get a completely different message id — which does not crash, it just means every frame
 * of one message lands in a different reassembly bucket and nothing ever completes. There
 * is no error for that anywhere in either client.
 *
 * ============================================================ THE MTU STORY
 *
 * This is the question the row is really about. A Meshtastic packet has ~237 usable payload
 * bytes; OSHI's answer is **chunk, with a hard refusal above a cap**, and the numbers are
 * asymmetric between sending and receiving:
 *
 *  - **[CHUNK_BYTES] = 180** payload bytes per frame, so a frame is at most 188 bytes on
 *    the wire — comfortably inside 237 rather than at its edge
 *    (`MeshtasticManager.swift:511-513`, `:171`).
 *  - **[MAX_CHUNKS] = 12 on SEND.** More than twelve frames is REFUSED, not truncated and
 *    not split further: `guard chunks.count <= 12 else { refuse(.tooLarge(bytes:)); return
 *    false }` (`swift:514-519`). So the outbound budget is **2 160 bytes of JSON**. iOS's
 *    own comment gives the reason and it is a physical one, not a protocol one: *"radio TX
 *    queue is shallow (~16-32) and LongFast airtime ~1-2 s/frame — larger sends overflow
 *    the queue and die in reassembly"*.
 *  - **[MAX_TOTAL] = 40 on RECEIVE** (`swift:1068`). The receiver is deliberately more
 *    permissive than the sender: it has to cope with whatever a peer or a future build put
 *    on the air. A `total` above 40 is treated as a corrupt frame, not a big message.
 *
 * The refusal is a first-class outcome and must reach the user. [frames] returns null for
 * it rather than throwing or silently truncating, because iOS records it as a named
 * refusal (`.tooLarge`) that the LoRa UI displays — and "my message won't send off-grid"
 * with no explanation is the failure this design is avoiding.
 *
 * **Media never travels on LoRa at all.** `queueOrSendSecureMessage` refuses before any of
 * this runs when the message has a `mediaAttachment` or `originalMediaData`
 * (`swift:449-455`), with its own comment explaining that the check lives there so every
 * refusal is recorded in one place. See [LoRaSecureMessage.carriesMedia].
 *
 * ============================================================ WHAT THE RECEIVER REFUSES
 *
 * [parse] reproduces `handleOshiChunk`'s four guards (`swift:1062-1068`), and the first of
 * them is the one an independent implementation gets wrong:
 *
 *  1. `payload.count > 8` — **strictly greater**. A frame with a valid header and an EMPTY
 *     chunk is rejected. Accepting it would insert a zero-length part that satisfies the
 *     completeness count while contributing nothing, so the message would reassemble short
 *     and then fail JSON parsing with no indication why.
 *  2. the magic must be `0x4F 0x4D`.
 *  3. `total > 0` and `seq < total` — a frame that indexes past its own message.
 *  4. `total <= 40`.
 */
object LoRaFrame {

    /** `0x4F` — `'O'` (`MeshtasticManager.swift:521`). */
    const val MAGIC_0: Byte = 0x4F

    /** `0x4D` — `'M'`. */
    const val MAGIC_1: Byte = 0x4D

    /** magic(2) + msgId(4) + seq(1) + total(1). */
    const val HEADER_BYTES = 8

    /**
     * Payload bytes per frame (`MeshtasticManager.swift:171`). With the 8-byte header a
     * frame is 188 bytes, inside Meshtastic's ~237-byte usable payload with margin rather
     * than at its edge.
     */
    const val CHUNK_BYTES = 180

    /** Frames per message on SEND. Above this the message is refused (`swift:514-519`). */
    const val MAX_CHUNKS = 12

    /** The outbound JSON budget that [MAX_CHUNKS] implies: 12 × 180 = 2 160 bytes. */
    const val MAX_SEND_BYTES = MAX_CHUNKS * CHUNK_BYTES

    /** Frames per message a RECEIVER will entertain (`swift:1068`). Deliberately > [MAX_CHUNKS]. */
    const val MAX_TOTAL = 40

    /** Partial messages older than this are dropped (`swift:1073-1074`). */
    const val REASSEMBLY_TTL_MS = 180_000L

    /** Meshtastic `PortNum.PRIVATE_APP`. OSHI's frames ride this port and no other. */
    const val OSHI_PORTNUM = 256

    /** Meshtastic's broadcast node number, `0xFFFFFFFF`. See [LoRaNodeNum]. */
    const val BROADCAST_NODE: UInt = 0xFFFFFFFFu

    /**
     * Split at exactly [CHUNK_BYTES] (`swift:511-513`).
     *
     * An EMPTY input yields an empty list, not one empty chunk. That is what Swift's
     * `stride(from: 0, to: 0, by: 180)` produces, and it matters: a single empty frame
     * would be rejected by every receiver's `> 8` guard, so emitting one would put a frame
     * on the air that cannot be read.
     */
    fun chunk(payload: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>((payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        var i = 0
        while (i < payload.size) {
            val end = minOf(i + CHUNK_BYTES, payload.size)
            out.add(payload.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /**
     * One frame (`swift:520-525`).
     *
     * [seq] and [total] are written as single bytes with no range check beyond masking,
     * matching `UInt8(i)` / `UInt8(chunks.count)`. Callers reach this through [frames],
     * which enforces the real bounds; it is public so a test can build a frame the sender
     * would never produce and watch [parse] refuse it.
     */
    fun frame(msgId: UInt, seq: Int, total: Int, chunk: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(HEADER_BYTES + chunk.size)
        o.write(MAGIC_0.toInt())
        o.write(MAGIC_1.toInt())
        val id = msgId.toInt()
        o.write(id and 0xFF)                  // LITTLE-endian — see the class doc
        o.write((id ushr 8) and 0xFF)
        o.write((id ushr 16) and 0xFF)
        o.write((id ushr 24) and 0xFF)
        o.write(seq and 0xFF)
        o.write(total and 0xFF)
        o.write(chunk)
        return o.toByteArray()
    }

    /**
     * Every frame for one message, or **null when it is refused for being too large**.
     *
     * Null is a real outcome the caller must surface — iOS turns it into `.tooLarge(bytes:)`
     * and shows it. Truncating instead would put a prefix of a JSON object on the air that
     * the far side would reassemble "successfully" and then fail to parse, which is the same
     * lost message with a worse diagnosis.
     *
     * An empty [payload] also returns null: there is nothing to frame, and a caller asking
     * to send zero bytes is a bug in the caller, not a zero-frame message.
     */
    fun frames(msgId: UInt, payload: ByteArray): List<ByteArray>? {
        if (payload.isEmpty()) return null
        val chunks = chunk(payload)
        if (chunks.size > MAX_CHUNKS) return null
        return chunks.mapIndexed { i, c -> frame(msgId, i, chunks.size, c) }
    }

    /** A parsed frame header plus its chunk. */
    data class Header(val msgId: UInt, val seq: Int, val total: Int, val chunk: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Header && msgId == other.msgId && seq == other.seq &&
                total == other.total && chunk.contentEquals(other.chunk)

        override fun hashCode(): Int =
            (((msgId.hashCode() * 31 + seq) * 31 + total) * 31) + chunk.contentHashCode()
    }

    /**
     * Validate and split a received frame, or null when it is not ours or is malformed
     * (`swift:1062-1068`). See the class doc for the four guards and why the first is `>`
     * and not `>=`.
     */
    fun parse(payload: ByteArray): Header? {
        if (payload.size <= HEADER_BYTES) return null
        if (payload[0] != MAGIC_0 || payload[1] != MAGIC_1) return null
        val msgId = ((payload[2].toLong() and 0xFF) or
            ((payload[3].toLong() and 0xFF) shl 8) or
            ((payload[4].toLong() and 0xFF) shl 16) or
            ((payload[5].toLong() and 0xFF) shl 24)).toUInt()
        val seq = payload[6].toInt() and 0xFF
        val total = payload[7].toInt() and 0xFF
        if (total <= 0 || seq >= total || total > MAX_TOTAL) return null
        return Header(msgId, seq, total, payload.copyOfRange(HEADER_BYTES, payload.size))
    }
}

/**
 * Reassembles `"OM"` frames into whole payloads — `handleOshiChunk`
 * (`OSHI/MeshtasticManager.swift:1062-1080`).
 *
 * Four details decide whether two platforms agree, and all four are reproduced rather than
 * improved:
 *
 *  - **`total` comes from the FIRST frame seen** for a message id (`:1070`). A later frame
 *    claiming a different total does not re-open or resize the entry. That is what makes a
 *    hostile or corrupt `total` unable to hold a buffer open.
 *  - **A duplicate `seq` OVERWRITES and does not advance the count** (`:1071`) — the map is
 *    keyed by `seq`. LoRa retransmits, so duplicates are the normal case, not an attack;
 *    counting them would complete a message that is still missing a part.
 *  - **The stale sweep runs AFTER the insert** (`:1073-1074`). The shipped comment presents
 *    this as what lets a fresh entry survive its own arrival — but that claim does not
 *    survive being checked: a fresh entry's age is `nowMs - nowMs = 0`, and the eviction
 *    test is `age >= ttlMs`, so with any POSITIVE TTL the ordering is unobservable. It was
 *    mutated (sweep moved before the insert) and **every test still passed**, which under
 *    PARITY.md working rule 3 means it is not a guard. It is kept in the shipped order
 *    anyway — matching two implementations costs nothing — but it is recorded here as
 *    defensive, not load-bearing, so nobody later mistakes it for a check that is holding
 *    something up. The ordering would only matter for `ttlMs == 0`, which no caller sets.
 *  - **A complete set is removed before its parts are concatenated** (`:1077-1079`), so a
 *    late duplicate of the last frame cannot deliver the same message twice.
 *
 * **Not thread-safe**, and deliberately not: iOS confines this to the main queue and
 * Android to one coroutine scope. A lock here would hide from the caller that the radio
 * callback and the send path must not both touch it.
 *
 * The clock is a PARAMETER, never `System.currentTimeMillis()` read inside. A reassembler
 * whose TTL cannot be driven from a test is a TTL nobody has watched expire.
 */
class LoRaReassembler(private val ttlMs: Long = LoRaFrame.REASSEMBLY_TTL_MS) {

    private class Entry(val total: Int, val startedAtMs: Long) {
        val parts = HashMap<Int, ByteArray>()
    }

    private val pending = HashMap<UInt, Entry>()

    /** Partial messages currently held — for tests and for a diagnostics line. */
    val pendingCount: Int get() = pending.size

    /**
     * @return the fully reassembled payload, or null while parts are still missing or the
     *   frame was rejected. Rejection and incompleteness are the same answer here on
     *   purpose: both mean "nothing to hand upward yet", and neither is worth an exception
     *   on a transport where a corrupt frame is an ordinary event.
     */
    fun accept(framePayload: ByteArray, nowMs: Long): ByteArray? {
        val h = LoRaFrame.parse(framePayload) ?: return null

        val entry = pending.getOrPut(h.msgId) { Entry(h.total, nowMs) }
        entry.parts[h.seq] = h.chunk

        // AFTER the insert — see the class doc.
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.startedAtMs >= ttlMs) it.remove()
        }

        if (entry.parts.size != entry.total) return null
        pending.remove(h.msgId)

        val out = ByteArrayOutputStream()
        for (i in 0 until entry.total) {
            // A hole is not a message. Reachable when a frame arrived with a `seq` inside
            // the FIRST frame's `total` but the entry was sized by a different total —
            // i.e. a peer that changed its mind mid-message.
            val part = entry.parts[i] ?: return null
            out.write(part)
        }
        return out.toByteArray()
    }

    fun clear() = pending.clear()
}
