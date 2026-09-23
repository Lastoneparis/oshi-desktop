package com.oshi.desktop.ui.state

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class HeaderStatusTest {

    // ------------------------------------------------------------------ reach badge

    @Test
    fun aRiseIsShownAtOnce() {
        val t = NetworkBadgeTracker()
        assertEquals(2, t.observe(2, false, 0, 0).meshPeers)
    }

    @Test
    fun aFlapShorterThanTheGraceIsNeverShown() {
        val t = NetworkBadgeTracker()
        t.observe(1, false, 0, 0)
        assertEquals(1, t.observe(0, false, 0, 1_000).meshPeers)
        assertEquals(1, t.observe(1, false, 0, 3_000).meshPeers)
        // the drop clock restarted: a new drop needs its own full grace
        assertEquals(1, t.observe(0, false, 0, 4_000).meshPeers)
        assertEquals(1, t.observe(0, false, 0, 4_000 + NetworkBadgeTracker.DROP_GRACE_MS - 1).meshPeers)
    }

    @Test
    fun aDropThatLastsIsShown() {
        val t = NetworkBadgeTracker()
        t.observe(3, false, 0, 0)
        t.observe(0, false, 0, 1_000)
        val b = t.observe(0, false, 0, 1_000 + NetworkBadgeTracker.DROP_GRACE_MS)
        assertEquals(0, b.meshPeers)
        assertFalse(b.connected)
    }

    @Test
    fun loraCountsOnlyWhileAttachedAndAttachingAloneIsConnected() {
        val t = NetworkBadgeTracker()
        assertEquals(0, t.observe(0, false, 5, 0).loraNodes)
        val b = t.observe(0, true, 0, 0)
        assertTrue(b.connected)
        assertEquals(4, t.observe(0, true, 4, 0).loraNodes)
    }

    // ------------------------------------------------------------------ incoming live shares

    private val label = { from: String -> "name-$from" }

    @Test
    fun oneLiveShareIsCountedUntilItEnds() {
        val s = IncomingLiveShares()
        s.observe("alice", "s1", live = true, endsAtMs = 10_000)
        assertEquals(listOf(IncomingShare("alice", "name-alice", 10_000L)), s.active(5_000, label))
        assertTrue(s.active(10_000, label).isEmpty())
    }

    @Test
    fun aStopIsFinalAndALaterPingCannotExtend() {
        val s = IncomingLiveShares()
        s.observe("bob", "s1", live = true, endsAtMs = 10_000)
        s.observe("bob", "s1", live = true, endsAtMs = 99_000)
        assertEquals(10_000L, s.active(0, label).single().endsAtMs)
        s.observe("bob", "s1", live = false, endsAtMs = 10_000)
        s.observe("bob", "s1", live = true, endsAtMs = 10_000)
        assertTrue(s.active(0, label).isEmpty())
    }

    @Test
    fun severalSendersAreCountedOncePerSender() {
        val s = IncomingLiveShares()
        s.observe("alice", "a1", live = true, endsAtMs = 20_000)
        s.observe("alice", "a2", live = true, endsAtMs = 30_000)
        s.observe("carol", "c1", live = true, endsAtMs = 15_000)
        val active = s.active(0, label)
        assertEquals(listOf("carol", "alice"), active.map { it.from })
        assertEquals(30_000L, active.last().endsAtMs)
    }

    @Test
    fun aSessionIdCannotBeTakenOverByAnotherSender() {
        val s = IncomingLiveShares()
        s.observe("alice", "s1", live = true, endsAtMs = 20_000)
        s.observe("mallory", "s1", live = false, endsAtMs = 20_000)
        assertEquals(listOf("alice"), s.active(0, label).map { it.from })
    }
}
