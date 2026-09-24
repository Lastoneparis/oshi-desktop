package com.oshi.desktop.devsync

import com.oshi.desktop.app.WiringFixture
import com.oshi.desktop.block.BlockPolicy
import com.oshi.desktop.group.GroupDefinition
import com.oshi.desktop.group.GroupMember
import com.oshi.desktop.group.GroupType
import com.oshi.messenger.network.v2.devsync.DevSyncCrypto
import com.oshi.messenger.network.v2.devsync.InMemoryStateStore
import com.oshi.messenger.network.v2.devsync.SyncContact
import com.oshi.messenger.network.v2.devsync.SyncGroup
import com.oshi.messenger.network.v2.devsync.SyncMerge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * __BLOCK_SYNC_LWW_2026_09_24__ / __GROUP_MUTE_SYNC_2026_09_24__ What the desktop's devsync store
 * exports and applies for blocks and group mutes (design §8.5, merge.json):
 *  - never toggled here → a block untimestamped (legacy gap-fill), no block ABSENT (never false@now);
 *  - a local block/unblock is stamped, and an unblock travels as blocked:false + blockedAt;
 *  - a synced value keeps the MERGED time (the next export does not look newer than it is);
 *  - the same for a group's mute (`muted` / `mutedAt`).
 */
class DesktopDevSyncBlockMuteTest {

    private val fx = WiringFixture()

    @After
    fun tearDown() = fx.close()

    private val bob = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256("bob".toByteArray()))
    private val bobUrlSafe = bob.replace('+', '-').replace('/', '_').trimEnd('=')
    private val gid = "0A1B2C3D-4E5F-4061-8272-93A4B5C6D7E9"

    private fun store(c: com.oshi.desktop.app.OshiClient) =
        DesktopDevSyncStore({ c.address }, c.messages, c.groups, c.contacts, c.mediaDir, c.mediaVault, InMemoryStateStore())

    @Test
    fun `contacts export - legacy, block, unblock`() {
        val c = fx.client("me", publish = false)
        val s = store(c)
        c.contacts.seen(bob, 1_000)
        s.contacts().single { it.publicKey == bob }.let {
            assertNull("a contact never blocked here exports no block at all", it.blocked)
            assertNull(it.blockedAtMs)
        }
        c.contacts.applySyncedBlock(bob, true, null)   // a legacy (untimestamped) block
        s.contacts().single().let { assertEquals(true, it.blocked); assertNull(it.blockedAtMs) }

        c.block(bob)   // already blocked: no restamp
        assertNull(c.contacts.get(bob)!!.blockedAtMs)
        c.unblock(bob)
        val unblocked = s.contacts().single()
        assertEquals("an unblock is exported", false, unblocked.blocked)
        assertNotNull("with its time", unblocked.blockedAtMs)
        c.block(bob)
        val reblocked = s.contacts().single()
        assertEquals(true, reblocked.blocked)
        assertTrue("a restamp is strictly later", reblocked.blockedAtMs!! > unblocked.blockedAtMs!!)
    }

    @Test
    fun `putContact applies the merged unblock on every spelling and keeps its time`() {
        val c = fx.client("me", publish = false)
        val s = store(c)
        c.contacts.seen(bobUrlSafe, 1_000)
        c.contacts.block(bobUrlSafe, 1_758_000_000_000)
        val mine = s.contacts().single()
        val remote = SyncContact(bob, blocked = false, blockedAtMs = 1_759_000_000_000)
        val merged = SyncMerge.contact(mine, remote)
        assertEquals(false, merged.blocked)
        s.putContact(merged)
        assertFalse("the unblock missed a spelling", BlockPolicy.isBlocked(c.contacts, bob))
        assertEquals(1_759_000_000_000, c.contacts.get(bobUrlSafe)!!.blockedAtMs)
        // A stale block from a third device does not re-block (merge.json stale_block_does_not_undo_newer_unblock).
        val stale = SyncContact(bob, blocked = true, blockedAtMs = 1_758_500_000_000)
        val again = SyncMerge.contact(s.contacts().first { BlockPolicy.normalizeKey(it.publicKey) == BlockPolicy.normalizeKey(bob) }, stale)
        s.putContact(again)
        assertFalse(BlockPolicy.isBlocked(c.contacts, bob))
    }

    private fun group(c: com.oshi.desktop.app.OshiClient) = c.groups.put(
        GroupDefinition(gid, "Team", GroupType.COLLABORATIVE, c.address,
            listOf(GroupMember(c.address, joinedAtUnixMillis = 1_000, isAdmin = true), GroupMember(bob, joinedAtUnixMillis = 1_000)),
            1_000, 2_000)
    )

    @Test
    fun `group mute export and apply`() {
        val c = fx.client("me", publish = false)
        val s = store(c)
        group(c)
        s.groups().single().let { assertNull("never muted: absent", it.muted); assertNull(it.mutedAtMs) }
        c.setGroupMuted(gid, true)
        val muted = s.groups().single()
        assertEquals(true, muted.muted)
        assertNotNull(muted.mutedAtMs)

        // A later unmute from another device wins and keeps its time.
        val later = muted.mutedAtMs!! + 60_000
        val merged = SyncMerge.group(muted, muted.copy(muted = false, mutedAtMs = later))
        s.putGroup(merged)
        assertFalse(c.isGroupMuted(gid))
        assertEquals(later, c.groups.mutedAt(gid))
        assertEquals(false, s.groups().single().muted)
        // A peer that predates the fields sends none: the local value stays.
        s.putGroup(merged.copy(muted = null, mutedAtMs = null))
        assertFalse(c.isGroupMuted(gid))
        assertEquals(later, c.groups.mutedAt(gid))
        // The group-update WIRE still never carries the mute (group-admin channel, not devsync).
        c.setGroupMuted(gid, true)
        assertFalse(com.oshi.desktop.group.GroupUpdateWire.encodeDefinition(c.groups.get(gid)!!).contains("\"isMuted\":true"))
    }
}
