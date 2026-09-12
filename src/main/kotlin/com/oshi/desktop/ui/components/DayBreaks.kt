package com.oshi.desktop.ui.components

import com.oshi.desktop.i18n.t
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date separators and message grouping, derived from the ONE timestamp the model publishes.
 *
 * ============================================================ WHY IT PARSES A STRING
 *
 * `MessageRow` carries `stamp: String` and no epoch. That string is not free-form: it is
 * `ClientCli.stamp`, `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")` in the system zone,
 * and it is the SAME string the REPL prints. Re-deriving a millisecond value here would mean
 * a second reader of the store and a second timezone decision, and the two surfaces could
 * then disagree about which day a message landed on. Reading the model's own output keeps
 * one answer.
 *
 * The cost is stated rather than hidden: **this is coupled to that format.** If `stamp` ever
 * changes shape, [dayOf] stops matching and this file draws NO separators and groups nothing
 * — it degrades to a flat list, which is wrong but not misleading. It never guesses a date.
 * `DayBreaksTest` pins the coupling by parsing a string produced by the real formatter.
 *
 * ============================================================ WHAT GROUPING CAN AND CANNOT DO
 *
 * The shipped app groups consecutive messages from the same sender within 120 seconds
 * (`ChatView.swift`, 2pt row padding when grouped and 10pt when not). This window's stamp has
 * MINUTE resolution, so a 120-second window is not derivable. Same sender in the same minute
 * is the strictly tighter rule that the data supports, and it is what [isGrouped] implements.
 * Documented rather than approximated silently: a run that straddles a minute boundary breaks
 * here and would not on the phone.
 */
object DayBreaks {

    /** `yyyy-MM-dd HH:mm` — 10 date chars, a space, 5 time chars. */
    private val SHAPE = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})$""")

    /** The `yyyy-MM-dd` half, or null when the stamp is not the shape this file knows. */
    fun dayOf(stamp: String?): String? = stamp?.let { SHAPE.matchEntire(it)?.groupValues?.get(1) }

    /** The `HH:mm` half. Falls back to the whole stamp so a footer is never blank. */
    fun timeOf(stamp: String?): String =
        stamp?.let { SHAPE.matchEntire(it)?.groupValues?.get(2) } ?: stamp.orEmpty()

    /**
     * The separator to draw ABOVE this row, or null for no separator.
     *
     * The first row of a thread always gets one (its `previous` is null), because a log that
     * opens with a bubble and no date leaves the reader guessing whether it is from today.
     * A day that follows an UNPARSEABLE stamp also gets one — announcing a known day after an
     * unknown one is right; carrying the unknown forward as if it matched would not be.
     */
    fun separatorAbove(previousStamp: String?, stamp: String?, today: LocalDate): String? {
        val day = dayOf(stamp) ?: return null
        val prev = dayOf(previousStamp)
        if (prev == day) return null
        return label(day, today)
    }

    /**
     * "Today", "Yesterday", a weekday inside the last week, otherwise the full date.
     *
     * `today` is a parameter, never `LocalDate.now()` inside: a function that reads the clock
     * cannot be asserted against a fixture, and "Today" is exactly the label most likely to be
     * wrong at a day boundary.
     *
     * THE TWO WORDS COME FROM THE SHARED CATALOG, not from here. `messages.today` and
     * `messages.yesterday` are present and non-blank in all 34 locales — checked, not
     * assumed — so this is a free win: the separator over every conversation was English
     * in 33 languages for the price of two string literals. The weekday and the date were
     * already localised, by `Locale.getDefault()` inside `DayOfWeek.getDisplayName` and
     * the formatters, which is what made the two hardcoded words easy to miss: eight days
     * out of ten this function returns something correctly translated.
     */
    fun label(day: String, today: LocalDate): String {
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
        val delta = date.toEpochDay() - today.toEpochDay()
        return when {
            delta == 0L -> t("messages.today")
            delta == -1L -> t("messages.yesterday")
            delta in -6L..-2L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            else -> LONG_DATE.format(date)
        }
    }

    /**
     * The list's right-hand timestamp: a time for today, a word for yesterday, a date before.
     *
     * A conversation list that showed `14:03` against a message from March is the bug this
     * exists to avoid, and it is invisible until the list is a week old.
     */
    fun compactStamp(stamp: String?, today: LocalDate): String {
        val day = dayOf(stamp) ?: return stamp.orEmpty()
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return stamp.orEmpty()
        val delta = date.toEpochDay() - today.toEpochDay()
        return when {
            delta == 0L -> timeOf(stamp)
            delta == -1L -> t("messages.yesterday")
            delta in -6L..-2L -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            else -> SHORT_DATE.format(date)
        }
    }

    /**
     * True when this row continues the previous one and should sit tight against it.
     *
     * Requires the same sender AND the same parseable minute. An unparseable stamp on either
     * side is never grouped — an unknown time must not glue two rows together.
     */
    fun isGrouped(previous: RowKey?, current: RowKey): Boolean {
        if (previous == null) return false
        if (previous.fromMe != current.fromMe) return false
        val a = dayOf(previous.stamp) ?: return false
        val b = dayOf(current.stamp) ?: return false
        if (a != b) return false
        // No emptiness check on the times, and that absence is deliberate rather than
        // careless. [timeOf] falls back to the whole stamp, so two unparseable stamps would
        // compare EQUAL — but an unparseable stamp cannot reach this line, because [dayOf]
        // already returned null for it two lines up. A `ta.isNotEmpty()` clause was written
        // here first and SURVIVED its mutation run: that is what unreachable code looks like,
        // and a redundant guard that reads as a real one is worse than no guard. The guard
        // that actually holds this rule is the pair of `?: return false` above, and THAT is
        // what `an unknown time never glues two rows together` was watched failing against.
        return timeOf(previous.stamp) == timeOf(current.stamp)
    }

    /** The two fields grouping depends on, so the rule can be tested without a `MessageRow`. */
    data class RowKey(val fromMe: Boolean, val stamp: String?)

    private val LONG_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

    private val SHORT_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
}
