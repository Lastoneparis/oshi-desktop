package com.oshi.desktop.group

import com.oshi.desktop.pairing.QrImageDecoder
import com.oshi.desktop.pairing.QrPng
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicGroupDirectoryTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Exactly what Android's `broadcastPublicGroupsViaMesh` writes. */
    private val android = """{"groupId":"6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b","groupName":"Lisbon riders","adminPublicKey":"QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWY=","memberCount":12,"avatar":null,"timestamp":816000000.5}"""

    @Test
    fun `an Android ad is heard, listed by name, and expires when no longer announced`() {
        val d = PublicGroupDirectory(ttlMs = 120_000)
        val ad = d.offer(android, "peer", 1_000)!!
        assertEquals("Lisbon riders", ad.name)
        assertEquals(12, ad.memberCount)
        assertEquals(GroupIdentity.canonicalGroupId("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b"), ad.groupId)
        assertNotNull(d.get("6F1C2A3B-4D5E-4F60-8A7B-9C0D1E2F3A4B"))
        assertEquals(1, d.list(100_000).size)
        assertEquals(0, d.list(1_000 + 120_001).size)
    }

    @Test
    fun `a malformed or hostile ad is refused or neutralised`() {
        val d = PublicGroupDirectory()
        assertNull(d.offer("not json", "p", 0))
        assertNull(d.offer("""{"groupId":"nope","groupName":"x","adminPublicKey":"k"}""", "p", 0))
        assertNull(d.offer("""{"groupId":"6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b","groupName":"   ","adminPublicKey":"k"}""", "p", 0))
        assertNull(d.offer("""{"groupId":"6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b","groupName":"x"}""", "p", 0))
        val evil = d.offer("""{"groupId":"6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b","groupName":"‮evil\nname${"x".repeat(300)}","adminPublicKey":"k","memberCount":-4}""", "p", 0)!!
        assertTrue(evil.name.none { it == '‮' || it == '\n' })
        assertEquals(PublicGroupDirectory.MAX_NAME, evil.name.length)
        assertEquals(1, evil.memberCount)
    }

    @Test
    fun `our own announcement has Android's shape and an Apple-epoch timestamp`() {
        val g = GroupDefinition(
            groupId = "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b",
            name = "Desk group",
            type = GroupType.PUBLIC,
            adminPublicKey = "admin",
            members = listOf(GroupMember(publicKey = "admin", joinedAtUnixMillis = 0, isAdmin = true), GroupMember(publicKey = "b", joinedAtUnixMillis = 0, isAdmin = false)),
            createdAtUnixMillis = 0,
            lastActivityUnixMillis = 0,
        )
        val o = JSONObject(PublicGroupDirectory.encode(g, 1_794_307_200_000L))
        assertEquals(setOf("groupId", "groupName", "adminPublicKey", "memberCount", "avatar", "timestamp"), o.keySet())
        assertEquals(2, o.getInt("memberCount"))
        assertEquals(816_000_000.0, o.getDouble("timestamp"), 0.001)
        assertTrue(o.isNull("avatar"))
        // And it reads back.
        assertEquals("Desk group", PublicGroupDirectory.parse(o.toString(), "me", 0)!!.name)
    }

    @Test
    fun `a saved group QR image scans back to the same invite`() {
        val link = GroupInvite.link("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b", "Café + amis/€", "QUJD+/=")
        val f = tmp.root.resolve("qr.png")
        QrPng.write(link, f)
        val read = QrImageDecoder.decode(f).getOrThrow()
        assertEquals(link, read)
        val inv = GroupInvite.parse(read)!!
        assertEquals("Café + amis/€", inv.name)
        assertEquals("QUJD+/=", inv.inviter)
    }
}
