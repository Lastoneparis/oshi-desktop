package com.oshi.desktop.block

import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.DeliveryReceipt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * __BLOCKED_NOTICE_SWITCH_2026_09_23__ "When I block someone … he will be notified."
 *
 * The gate's three rules in isolation, then the whole loop between two real clients over
 * the in-process relay: A blocks B → B writes → A stores nothing and raises nothing → B
 * gets ONE localized notice row, silently → B writes again the same day → no second
 * notice → A unblocks → B's next message arrives.
 */
class BlockedNoticeTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    // ------------------------------------------------------------ the gate

    @Test
    fun `the switch is on per the owner's request`() {
        assertTrue(BlockedNoticeGate.ENABLED)
        assertEquals(24L * 60 * 60 * 1000, BlockedNoticeGate.INTERVAL_MS)
    }

    @Test
    fun `machinery and the notice itself are never answered`() {
        for (t in listOf(
            DeliveryReceipt.encode("x"), "📖READ_RECEIPT📖{}", "⌨️TYPING⌨️", "✍️TYPING✍️",
            "📸PROFILE_UPDATE📸{}", "🎨WALLPAPER_UPDATE🎨x", "🔧ACTION🔧{}",
            ControlPrefix.BLOCKED_NOTICE, "{\"type\":\"member_sync\",\"groupId\":\"g\"}",
        )) assertFalse("$t must not draw a notice", BlockedNoticeGate.isWorthANotice(t))
        assertTrue(BlockedNoticeGate.isWorthANotice("are you there?"))
        assertTrue("a call attempt is someone trying to reach you", BlockedNoticeGate.isWorthANotice(null))
        assertTrue(BlockedNoticeGate.isWorthANotice(ControlPrefix.CALL_SIGNAL + "{}"))
    }

    @Test
    fun `once per peer per day, persisted across restarts, never to myself`() {
        val dir = Files.createTempDirectory("oshi-notice").toFile()
        try {
            val f = File(dir, "n.json")
            var now = 1_000_000_000_000L
            val key = ByteArray(32) { 7 }
            val g1 = BlockedNoticeGate(f, key) { now }
            assertTrue(g1.claim("peerA=", "hi", "me"))
            assertFalse("same day, same peer (other base64 spelling)", g1.claim("peerA", "hi again", "me"))
            assertTrue("another peer has their own budget", g1.claim("peerB", "hi", "me"))
            assertFalse(g1.claim("me", "hi", "me"))
            // A restart must not reset the budget.
            val g2 = BlockedNoticeGate(f, key) { now }
            assertFalse(g2.claim("peerA", "hi", "me"))
            now += BlockedNoticeGate.INTERVAL_MS
            assertTrue("a day later they are told again", g2.claim("peerA", "hi", "me"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ end to end

    @Test
    fun `a blocked sender is told once, silently, and the blocker sees nothing`() {
        val a = fx.client("a")
        val b = fx.client("b")
        var aAlerts = 0
        var bAlerts = 0
        a.onMessage = { aAlerts++ }
        b.onMessage = { bAlerts++ }
        a.block(b.address)

        assertEquals(com.oshi.desktop.app.OshiClient.SendOutcome.SENT, b.send(a.address, "hello?"))
        a.router.poll()
        assertTrue("the blocker stored a blocked peer's message", a.messages.messages(b.address).isEmpty())
        assertEquals("the blocker was alerted by a blocked peer", 0, aAlerts)

        b.router.poll()
        val rows = b.messages.messages(a.address).filter { !it.fromMe }
        assertEquals("the blocked sender was not told (or told twice)", 1, rows.size)
        assertTrue(rows.single().content.orEmpty().startsWith("🚫 "))
        assertFalse("the raw sentinel leaked", rows.single().content.orEmpty().contains("BLOCKED"))
        assertEquals("the notice must not raise an alert", 0, bAlerts)

        // Same day: another message draws no second notice.
        b.send(a.address, "still there?")
        a.router.poll()
        b.router.poll()
        assertEquals(1, b.messages.messages(a.address).count { !it.fromMe })

        // Unblock restores delivery.
        a.unblock(b.address)
        b.send(a.address, "back")
        a.router.poll()
        assertEquals(listOf("back"), a.messages.messages(b.address).map { it.content })
        assertEquals(1, aAlerts)
    }
}
