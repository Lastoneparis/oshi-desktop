package com.oshi.desktop.lora

/**
 * __DESKTOP_LORA_REFUSAL_2026_10_29__ Why a message did not go to the radio — Android's
 * `LoRaSendRefusal`, which this client had no equivalent of.
 *
 * Before this, every desktop send path asked one question: is a link open? If it was, the
 * bytes were written and `true` came back. `LoRaLink.send` is honest about what that means
 * — *"it means the bytes left this process"* — but nothing above it was, so a radio that
 * was **muted** or had **no region** accepted every message and transmitted none, and the
 * app said sent.
 *
 * That is not a hypothetical failure mode. An entire evening of this project went into a
 * position that never reached a second radio while every write returned true; the cause
 * was different, but the shape was identical and the lesson is the one encoded here: a
 * successful write is not a transmission, and the states that make it meaningless are
 * knowable BEFORE sending.
 *
 * ## What is deliberately NOT refused
 *
 * Admin frames. [RADIO_MUTED] and [NO_REGION] are the two conditions an owner fixes by
 * sending an admin frame — `tx_enabled` back on, a region set — so refusing admin on those
 * grounds would make an unconfigured radio impossible to configure from the app that
 * noticed. Admin is unicast to our own node over the cable and does not need the
 * transmitter at all.
 */
enum class LoRaSendRefusal {
    /** No radio attached, or attached and still handshaking. */
    NOT_CONNECTED,

    /** The node reports `tx_enabled: false`. It accepts frames and transmits nothing. */
    RADIO_MUTED,

    /** The node reports no region. It cannot legally transmit and will not. */
    NO_REGION,

    /** Over the lane's byte budget. The size is in the message, never a truncation. */
    TOO_LARGE,

    /** Media over LoRa is off. The person turns it on in Settings, knowing the cost. */
    CARRIES_MEDIA;

    /**
     * One sentence, in the words the pane shows.
     *
     * English here, unlike Android's `stringKey`, and that is a platform difference rather
     * than an oversight: this client's catalogs are EXTRACTED from the iOS tree
     * (PARITY row 1.5), so a desktop-only string has no home in them and inventing one
     * would create a key the extractor deletes on its next run. The pane renders this
     * directly, as it already does for every other LoRa refusal wording.
     */
    val reason: String
        get() = when (this) {
            NOT_CONNECTED -> "No radio is attached."
            RADIO_MUTED ->
                "The radio is muted — it reports tx_enabled off, so it would accept this and " +
                    "transmit nothing."
            NO_REGION ->
                "The radio has no region set, so it will not transmit. Choose one and try again."
            TOO_LARGE -> "Too big for one radio message."
            CARRIES_MEDIA ->
                "Attachments are not allowed over the radio. Turn it on above if you accept " +
                    "the wait."
        }

    /** True when the Radio pane itself holds the control that fixes it. */
    val fixableHere: Boolean
        get() = this == NO_REGION || this == CARRIES_MEDIA || this == NOT_CONNECTED

    companion object {
        /**
         * The check every send makes before writing, and the reason it exists.
         *
         * @param attached a live link to the radio
         * @param state what the node says about ITSELF — null when nothing has been read
         *   yet, which is NOT a refusal: a radio mid-handshake has said nothing, and
         *   refusing on silence would block the first message of every session.
         */
        fun forSend(attached: Boolean, state: LoRaRadioState?): LoRaSendRefusal? = when {
            !attached -> NOT_CONNECTED
            state == null || !state.known -> null
            !state.transmitEnabled -> RADIO_MUTED
            state.regionCode == null || state.regionCode == 0L -> NO_REGION
            else -> null
        }
    }
}
