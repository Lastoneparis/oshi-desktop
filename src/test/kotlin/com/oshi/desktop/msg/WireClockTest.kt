package com.oshi.desktop.msg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PARITY.md row 0.18 — the four date epochs.
 *
 * The ledger row's only note is "four different date epochs live in these payloads," so the
 * first tests written for the row are about the epochs and not about the payloads.
 *
 * Two of these tests read the SHIPPED trees rather than restating a number: the offset and
 * the Apple-epoch convention are facts about iOS and Android source, and a test that only
 * asserted `978307200 == 978307200` would pass forever while the shipped clients changed
 * underneath it.
 */
class WireClockTest {

    private val androidRoot = File(
        System.getProperty("oshi.android.root") ?: "/Users/HUGOMORICEAU/Documents/Genesis/OSHI-Android"
    )
    private val iosRoot = File(
        System.getProperty("oshi.ios.root") ?: "/Users/HUGOMORICEAU/Documents/Genesis/OSHI"
    )

    /** A round Unix instant in 2026, the same base Android's own wire test uses. */
    private val baseMs = 1_770_000_000_000L

    /** `1_770_000_000_000 / 1000 - 978_307_200` — computed by hand, not by the code under test. */
    private val baseApple = 791_692_800.0

    // ------------------------------------------------------------------ the offset itself

    @Test
    fun `apple epoch offset is 978307200 seconds`() {
        assertEquals(978_307_200.0, WireClock.APPLE_EPOCH_OFFSET_SECONDS, 0.0)
    }

    /**
     * The offset is not our constant to choose — it is Foundation's, restated by Android in
     * four places. If the Android tree ever stops carrying it, this desktop client's copy has
     * become an unanchored magic number and should be re-derived rather than trusted.
     */
    @Test
    fun `the shipped Android tree still states the same offset`() {
        val repo = File(androidRoot, "app/src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        assumeTrue("Android tree not present on this machine", repo.isFile)
        val text = repo.readText()
        assertTrue(
            "MessageRepository no longer states APPLE_EPOCH_OFFSET_SECONDS = 978307200.0 — " +
                "WireClock's constant has lost its anchor",
            text.contains("APPLE_EPOCH_OFFSET_SECONDS = 978307200.0"),
        )
    }

    /**
     * The iOS side of the same fact. There is no literal `978307200` in the Swift source —
     * and there cannot be, because iOS gets the epoch for free from `Date` under a BARE
     * `JSONEncoder`. This test pins the thing that actually makes it Apple-epoch: that the
     * payload structs declare `timestamp: Date` and are encoded with a plain `JSONEncoder()`
     * with no `dateEncodingStrategy`. The day someone adds `.iso8601` to one of them, the
     * desktop's Apple-epoch emitter starts sinking those payloads with a `typeMismatch`
     * (PLAN.md §4.2) — and this test is what says so.
     */
    @Test
    fun `iOS row 0-18 payload encoders are still bare JSONEncoders`() {
        val files = listOf(
            "TypingIndicatorManager.swift" to "TypingStatus",
            "MessageReactionManager.swift" to "ReactionPayload",
            "MessageManager.swift" to "MessageActionPayload",
        )
        for ((name, struct) in files) {
            val f = File(iosRoot, name)
            assumeTrue("iOS tree not present on this machine", f.isFile)
            val text = f.readText()
            val at = text.indexOf("struct $struct")
            assertTrue("$struct no longer exists in $name", at >= 0)
            // The declaration + its encoder live within the struct body; take a generous window.
            val window = text.substring(at, minOf(text.length, at + 4000))
            assertTrue("$struct no longer declares `timestamp: Date`", window.contains("timestamp: Date"))
            assertFalse(
                "$struct's encoder now sets a dateEncodingStrategy — it is no longer Apple-epoch, " +
                    "and WireClock.toAppleSeconds is now wrong for this payload",
                window.contains("dateEncodingStrategy"),
            )
        }
    }

    // ---------------------------------------------------------------------- conversions

    @Test
    fun `unix millis convert to apple seconds`() {
        assertEquals(baseApple, WireClock.toAppleSeconds(baseMs), 0.0)
    }

    @Test
    fun `apple seconds convert back to unix millis`() {
        assertEquals(baseMs, WireClock.toUnixMillis(baseApple))
    }

    @Test
    fun `a fractional instant survives the round trip`() {
        val ms = 1_770_000_000_123L
        assertEquals(ms, WireClock.toUnixMillis(WireClock.toAppleSeconds(ms)))
    }

    // ------------------------------------------------------------------- the guard itself

    /**
     * THE bug of this row, from the receiving side: three shipped Android emitters put
     * `System.currentTimeMillis()` into an Apple-epoch field. iOS decodes it without
     * throwing, as roughly the year 55 000, and stamps it into `editedAt`.
     */
    @Test
    fun `unix millis in an apple-epoch field is refused, not decoded as the year 55000`() {
        val millisByMistake = baseMs.toDouble()   // 1.77e12 where ~7.9e8 was expected
        assertTrue(WireClock.looksLikeUnixMillis(millisByMistake))
        assertNull(
            "1.77e12 must not convert — it is Unix millis wearing an Apple-epoch field",
            WireClock.toUnixMillis(millisByMistake),
        )
    }

    /** The emitter is the strict half: we control our own clock. */
    @Test(expected = IllegalArgumentException::class)
    fun `emitting a timestamp outside the calendar window throws`() {
        WireClock.toAppleSeconds(baseApple.toLong())   // apple seconds handed in as if millis
    }

    @Test
    fun `non-finite values are refused rather than formatted`() {
        assertNull(WireClock.toUnixMillis(Double.NaN))
        assertNull(WireClock.toUnixMillis(Double.POSITIVE_INFINITY))
        assertTrue(WireClock.looksLikeUnixMillis(Double.NaN))
    }

    /**
     * The honest limit of the guard, asserted so nobody later "improves" it into a
     * seconds-vs-Apple heuristic. 2026's Unix-seconds value is a perfectly plausible
     * Apple-epoch value for 2057 — no magnitude test can separate them, and a decoder that
     * silently shifted such a value by 31 years would be inventing data.
     */
    @Test
    fun `unix seconds and apple seconds are NOT distinguishable by magnitude`() {
        val unixSeconds2026 = 1_770_000_000.0
        assertFalse("the millis guard must not fire on it", WireClock.looksLikeUnixMillis(unixSeconds2026))
        assertTrue("it does sit in the ambiguous band", WireClock.looksLikeUnixSeconds(unixSeconds2026))
        // ...and converting it anyway yields a date 31 years late, which is exactly why
        // looksLikeUnixSeconds is advisory and never acted on.
        assertEquals(2_748_307_200_000L, WireClock.toUnixMillis(unixSeconds2026))
    }

    // ------------------------------------------------------------------ number formatting

    /**
     * PLAN.md §3's hazard in its second disguise: Maven `org.json` prints an integral
     * `773100000.0` as `7.731E8`, Android's prints `773100000`, and Swift agrees with
     * Android. Both parse identically — this is about keeping a byte-for-byte diff against
     * an Android payload meaningful, which is the method this whole project tests by.
     */
    @Test
    fun `an integral apple timestamp prints without an exponent and without a decimal point`() {
        assertEquals("791692800", WireClock.jsonNumber(baseApple))
        assertFalse(WireClock.jsonNumber(baseApple).contains("E"))
        assertFalse(WireClock.jsonNumber(baseApple).contains("."))
    }

    @Test
    fun `a fractional apple timestamp prints in plain decimal, not scientific`() {
        val s = WireClock.jsonNumber(791_692_800.1229999)
        assertEquals("791692800.1229999", s)
        assertFalse("scientific notation would still parse, but stops diffs working", s.contains("E"))
    }

    /** Whatever it printed has to survive a real JSON parser, both ways. */
    @Test
    fun `printed numbers round trip through a JSON parser`() {
        for (ms in listOf(baseMs, 1_770_000_000_123L, 946_684_800_000L, 4_102_444_799_000L)) {
            val apple = WireClock.toAppleSeconds(ms)
            val json = org.json.JSONObject("{\"t\":${WireClock.jsonNumber(apple)}}")
            assertEquals("ms=$ms", ms, WireClock.toUnixMillis(json.getDouble("t")))
        }
    }
}
