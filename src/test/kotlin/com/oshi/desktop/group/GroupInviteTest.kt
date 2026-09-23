package com.oshi.desktop.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __GROUP_PARITY_2026_09_23__ invite links, against iOS's emitter and its four accepted forms. */
class GroupInviteTest {

    private val gid = "3F2A9C10-1B2C-4D5E-8F90-ABCDEF012345"
    private val key = "q+/Abc=+xyz/0123456789ABCDEFGHIJKLMNOPQRS="

    @Test
    fun `the link is iOS's universal link with an uppercase gid and a key that survives`() {
        val link = GroupInvite.link(gid.lowercase(), "Weekend hike & co", key)
        assertTrue(link.startsWith("https://oshi-messenger.com/group?gid=$gid&name="))
        // iOS URLComponents leaves + / = of a base64 key as they are.
        assertTrue(link.endsWith("&inviter=$key"))
        val back = GroupInvite.parse(link)!!
        assertEquals(gid, back.groupId)
        assertEquals("Weekend hike & co", back.name)
        assertEquals(key, back.inviter)
    }

    @Test
    fun `iOS's custom scheme and both legacy forms are accepted`() {
        assertEquals(key, GroupInvite.parse("oshi://group/join?gid=$gid&name=Crew&inviter=$key")!!.inviter)
        assertEquals(gid, GroupInvite.parse("oshi://group/$gid")!!.groupId)
        assertNull(GroupInvite.parse("oshi://group/$gid")!!.inviter)
        assertEquals(gid, GroupInvite.parse("https://oshi-messenger.com/group/$gid")!!.groupId)
    }

    @Test
    fun `a link inside a share message is found, and a percent-encoded key decodes`() {
        val shared = "Join my OSHI group: https://oshi-messenger.com/group?gid=$gid&name=A%20B&inviter=q%2B%2FAbc%3D"
        val inv = GroupInvite.parse(shared)!!
        assertEquals("A B", inv.name)
        assertEquals("q+/Abc=", inv.inviter)
    }

    @Test
    fun `no gid, a non-UUID gid, another host or a contact code is not an invite`() {
        assertNull(GroupInvite.parse("oshi://group/join"))
        assertNull(GroupInvite.parse("https://oshi-messenger.com/group?gid=nope"))
        assertNull(GroupInvite.parse("https://evil.example/group?gid=$gid"))
        assertNull(GroupInvite.parse("oshi://add?key=$key"))
        assertNull(GroupInvite.parse(null))
    }
}
