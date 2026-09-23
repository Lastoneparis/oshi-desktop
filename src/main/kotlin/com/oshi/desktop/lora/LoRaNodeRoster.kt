package com.oshi.desktop.lora

/**
 * Which Meshtastic nodes the attached radio has heard, and when — for the "LoRa N" count in
 * the Messages header (__LORA_ONLINE_COUNT_2026_09_22__, same rule as iOS).
 *
 * Fed with every `FromRadio` off [LoRaLink]. Three variants carry what this needs:
 *  - `my_info` (field 3) → `MyNodeInfo.my_node_num` (field 1): our OWN radio, never counted;
 *  - `node_info` (field 4) → `NodeInfo.num` (1) and `last_heard` (5, fixed32 Unix seconds);
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
    }

    private val lock = Any()
    private var myNodeNum: UInt = 0u
    /** node number → last heard, Unix millis. 0 = known but never heard with a trusted clock. */
    private val lastHeardMs = HashMap<UInt, Long>()

    /** Fold one raw `FromRadio`. Anything it does not recognise is ignored. */
    fun observeFromRadio(fromRadio: ByteArray, nowMs: Long) {
        val fields = runCatching { LoRaProto.parseFields(fromRadio) }.getOrNull() ?: return
        for (f in fields) {
            when {
                f.number == 3 && f.wire == 2 ->
                    LoRaProto.field(f.payload, 1, wire = 0)?.let { synchronized(lock) { myNodeNum = it.varintValue.toUInt() } }
                f.number == 4 && f.wire == 2 -> observeNodeInfo(f.payload, nowMs)
                f.number == 2 && f.wire == 2 ->
                    LoRaProto.field(f.payload, 1, wire = 5)?.asUInt32()?.let { heard(it, nowMs) }
            }
        }
    }

    private fun observeNodeInfo(nodeInfo: ByteArray, nowMs: Long) {
        val num = LoRaProto.field(nodeInfo, 1, wire = 0)?.varintValue?.toUInt() ?: return
        if (num == 0u) return
        val reportedS = LoRaProto.field(nodeInfo, 5, wire = 5)?.asUInt32()?.toLong()
        val reportedMs = reportedS?.takeIf { it > PLAUSIBLE_EPOCH_S }?.let { minOf(nowMs, it * 1000) } ?: 0L
        synchronized(lock) {
            // Never move a node we already heard directly back in time.
            lastHeardMs[num] = maxOf(lastHeardMs[num] ?: 0L, reportedMs)
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

    /** Forget everything — on detach, a different radio may be attached next. */
    fun clear() = synchronized(lock) {
        myNodeNum = 0u
        lastHeardMs.clear()
    }
}
