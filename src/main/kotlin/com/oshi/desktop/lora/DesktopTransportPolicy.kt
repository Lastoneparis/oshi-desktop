package com.oshi.desktop.lora

/**
 * Which lane carries a message, and what happens when the first one does not reach anyone.
 *
 * __DESKTOP_RADIO_LANE_2026_10_28__ Port of iOS `LoRaTransport.TransportPolicy`
 * (`OSHI/LoRaTransport.swift`). Until this existed, the desktop's radio was a SCREEN: you
 * could send over it by going to the Radio pane and choosing to. iOS does not work that way
 * — there the radio is a transport the router picks, which is what makes "OSHI works with
 * no internet" true of the app rather than of a feature inside it.
 *
 * Pure on purpose: it takes booleans and returns an order. Nothing here opens a socket or
 * reads a preference, so the whole decision table is testable, and the list a diagnostics
 * screen shows cannot drift from the branch the sender takes — iOS records that drift as
 * the entire reason its version exists.
 *
 * ============================================================ TWO SWITCHES, NOT ONE
 *
 * They are different questions and conflating them is how a message goes somewhere the
 * person forbade:
 *
 *  - **Prefer my radio over the internet** — an ORDER. Try the radio first; the relay still
 *    picks up what the radio could not deliver. A preference, not a sacrifice.
 *  - **Never send over the internet** — a BAN. The relay is not a fallback, not after a
 *    timeout, not when the radio fails. A message with nowhere to go WAITS rather than
 *    being quietly re-routed, which is the point of asking for it.
 *
 * The ban outranks the order, and [internetPicksUpAfterRadio] re-reads it rather than
 * assuming the send-time answer still holds ninety seconds later.
 */
object DesktopTransportPolicy {

    enum class Lane { RADIO, RELAY }

    /**
     * How long a radio send stays interesting before the relay is offered the message.
     *
     * iOS's `MeshtasticManager.loraAckWindow`, the same 90 s for the same reason: Meshtastic
     * retries for well under a minute, so a later answer would be for a packet id already
     * reused. **Silence is also a failure** — OSHI broadcasts, and a broadcast is never
     * acknowledged, so "nothing came back" is the ONLY signal this window can have and it
     * must be treated as one. iOS learned that the hard way: its first version armed the
     * window on unicast only, and a broadcast therefore had nothing watching the radio.
     */
    const val ACK_WINDOW_MS = 90_000L

    /**
     * The lanes to try, in order. Empty means the message cannot go anywhere right now and
     * must WAIT — never "fail", and never be re-routed.
     */
    fun order(
        radioAttached: Boolean,
        relayPossible: Boolean,
        preferRadio: Boolean,
        internetAllowed: Boolean,
    ): List<Lane> {
        val relay = if (relayPossible && internetAllowed) listOf(Lane.RELAY) else emptyList()
        val radio = if (radioAttached) listOf(Lane.RADIO) else emptyList()
        return if (preferRadio) radio + relay else relay + radio
    }

    /** Whether the radio is offered before the relay — DERIVED from [order], never re-derived. */
    fun radioComesBeforeRelay(
        radioAttached: Boolean,
        relayPossible: Boolean,
        preferRadio: Boolean,
        internetAllowed: Boolean,
    ): Boolean {
        val o = order(radioAttached, relayPossible, preferRadio, internetAllowed)
        val radio = o.indexOf(Lane.RADIO)
        if (radio < 0) return false
        val relay = o.indexOf(Lane.RELAY)
        return relay < 0 || radio < relay
    }

    /**
     * The radio did not reach them. May the relay pick it up?
     *
     * Port of iOS's `internetPicksUpAfterRadio`. `alreadyDelivered` is checked FIRST: a
     * message another transport has already delivered must not be sent a second time
     * because a timer fired.
     */
    fun internetPicksUpAfterRadio(
        relayPossible: Boolean,
        internetAllowed: Boolean,
        alreadyDelivered: Boolean,
    ): Boolean {
        if (alreadyDelivered) return false
        return relayPossible && internetAllowed
    }
}
