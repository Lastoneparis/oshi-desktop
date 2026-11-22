package com.oshi.desktop.lora

import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_LORA_BEACON_FIXED_2026_10_28__ READ the node's `Config.position` back.
 *
 * Read-only on purpose, and that is the whole design. The open question is why neither a
 * POSITION_APP broadcast nor `AdminMessage{ set_fixed_position }` put this node on another
 * radio's map, and the standing hypothesis is that `fixed_position` must also be true in
 * `Config.position`. The tempting next move is to write that config — and writing it is how
 * this gets worse, because **protobuf omits defaults and the firmware REPLACES the whole
 * submessage**: a `PositionConfig` carrying only `fixed_position: true` arrives with
 * `position_broadcast_secs = 0`, `position_flags = 0` and `gps_mode = 0`. This repository
 * already has that scar in `LoRaAdmin.setConfigFrame` — "leaving `use_preset` out sends
 * false, which produces a node that is configured, on the air, and unable to talk to
 * anyone".
 *
 * So: look first. The radio sends its whole config in the handshake burst
 * (`FromRadio.config`, field 5; `Config.position`, field 2), which costs nothing and
 * answers two things at once —
 *
 *   1. did the admin write land? If `fixed_position` is TRUE, the frame was honoured and
 *      the hypothesis is wrong; if FALSE, the admin message never took effect and the
 *      problem is upstream of any config flag.
 *   2. what are the other fields, so a later write can set them all explicitly instead of
 *      zeroing them.
 *
 * It opens its own link rather than going through `OshiClient`, because the client folds
 * every `FromRadio` into the roster and nothing hands the raw config out.
 */
class LivePositionConfigReadBench {

    @Test
    fun readBackThePositionConfig() {
        val dev = System.getenv("OSHI_LIVE_RADIO_A")
        Assume.assumeTrue("set OSHI_LIVE_RADIO_A", !dev.isNullOrBlank())

        val frames = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        val link = LoRaLink(
            channel = LoRaChannel.Serial(dev!!),
            onFromRadio = { frames += it },
            log = { println("LINK $it") },
        )
        try {
            link.start()
            // The config burst arrives right after the handshake; 12 s is generous.
            Thread.sleep(12_000)
            println("CFG ${frames.size} FromRadio frames captured")

            var sawConfig = false
            for (f in frames.toList()) {
                // FromRadio.config is field 5, length-delimited.
                val config = LoRaProto.field(f, 5, wire = 2)?.payload ?: continue
                sawConfig = true
                // Config.position is field 2.
                val position = LoRaProto.field(config, 2, wire = 2)?.payload
                if (position == null) {
                    val which = LoRaProto.parseFields(config).joinToString { "field ${it.number}" }
                    println("CFG   a Config that is not position: $which")
                    continue
                }
                println("CFG ✅ Config.position = ${position.joinToString("") { "%02x".format(it) }}")
                for (p in LoRaProto.parseFields(position)) {
                    val name = when (p.number) {
                        1 -> "position_broadcast_secs"
                        2 -> "position_broadcast_smart_enabled"
                        3 -> "fixed_position"
                        4 -> "gps_enabled (deprecated)"
                        5 -> "gps_update_interval"
                        7 -> "position_flags"
                        10 -> "broadcast_smart_minimum_distance"
                        11 -> "broadcast_smart_minimum_interval_secs"
                        13 -> "gps_mode"
                        else -> "field ${p.number}"
                    }
                    val v = if (p.wire == 0) p.varintValue.toString() else "wire=${p.wire}"
                    println("CFG     $name = $v")
                }
            }
            // Our OWN NodeInfo, which is what the node would announce. `fixed_position`
            // being true only says the node is WILLING to announce a fixed position; if its
            // own entry carries no Position, there is nothing to send and every downstream
            // explanation (duty cycle, interval, portnum) is beside the point.
            //
            // NodeInfo.num is field 1, `position` is field 3 — NOT field 4, which is `snr`.
            // That distinction is the one that left all 45 nodes nameless in row 1.2-a.
            val myNum = frames.toList().firstNotNullOfOrNull { f ->
                LoRaProto.field(f, 3, wire = 2)?.payload?.let { LoRaProto.field(it, 1, wire = 0)?.varintValue }
            }
            println("CFG my node = ${myNum?.let { "!%08x".format(it) } ?: "unknown"}")
            var mine = 0
            for (f in frames.toList()) {
                val ni = LoRaProto.field(f, 4, wire = 2)?.payload ?: continue
                val num = LoRaProto.field(ni, 1, wire = 0)?.varintValue ?: continue
                if (myNum != null && num != myNum) continue
                mine++
                val pos = LoRaProto.field(ni, 3, wire = 2)?.payload
                if (pos == null) {
                    println("CFG ❌ our own NodeInfo carries NO position — nothing to announce")
                } else {
                    var la: Double? = null; var lo: Double? = null
                    for (pf in LoRaProto.parseFields(pos)) {
                        if (pf.wire != 5) continue
                        when (pf.number) {
                            1 -> la = pf.asInt32() * 1e-7
                            2 -> lo = pf.asInt32() * 1e-7
                        }
                    }
                    println("CFG ✅ our own NodeInfo HAS a position: lat=$la lon=$lo")
                }
            }
            if (mine == 0) println("CFG (our own NodeInfo was not in the burst)")

            // __DESKTOP_LORA_REBOOT_2026_10_28__ UPTIME, so "did the reboot actually
            // happen" stops being a guess. `NodeInfo.device_metrics` is field 6 and
            // `DeviceMetrics.uptime_seconds` is field 5. The /dev node's timestamp cannot
            // answer this — it changed on BOTH radios when the test merely opened them.
            for (f in frames.toList()) {
                val ni = LoRaProto.field(f, 4, wire = 2)?.payload ?: continue
                val num = LoRaProto.field(ni, 1, wire = 0)?.varintValue ?: continue
                if (myNum != null && num != myNum) continue
                val dm = LoRaProto.field(ni, 6, wire = 2)?.payload ?: continue
                val up = LoRaProto.field(dm, 5, wire = 0)?.varintValue ?: continue
                println("CFG our uptime = ${up}s (${up / 60}min) — a reboot shows as a small number")
                // __DESKTOP_LORA_CHANUTIL_2026_10_28__ `PositionModule::runOnce` returns
                // EARLY unless `airTime->isTxAllowedChannelUtil(...)`, which is 25% for a
                // non-tracker role. On a mesh this busy that gate, not the 6-hour floor,
                // may be what has been silencing the position all evening.
                // DeviceMetrics.channel_utilization is field 3, air_util_tx is 4; both are
                // floats (wire type 5).
                LoRaProto.field(dm, 3, wire = 5)?.let {
                    val pct = java.lang.Float.intBitsToFloat(it.asInt32())
                    println("CFG channel_utilization = %.1f%%  (PositionModule needs < 25%%)".format(pct))
                }
                LoRaProto.field(dm, 4, wire = 5)?.let {
                    val pct = java.lang.Float.intBitsToFloat(it.asInt32())
                    println("CFG air_util_tx = %.1f%%".format(pct))
                }
            }

            // __DESKTOP_LORA_REBOOT_2026_10_28__ The firmware version ON THE RADIO.
            // `FromRadio.metadata` is field 13, `DeviceMetadata.firmware_version` is 1.
            // Needed because the source being read on this Mac (`oshi-mesh-stock`, 2.8.1)
            // says a fixed position IS restored into localPosition at boot and announced
            // — which contradicts what the radio did. When the source and the device
            // disagree, the first thing to doubt is that they are the same software.
            for (f in frames.toList()) {
                val meta = LoRaProto.field(f, 13, wire = 2)?.payload ?: continue
                val ver = LoRaProto.field(meta, 1, wire = 2)?.payload?.decodeToString() ?: continue
                println("CFG radio firmware = $ver   (local source checkout = 2.8.1)")
                break
            }

            if (!sawConfig) {
                println("CFG ❌ no FromRadio.config in the burst — the read, not the radio, is what failed")
                val tally = HashMap<Int, Int>()
                for (f in frames.toList()) for (p in LoRaProto.parseFields(f)) {
                    tally[p.number] = (tally[p.number] ?: 0) + 1
                }
                println("CFG    FromRadio fields seen: " + tally.toSortedMap().entries.joinToString { "${it.key}x${it.value}" })
            }
        } finally {
            runCatching { link.stop() }
        }
    }
}
