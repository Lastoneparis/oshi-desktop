package com.oshi.desktop.net

import com.oshi.desktop.DesktopEnvelope
import com.oshi.desktop.DesktopIdentity
import com.oshi.desktop.DesktopV2Signer
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_FANOUT_2026_11_24__ The desktop group fan-out (one request per member) did not retry a
 * relay 429. Reproduced on a local copy of the production relay (bucket 60, refill 10/s): a
 * 200-member group at 20 ms RTT reached 111 members and silently skipped 89, shown as "Sent".
 */
class GroupFanoutRateLimitTest {

    private val relay = FakeRelay()
    private val identity = DesktopIdentity.generate()
    private val http = V2Http(DesktopV2Signer(identity), relay.baseUrl)
    private val sleeps = ArrayList<Long>()
    private val client = V2MessagesClient(http) { sleeps += it }

    @After fun tearDown() = relay.close()

    private fun envelope(id: String) = DesktopEnvelope(
        msgId = id, from = identity.userKey, to = "PEER+key/with=padding",
        header = ByteArray(40) { it.toByte() }, ciphertext = ByteArray(32) { (it * 3).toByte() }, ts = 1787601120588L,
    )

    @Test
    fun `a 429 is waited out with Retry-After, then the send succeeds`() {
        var n = 0
        relay.responder = { if (++n <= 2) 429 to """{"error":"rate-limited"}""" else 200 to """{"accepted":["m1"]}""" }
        relay.responseHeaders = { if (n <= 2) mapOf("Retry-After" to "2") else emptyMap() }
        assertEquals(listOf("m1"), client.send(envelope("m1")))
        assertEquals(3, relay.requests().size)
        assertEquals(listOf(2000L, 2000L), sleeps)
        assertFalse(client.lastSendRateLimited)
        assertTrue("every attempt is signed", relay.requests().all { it.signature != null })
    }

    @Test
    fun `without Retry-After (relay before the patch) it backs off 1, 2, 4 s`() {
        var n = 0
        relay.responder = { if (++n <= 3) 429 to "{}" else 200 to """{"accepted":["m1"]}""" }
        assertEquals(listOf("m1"), client.send(envelope("m1")))
        assertEquals(listOf(1000L, 2000L, 4000L), sleeps)
    }

    @Test
    fun `a relay that keeps refusing is given up after the budget and reported`() {
        relay.responder = { 429 to "{}" }
        relay.responseHeaders = { mapOf("Retry-After" to "30") }
        assertEquals(emptyList<String>(), client.send(envelope("m1")))
        assertTrue(client.lastSendRateLimited)
        assertEquals(listOf(30_000L, 30_000L, 30_000L, 30_000L), sleeps)
    }

    @Test
    fun `the device batch waits out a 429 too`() {
        var n = 0
        relay.responder = { if (++n == 1) 429 to "{}" else 200 to """{"accepted":["m1"]}""" }
        relay.responseHeaders = { mapOf("Retry-After" to "1") }
        val r = client.sendBatch(listOf(envelope("m1")))
        assertEquals(200, r.code)
        assertEquals(listOf(1000L), sleeps)
    }

    @Test
    fun `other errors are not retried (unchanged)`() {
        relay.responder = { 500 to "nope" }
        assertEquals(emptyList<String>(), client.send(envelope("m1")))
        assertEquals(1, relay.requests().size)
        assertFalse(client.lastSendRateLimited)
    }

    @Test
    fun `policy caps and parsing`() {
        assertEquals(RateLimitBackoff.MAX_SINGLE_WAIT_MS, RateLimitBackoff.nextDelayMs("999", 0, 0))
        assertEquals(1000L, RateLimitBackoff.nextDelayMs("0", 0, 0))
        assertNull(RateLimitBackoff.nextDelayMs("30", 0, RateLimitBackoff.MAX_RATE_LIMIT_WAIT_MS))
        assertNull(RateLimitBackoff.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun `the group fan-out reports rate-limited members and stops instead of skipping silently`() {
        val src = File("src/main/kotlin/com/oshi/desktop/app/OshiClient.kt").readText()
        val fan = src.substring(src.indexOf("private fun groupFanOut("))
        assertTrue(fan.contains("router.lastSendRateLimited"))
        assertTrue(fan.contains("if (rateLimited.isNotEmpty()) { rateLimited += member; continue }"))
        assertTrue(fan.contains("rateLimited = rateLimited, transient = transient)"))   // __GROUP_RESEND_2026_11_25__
    }
}
