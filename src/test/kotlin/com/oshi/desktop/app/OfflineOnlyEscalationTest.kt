package com.oshi.desktop.app

import com.oshi.desktop.lora.DesktopTransportPolicy
import com.oshi.desktop.msg.OfflineOnlyPayload
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_OFFLINE_ONLY_2026_10_29__ The 90-second radio escalation must ask the
 * per-conversation switch, not only the app-wide ban.
 *
 * This is the second of the two places that decide whether the relay may carry something,
 * and the one a per-conversation restriction gets missed in. The failure is silent and total:
 * the message leaves correctly over the radio, nothing acknowledges it, and ninety seconds
 * later the timer hands the SAME message to the relay — breaking the one promise the switch
 * makes completely, in a conversation someone deliberately took off the internet.
 *
 * iOS carries the identical warning on its own version of this function: *"Reading the global
 * here as well would let a conversation marked offline-only be escalated onto the relay
 * anyway."* Two clients, independently, both needed telling.
 *
 * Driven against the pure policy rather than a timer, because what must be pinned is the
 * COMPOSITION — that the value handed to `internetPicksUpAfterRadio` is the folded one. The
 * fold itself is `OfflineOnlyPayloadTest`'s subject.
 */
class OfflineOnlyEscalationTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    /**
     * THE CALL SITE, not the policy function.
     *
     * The two tests below this one exercise `internetPicksUpAfterRadio` with a folded value
     * and are worth keeping, but they could not catch the bug: a mutation that dropped the
     * per-conversation half from the timer and read only the app-wide ban left the entire
     * suite green. That is why `relayMayPickUpAfterRadio` exists as a named function — this
     * drives the real decision, on a real client, with a real restricted conversation.
     */
    @Test
    fun `the client refuses to escalate a restricted conversation onto the relay`() {
        val a = fx.client("a")
        val b = fx.client("b")

        // An ordinary conversation escalates: the counterfactual first, so a literal `false`
        // in the implementation would fail here rather than pass silently.
        assertTrue(
            "an ordinary conversation must still escalate after the radio goes quiet",
            a.relayMayPickUpAfterRadio(b.address, alreadyDelivered = false),
        )

        a.setOfflineOnly(b.address, true)
        assertFalse(
            "ninety seconds after a silent radio, this conversation was handed to the relay " +
                "it was deliberately taken off",
            a.relayMayPickUpAfterRadio(b.address, alreadyDelivered = false),
        )

        // Lifting it restores the escalation, so the refusal tracks the switch rather than
        // being a one-way latch.
        a.setOfflineOnly(b.address, false)
        assertTrue(a.relayMayPickUpAfterRadio(b.address, alreadyDelivered = false))

        // And a delivered message is never pulled back, either way.
        assertFalse(a.relayMayPickUpAfterRadio(b.address, alreadyDelivered = true))
    }

    @Test
    fun `the escalation is refused for a conversation that is offline-only`() {
        val folded = OfflineOnlyPayload.internetAllowed(
            globalInternetAllowed = true,          // the app-wide ban is OFF
            conversationIsOfflineOnly = true,      // but THIS conversation is restricted
        )
        assertFalse("the fold must say no", folded)
        assertFalse(
            "a restricted conversation was escalated onto the relay after the radio went quiet",
            DesktopTransportPolicy.internetPicksUpAfterRadio(
                relayPossible = true,
                internetAllowed = folded,
                alreadyDelivered = false,
            ),
        )
    }

    /**
     * The counterfactual, so the test above is not merely asserting that `false` is false:
     * with the conversation unrestricted and everything else identical, the escalation DOES
     * run. Without this row, replacing the fold with a literal `false` would pass.
     */
    @Test
    fun `an unrestricted conversation still escalates`() {
        val folded = OfflineOnlyPayload.internetAllowed(
            globalInternetAllowed = true,
            conversationIsOfflineOnly = false,
        )
        assertTrue(folded)
        assertTrue(
            "the escalation must still work for an ordinary conversation",
            DesktopTransportPolicy.internetPicksUpAfterRadio(
                relayPossible = true,
                internetAllowed = folded,
                alreadyDelivered = false,
            ),
        )
    }

    /** And a delivered message is never pulled back onto the relay, restricted or not. */
    @Test
    fun `a delivered message never escalates`() {
        for (restricted in listOf(true, false)) {
            assertFalse(
                DesktopTransportPolicy.internetPicksUpAfterRadio(
                    relayPossible = true,
                    internetAllowed = OfflineOnlyPayload.internetAllowed(true, restricted),
                    alreadyDelivered = true,
                ),
            )
        }
    }
}
