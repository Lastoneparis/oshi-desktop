package com.oshi.desktop.lora

import com.oshi.desktop.lora.DesktopTransportPolicy.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which lane carries a message — the decision table, with the ban and the order kept apart.
 *
 * Every case here is one a person can create from the Radio screen with two clicks, and the
 * one that matters most is the last section: a BAN is not a stronger ORDER. Conflating them
 * is how a message ends up on a relay someone explicitly forbade, and no error anywhere
 * would report it.
 */
class DesktopTransportPolicyTest {

    // ---------------------------------------------------------------- the order

    @Test
    fun `by default the relay goes first and the radio is the fallback`() {
        assertEquals(
            listOf(Lane.RELAY, Lane.RADIO),
            DesktopTransportPolicy.order(radioAttached = true, relayPossible = true, preferRadio = false, internetAllowed = true),
        )
    }

    @Test
    fun `preferring the radio puts it first and keeps the relay behind it`() {
        assertEquals(
            listOf(Lane.RADIO, Lane.RELAY),
            DesktopTransportPolicy.order(radioAttached = true, relayPossible = true, preferRadio = true, internetAllowed = true),
        )
    }

    @Test
    fun `with no radio attached there is only the relay`() {
        assertEquals(
            listOf(Lane.RELAY),
            DesktopTransportPolicy.order(radioAttached = false, relayPossible = true, preferRadio = true, internetAllowed = true),
        )
    }

    @Test
    fun `with no relay the radio carries it, preference or not`() {
        assertEquals(
            listOf(Lane.RADIO),
            DesktopTransportPolicy.order(radioAttached = true, relayPossible = false, preferRadio = false, internetAllowed = true),
        )
    }

    // ---------------------------------------------------------------- the ban

    /**
     * THE CASE THIS FILE EXISTS FOR. "Never over the internet" with no radio attached must
     * produce NOWHERE, so the caller holds the message. An empty list is not a failure and
     * must not be read as one — a banned relay that quietly carried the message anyway is
     * precisely the quiet re-routing the switch is asked for to prevent.
     */
    @Test
    fun `a banned internet with no radio leaves nowhere to go`() {
        assertEquals(
            emptyList<Lane>(),
            DesktopTransportPolicy.order(radioAttached = false, relayPossible = true, preferRadio = false, internetAllowed = false),
        )
    }

    @Test
    fun `a banned internet never leaves the relay in the order, even as a fallback`() {
        val o = DesktopTransportPolicy.order(radioAttached = true, relayPossible = true, preferRadio = true, internetAllowed = false)
        assertEquals(listOf(Lane.RADIO), o)
        assertFalse("the relay must not be reachable as a fallback under a ban", o.contains(Lane.RELAY))
    }

    /** And the ban outranks the order: turning OFF "prefer radio" cannot resurrect the relay. */
    @Test
    fun `the ban outranks the preference`() {
        assertEquals(
            listOf(Lane.RADIO),
            DesktopTransportPolicy.order(radioAttached = true, relayPossible = true, preferRadio = false, internetAllowed = false),
        )
    }

    // ---------------------------------------------------------------- derived, never re-derived

    @Test
    fun `radioComesBeforeRelay agrees with the order it is derived from`() {
        for (radio in listOf(true, false)) {
            for (relay in listOf(true, false)) {
                for (prefer in listOf(true, false)) {
                    for (allowed in listOf(true, false)) {
                        val o = DesktopTransportPolicy.order(radio, relay, prefer, allowed)
                        val expected = o.indexOf(Lane.RADIO).let { r ->
                            r >= 0 && (o.indexOf(Lane.RELAY) < 0 || r < o.indexOf(Lane.RELAY))
                        }
                        assertEquals(
                            "radio=$radio relay=$relay prefer=$prefer allowed=$allowed",
                            expected,
                            DesktopTransportPolicy.radioComesBeforeRelay(radio, relay, prefer, allowed),
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- the 90 s escalation

    @Test
    fun `the window is iOS's ninety seconds`() {
        // MeshtasticManager.loraAckWindow. Meshtastic retries for well under a minute, so a
        // later answer would be for a packet id already reused.
        assertEquals(90_000L, DesktopTransportPolicy.ACK_WINDOW_MS)
    }

    @Test
    fun `the internet picks up what the radio could not deliver`() {
        assertTrue(
            DesktopTransportPolicy.internetPicksUpAfterRadio(
                relayPossible = true, internetAllowed = true, alreadyDelivered = false,
            )
        )
    }

    /** A message another transport already delivered must not be sent again by a timer. */
    @Test
    fun `an already delivered message is not escalated`() {
        assertFalse(
            DesktopTransportPolicy.internetPicksUpAfterRadio(
                relayPossible = true, internetAllowed = true, alreadyDelivered = true,
            )
        )
    }

    /**
     * Someone who turned the ban on DURING those ninety seconds asked for the message to
     * wait, not to leave a minute later. The policy is re-read at expiry, never captured.
     */
    @Test
    fun `a banned internet does not pick up after the radio either`() {
        assertFalse(
            DesktopTransportPolicy.internetPicksUpAfterRadio(
                relayPossible = true, internetAllowed = false, alreadyDelivered = false,
            )
        )
    }

    @Test
    fun `with no relay there is nothing to escalate to`() {
        assertFalse(
            DesktopTransportPolicy.internetPicksUpAfterRadio(
                relayPossible = false, internetAllowed = true, alreadyDelivered = false,
            )
        )
    }
}
