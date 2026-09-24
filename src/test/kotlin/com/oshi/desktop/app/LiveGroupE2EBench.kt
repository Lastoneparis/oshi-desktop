package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * __GROUP_E2E_V2_2026_09_23__ `docs/GROUP_E2E_V2_SPEC.md` §9 interop round, Desktop↔Desktop,
 * against the PRODUCTION relay with throwaway identities.
 *
 * Gate: `OSHI_LIVE_GROUP=1` (optional `OSHI_SERVER`). Writes the test identities and canaries to
 * `OSHI_LIVE_GROUP_OUT` (a file) so the server can be checked read-only afterwards for anything
 * readable those identities left behind.
 */
class LiveGroupE2EBench {

    private fun live() = Assume.assumeTrue("set OSHI_LIVE_GROUP=1", System.getenv("OSHI_LIVE_GROUP") == "1")

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun client(name: String): OshiClient {
        val dir = Files.createTempDirectory("oshi-live-group-$name").toFile()
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name,
            log = { logs += "[$name] $it" })
        c.router.refreshConfig()
        assertTrue("$name: v2 gate closed", c.config.isEnabledCached())
        assertTrue("$name: bundle publish", c.router.publishBundleIfNeeded())
        runCatching { c.deviceMailbox.tick() }
        return c
    }

    private fun pump(vararg cs: OshiClient, rounds: Int = 3) {
        repeat(rounds) {
            for (c in cs) {
                runCatching { c.deviceMailbox.tick() }
                runCatching { c.router.poll() }.onFailure { logs += "poll: ${it.message}" }
            }
            Thread.sleep(700)
        }
    }

    private fun until(what: String, vararg cs: OshiClient, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            pump(*cs, rounds = 1)
        }
        synchronized(logs) { logs.takeLast(60).forEach(::println) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun groupRoundOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val canary = "CANARY-GRP-$tag"
        val a = client("a"); val b = client("b"); val cc = client("c"); val d = client("d")
        val all = arrayOf(a, b, cc, d)
        System.getenv("OSHI_LIVE_GROUP_OUT")?.let { out ->
            File(out).writeText(
                "startMs=${System.currentTimeMillis()}\ncanary=$canary\n" +
                    all.joinToString("\n") { "key=${it.address}" } + "\n"
            )
        }
        println("device mode: a=${a.deviceMailbox.flagOn}")

        // create
        val g = a.createGroup("$canary-name", listOf(b.address, cc.address))
        val gid = g.groupId
        until("B and C learn the group", *all) { b.groups.get(gid) != null && cc.groups.get(gid) != null }
        assertEquals(1, b.groups.get(gid)!!.stateVersion)

        // text
        val r1 = a.sendGroupText(gid, "$canary-text")
        assertEquals(2, r1.sent); assertTrue(r1.unreachable.isEmpty())
        until("text reaches B and C", *all) {
            b.messages.messages(gid).any { it.content == "$canary-text" } && cc.messages.messages(gid).any { it.content == "$canary-text" }
        }
        val msgId = r1.messageId!!

        // reply-ish text from B
        b.sendGroupText(gid, "$canary-from-b")
        until("B's text reaches A", *all) { a.messages.messages(gid).any { it.content == "$canary-from-b" } }

        // reaction (A on B's message)
        val bMsg = a.messages.messages(gid).first { it.content == "$canary-from-b" }
        assertNotNull(a.sendGroupReaction(gid, bMsg.id, "✅"))
        until("reaction reaches B", *all) { b.messages.message(gid, bMsg.id)?.reactions?.isNotEmpty() == true }

        // edit + delete
        a.editGroupMessage(gid, msgId, "$canary-edited")
        until("edit reaches C", *all) { cc.messages.messages(gid).any { it.content == "$canary-edited" } }
        a.deleteGroupMessage(gid, msgId)
        until("delete reaches B", *all) { b.messages.messages(gid).firstOrNull { it.id.equals(msgId, true) }?.isDeletedForEveryone == true }

        // media
        val f = File(Files.createTempDirectory("oshi-live-media").toFile(), "canary-$tag.txt")
        f.writeText("$canary-media-bytes " + "x".repeat(4000))
        val rm = a.sendGroupFile(gid, f, caption = "$canary-caption")
        assertNotNull(rm); assertEquals(2, rm!!.sent)
        until("media reaches B with bytes", *all) {
            b.messages.messages(gid).any { it.mediaRef != null && it.content == "$canary-caption" }
        }
        val got = b.messages.messages(gid).first { it.content == "$canary-caption" }
        assertTrue(String(b.mediaVault.readBytes(File(got.mediaRef!!), 1 shl 20)).startsWith("$canary-media-bytes"))

        // rename
        a.renameGroup(gid, "$canary-renamed")
        until("rename reaches B and C", *all) { b.groups.get(gid)?.name == "$canary-renamed" && cc.groups.get(gid)?.name == "$canary-renamed" }

        // add D
        a.addGroupMember(gid, d.address)
        until("D learns the group; B sees D", *all) { d.groups.get(gid) != null && b.groups.get(gid)?.isMember(d.address) == true }

        // remove C
        a.removeGroupMember(gid, cc.address)
        until("C is out; B sees C evicted", *all) { cc.groups.get(gid) == null && b.groups.get(gid)?.isEvicted(cc.address) == true }

        // B leaves
        assertTrue(b.leaveGroup(gid))
        until("A and D see B gone", *all) { a.groups.get(gid)?.isMember(b.address) == false && d.groups.get(gid)?.isMember(b.address) == false }
        assertNull(b.groups.get(gid))

        // after: only D is a recipient
        val r2 = a.sendGroupText(gid, "$canary-after")
        assertEquals(1, r2.recipients); assertEquals(1, r2.sent)
        until("D gets the last text", *all) { d.messages.messages(gid).any { it.content == "$canary-after" } }
        pump(*all)
        assertFalse(cc.messages.messages(gid).any { it.content == "$canary-after" })
        assertFalse(b.messages.messages(gid).any { it.content == "$canary-after" })
        println("LIVE GROUP ROUND OK gid=$gid canary=$canary")
        all.forEach { runCatching { it.close() } }
    }
}
