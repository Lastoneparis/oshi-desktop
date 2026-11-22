package com.oshi.desktop.app

import com.oshi.desktop.group.MentionWire
import com.oshi.desktop.store.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files

/**
 * __MENTIONS_2026_09_23__ `@name` over the PRODUCTION relay with three throwaway desktop accounts:
 * A mentions B in a group of three; B and C both store the prose body and the admitted target.
 *
 *     OSHI_LIVE_GROUP=1 ./gradlew test --tests 'com.oshi.desktop.app.LiveMentionBench'
 */
class LiveMentionBench {

    private fun live() = Assume.assumeTrue("set OSHI_LIVE_GROUP=1", System.getenv("OSHI_LIVE_GROUP") == "1")

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun client(name: String): OshiClient {
        val dir = Files.createTempDirectory("oshi-live-mention-$name").toFile()
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
    fun mentionOnProduction() {
        live()
        val tag = java.util.UUID.randomUUID().toString().take(8)
        val a = client("a"); val b = client("b"); val c = client("c")
        val all = arrayOf(a, b, c)
        val g = a.createGroup("MENTION-$tag", listOf(b.address, c.address))
        val gid = g.groupId
        until("B and C learn the group", *all) { b.groups.get(gid) != null && c.groups.get(gid) != null }

        val bee = MentionWire.Mention(b.address, "Bee")
        val text = "@Bee please look $tag"
        val t0 = System.currentTimeMillis()
        val r = a.sendGroupText(gid, text, mentions = listOf(bee))
        assertEquals(2, r.sent)
        until("the mention reaches B and C", *all) {
            listOf(b, c).all { m -> m.messages.messages(gid).any { it.content == text && it.mentions == listOf(bee) } }
        }
        println("[live-mention] B and C stored '$text' with the admitted @Bee in ${System.currentTimeMillis() - t0} ms")
        listOf(a, b, c).forEach { runCatching { it.close() } }
    }
}
