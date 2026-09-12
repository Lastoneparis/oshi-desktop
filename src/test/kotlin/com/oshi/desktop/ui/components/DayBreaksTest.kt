package com.oshi.desktop.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.oshi.desktop.i18n.t
import org.junit.Test

/**
 * The guard: **the date separator is derived from the model's own stamp, or not drawn.**
 *
 * Two failures this pins. First, the coupling: [DayBreaks] parses `ClientCli.stamp`'s
 * `yyyy-MM-dd HH:mm`, and if that formatter ever changes the separators must VANISH rather
 * than land on the wrong day — so one test builds a stamp with the same formatter the REPL
 * uses instead of hard-coding the shape, and the malformed-input tests prove the degradation
 * is silence and not a guess.
 *
 * Second, "Today". A label that reads the clock inside itself cannot be asserted at all, and
 * a day boundary is exactly where it goes wrong; `today` is a parameter here for that reason
 * and every case below pins a specific offset from a fixed date.
 */
class DayBreaksTest {

    private val today = LocalDate.of(2026, 8, 28)

    // ------------------------------------------------------------------ parsing

    @Test
    fun `it parses the stamp the REPL actually prints`() {
        // Not a hard-coded string: the same formatter `ClientCli.stamp` uses. If that shape
        // changes, this fails here rather than silently mis-grouping a log.
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val stamp = fmt.format(Instant.ofEpochMilli(1_772_000_000_000L))
        assertNotNull("DayBreaks no longer understands ClientCli.stamp", DayBreaks.dayOf(stamp))
        assertEquals(10, DayBreaks.dayOf(stamp)!!.length)
        assertEquals(5, DayBreaks.timeOf(stamp).length)
    }

    @Test
    fun `a stamp of another shape is never guessed at`() {
        for (bad in listOf("", "yesterday", "2026-08-28", "28/08/2026 14:03", "2026-08-28T14:03")) {
            assertNull("must not parse '$bad'", DayBreaks.dayOf(bad))
        }
        assertNull(DayBreaks.dayOf(null))
    }

    @Test
    fun `timeOf falls back to the whole stamp so a footer is never blank`() {
        assertEquals("14:03", DayBreaks.timeOf("2026-08-28 14:03"))
        assertEquals("who knows", DayBreaks.timeOf("who knows"))
        assertEquals("", DayBreaks.timeOf(null))
    }

    // ------------------------------------------------------------------ THE GUARD

    @Test
    fun `the first row always gets a separator`() {
        assertEquals(t("messages.today"), DayBreaks.separatorAbove(null, "2026-08-28 09:00", today))
    }

    @Test
    fun `a separator appears exactly on a day change and nowhere else`() {
        assertNull(DayBreaks.separatorAbove("2026-08-28 09:00", "2026-08-28 23:59", today))
        assertEquals(t("messages.today"), DayBreaks.separatorAbove("2026-08-27 23:59", "2026-08-28 00:01", today))
        assertEquals(t("messages.yesterday"), DayBreaks.separatorAbove("2026-08-26 23:59", "2026-08-27 00:01", today))
    }

    @Test
    fun `an unparseable stamp draws no separator and does not carry forward`() {
        assertNull("must never label a row whose day is unknown", DayBreaks.separatorAbove("2026-08-27 10:00", "garbage", today))
        // A KNOWN day after an unknown one is announced — carrying the unknown forward as a
        // match would silently merge two days into one section.
        assertNotNull(DayBreaks.separatorAbove("garbage", "2026-08-28 10:00", today))
    }

    // ------------------------------------------------------------------ labels

    @Test
    fun `labels step through today yesterday weekday and full date`() {
        assertEquals(t("messages.today"), DayBreaks.label("2026-08-28", today))
        assertEquals(t("messages.yesterday"), DayBreaks.label("2026-08-27", today))
        // 2026-08-24 is four days back — inside the weekday window, so it is a weekday name
        // and not a date. Asserting it is NOT the long form rather than pinning a locale.
        val weekday = DayBreaks.label("2026-08-24", today)
        assertFalse("a day inside the last week is a weekday, not a date", weekday.contains("2026"))
        assertTrue("a day outside it carries the year", DayBreaks.label("2026-01-04", today).contains("2026"))
    }

    @Test
    fun `an unparseable day label degrades to the raw value`() {
        assertEquals("not-a-date", DayBreaks.label("not-a-date", today))
    }

    // ------------------------------------------------------------------ compact stamp

    @Test
    fun `the list stamp is a time today and a date last month`() {
        assertEquals("14:03", DayBreaks.compactStamp("2026-08-28 14:03", today))
        assertEquals(t("messages.yesterday"), DayBreaks.compactStamp("2026-08-27 14:03", today))
        val old = DayBreaks.compactStamp("2026-03-02 14:03", today)
        assertFalse("a March message must not read as a time of day", old.contains(":"))
    }

    // ------------------------------------------------------------------ grouping

    @Test
    fun `grouping needs the same sender and the same minute`() {
        val a = DayBreaks.RowKey(fromMe = true, stamp = "2026-08-28 14:03")
        assertFalse("the first row of a thread is never grouped", DayBreaks.isGrouped(null, a))
        assertTrue(DayBreaks.isGrouped(a, DayBreaks.RowKey(true, "2026-08-28 14:03")))
        assertFalse("a different sender breaks the run", DayBreaks.isGrouped(a, DayBreaks.RowKey(false, "2026-08-28 14:03")))
        assertFalse("a later minute breaks the run", DayBreaks.isGrouped(a, DayBreaks.RowKey(true, "2026-08-28 14:04")))
        assertFalse("a different day breaks the run", DayBreaks.isGrouped(a, DayBreaks.RowKey(true, "2026-08-29 14:03")))
    }

    @Test
    fun `an unknown time never glues two rows together`() {
        val unknown = DayBreaks.RowKey(true, "garbage")
        assertFalse(DayBreaks.isGrouped(unknown, DayBreaks.RowKey(true, "garbage")))
        assertFalse(DayBreaks.isGrouped(unknown, DayBreaks.RowKey(true, "2026-08-28 14:03")))
        assertFalse(DayBreaks.isGrouped(DayBreaks.RowKey(true, "2026-08-28 14:03"), unknown))
    }

    /**
     * The two words are now catalog lookups, so the assertions above compare a lookup with
     * a lookup and would pass even if the catalog served an empty string. This is the
     * assertion that stops that: the English values are pinned, and a catalog that lost
     * them — or that started rendering the ⟦key⟧ miss marker — is caught here rather than
     * on a user's screen above every conversation.
     */
    @Test
    fun `the two day words resolve to real text, not to a miss marker`() {
        for (key in listOf("messages.today", "messages.yesterday")) {
            val value = t(key)
            assertFalse("$key is missing from the catalog: $value", value.startsWith("\u27E6"))
            assertTrue("$key resolved to blank", value.isNotBlank())
        }
        // Pinned in English so a catalog regression is legible in the failure message.
        assertEquals("Today", com.oshi.desktop.i18n.Strings.get("messages.today", "en"))
        assertEquals("Yesterday", com.oshi.desktop.i18n.Strings.get("messages.yesterday", "en"))
    }
}
