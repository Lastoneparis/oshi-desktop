package com.oshi.desktop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * __GATE_NOT_YET_IS_NOT_NO_2026_10_29__ "I have not heard back yet" is not "your account is
 * gated", and until now both printed the same sentence.
 *
 * [V2ConfigGate.isEnabledCached] is false before the first `/v2/config` answer AND after one
 * that said no. On this relay that route has been measured at 9-25 s, so the first state is
 * one a user reaches by starting the app and typing — and the message sent them to look at
 * their account. The gate's own [V2ConfigGate.lastDecision] comment already lists the four
 * causes the old wording conflated; [V2ConfigGate.everAnswered] separates the first.
 */
class V2GateNotYetTest {

    @Test
    fun `a fresh gate has answered nothing and says so`() {
        val gate = V2ConfigGate(baseUrl = "http://127.0.0.1:1", log = {})
        assertFalse("closed before any answer", gate.isEnabledCached())
        assertFalse("and it must not claim to have been told", gate.everAnswered)
        assertEquals("not checked yet", gate.lastDecision)
    }

    @Test
    fun `a local override opens the gate without inventing an answer`() {
        val gate = V2ConfigGate(baseUrl = "http://127.0.0.1:1", log = {})
        gate.setLocalEnabled(true)
        // The override is a developer switch, not a server reply: it may open the gate,
        // but it must not make the client claim the server said anything.
        assertFalse("an override is not an answer", gate.everAnswered)
    }
}
