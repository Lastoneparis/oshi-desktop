package com.oshi.desktop.app

import com.oshi.desktop.msg.ReplyEnvelope
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * __GROUP_PARITY_2026_09_23__ Replies and the group picture on the PRODUCTION relay, three
 * throwaway desktop identities. Gated like [LiveGroupE2EBench]: `OSHI_LIVE_GROUP=1`.
 */
class LiveGroupParityBench {

    private fun live() = Assume.assumeTrue("set OSHI_LIVE_GROUP=1", System.getenv("OSHI_LIVE_GROUP") == "1")

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun client(name: String): OshiClient {
        val dir = Files.createTempDirectory("oshi-live-gparity-$name").toFile()
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name, log = { logs += "[$name] $it" })
        c.router.refreshConfig()
        assertTrue("$name: v2 gate closed", c.config.isEnabledCached())
        assertTrue("$name: bundle publish", c.router.publishBundleIfNeeded())
        runCatching { c.deviceMailbox.tick() }
        return c
    }

    private fun until(what: String, vararg cs: OshiClient, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            for (c in cs) {
                runCatching { c.deviceMailbox.tick() }
                runCatching { c.router.poll() }
            }
            Thread.sleep(700)
        }
        synchronized(logs) { logs.takeLast(40).forEach(::println) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun repliesAndPictureOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val a = client("a"); val b = client("b"); val c = client("c")
        val all = arrayOf(a, b, c)

        val g = a.createGroup("PARITY-$tag", listOf(b.address, c.address))
        val gid = g.groupId
        until("B and C learn the group", *all) { b.groups.get(gid) != null && c.groups.get(gid) != null }

        val t0 = System.currentTimeMillis()
        a.sendGroupText(gid, "question-$tag")
        until("A's text reaches B", *all) { b.messages.messages(gid).any { it.content == "question-$tag" } }
        val original = b.messages.messages(gid).first { it.content == "question-$tag" }

        val r = b.sendGroupText(
            gid, "answer-$tag",
            ReplyEnvelope.Quote(original.id, a.address, "question-$tag", original.sentAtMs),
        )
        assertEquals(2, r.sent)
        until("B's reply reaches A and C with its quote", *all) {
            listOf(a, c).all { m ->
                m.messages.messages(gid).any {
                    val u = ReplyEnvelope.unwrap(it.content)
                    u?.content == "answer-$tag" && u.quote?.originalMessageId.equals(original.id, true)
                }
            }
        }
        println("[parity] group reply delivered to both members in ${System.currentTimeMillis() - t0} ms")

        // 1:1 reply over the same production relay.
        a.send(b.address, "direct-$tag")
        until("direct text reaches B", *all) { b.messages.messages(a.address).any { it.content == "direct-$tag" } }
        val d = b.messages.messages(a.address).first { it.content == "direct-$tag" }
        b.send(a.address, "direct-answer-$tag", ReplyEnvelope.Quote(d.id, a.address, "direct-$tag", d.sentAtMs))
        until("direct reply reaches A (it used to be dropped)", *all) {
            a.messages.messages(b.address).any { ReplyEnvelope.unwrap(it.content)?.content == "direct-answer-$tag" }
        }

        // Picture: C (plain member of a collaborative group) sets it, A and B receive it.
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val set = c.setGroupPicture(gid, png)
        assertNotNull(set)
        until("picture reaches A and B", *all) {
            a.groups.get(gid)?.groupPictureBase64 == set!!.groupPictureBase64 &&
                b.groups.get(gid)?.groupPictureBase64 == set.groupPictureBase64
        }
        println("[parity] picture ${set!!.groupPictureBase64!!.length} b64 chars delivered to both members")

        // Description + pin (C, a plain member of a collaborative group, has the permission on iOS too).
        c.setGroupDescription(gid, "desc-$tag")
        val pinned = c.setGroupPin(gid, original.id)!!
        until("description and pin reach A and B", *all) {
            listOf(a, b).all { m -> m.groups.get(gid)?.description == "desc-$tag" && m.groups.get(gid)?.pinnedMessageId.equals(pinned.pinnedMessageId, true) }
        }
        println("[parity] description + pin delivered")

        // Invite link: a FOURTH identity joins through A's link; A (admin) admits it for everyone.
        val dd = client("d")
        val all4 = arrayOf(a, b, c, dd)
        val (outcome, _) = dd.joinGroupFromInvite(a.groupInviteLink(gid)!!)
        assertEquals(OshiClient.JoinOutcome.REQUESTED, outcome)
        val tJoin = System.currentTimeMillis()
        until("D is admitted and B, C hear about it", *all4) {
            dd.groups.get(gid)?.isMember(a.address) == true && dd.groups.get(gid)?.members?.size == 4 &&
                b.groups.get(gid)?.isMember(dd.address) == true && c.groups.get(gid)?.isMember(dd.address) == true
        }
        println("[parity] invite join completed on all members in ${System.currentTimeMillis() - tJoin} ms")
        a.sendGroupText(gid, "welcome-$tag")
        until("D receives group text as a member", *all4) { dd.messages.messages(gid).any { it.content == "welcome-$tag" } }

        // Forward (group message → 1:1) and delete-for-me.
        assertEquals(OshiClient.SendOutcome.SENT, b.forwardMessage(gid, original.id, c.address))
        until("forward reaches C", *all4) {
            c.history(b.address).any { ReplyEnvelope.unwrap(it.content)?.forwardedFrom != null }
        }
        assertTrue(a.deleteMessageForMe(gid, original.id))
        assertTrue(a.history(gid).none { it.id.equals(original.id, true) })
        println("[parity] forward delivered; delete-for-me local")
    }
}
