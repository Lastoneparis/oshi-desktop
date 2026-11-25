package com.oshi.desktop.group

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** __GROUP_RESEND_2026_11_25__ the durable queue of members a group message has not reached yet. */
class GroupPendingDeliveryTest {
    private val dir: File = Files.createTempDirectory("oshi-pending").toFile()
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private fun store() = GroupPendingDeliveryStore(File(dir, GroupPendingDeliveryStore.FILE_NAME), key)
    private val members = (1..5).map { "member$it" }.toSet()
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @After fun tearDown() { dir.deleteRecursively() }

    private fun entry(payload: ByteArray? = "hi".toByteArray()) = GroupPendingDeliveryStore.Entry(
        msgId = "M1", groupId = "G1", conversationId = "G1", total = 0, pending = members, attempts = 0,
        firstAt = t0, nextAt = t0, payload = payload,
    )

    @Test fun `everybody reached - nothing left`() {
        val s = store(); s.begin(entry())
        assertNull(s.settle("M1", members, emptyList(), now = t0))
        assertTrue(s.all().isEmpty())
    }

    @Test fun `partial send - honest counts, no-bundle and blocked members are not owed`() {
        val s = store(); s.begin(entry())
        val e = s.settle("M1", listOf("member1", "member2"), listOf("member3"), now = t0)!!
        assertEquals(setOf("member4", "member5"), e.pending)
        assertEquals(4, e.total)
        assertEquals(2, e.delivered)
    }

    @Test fun `survives a restart, sealed on disk, payload kept`() {
        store().begin(entry("SECRET_BODY".toByteArray()))
        val f = File(dir, GroupPendingDeliveryStore.FILE_NAME)
        assertFalse(f.readText(Charsets.ISO_8859_1).contains("member4"))
        val e = store().get("M1")!!
        assertEquals(members, e.pending)
        assertArrayEquals("SECRET_BODY".toByteArray(), e.payload)
        assertEquals(listOf("M1"), store().due(now = t0 + min).map { it.msgId })
    }

    @Test fun `backoff 1, 5, 30 min then 3 h, Retry-After wins when longer`() {
        val s = store(); s.begin(entry())
        var now = t0
        val waits = (1..5).map { val e = s.settle("M1", emptyList(), emptyList(), now = now)!!; val w = e.nextAt - now; now = e.nextAt; w }
        assertEquals(listOf(min, 5 * min, 30 * min, 180 * min, 180 * min), waits)
        assertEquals(now + 600 * min, s.settle("M1", emptyList(), emptyList(), now = now, retryAfterMs = 600 * min)!!.nextAt)
    }

    @Test fun `7 days - no more automatic retries, Retry now revives`() {
        val s = store(); s.begin(entry())
        val late = t0 + GroupResendSchedule.MAX_AGE_MS
        assertTrue(s.settle("M1", emptyList(), emptyList(), now = late)!!.gaveUp)
        assertTrue(s.due(now = late + 1000 * min).isEmpty())
        s.makeDue(now = late)                 // connectivity back does NOT revive a given-up entry
        assertTrue(s.due(now = late).isEmpty())
        s.makeDue("M1", now = late)           // Retry now does
        assertEquals(1, s.due(now = late).size)
    }

    @Test fun `members who left are dropped, and the entry with them`() {
        val s = store(); s.begin(entry())
        s.settle("M1", listOf("member1", "member2", "member3"), emptyList(), now = t0)
        assertTrue(s.dropDeparted("G1", members - "member5").isEmpty())
        assertEquals(setOf("member4"), s.get("M1")!!.pending)
        assertEquals(listOf("M1"), s.dropDeparted("G1", setOf("member1")))
        assertNull(s.get("M1"))
    }

    @Test fun `a shared key message replaces the per-member media fallback`() {
        val s = store(); s.begin(entry(payload = null).copy(mediaPath = "/tmp/x.jpg"))
        val e = s.settle("M1", listOf("member1"), emptyList(), now = t0, payload = "KEY".toByteArray())!!
        assertArrayEquals("KEY".toByteArray(), e.payload)
        assertNull(e.mediaPath)
    }
}
