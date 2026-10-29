package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_ANDROID_POLL_BENCH_2026_10_29__ This client sends a POLL and the SHIPPED Android
 * app has to render it.
 *
 * The poll codec written today is a THIRD implementation — Android's `PollMessage.kt` could
 * not be compiled here because its wire entry points reach `OSHIControlPayload`, which is 954
 * lines of `Context` and `R`. The mitigation was golden byte vectors hand-written from both
 * shipped sources, which is the right mitigation and still not the shipped decoder answering.
 *
 * This is the shipped decoder answering: a poll card on the phone's screen means the JSON
 * this client emits — keys `v`, `id`, `q`, `opts`, `multi`, `anon`, `at`, `by`, with `at` in
 * UNIX seconds — is what `PollMessage.fromMessageString` parses.
 *
 * Gate: `OSHI_ANDROID_PEER=<the app's Identity Address>`.
 */
class LiveAndroidPollBench {

    @Test
    fun thisClientsPollRendersOnTheShippedAndroidApp() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-poll").toFile()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = dir, secretStore = InMemorySecretStore(),
            displayName = "desk-poll", log = { logs += it },
        )
        try {
            var open = false
            for (attempt in 0 until 12) {
                runCatching { c.router.refreshConfig() }
                if (c.config.isEnabledCached()) { open = true; break }
                println("POLL gate attempt ${attempt + 1}")
                Thread.sleep(10_000)
            }
            assertTrue("v2 gate closed", open)
            assertTrue("bundle publish", c.router.publishBundleIfNeeded())

            val tag = System.currentTimeMillis().toString().takeLast(5)
            val question = "Desktop poll $tag — pizza or sushi?"
            println("POLL desktop address : ${c.address}")
            println("POLL question        : $question")

            val outcome = c.sendPoll(peer!!, question, listOf("Pizza", "Sushi", "Either"))
            println("POLL send outcome    : $outcome")
            assertNotNull("sanitize refused a valid poll", outcome)
            assertEquals(OshiClient.SendOutcome.SENT, outcome)

            val row = c.messages.messages(peer).last()
            val decoded = com.oshi.desktop.msg.PollPayload.decodePoll(row.content)
            assertNotNull("our own row must decode as a poll", decoded)
            println("POLL id              : ${decoded!!.id}")
            println("POLL >>> now read the Android screen for a poll card <<<")
            Thread.sleep(25_000)
        } finally {
            runCatching { c.close() }
            dir.deleteRecursively()
        }
    }
}
