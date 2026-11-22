package com.oshi.desktop.app

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * A GROUP created on the desktop, with a REAL Android client in it — PARITY row 0.17.
 *
 * `LiveAndroidWireInteropBench` already covers the wire: an identity that *"plays an
 * Android 1.x phone"*, with bytes built exactly as `GroupManager.kt` builds them. That is a
 * simulation of the phone, written from the phone's source. It is strong and it is not the
 * same claim as the shipped app parsing what this client actually emits.
 *
 * This runs against the app. The desktop creates the group, adds the Android identity, and
 * sends; whether the Android side shows it is read off the device's own view hierarchy,
 * outside this test.
 *
 * Gate: `OSHI_GROUP_HOME` (a persistent identity — the Android side must already know it)
 * and `OSHI_ANDROID_PEER`.
 */
class LiveAndroidGroupBench {

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    /** See `LiveAndroidLocationBench`: an in-memory store cannot carry a vault across JVMs. */
    private class FileSecretStore(private val file: File) : com.oshi.desktop.store.SecretStore {
        override val id = "file (TEST ONLY): ${file.name}"
        private fun load(): MutableMap<String, String> =
            if (!file.isFile) mutableMapOf()
            else file.readLines().filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
        private fun save(m: Map<String, String>) {
            file.parentFile?.mkdirs(); file.writeText(m.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
        override fun get(account: String): ByteArray? =
            load()[account]?.let { java.util.Base64.getDecoder().decode(it) }
        override fun put(account: String, secret: ByteArray) {
            val m = load(); m[account] = java.util.Base64.getEncoder().encodeToString(secret); save(m)
        }
        override fun delete(account: String) { val m = load(); m.remove(account); save(m) }
    }

    @Test
    fun aDesktopGroupReachesTheAndroidApp() {
        val h = System.getenv("OSHI_GROUP_HOME")
        val peerEnv = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_GROUP_HOME and OSHI_ANDROID_PEER", !h.isNullOrBlank() && !peerEnv.isNullOrBlank())
        val peer: String = peerEnv!!
        val home = File(h!!).apply { mkdirs() }

        val c = OshiClient(home = home, secretStore = FileSecretStore(File(home, "test-secrets.txt")),
            displayName = "desk-group", log = { logs += it })
        var open = false
        for (attempt in 0 until 3) {
            runCatching { c.router.refreshConfig() }
            if (c.config.isEnabledCached()) { open = true; break }
            Thread.sleep(1_000)
        }
        assertTrue("v2 gate closed", open)
        assertTrue("bundle publish", c.router.publishBundleIfNeeded())
        println("LIVE_AGRP desktop address: ${c.address}")

        // Phase 2: the Android side has replied in a group created earlier; find it.
        val awaitIn = System.getenv("OSHI_GROUP_AWAIT")
        if (!awaitIn.isNullOrBlank()) {
            // BY NAME, not by id. The first run of this passed the id as the LOG printed
            // it — `take(16)` with an ellipsis — so the canonical id was a prefix, the
            // conversation key did not exist, and the rows came back empty for a group
            // that was perfectly intact. A truncated identifier is not an identifier.
            val group = c.groups.all().firstOrNull { it.name == awaitIn }
                ?: error("no group named '$awaitIn'; have: " + c.groups.all().map { it.name })
            val gid = com.oshi.desktop.group.GroupIdentity.canonicalGroupId(group.groupId)
            val deadline = System.currentTimeMillis() + 180_000
            var row: com.oshi.desktop.store.Message? = null
            while (System.currentTimeMillis() < deadline && row == null) {
                runCatching { c.router.poll() }
                row = c.messages.messages(gid).firstOrNull {
                    !it.fromMe && it.content.orEmpty().contains("ANDGRP-REPLY")
                }
                Thread.sleep(1_500)
            }
            if (row == null) println("LIVE_AGRP rows: " + c.messages.messages(gid).map { "${it.fromMe}:${it.content?.take(30)}" })
            assertTrue("no group message from the Android app arrived", row != null)
            println("LIVE_AGRP OK (inbound) — from the Android app: ${row!!.content?.take(60)}")
            return
        }

        val tag = java.util.UUID.randomUUID().toString().take(6)
        val g = c.createGroup("DeskGrp-$tag", listOf(peer))
        println("LIVE_AGRP group created: ${g.groupId.take(16)}… name=DeskGrp-$tag")

        // Let the definition reach the phone before the content: the app has to know the
        // group exists to file a message into it.
        repeat(8) { runCatching { c.router.poll() }; Thread.sleep(1_000) }

        val canary = "AGRP-$tag"
        val report = c.sendGroupText(g.groupId, canary)
        println("LIVE_AGRP sendGroupText: sent=${report.sent} unreachable=${report.unreachable.size} delivered=${report.delivered}")
        assertTrue("the group text reached no member", report.sent > 0)
        assertTrue("the Android member was unreachable", report.unreachable.isEmpty())
        repeat(10) { runCatching { c.router.poll() }; Thread.sleep(1_000) }
        println("LIVE_AGRP CANARY=$canary GROUPNAME=DeskGrp-$tag")
    }
}
