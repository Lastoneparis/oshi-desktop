package com.oshi.desktop.msg

/**
 * __DESKTOP_INLINE_MARKUP_2026_10_29__ `*bold*`, `_italic_`, `~strike~` and `` `code` ``
 * inside a message — iOS's `InlineMarkup.swift`, which this client had no equivalent of.
 *
 * iOS runs every message through it (`ClickableTextView:115,166`) and strips the markers for
 * the surfaces that cannot style (`GroupViews:4755`). The desktop drew the raw string, so a
 * message an iPhone renders as **bold** arrived here with its asterisks showing — a small,
 * constant, every-message difference.
 *
 * ## It is DELIBERATELY CONSERVATIVE, and that is the whole design
 *
 * This runs on every message anybody sends, including text the sender never meant as
 * formatting: `file_name_with_underscores`, `a*b*c` in a maths note, a shell command in
 * backticks. **Rules that turn ordinary punctuation into invisible formatting are worse than
 * no formatting at all**, because the failure deletes characters from somebody's message. So
 * a marker only counts when it looks like it was meant:
 *
 *  - the opening marker sits after a BOUNDARY (start of text, whitespace, punctuation);
 *  - the closing marker is followed by one;
 *  - the run between them is non-empty, contains no newline, and neither starts nor ends
 *    with whitespace.
 *
 * iOS gives the concrete reason for that last rule and it is the one worth keeping in front
 * of you: in `2 * 3 * 4 = 24` the two asterisks DO pair, with `" 3 "` between them, so a
 * naive parser renders `2  3  4 = 24` — silently deleting characters from an ordinary
 * arithmetic message. Requiring the marker to sit tight against its text is what separates
 * `*bold*` from punctuation that happens to line up.
 *
 * ## Offsets are in CHARS here, not UTF-16
 *
 * iOS reports `location`/`length` in UTF-16 units because they become an `NSRange`. Compose
 * styles an `AnnotatedString`, which indexes in Kotlin `Char`s — also UTF-16 code units, so
 * the two agree for every input including emoji and astral characters. Stated because it
 * looks like a difference and is not: a `String.length` in Kotlin IS the UTF-16 count.
 */
object InlineMarkup {

    enum class Kind { BOLD, ITALIC, STRIKETHROUGH, CODE }

    /** A styled run, in character offsets into the RETURNED plain text. */
    data class Span(val kind: Kind, val location: Int, val length: Int)

    data class Parsed(val text: String, val spans: List<Span>)

    private val markers: List<Pair<Char, Kind>> = listOf(
        '*' to Kind.BOLD,
        '_' to Kind.ITALIC,
        '~' to Kind.STRIKETHROUGH,
        '`' to Kind.CODE,
    )

    /**
     * May this character sit immediately outside a marker?
     *
     * Anything else — a letter, a digit — means the marker is part of a word, and
     * `snake_case_name` must not become italic. Null is the start or the end of the text.
     */
    private fun isBoundary(c: Char?): Boolean {
        if (c == null) return true
        return c.isWhitespace() || !c.isLetterOrDigit()
    }

    /** The text with markers removed, and the runs to style. */
    fun parse(input: String): Parsed {
        // Cheap bail-out: the overwhelming majority of messages contain no marker at all,
        // and this runs on every one of them.
        if (input.none { c -> markers.any { it.first == c } }) return Parsed(input, emptyList())

        val out = StringBuilder()
        val spans = ArrayList<Span>()
        var i = 0

        while (i < input.length) {
            val c = input[i]
            val kind = markers.firstOrNull { it.first == c }?.second
            if (kind == null) {
                out.append(c); i++; continue
            }
            // The opening marker must follow a boundary — measured against what has already
            // been EMITTED, not the input, so a stripped marker does not count as context.
            if (!isBoundary(out.lastOrNull())) {
                out.append(c); i++; continue
            }

            // A closing marker on the SAME line, with something between.
            var j = i + 1
            var close = -1
            while (j < input.length) {
                val d = input[j]
                if (d == '\n' || d == '\r') break
                if (d == c) {
                    val after = if (j + 1 < input.length) input[j + 1] else null
                    if (j > i + 1 && isBoundary(after)) close = j
                    break
                }
                j++
            }
            if (close < 0) {
                // No valid partner — the marker is the literal character it is. This is what
                // protects `2 * 3 * 4` and a lone `_`.
                out.append(c); i++; continue
            }

            val inner = input.substring(i + 1, close)
            if (inner.isEmpty() || inner.first().isWhitespace() || inner.last().isWhitespace()) {
                out.append(c); i++; continue
            }

            spans.add(Span(kind, out.length, inner.length))
            out.append(inner)
            i = close + 1
        }
        return Parsed(out.toString(), spans)
    }

    /**
     * The message as it will be SEEN, markers gone and nothing styled.
     *
     * For the surfaces that show a message but cannot style it — a reply quote, the pinned
     * bar, a conversation row's last line, a notification. iOS notes why it matters: those
     * drew the raw string, so a reply to `*gras*` quoted it with the asterisks still
     * attached, and the one place the markers are guaranteed to show is the place the
     * feature exists to hide them.
     */
    fun stripped(input: String): String = parse(input).text

    /** True when the text contains at least one run this parser would style. */
    fun hasFormatting(input: String): Boolean = parse(input).spans.isNotEmpty()
}
