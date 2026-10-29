package com.oshi.desktop.lora

/**
 * Which Meshtastic nodes the attached radio has heard, and when — for the "LoRa N" count in
 * the Messages header (__LORA_ONLINE_COUNT_2026_09_22__, same rule as iOS).
 *
 * Fed with every `FromRadio` off [LoRaLink]. Three variants carry what this needs:
 *  - `my_info` (field 3) → `MyNodeInfo.my_node_num` (field 1): our OWN radio, never counted;
 *  - `node_info` (field 4) → `NodeInfo.num` (1), `user` (2), `position` (3) and
 *    `last_heard` (5): a plain `uint32`, so a VARINT on the wire. This line said fixed32
 *    until 2026-10-29, which is the misreading __LORA_NODEINFO_FIELDS_2026_10_28__ below
 *    removed from the code — the doc outlived the bug it described and would have taught
 *    the next reader to reintroduce it. A fixed32 is still accepted on input;
 *  - `packet` (field 2) → `MeshPacket.from` (1, fixed32): heard just now.
 *
 * THE NODE DB IS DUMPED IN FULL AT EVERY ATTACH. Stamping "now" on each `node_info` would make
 * every node the radio ever stored look online — a node from last month counted beside one
 * heard a minute ago. That was the iOS defect this mirrors; the radio's own `last_heard` is used
 * instead, and a radio that never got the time (0 or a 1970-ish value) is not trusted: such a
 * node is recorded as heard NEVER until a packet from it actually arrives.
 *
 * "Online" is Meshtastic's own definition: heard within the last two hours.
 *
 * Not thread-safe by itself; [OshiClient] feeds it from the link thread and reads it from the
 * UI, so every entry point is synchronized.
 */
class LoRaNodeRoster {

    companion object {
        const val ONLINE_WINDOW_MS: Long = 2L * 60 * 60 * 1000

        /** Below this a radio clock is not set (2020-09-13): the value is ignored. */
        private const val PLAUSIBLE_EPOCH_S: Long = 1_600_000_000

        /** Mean Earth radius, km — the figure the haversine formula is quoted with. */
        private const val EARTH_RADIUS_KM = 6371.0

        /**
         * Great-circle distance. Haversine rather than the equirectangular shortcut: the
         * shortcut is wrong by a useful amount near the poles, and "useful" is the whole
         * point of showing a number instead of an SNR band.
         */
        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }

    private val lock = Any()
    private var myNodeNum: UInt = 0u
    /** node number → last heard, Unix millis. 0 = known but never heard with a trusted clock. */
    private val lastHeardMs = HashMap<UInt, Long>()
    /** node number → the name the radio announced. Absent for a node that only ever sent packets. */
    private val names = HashMap<UInt, String>()
    /** node number → where it last said it was. */
    private val positions = HashMap<UInt, Pair<Double, Double>>()

    /**
     * One node the radio has heard — what the header's peers list shows for it.
     *
     * [name] is empty when the radio never announced one: a node whose `NodeInfo` we have not
     * seen is still heard and still counted, and the UI renders `lora.node.unnamed` for it
     * rather than dressing a hex id up as a name.
     */
    data class Node(
        val num: UInt,
        val name: String,
        val lastHeardMs: Long,
        /** Where it last said it was, or null. See [observePosition]. */
        val latitude: Double? = null,
        val longitude: Double? = null,
        /**
         * Kilometres from OUR radio, or null when either end has no fix.
         *
         * Null is the honest answer and the common one: a radio indoors has no GPS lock,
         * and a distance to a node that simply never said where it is would be a made-up
         * number. iOS says the same thing in the same words
         * (`MeshtasticSettingsView.swift:296-298`) and falls back to an SNR-estimated band
         * with a `~`, which is a BAND — the same signal means very different distances over
         * water and through a town.
         */
        val distanceKm: Double? = null,
    )

    /** Fold one raw `FromRadio`. Anything it does not recognise is ignored. */
    fun observeFromRadio(fromRadio: ByteArray, nowMs: Long) {
        val fields = runCatching { LoRaProto.parseFields(fromRadio) }.getOrNull() ?: return
        for (f in fields) {
            when {
                f.number == 3 && f.wire == 2 ->
                    LoRaProto.field(f.payload, 1, wire = 0)?.let { synchronized(lock) { myNodeNum = it.varintValue.toUInt() } }
                f.number == 4 && f.wire == 2 -> observeNodeInfo(f.payload, nowMs)
                f.number == 2 && f.wire == 2 -> {
                    LoRaProto.field(f.payload, 1, wire = 5)?.asUInt32()?.let { heard(it, nowMs) }
                    observePacketForPosition(f.payload)
                }
            }
        }
    }

    private fun observeNodeInfo(nodeInfo: ByteArray, nowMs: Long) {
        val num = LoRaProto.field(nodeInfo, 1, wire = 0)?.varintValue?.toUInt() ?: return
        if (num == 0u) return
        // __LORA_NODEINFO_FIELDS_2026_10_28__ `last_heard` is field 5 and it is a plain
        // `uint32`, so on the wire it is a VARINT. This asked for wire 5 (fixed32) only,
        // found nothing, and recorded every node as heard-never — which is why the roster's
        // documented behaviour ("the radio's own last_heard") had never once executed. Both
        // encodings are accepted: a fixed32 costs nothing to allow and some firmwares emit
        // one. MEASURED on a ThinkNode M1: 67 NodeInfo frames in 25 s, none carrying a
        // field 5 that wire 5 could see.
        val reportedS = (LoRaProto.field(nodeInfo, 5, wire = 0)?.varintValue
            ?: LoRaProto.field(nodeInfo, 5, wire = 5)?.asUInt32()?.toLong())
        val reportedMs = reportedS?.takeIf { it > PLAUSIBLE_EPOCH_S }?.let { minOf(nowMs, it * 1000) } ?: 0L
        // `NodeInfo.user` is FIELD 2 — a `User`: id (1), long_name (2), short_name (3). The
        // long name is what every other client labels the node with; the short name is the
        // four bytes its own screen shows, and is the fallback rather than a second line.
        //
        // __LORA_NODEINFO_FIELDS_2026_10_28__ This read field 4, which in `NodeInfo` is
        // `snr`, a float — a fixed32, not a submessage. So the lookup for a wire-2 field 4
        // never matched and EVERY node came back unnamed, on every radio, forever. The
        // header drew `lora.node.unnamed` for all of them and looked like a radio that had
        // simply not learned any names yet. Caught by pointing it at a real device (PARITY
        // row 1.2-a's own "NOT verified: a real radio") and seeing 45 nameless nodes;
        // confirmed on the wire, where field 2 is 46-84 bytes of User and field 4 is w5.
        val announced = LoRaProto.field(nodeInfo, 2, wire = 2)?.let { user ->
            val long = LoRaProto.field(user.payload, 2, wire = 2)?.payload?.decodeToString()?.trim().orEmpty()
            val shortName = LoRaProto.field(user.payload, 3, wire = 2)?.payload?.decodeToString()?.trim().orEmpty()
            long.ifEmpty { shortName }
        }?.takeIf { it.isNotEmpty() }
        // __LORA_NODEINFO_POSITION_2026_10_28__ `NodeInfo.position` is FIELD 3, and this
        // never read it — so a node that announced where it is inside its NodeInfo was
        // recorded as heard, named, and placeless.
        //
        // Found while asking why a radio given a fixed position never appeared on another
        // radio's map. That question is not settled by this (the sender has its own
        // problem: a client POSITION_APP broadcast reaches nobody, measured), but this half
        // is a defect on its own terms and in the same family as the bug six lines above —
        // a field number nobody checked against a real packet. The roster's positions came
        // only from portnum-3 traffic, which is why 36 nodes had one and the fixed node did
        // not.
        //
        // Same `observePosition` as the packet path, so the 0/0 and plausibility guards are
        // applied once, in one place, and a NodeInfo cannot write something a packet could
        // not.
        LoRaProto.field(nodeInfo, 3, wire = 2)?.payload?.let { observePosition(num, it) }

        synchronized(lock) {
            // Never move a node we already heard directly back in time.
            lastHeardMs[num] = maxOf(lastHeardMs[num] ?: 0L, reportedMs)
            // A name is only ever REPLACED by another name: a later NodeInfo with the user field
            // absent must not blank the label of a node already in the list.
            if (announced != null) names[num] = announced
        }
    }

    private fun heard(num: UInt, nowMs: Long) {
        if (num == 0u) return
        synchronized(lock) { lastHeardMs[num] = maxOf(lastHeardMs[num] ?: 0L, nowMs) }
    }

    /** Other nodes heard within [ONLINE_WINDOW_MS] of [nowMs]; our own radio excluded. */
    fun onlineCount(nowMs: Long): Int = synchronized(lock) {
        lastHeardMs.count { (num, heard) ->
            num != myNodeNum && heard > 0 && nowMs - heard < ONLINE_WINDOW_MS
        }
    }

    /**
     * Exactly the nodes [onlineCount] counts, named, most recently heard first — the list
     * behind the header badge (iOS `PeersView`, the "LoRa radios" section).
     *
     * Deliberately derived from the SAME predicate as the count: a list and a count that
     * disagree is the defect this mirrors on the phones, where a node aged out of one and not
     * the other.
     */
    fun onlineNodes(nowMs: Long): List<Node> = synchronized(lock) {
        lastHeardMs.entries
            .filter { (num, heard) -> num != myNodeNum && heard > 0 && nowMs - heard < ONLINE_WINDOW_MS }
            .map { (num, heard) ->
                val pos = positions[num]
                Node(
                    num = num,
                    name = names[num].orEmpty(),
                    lastHeardMs = heard,
                    latitude = pos?.first,
                    longitude = pos?.second,
                    distanceKm = distanceFromUs(pos),
                )
            }
            .sortedByDescending { it.lastHeardMs }
    }

    /**
     * OUR OWN radio's node number, or 0 before `my_info` has arrived.
     *
     * An admin write is addressed to this and never to a peer: it configures the node on
     * the other end of the cable. Zero means the handshake has not finished, and a write
     * addressed to node 0 would go nowhere — so callers refuse rather than send it.
     */
    fun myNodeNumOrZero(): UInt = synchronized(lock) { myNodeNum }

    /** Forget everything — on detach, a different radio may be attached next. */
    fun clear() = synchronized(lock) {
        myNodeNum = 0u
        lastHeardMs.clear()
        names.clear()
        positions.clear()
    }

    /**
     * __DESKTOP_LORA_POSITION_2026_10_28__ A node just said where it is — POSITION_APP,
     * portnum 3.
     *
     * Port of iOS `MeshtasticManager.handlePosition` (`swift:1422`), which notes that this
     * portnum *"had no case in the receive switch"* there either, so a position only ever
     * arrived in the node database at connect time. On the air it is the most common packet
     * there is: a single capture on this mesh held 135 of them.
     *
     * `from` is the ORIGINAL sender in mesh.proto and survives relaying, so this is the node
     * that owns the GPS, not the last hop that forwarded it.
     */
    private fun observePacketForPosition(packet: ByteArray) {
        val from = LoRaProto.field(packet, 1, wire = 5)?.asUInt32() ?: return
        if (from == 0u) return
        val decoded = LoRaProto.field(packet, 4, wire = 2)?.payload ?: return
        val portnum = LoRaProto.field(decoded, 1, wire = 0)?.varintValue ?: return
        if (portnum != LoRaProto.PORT_POSITION) return
        val payload = LoRaProto.field(decoded, 2, wire = 2)?.payload ?: return
        observePosition(from, payload)
    }

    /**
     * `Position.latitude_i` / `longitude_i` — sfixed32, scaled by 1e-7.
     *
     * **A 0/0 position is DISCARDED, not stored.** That is what a node with no fix reports,
     * and writing it would move a node that HAS a fix into the Gulf of Guinea — iOS's words
     * and iOS's bug. The plausibility bounds are the second half of the same guard: a
     * corrupt frame must not put a node off the planet.
     */
    internal fun observePosition(from: UInt, payload: ByteArray) {
        val fields = runCatching { LoRaProto.parseFields(payload) }.getOrNull() ?: return
        var lat: Double? = null
        var lon: Double? = null
        for (f in fields) {
            if (f.wire != 5) continue
            val raw = f.asUInt32()?.toInt() ?: continue
            when (f.number) {
                1 -> lat = raw * 1e-7
                2 -> lon = raw * 1e-7
            }
        }
        val la = lat ?: return
        val lo = lon ?: return
        if (la == 0.0 && lo == 0.0) return
        if (la < -90 || la > 90 || lo < -180 || lo > 180) return
        synchronized(lock) { positions[from] = la to lo }
    }

    /** Our own radio's position, when it has a fix. */
    private fun ourPosition(): Pair<Double, Double>? = positions[myNodeNum]

    private fun distanceFromUs(there: Pair<Double, Double>?): Double? {
        val here = ourPosition() ?: return null
        val t = there ?: return null
        return haversineKm(here.first, here.second, t.first, t.second)
    }
}
