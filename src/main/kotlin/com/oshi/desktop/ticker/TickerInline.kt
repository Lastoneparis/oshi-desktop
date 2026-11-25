package com.oshi.desktop.ticker

/**
 * __TICKER_INLINE_2026_11_24__ `@AAPL` inside the sentence becomes a bold, clickable `$AAPL`.
 *
 * Both phones do this and the desktop did not: iOS rewrites the token in the bubble text
 * (`OSHI/ClickableTextView.swift:222-240`, `.stronglyEmphasized` + link colour) and Android
 * followed (`ui/components/LinkPreview.kt` `buildLinkAnnotatedString`, "`$AAPL` is the
 * contract"). The desktop drew the price chip under the bubble but left the sentence saying
 * `@AAPL`, so the same message read as a ticker on a phone and as a half-mention here.
 *
 * Pure text → text: the rewrite changes the string's LENGTH (`@NASDAQ:TSLA`, 12 chars,
 * shows as `$TSLA`, 5), so it returns an offset map the caller pushes every other span
 * (mentions, `*bold*` runs) through — the map Android keeps for the same reason.
 */
object TickerInline {

    /** A ticker as shown: [start]/[end] index the REWRITTEN text. */
    data class Placed(val match: TickerLinks.Match, val start: Int, val end: Int)

    class Rewritten(
        val text: String,
        val tickers: List<Placed>,
        /** `map[i]` = where index `i` of the input landed in [text]; size = input length + 1. */
        private val map: IntArray,
    ) {
        fun map(index: Int): Int = map[index.coerceIn(0, map.lastIndex)]

        /** Length of the text before the rewrite. */
        val originalLength: Int get() = map.size - 1
    }

    /**
     * Rewrite every ticker in [text] that does not overlap one of [exclude] (a picked
     * person's `@name` always wins over a symbol — `mentionRanges`).
     */
    fun rewrite(text: String, exclude: List<IntRange> = emptyList()): Rewritten {
        val matches = TickerLinks.matches(text).filter { m -> exclude.none { r -> m.start <= r.last && r.first < m.end } }
        val map = IntArray(text.length + 1)
        if (matches.isEmpty()) {
            for (i in map.indices) map[i] = i
            return Rewritten(text, emptyList(), map)
        }
        val out = StringBuilder(text.length)
        val placed = ArrayList<Placed>(matches.size)
        var last = 0
        for (m in matches) {
            if (m.start < last) continue
            for (i in last until m.start) { map[i] = out.length; out.append(text[i]) }
            val s = out.length
            for (i in m.start until m.end) map[i] = s
            out.append(m.display)
            placed += Placed(m, s, out.length)
            last = m.end
        }
        for (i in last until text.length) { map[i] = out.length; out.append(text[i]) }
        map[text.length] = out.length
        return Rewritten(out.toString(), placed, map)
    }
}
