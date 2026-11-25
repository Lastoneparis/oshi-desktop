package com.oshi.desktop.lora

/**
 * __DESKTOP_TOOLS_2027__ Everything the radio card shows about the ATTACHED radio, in one
 * snapshot: what the node reported about itself (`DeviceMetrics`), what our radio measured
 * on the last packet it heard, and the configuration it read back.
 *
 * Every measured value is nullable and stays null until the radio says it — the card draws
 * "—", never a 0 that would read as an empty battery or a dead channel (Android
 * `LoRaNodeStatus`, iOS `LoRaNodeProtocol.swift:107-161`).
 */
data class LoRaRadioCard(
    val nodeNum: UInt,
    val name: String?,
    /** 0–100, or 101 for "on external power" (Meshtastic's convention). */
    val batteryLevel: Int?,
    val voltage: Float?,
    /** Percent of the channel busy with anyone's traffic. */
    val channelUtilization: Float?,
    /** Percent of the last hour this radio transmitted — the legal duty-cycle budget. */
    val airUtilTx: Float?,
    val uptimeSeconds: Long?,
    val lastRssi: Int?,
    val lastSnr: Float?,
    val lastPacketMs: Long?,
    val region: LoRaAdmin.Region?,
    val modemPreset: Long?,
    val txPowerDbm: Int?,
    val muted: Boolean,
    val noRegion: Boolean,
    val nodesNow: Int,
) {
    val onExternalPower: Boolean get() = (batteryLevel ?: 0) > 100

    /** Four bars from SNR, the way the phones bucket it; null when nothing was heard. */
    val signalBars: Int?
        get() = lastSnr?.let { s ->
            when {
                s >= 5f -> 4
                s >= 0f -> 3
                s >= -7f -> 2
                s >= -15f -> 1
                else -> 0
            }
        } ?: lastRssi?.let { r ->
            when {
                r >= -90 -> 4
                r >= -105 -> 3
                r >= -115 -> 2
                r >= -125 -> 1
                else -> 0
            }
        }

    /**
     * EU 868 allows 10 % of each hour on the default sub-band, and Meshtastic stops at that
     * line; elsewhere the firmware still keeps a budget. The card warns from 80 % of it.
     */
    val airtimeLimitPercent: Float get() = if (region == LoRaAdmin.Region.EU868 || region == LoRaAdmin.Region.EU433) 10f else 25f
}
