package com.oshi.desktop.ui.state

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_THREAD_SEARCH_2026_10_29__ Searching inside one conversation.
 *
 * The cases here are the ones nobody reproduces by hand: an accented query, a day boundary
 * at midnight in a named zone, and a deleted row whose words were revoked.
 */
class ThreadSearchTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, paris).toInstant().toEpochMilli()

    private fun row(id: String, body: String, ms: Long, deleted: Boolean = false) = MessageRow(
        id = id, fromMe = false, who = "them", body = body, attachment = null,
        stamp = "", status = null, failed = false, edited = false, deleted = deleted,
        reactions = "", sentAtMs = ms,
    )

    private val rows = listOf(
        row("1", "Bonjour, ça va ?", at(2026, 10, 27, 9)),
        row("2", "On se voit demain", at(2026, 10, 28, 14)),
        row("3", "José arrive à 8h", at(2026, 10, 28, 23, 30)),
        row("4", "Just after midnight", at(2026, 10, 29, 0, 15)),
    )

    @Test
    fun `a blank query and no day returns everything`() {
        assertEquals(rows, ThreadSearch.apply(rows, "", null, paris))
        assertEquals(rows, ThreadSearch.apply(rows, "   ", null, paris))
    }

    @Test
    fun `a query matches case-insensitively`() {
        assertEquals(listOf("2"), ThreadSearch.apply(rows, "DEMAIN", null, paris).map { it.id })
    }

    /**
     * ACCENTS FOLD, and they must fold the same way `ConversationFilter` folds them: somebody
     * who finds the conversation by typing `jose` and then cannot find the message inside it
     * would reasonably conclude the message is gone.
     */
    @Test
    fun `a query matches without accents, both directions`() {
        assertEquals(listOf("3"), ThreadSearch.apply(rows, "jose", null, paris).map { it.id })
        assertEquals(listOf("1"), ThreadSearch.apply(rows, "ca va", null, paris).map { it.id })
        assertEquals(listOf("3"), ThreadSearch.apply(rows, "José", null, paris).map { it.id })
    }

    /**
     * THE DAY BOUNDARY. Row 3 is 23:30 on the 28th and row 4 is 00:15 on the 29th — forty-five
     * minutes apart and on different days. A filter that compared formatted strings, or that
     * used UTC while the viewer is in Paris, puts them on the same day in winter and on
     * different ones in summer.
     */
    @Test
    fun `a day filter respects the viewer's zone at midnight`() {
        assertEquals(
            listOf("2", "3"),
            ThreadSearch.apply(rows, "", LocalDate.of(2026, 10, 28), paris).map { it.id },
        )
        assertEquals(
            listOf("4"),
            ThreadSearch.apply(rows, "", LocalDate.of(2026, 10, 29), paris).map { it.id },
        )
    }

    @Test
    fun `query and day narrow together`() {
        assertEquals(
            listOf("3"),
            ThreadSearch.apply(rows, "jose", LocalDate.of(2026, 10, 28), paris).map { it.id },
        )
        assertTrue(
            "a query that matches on another day must not widen to it",
            ThreadSearch.apply(rows, "jose", LocalDate.of(2026, 10, 27), paris).isEmpty(),
        )
    }

    /**
     * A DELETED ROW IS NOT FINDABLE BY WORDS. Its body is already "(deleted)", so matching it
     * would let someone search for a word and be shown a message whose words were revoked.
     */
    @Test
    fun `a deleted row never matches a text query`() {
        val withDeleted = rows + row("5", "(deleted)", at(2026, 10, 29, 10), deleted = true)
        assertTrue(ThreadSearch.apply(withDeleted, "deleted", null, paris).isEmpty())
        // ...but it is still there when only a day is asked for: the row exists, and hiding
        // it from a day view would misrepresent the conversation.
        assertEquals(
            listOf("4", "5"),
            ThreadSearch.apply(withDeleted, "", LocalDate.of(2026, 10, 29), paris).map { it.id },
        )
    }

    /** A row with no timestamp cannot be placed on a day, so a day filter excludes it. */
    @Test
    fun `a row with no timestamp is excluded by a day filter but not by a query`() {
        val odd = rows + row("6", "no stamp", 0L)
        assertEquals(listOf("6"), ThreadSearch.apply(odd, "no stamp", null, paris).map { it.id })
        assertTrue(ThreadSearch.apply(odd, "no stamp", LocalDate.of(2026, 10, 29), paris).isEmpty())
    }

    @Test
    fun `an empty result stays empty rather than widening`() {
        assertTrue(ThreadSearch.apply(rows, "zzz-not-here", null, paris).isEmpty())
    }

    @Test
    fun `the days with messages are listed newest first and deduplicated`() {
        assertEquals(
            listOf(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 27)),
            ThreadSearch.daysWithMessages(rows, paris),
        )
    }
}
