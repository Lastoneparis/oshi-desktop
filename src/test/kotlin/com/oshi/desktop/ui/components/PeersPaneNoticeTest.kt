package com.oshi.desktop.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MESH_OFF_IS_A_SENTENCE_2026_10_29__ PARITY.md row 1.2-a records half an hour lost to
 * this: with `--ui` and no `--mesh`, the scanning box will not tick, and the pane answered
 * "Scanning off. Nothing is announced…" — which describes a successful switch-off to
 * someone who had just asked for ON. Every symptom then looks like blocked mDNS.
 */
class PeersPaneNoticeTest {

    @Test
    fun `a refusal with the mesh off names the launch flag`() {
        val s = discoveryNotice(wanted = true, resulting = false, meshRunning = false)
        assertTrue("must name --mesh: $s", s.contains("--mesh"))
        assertTrue("must not read as a successful off: $s", !s.startsWith("Scanning off"))
    }

    @Test
    fun `a refusal with the mesh running blames the network, not a setting`() {
        val s = discoveryNotice(wanted = true, resulting = false, meshRunning = true)
        assertTrue("must not send them to --mesh when it IS running: $s", !s.contains("--mesh"))
        assertTrue("must say what it actually is: $s", s.contains("multicast"))
        assertTrue("must not read as a successful off: $s", !s.startsWith("Scanning off"))
    }

    @Test
    fun `turning it off deliberately still says so`() {
        val s = discoveryNotice(wanted = false, resulting = false, meshRunning = true)
        assertTrue(s, s.startsWith("Scanning off"))
    }

    @Test
    fun `turning it on reports success`() {
        val s = discoveryNotice(wanted = true, resulting = true, meshRunning = true)
        assertTrue(s, s.startsWith("Scanning."))
    }
}
