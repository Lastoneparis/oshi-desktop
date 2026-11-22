package com.oshi.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * __GATE_NOT_YET_IS_NOT_NO_2026_10_29__ The three states, in one place, because four
 * surfaces each decided this independently and all four got it wrong the same way.
 *
 * `isEnabledCached()` is false before the first `/v2/config` answer and after one that
 * said no. Every surface branched on that single boolean and told a client still waiting
 * that its ACCOUNT was refused — the REPL's send outcome, the window's send notice, the
 * chip under the user's own name, and the account pane opened precisely to find out.
 */
class GateDisplayTest {

    @Test
    fun `an open gate is open whatever else is true`() {
        assertEquals(GateDisplay.OPEN, gateDisplay(gateOpen = true, gateAnswered = true))
        // A local override opens the gate without the server having answered; that is
        // still OPEN, and must not be reported as "asking".
        assertEquals(GateDisplay.OPEN, gateDisplay(gateOpen = true, gateAnswered = false))
    }

    @Test
    fun `closed with no answer yet is ASKING, not a verdict about the account`() {
        assertEquals(GateDisplay.ASKING, gateDisplay(gateOpen = false, gateAnswered = false))
    }

    @Test
    fun `closed after the server said so is CLOSED`() {
        assertEquals(GateDisplay.CLOSED, gateDisplay(gateOpen = false, gateAnswered = true))
    }
}
