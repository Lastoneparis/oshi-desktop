package com.oshi.desktop.scheduled

import com.oshi.desktop.msg.WireClock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two on-disk shapes of PARITY.md row 0.25, and the epoch that separates them.
 *
 * The epoch is the assertion that matters and it is not visible in the Swift source:
 * `ScheduledMessageManager.swift:18` declares `let scheduledTime: Date` and `:270` persists
 * with a bare `JSONEncoder()`, which is `.deferredToDate` — a `Double` of seconds since
 * 2001-01-01. Android is `Long` millis (`ScheduledMessageManager.kt:78`). The reference
 * value below is Android's own published constant, asserted from
 * `SettingsSyncWireFormatTest.apple reference time is offset from unix epoch by 978307200 seconds`:
 * 2001-01-01T00:00:00Z is 0.0 in Apple's frame.
 */
class ScheduledMessageWireFormatTest {

    private val APPLE_ZERO_UNIX_MS = 978_307_200_000L
    private val RECIPIENT = "kr/6aAArDrl91TOXf/bjv2u1SiRf+H8gIi8BBgPYpi0="

    private fun sample(scheduledMs: Long = 1_786_961_472_000L) = ScheduledMessage(
        id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
        recipient = RECIPIENT,
        content = "Thinking of you",
        scheduledAtMs = scheduledMs,
        createdAtMs = scheduledMs - 3_600_000L,
        tone = ScheduledMessage.Tone.ROMANTIC,
        aiGenerated = true,
    )

    // ────────────────────────────────────── the epoch

    /** Android's published vector, restated against the one converter this client has. */
    @Test
    fun `apple reference zero is 2001-01-01 and the offset is 978307200 seconds`() {
        assertEquals(0.0, WireClock.toAppleSeconds(APPLE_ZERO_UNIX_MS), 0.0001)
        assertEquals(978_307_200.0, WireClock.APPLE_EPOCH_OFFSET_SECONDS, 0.0)
    }

    /**
     * iOS `scheduledTime` is Apple-epoch SECONDS. Writing Unix millis into that field is
     * the failure Android documented as landing "somewhere in the year 32000"
     * (`MultiDeviceSyncManager.kt:120-126`).
     */
    @Test
    fun `the ios shape carries apple epoch seconds and not unix millis`() {
        val m = sample()
        val o = ScheduledMessage.toIosJson(m)
        val ts = o.getDouble("scheduledTime")

        assertEquals(m.scheduledAtMs / 1000.0 - 978_307_200.0, ts, 0.001)
        assertTrue("an Apple-epoch value for a 2026 date is ~8e8, not ~1.7e12: $ts", ts < 1e10)
        assertFalse("the raw millis must not appear anywhere in the record",
            o.toString().contains(m.scheduledAtMs.toString()))
    }

    /** Android is the other epoch: `Long`, Unix millis, compared to `currentTimeMillis()`. */
    @Test
    fun `the android shape carries unix millis`() {
        val m = sample()
        val o = ScheduledMessage.toAndroidJson(m)
        assertEquals(m.scheduledAtMs, o.getLong("scheduledTime"))
        assertTrue(o.getLong("scheduledTime") > 1e12)
    }

    /**
     * The two files have the same NAME (`scheduled_messages.json` on both) and are mutually
     * unreadable. Reading one with the other's decoder produces a timestamp roughly 55 000
     * years out — WireClock's magnitude guard catches it, and that is what makes the
     * mistake loud instead of silent.
     */
    @Test
    fun `reading an android file with the ios decoder is caught by the magnitude guard`() {
        val androidRecord = ScheduledMessage.toAndroidJson(sample())
        val misread = JSONObject()
            .put("id", androidRecord.getString("id"))
            .put("conversationId", androidRecord.getString("recipientPublicKey"))
            .put("scheduledTime", androidRecord.getLong("scheduledTime").toDouble())
            .put("generatedContent", "x")

        assertTrue(WireClock.looksLikeUnixMillis(androidRecord.getLong("scheduledTime").toDouble()))
        assertNull(
            "a scheduled message with an unusable delivery time is not a scheduled message",
            ScheduledMessage.fromIosJson(misread)
        )
    }

    /** …and the reverse: an iOS file read as Android millis lands in 1970. */
    @Test
    fun `reading an ios file with the android decoder lands in 1970`() {
        val ios = ScheduledMessage.toIosJson(sample())
        val asMillis = ios.getDouble("scheduledTime").toLong()
        assertTrue("~8e8 ms is 1970-01-11, not 2026: $asMillis", asMillis < 1_000_000_000L)
    }

    // ────────────────────────────────────── round trips

    @Test
    fun `the ios shape round trips`() {
        val m = sample()
        val back = ScheduledMessage.fromIosJson(ScheduledMessage.toIosJson(m))!!
        assertEquals(m.recipient, back.recipient)
        assertEquals(m.content, back.content)
        assertEquals(m.scheduledAtMs, back.scheduledAtMs)
        assertEquals(m.createdAtMs, back.createdAtMs)
        assertEquals(m.tone, back.tone)
        assertEquals(m.aiGenerated, back.aiGenerated)
        assertEquals(m.status, back.status)
    }

    @Test
    fun `the android shape round trips`() {
        val m = sample().copy(isGroup = true)
        assertEquals(m, ScheduledMessage.fromAndroidJson(ScheduledMessage.toAndroidJson(m)))
    }

    // ────────────────────────────────────── the case difference

    /** Swift raw values are lower-case (`swift:26,29`); Gson emits the enum NAME (`.kt:72`). */
    @Test
    fun `status and tone differ only in case between the two platforms`() {
        val m = sample().copy(status = ScheduledMessage.Status.CANCELLED)
        assertEquals("cancelled", ScheduledMessage.toIosJson(m).getString("status"))
        assertEquals("CANCELLED", ScheduledMessage.toAndroidJson(m).getString("status"))
        assertEquals("romantic", ScheduledMessage.toIosJson(m).getString("relationshipTone"))
        assertEquals("ROMANTIC", ScheduledMessage.toAndroidJson(m).getString("tone"))
    }

    @Test
    fun `either spelling decodes`() {
        assertEquals(ScheduledMessage.Status.SENT, ScheduledMessage.Status.fromWire("sent"))
        assertEquals(ScheduledMessage.Status.SENT, ScheduledMessage.Status.fromWire("SENT"))
        assertEquals(ScheduledMessage.Tone.FAMILY, ScheduledMessage.Tone.fromWire("FAMILY"))
        assertEquals(ScheduledMessage.Tone.FAMILY, ScheduledMessage.Tone.fromWire("family"))
        assertNull("an unknown status is not silently PENDING at the enum level",
            ScheduledMessage.Status.fromWire("exploded"))
    }

    /** The four statuses are the same four on both platforms (`swift:26`, `.kt:72`). */
    @Test
    fun `there are exactly four statuses`() {
        assertEquals(
            listOf("pending", "sent", "cancelled", "failed"),
            ScheduledMessage.Status.entries.map { it.iosWire }
        )
        assertEquals(
            listOf("PENDING", "SENT", "CANCELLED", "FAILED"),
            ScheduledMessage.Status.entries.map { it.androidWire }
        )
    }

    /** Five tones, same five, same order (`swift:29`, `.kt:24-29`). */
    @Test
    fun `there are exactly five relationship tones`() {
        assertEquals(
            listOf("romantic", "friendly", "family", "casual", "formal"),
            ScheduledMessage.Tone.entries.map { it.iosWire }
        )
    }

    // ────────────────────────────────────── the effective-content override

    /**
     * `effectiveContent = userEditedContent ?? generatedContent` (`swift:57-59`) is what
     * iOS actually sends. A decoder that read only `generatedContent` would send the AI's
     * draft instead of the user's edit of it.
     */
    @Test
    fun `a user edit overrides the generated content`() {
        val o = JSONObject()
            .put("id", "A").put("conversationId", RECIPIENT).put("conversationName", "")
            .put("scheduledTime", 800_000_000.0)
            .put("generatedContent", "AI DRAFT")
            .put("userEditedContent", "what the user actually wrote")
            .put("status", "pending").put("createdAt", 799_000_000.0)
        assertEquals("what the user actually wrote", ScheduledMessage.fromIosJson(o)!!.content)
    }

    // ────────────────────────────────────── the keyNotFound hazard

    /**
     * `conversationName` is a NON-OPTIONAL `let String` on iOS (`swift:17`), and
     * `JSONDecoder` throws `keyNotFound` for a missing non-optional. iOS decodes the whole
     * file in one `try?` (`swift:276-279`), so that throw returns nil for the ENTIRE array
     * — one abbreviated record costs every scheduled message the user has. Same shape as
     * `partial-dto-in-one-payload-empties-the-whole-list`.
     */
    @Test
    fun `the ios encoder always emits conversationName even when empty`() {
        val o = ScheduledMessage.toIosJson(sample())
        assertTrue("a missing key here costs the WHOLE array on iOS", o.has("conversationName"))
        assertEquals("", o.getString("conversationName"))
        assertEquals("Alice", ScheduledMessage.toIosJson(sample(), "Alice").getString("conversationName"))
    }

    /** Every field iOS declares non-optional is present. */
    @Test
    fun `the ios record carries every field the swift struct declares`() {
        val o = ScheduledMessage.toIosJson(sample())
        for (k in listOf("id", "conversationId", "conversationName", "scheduledTime",
                "generatedContent", "relationshipTone", "wasAIGenerated", "status", "createdAt")) {
            assertTrue("missing $k", o.has(k))
        }
    }

    /** iOS ids are `UUID`, which `Codable` writes upper-case; Android's are lower-case. */
    @Test
    fun `the ios id is upper case and the android id is not rewritten`() {
        val m = sample()
        assertEquals(m.id.uppercase(), ScheduledMessage.toIosJson(m).getString("id"))
        assertEquals(m.id, ScheduledMessage.toAndroidJson(m).getString("id"))
        assertFalse(m.id == m.id.uppercase())
    }

    // ────────────────────────────────────── refusals

    @Test
    fun `a record with no scheduled time is unusable in both shapes`() {
        assertNull(ScheduledMessage.fromIosJson(
            JSONObject().put("id", "A").put("conversationId", RECIPIENT).put("generatedContent", "x")))
        assertNull(ScheduledMessage.fromAndroidJson(
            JSONObject().put("id", "A").put("recipientPublicKey", RECIPIENT).put("content", "x")))
    }

    @Test
    fun `a record with no recipient is unusable`() {
        assertNull(ScheduledMessage.fromIosJson(
            JSONObject().put("id", "A").put("scheduledTime", 800_000_000.0)))
        assertNotNull(ScheduledMessage.fromIosJson(
            JSONObject().put("id", "A").put("conversationId", RECIPIENT)
                .put("scheduledTime", 800_000_000.0).put("generatedContent", "x")))
    }

    @Test
    fun `a blank id or recipient is refused at construction`() {
        assertTrue(runCatching {
            ScheduledMessage(id = "", recipient = RECIPIENT, content = "x",
                scheduledAtMs = 1L, createdAtMs = 1L)
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            ScheduledMessage(id = "A", recipient = "  ", content = "x",
                scheduledAtMs = 1L, createdAtMs = 1L)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    /** `isDue` is `status == PENDING && scheduledTime <= now`, inclusive on both platforms. */
    @Test
    fun `isDue is pending-only and inclusive of the exact scheduled instant`() {
        val m = sample(1_000L)
        assertFalse(m.isDue(999L))
        assertTrue(m.isDue(1_000L))
        assertTrue(m.isDue(1_001L))
        assertFalse(m.copy(status = ScheduledMessage.Status.SENT).isDue(2_000L))
        assertFalse(m.copy(status = ScheduledMessage.Status.CANCELLED).isDue(2_000L))
        assertFalse(m.copy(status = ScheduledMessage.Status.FAILED).isDue(2_000L))
    }
}
