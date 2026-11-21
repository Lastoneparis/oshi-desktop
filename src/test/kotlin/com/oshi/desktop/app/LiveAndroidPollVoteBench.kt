package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * __DESKTOP_ANDROID_POLL_VOTE_BENCH_2026_10_29__ The SHIPPED Android app votes, and this
 * client has to count it.
 *
 * [LiveAndroidPollBench] proved the outbound half — a poll card drawn on the phone from this
 * client's bytes. This proves the inbound half, which is the one with the rules in it: a
 * `📊POLL_VOTE📊` is SILENT (never a bubble), the voter is the authenticated sender and never
 * a field in the JSON, and the ballot must land in the tally rather than anywhere else.
 *
 * It sends a poll, then polls for a ballot. Tap an option on the phone while it waits.
 *
 * ## THIS BENCH FAILS TODAY, AND THE FAULT IS NOT HERE
 *
 * Run 2026-10-29 against OSHI Android 1.6.59: the poll drew correctly on the phone, **Alpha**
 * was tapped, the phone's own card moved to `Alpha 100% · 1 vote` — and nothing arrived here
 * in 240 s.
 *
 * `ChatViewModel.sendPollVote` -> `MessageRepository.sendPollBallot` (`:7545`) encrypts with
 * the LEGACY `cryptoManager.encryptMessage` and hands off to `dispatchControlPayload`
 * (`:7605`), whose three legs are the Nearby mesh, the cross-platform mesh, and
 * `vpsClient.sendMessage` — which resolves to `sendMessageViaIPFS`. There is no
 * `ControlLane.preferV2` branch in it. This client implements no IPFS lane by deliberate
 * decision (PARITY row 0.23), so the ballot cannot arrive. The same function carries
 * REACTIONS (`:1609`), so those cannot either.
 *
 * It is the identical defect row 0.18 records and fixed for receipts and typing. The remedy
 * is one lane on the Android side.
 *
 * **Kept and expected to fail on purpose.** Deleting it would remove the only thing that will
 * notice the day that branch is added; a red bench that names its cause is worth more than a
 * green suite that never asked.
 *
 * Gate: `OSHI_ANDROID_PEER=<the app's Identity Address>`.
 */
class LiveAndroidPollVoteBench {

    @Test
    fun theShippedAndroidAppVotesAndThisClientTalliesIt() {
        val peer = System.getenv("OSHI_ANDROID_PEER")
        Assume.assumeTrue("set OSHI_ANDROID_PEER=<android identity address>", !peer.isNullOrBlank())

        val dir = Files.createTempDirectory("oshi-android-vote").toFile()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = OshiClient(
            home = dir, secretStore = InMemorySecretStore(),
            displayName = "desk-vote", log = { logs += it },
        )
        try {
            var open = false
            for (attempt in 0 until 12) {
                runCatching { c.router.refreshConfig() }
                if (c.config.isEnabledCached()) { open = true; break }
                Thread.sleep(10_000)
            }
            assertTrue("v2 gate closed", open)
            assertTrue("bundle publish", c.router.publishBundleIfNeeded())

            val tag = System.currentTimeMillis().toString().takeLast(5)
            println("VOTE desktop address : ${c.address}")
            val outcome = c.sendPoll(peer!!, "Vote bench $tag — pick one", listOf("Alpha", "Beta"))
            assertEquals(OshiClient.SendOutcome.SENT, outcome)
            val poll = com.oshi.desktop.msg.PollPayload.decodePoll(c.messages.messages(peer).last().content)
            assertNotNull(poll)
            println("VOTE poll id         : ${poll!!.id}")
            println("VOTE >>> tap an option on the Android poll card now <<<")

            val rowsBefore = c.messages.messages(peer).size
            var voted = false
            val deadline = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < deadline) {
                runCatching { c.router.poll() }
                if (c.pollBallots.ballotsFor(poll.id).isNotEmpty()) { voted = true; break }
                Thread.sleep(3_000)
            }
            val ballots = c.pollBallots.ballotsFor(poll.id)
            println("VOTE ballots         : $ballots")
            val results = c.pollBallots.results(poll, c.address)
            println("VOTE counts          : ${results.counts}  voters=${results.voterCount}")
            assertTrue(
                "no POLL_VOTE arrived from the Android app in 240 s — logs:\n" +
                    logs.takeLast(20).joinToString("\n"),
                voted,
            )
            assertEquals("one voter", 1, results.voterCount)
            assertEquals("the sum of the counts must be that one ballot", 1, results.counts.sum())
            // SILENT: a ballot must never have become a message row.
            assertEquals(
                "a vote became a bubble",
                rowsBefore, c.messages.messages(peer).size,
            )
            // The voter is the authenticated sender, not a field in the payload.
            val voter = ballots.keys.single()
            assertEquals(
                "the ballot was filed under the wrong identity",
                com.oshi.desktop.store.PollTally.normalize(peer), voter,
            )
        } finally {
            runCatching { c.close() }
            dir.deleteRecursively()
        }
    }
}
