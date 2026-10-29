package com.oshi.desktop.lora

/**
 * __DESKTOP_LORA_RADIO_STATE_2026_10_29__ What the node says about ITSELF — its region and
 * whether it will transmit at all.
 *
 * iOS reads both out of the config burst and this client read neither, so the pane could
 * say "attached" about a radio that was never going to put a byte on the air. iOS's own
 * words for why it bothers: *"a node with tx disabled looks identical to a healthy one
 * everywhere except here"*, and *"the one state that must never be assumed is the one that
 * silently drops every message"*.
 *
 * ## Reported, not requested
 *
 * `loraSetRegion` sends a region and this reads one back, and they are not the same fact.
 * A write can be refused, land on a different field, or never arrive; trusting the request
 * is how a client ends up insisting a radio is on EU868 while the radio disagrees. Every
 * value here comes from the node.
 *
 * ## The protobuf rule that decides both defaults
 *
 * `Config.lora` is field 6 of `Config`; inside it `region` is 7 and `tx_enabled` is 9
 * (checked against `protobufs/meshtastic/config.proto`, not from memory).
 *
 * Protobuf omits default values, so ABSENT and ZERO are the same byte sequence — none —
 * and the two fields need opposite readings of that:
 *
 *  - **region absent ⇒ UNSET**, which is a real state and a bad one: a node with no region
 *    cannot legally transmit and will not. Reading absence as "fine" would hide exactly
 *    the case the field exists to expose.
 *  - **tx_enabled absent ⇒ ENABLED.** This is the opposite reading and it is deliberate,
 *    mirroring iOS (`var txOn = true`). A firmware that ships `tx_enabled` true omits it
 *    from the wire, so treating absence as "muted" would paint every healthy radio as
 *    broken — a warning that fires constantly is one nobody reads.
 */
class LoRaRadioState {

    /** Meshtastic `RegionCode`, as the node reports it. 0 is UNSET and means "will not transmit". */
    @Volatile
    var regionCode: Long? = null
        private set

    /** False only when the node explicitly said `tx_enabled: false`. See the class note. */
    @Volatile
    var transmitEnabled: Boolean = true
        private set

    /** True once a config burst has actually been parsed — before that, nothing here is news. */
    @Volatile
    var known: Boolean = false
        private set

    /** The node is attached and will never transmit: no region, or muted. */
    val willNotTransmit: Boolean
        get() = known && (!transmitEnabled || regionCode == null || regionCode == 0L)

    /** Why, in the words the pane shows. Null when the radio is fine. */
    val blockedReason: String?
        get() = when {
            !known -> null
            !transmitEnabled -> "This radio is muted — its own tx_enabled is off, so nothing you send leaves it."
            regionCode == null || regionCode == 0L ->
                "This radio has no region set, so it will not transmit. Choose one above."
            else -> null
        }

    fun clear() {
        regionCode = null; transmitEnabled = true; known = false
    }

    /**
     * Fold one raw `FromRadio`. Returns true when it carried a `Config.lora`.
     *
     * Anything else is ignored rather than guessed at — the burst is mostly node info and
     * packets, and a parser that tried to find a region in those would eventually find one.
     */
    fun observeFromRadio(frame: ByteArray): Boolean {
        val config = runCatching { LoRaProto.field(frame, 5, wire = 2)?.payload }.getOrNull() ?: return false
        val lora = LoRaProto.field(config, 6, wire = 2)?.payload ?: return false
        var region: Long? = null
        var tx = true
        for (f in LoRaProto.parseFields(lora)) {
            if (f.wire != 0) continue
            when (f.number) {
                7 -> region = f.varintValue
                9 -> tx = f.varintValue != 0L
            }
        }
        regionCode = region
        transmitEnabled = tx
        known = true
        return true
    }
}
