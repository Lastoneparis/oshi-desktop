package com.oshi.desktop.block

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.call.CallLane
import com.oshi.desktop.call.CallRefusal
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupMessageWire
import com.oshi.desktop.group.GroupType
import com.oshi.desktop.group.GroupUpdateWire
import com.oshi.desktop.msg.ControlPrefix
import com.oshi.desktop.msg.DeliveryReceipt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __BLOCKED_BY_PEER_2026_09_24__ Owner decision: a blocked contact leaves NO missed-call trace.
 * The caller's client learns "X blocks me" from X's 🚫BLOCKED🚫 notice and never dials X again
 * (no offer, no VoIP push) until X writes to it again.
 *
 * The evidence table uses the SAME inputs as iOS (`BlockedByPeerStore.evidence`) and Android.
 * Also here: __BLOCKED_MEMBER_GROUPS_2026_09_24__ (a blocked member's group CONTENT is dropped,
 * held content included; their group STATE updates still reach the authorizer).
 */
class BlockedByPeerTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    // ------------------------------------------------------------ the evidence table

    @Test
    fun `evidence table matches iOS and Android`() {
        val e = BlockedByPeerStore.Companion::evidence
        assertEquals(BlockedByPeerStore.Evidence.BLOCKED, e(ControlPrefix.BLOCKED_NOTICE))
        assertEquals(BlockedByPeerStore.Evidence.UNBLOCKED, e("📬DELIVERY_RECEIPT📬x"))
        assertEquals(BlockedByPeerStore.Evidence.UNBLOCKED, e("📖READ_RECEIPT📖x"))
        assertEquals(BlockedByPeerStore.Evidence.UNBLOCKED, e("are you there?"))
        assertEquals(BlockedByPeerStore.Evidence.NONE, e("⌨️TYPING⌨️"))
        assertEquals(BlockedByPeerStore.Evidence.NONE, e("📸PROFILE_UPDATE📸{\"displayName\":\"x\"}"))
        assertEquals(BlockedByPeerStore.Evidence.NONE, e(null))
    }

    @Test
    fun `the record survives a restart, matches every key spelling, and clears`() {
        val dir = Files.createTempDirectory("oshi-blockedby").toFile()
        try {
            val f = File(dir, "b.json")
            val key = ByteArray(32) { 3 }
            val s1 = BlockedByPeerStore(f, key)
            s1.observe("ab+cd/ef==", ControlPrefix.BLOCKED_NOTICE)
            assertTrue(s1.isBlockedBy("ab-cd_ef"))
            s1.observe("ab+cd/ef==", "⌨️TYPING⌨️")
            assertTrue("mechanics say nothing", s1.isBlockedBy("ab+cd/ef=="))
            val s2 = BlockedByPeerStore(f, key)
            assertTrue("persisted", s2.isBlockedBy("ab+cd/ef"))
            assertFalse("sealed at rest", f.readText().contains("ab+cd"))
            s2.observe("ab-cd_ef", "📬DELIVERY_RECEIPT📬m1")
            assertFalse(s2.isBlockedBy("ab+cd/ef=="))
            assertFalse(BlockedByPeerStore(f, key).isBlockedBy("ab+cd/ef=="))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ end to end, two real clients

    @Test
    fun `told once, the caller never dials, then a message from the blocker lifts it`() {
        val a = fx.client("a")
        val b = fx.client("b")
        a.block(b.address)

        // B writes; A (the blocker) answers with the notice; B records it.
        b.send(a.address, "hello?")
        a.router.poll()
        b.router.poll()
        assertTrue("the notice did not register", b.blockedByPeers.isBlockedBy(a.address))

        // B's call is refused locally, before anything is posted.
        val d = b.calls.call(a.address)
        assertTrue(d is CallLane.Dialled.Refused && d.refusal == CallRefusal.UNAVAILABLE)
        assertTrue(fx.repl(b, "/call ${a.address}").any { it.contains("NOT RINGING — unavailable") })

        // A unblocks and writes: B's record lifts.
        a.unblock(b.address)
        assertEquals(OshiClient.SendOutcome.SENT, a.send(b.address, "sorry, back"))
        b.router.poll()
        assertFalse("a real message from the blocker must lift the record", b.blockedByPeers.isBlockedBy(a.address))
    }

    @Test
    fun `a receipt from the peer lifts the record, typing does not`() {
        val b = fx.client("b")
        val peer = fx.client("peer", publish = false).address
        fx.inbound(b, peer, ControlPrefix.BLOCKED_NOTICE)
        assertTrue(b.blockedByPeers.isBlockedBy(peer))
        assertEquals("the notice is still a localized row", 1, b.messages.messages(peer).size)
        fx.inbound(b, peer, "⌨️TYPING⌨️")
        assertTrue(b.blockedByPeers.isBlockedBy(peer))
        fx.inbound(b, peer, DeliveryReceipt.encode("m-1"))
        assertFalse(b.blockedByPeers.isBlockedBy(peer))
    }

    // ------------------------------------------------------------ groups: a blocked member

    private fun definition(groupId: String, members: List<Pair<String, Boolean>>, name: String = "crew", version: Int = 1) =
        GroupDefinition(
            groupId = groupId,
            name = name,
            type = GroupType.COLLABORATIVE,
            adminPublicKey = members.first().first,
            members = members.map { (k, admin) -> GroupMember(publicKey = k, joinedAtUnixMillis = 1_700_000_000_000L, isAdmin = admin) },
            createdAtUnixMillis = 1_700_000_000_000L,
            lastActivityUnixMillis = 1_700_000_000_000L,
            stateVersion = version,
        )

    private fun groupText(groupId: String, sender: String, id: String, body: String) =
        GroupMessageWire.encodeForEnvelope(
            GroupMessageWire.GroupMessagePayload(
                messageId = id, groupId = groupId, senderPublicKey = sender, body = body,
                timestampUnixMillis = System.currentTimeMillis(),
            )
        )

    @Test
    fun `a blocked member's group content is dropped, their roster update still applies`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        me.groups.put(definition("g30", listOf(peer to true, me.address to false)))
        me.block(peer)

        fx.inbound(me, peer, groupText("g30", peer, "gm-30", "spam"), groupId = "g30")
        assertTrue("a blocked member's group message was stored", me.messages.messages("g30").isEmpty())

        val renamed = definition("g30", listOf(peer to true, me.address to false), name = "renamed", version = 2)
        fx.inbound(me, peer, GroupUpdateWire.encodeDefinitionFramed(renamed))
        assertEquals("the roster/name update from a blocked admin must still apply", "renamed", me.groups.get("g30")!!.name)
        assertTrue("nothing about it reached the 1:1 thread", me.messages.messages(peer).isEmpty())
    }

    @Test
    fun `content held before the block is dropped when the group arrives`() {
        val me = fx.client("me")
        val peer = fx.client("peer", publish = false).address
        val carol = fx.client("carol", publish = false).address
        // Content for a group we do not know yet is held for replay.
        fx.inbound(me, peer, groupText("g31", peer, "gm-31a", "early"), groupId = "g31")
        fx.inbound(me, carol, groupText("g31", carol, "gm-31b", "hi all"), groupId = "g31")
        me.block(peer)
        fx.inbound(me, carol, GroupUpdateWire.encodeDefinitionFramed(
            definition("g31", listOf(carol to true, me.address to false, peer to false))))
        val rows = me.messages.messages("g31").map { it.content }
        assertFalse("held content from a now-blocked member was replayed", "early" in rows)
        assertTrue("the other member's held content is replayed", "hi all" in rows)
    }
}
