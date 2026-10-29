package com.oshi.desktop.lora

/**
 * __DESKTOP_LORA_TRAFFIC_2026_10_29__ What the radio just sent and received — iOS's
 * `MeshtasticManager.traffic`, which this client did not have.
 *
 * iOS shows a rolling list of radio events under "Live traffic" and this pane showed
 * nothing: a desktop owner could see WHICH nodes had been heard, never WHAT crossed. The
 * cost of that absence is not hypothetical — a whole evening went into asking why a
 * position never reached a second radio, and the one instrument that would have answered
 * it in a minute ("did a portnum-3 frame leave at all?") existed on the phones and not
 * here.
 *
 * ## It records SHAPES, never contents
 *
 * An entry carries a direction, a portnum, a byte count and a node id. It never carries
 * payload bytes, and that is a rule rather than an oversight:
 *
 *  - an OSHI envelope is ciphertext whose whole point is that this process is the only
 *    place it is readable, and copying it into a UI buffer that a screenshot or a support
 *    bundle can pick up undoes that for no diagnostic gain;
 *  - stock Meshtastic text IS plaintext on a public channel, so logging it leaks nothing
 *    to the mesh — but it would put a stranger's message into this machine's memory and
 *    onto its screen, which is not the same thing as it having passed through the air.
 *
 * iOS logs a 24-character prefix of interop text. That is not copied. A byte count answers
 * every question this log exists for.
 *
 * ## Bounded, and bounded for a reason
 *
 * A radio on a busy mesh delivers tens of frames a minute — the Swiss mesh this was built
 * against had 58 nodes and the receiver saw 39 positioned ones. An unbounded list is a slow
 * leak in a process that is expected to run for days. [CAPACITY] entries is what a person
 * can actually scroll.
 */
class LoRaTrafficLog(private val capacity: Int = CAPACITY) {

    /** One frame, as a shape. */
    data class Entry(
        /** `"→"` out of this machine, `"←"` into it. The same two glyphs iOS uses. */
        val direction: String,
        /** Meshtastic portnum, or null when the frame is not a mesh packet. */
        val portnum: Long?,
        /** Payload bytes. Never the payload. */
        val bytes: Int,
        /** `!a1b2c3d4` for the other end, or null when the frame does not name one. */
        val node: String?,
        val atMs: Long,
    ) {
        /** One line, as the pane shows it. */
        val summary: String
            get() = buildString {
                append(direction).append(' ')
                append(portName(portnum))
                append(" · ").append(bytes).append(" B")
                node?.let { append(" · ").append(it) }
            }
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** Newest first — a log nobody scrolls is read from the top. */
    fun recent(limit: Int = capacity): List<Entry> =
        synchronized(lock) { entries.toList().asReversed().take(limit) }

    fun clear() = synchronized(lock) { entries.clear() }

    fun record(direction: String, portnum: Long?, bytes: Int, node: String? = null, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            entries.addLast(Entry(direction, portnum, bytes, node, atMs))
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /**
     * Fold one raw `ToRadio` or `FromRadio` into an entry, reading only its shape.
     *
     * Returns false when the frame is not a mesh packet — a config burst, a `my_info`, a
     * `node_info`. Those are not traffic in the sense this log means, and recording them
     * would bury the handful of frames a person is looking for under the handshake.
     */
    fun observe(direction: String, frame: ByteArray, atMs: Long = System.currentTimeMillis()): Boolean {
        val fields = runCatching { LoRaProto.parseFields(frame) }.getOrNull() ?: return false
        // ToRadio.packet is field 1; FromRadio.packet is field 2.
        val packet = fields.firstOrNull { it.number == (if (direction == "→") 1 else 2) && it.wire == 2 }
            ?.payload ?: return false
        val decoded = LoRaProto.field(packet, 4, wire = 2)?.payload
        val portnum = decoded?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
        val payloadSize = decoded?.let { LoRaProto.field(it, 2, wire = 2)?.payload?.size } ?: 0
        // `to` (2) on the way out, `from` (1) on the way in — the other end, either way.
        val who = LoRaProto.field(packet, if (direction == "→") 2 else 1, wire = 5)?.asUInt32()
        val node = who?.takeIf { it != 0u }?.let {
            if (it == LoRaProto.BROADCAST_ADDR) "ALL" else "!%08x".format(it.toLong())
        }
        record(direction, portnum, payloadSize, node, atMs)
        return true
    }

    companion object {
        /** Entries kept. A busy mesh fills this in a couple of minutes. */
        const val CAPACITY = 120

        /**
         * Meshtastic's portnums, by the names their own `portnums.proto` uses, so a line
         * here can be matched against a capture from any other client.
         */
        fun portName(portnum: Long?): String = when (portnum) {
            null -> "—"
            1L -> "TEXT"
            3L -> "POSITION"
            4L -> "NODEINFO"
            6L -> "ADMIN"
            67L -> "TELEMETRY"
            70L -> "TRACEROUTE"
            LoRaProto.PORT_OSHI -> "OSHI"
            else -> "port $portnum"
        }
    }
}
