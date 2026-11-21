package com.oshi.desktop.app

import com.oshi.desktop.msg.PollPayload
import com.oshi.desktop.store.PollTally
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_POLLS_2027__ A poll in a group and a ballot back, through three real clients over
 * the fixture relay — Android's group poll path (`sendGroupMessage` with the poll payload,
 * `sendGroupPollBallot` ephemeral).
 */
class GroupPollWiringTest {

    private val fx = WiringFixture()

    @After fun tearDown() = fx.close()

    @Test
    fun `a group poll reaches every member and a member's ballot is counted, never stored`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val c = fx.client("c")
        val g = a.createGroup("trio", listOf(b.address, c.address))
        b.router.poll(); c.router.poll()

        val report = a.sendGroupPoll(g.groupId, "Pizza or sushi?", listOf("Pizza", "Sushi"))
        assertNotNull(report)
        assertTrue("the poll must reach the members", report!!.delivered)
        val mine = PollPayload.decodePoll(a.messages.messages(g.groupId).last().content)
        assertNotNull("the sender keeps the poll as a row", mine)

        b.router.poll(); c.router.poll()
        val onB = PollPayload.decodePoll(b.messages.messages(g.groupId).last().content)
        assertNotNull("the poll arrived as a group row on B", onB)
        assertEquals(mine!!.id, onB!!.id)

        val rowsOnC = c.messages.messages(g.groupId).size
        b.voteGroupPoll(g.groupId, onB.id, listOf(1))
        assertEquals(setOf(1), b.pollBallots.results(onB, b.address).myPicks)
        a.router.poll(); c.router.poll()

        val onA = a.pollBallots.results(mine, a.address)
        assertEquals("B's ballot never reached A", listOf(0, 1), onA.counts)
        assertEquals(listOf(PollTally.normalize(b.address)), onA.votersByOption[1])
        assertEquals("C counts it too", listOf(0, 1), c.pollBallots.results(mine, c.address).counts)
        assertEquals("a ballot is never a row", rowsOnC, c.messages.messages(g.groupId).size)
    }

    @Test
    fun `an unsendable group poll is refused before anything is sent`() {
        val a = fx.client("a")
        val b = fx.client("b")
        val g = a.createGroup("duo", listOf(b.address))
        val before = a.messages.messages(g.groupId).size
        assertNull(a.sendGroupPoll(g.groupId, "Only one", listOf("x")))
        assertEquals(before, a.messages.messages(g.groupId).size)
    }
}
