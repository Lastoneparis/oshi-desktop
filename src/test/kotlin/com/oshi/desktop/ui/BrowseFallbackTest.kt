package com.oshi.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** __LINUX_BROWSE_FALLBACK_2026_11_21__ links still open on a Linux desktop without GTK. */
class BrowseFallbackTest {

    @Test
    fun `AWT first, and nothing else when it works`() {
        val spawned = ArrayList<List<String>>()
        assertTrue(browseUrl("https://oshi-messenger.com/desktop", linux = true, awt = { true }, spawn = { spawned += it; true }))
        assertTrue(spawned.isEmpty())
    }

    @Test
    fun `off Linux a failing AWT is the end of it`() {
        val spawned = ArrayList<List<String>>()
        assertFalse(browseUrl("https://oshi-messenger.com", linux = false, awt = { false }, spawn = { spawned += it; true }))
        assertTrue(spawned.isEmpty())
    }

    @Test
    fun `only web links reach xdg-open, as one argument`() {
        val spawned = ArrayList<List<String>>()
        for (u in listOf("file:///etc/passwd", "javascript:alert(1)", "cashu:abc", "smb://host/share")) {
            assertFalse(u, browseUrl(u, linux = true, awt = { throw UnsupportedOperationException() }, spawn = { spawned += it; true }))
        }
        assertTrue(spawned.isEmpty())
    }

    @Test
    fun `Linux falls back to xdg-open when AWT cannot browse`() {
        val xdg = com.oshi.desktop.store.ExecutableLookup.which("xdg-open")
        Assume.assumeTrue("xdg-open not installed here", xdg != null)
        val spawned = ArrayList<List<String>>()
        assertTrue(browseUrl("https://oshi-messenger.com/desktop?a=b", linux = true, awt = { false }, spawn = { spawned += it; true }))
        assertEquals(1, spawned.size)
        assertEquals(2, spawned[0].size)
        assertEquals(xdg, spawned[0][0])
    }
}
