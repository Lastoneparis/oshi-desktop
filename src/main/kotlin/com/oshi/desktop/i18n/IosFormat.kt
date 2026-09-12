package com.oshi.desktop.i18n

/**
 * iOS format strings, translated into `java.util.Formatter` format strings.
 *
 * `%@` is not `%s` to the JDK — it is not a conversion at all, and
 * `String.format("Hi %@", x)` throws `UnknownFormatConversionException`. Neither is
 * `%ld`, which every `NSString` localisation uses freely and which Java rejects the same
 * way. So the 6505 `%@` and the `%ld` family have to be converted, and this is where.
 *
 * ## The part that is NOT a table lookup
 *
 * A percent sign in a translated sentence is usually not a conversion, it is the word
 * "percent". The shipped OSHI catalog has six of them in English alone:
 *
 *     about.openSource.paragraph1  "OSHI is **100% open source**, built with …"
 *     ai_on_device                 "100% On-Device"
 *     ai_feature_privacy           "100% on-device, no data sent"
 *     …
 *
 * `% o` is, by the letter of C's grammar, a valid conversion: space flag, octal
 * conversion. Hand `"100% open source"` to `String.format` with one argument and it
 * throws `IllegalFormatConversionException`; hand it none and it throws
 * `MissingFormatArgumentException`. On iOS this never fires because these strings are
 * used as literals and never passed through a format function. On a desktop client that
 * routed everything through one localised `format()` helper, it would fire on six real
 * strings in thirty-four languages.
 *
 * Two rules answer that, and both are deliberate divergences from C, written down here
 * because "not applicable" is a claim that has to be defended:
 *
 * 1. **The space flag is not recognised.** `%` followed by a space is a literal percent.
 *    Nothing in the 68 shipped catalogs uses the space flag for a real conversion; the
 *    only 39 hits for `% o`, and every other `% <letter>` in the histogram, are prose.
 * 2. **The recognised conversion set is the localisation subset**, not all of C:
 *    `@ d i u f F e E g G x X o s`. Excluded on purpose:
 *      * `n` — in C it WRITES THROUGH A POINTER; in Java it is a newline. Silently wrong
 *              either way, and no localised string ever wants it.
 *      * `p`, `a`, `A`, `S`, `C` — pointer / hex-float / unichar. Java has no `%p` and no
 *              `%a` for these purposes; they are not localisation specifiers.
 *      * `b`, `c` — Java HAS both, which is what makes them dangerous. The catalog's only
 *              hits are `settings.webSessions.revokeMessage` = "log out %browser% on
 *              %platform%" and `revokeAllMessage` = "all %count% computers" — a
 *              *named-placeholder* dialect that happens to live in the same file. Reading
 *              `%b`/`%c` as conversions turns those into silent garbage; not reading them
 *              turns them into literal text, which is what they are.
 *
 * Anything the grammar does not recognise is emitted as a LITERAL percent (`%%`), so a
 * converted string is always safe to hand to `String.format`.
 *
 * ## Plurals
 *
 * There are none to port. `find` over the iOS tree returns **zero `.stringsdict` files**,
 * which is the only mechanism iOS has for plural rules, so the shipped app has no plural
 * catalog: every count-bearing string is a flat key with a `%d` in it and one English
 * plural form baked in. Inventing a CLDR plural layer on the desktop would produce
 * grammar the phones do not produce, in a project whose whole rule is to match the
 * shipped bytes. [Strings] therefore has no `getPlural`, and `CatalogAuditTest` asserts
 * the zero so this stays a measured fact rather than an assumption that rots.
 */
object IosFormat {

    /** One conversion found in a string. */
    data class Spec(
        /** Character offset of the leading `%`. */
        val start: Int,
        /** Offset just past the conversion character. */
        val end: Int,
        /** 1-based explicit argument index (`%2$@`), or null for the next positional arg. */
        val argIndex: Int?,
        /** The iOS conversion character, e.g. `@`, `d`, `f`. */
        val conversion: Char,
        /** The Java conversion character this maps to. */
        val javaConversion: Char,
        /** Offset of the first flag character (after any `N$` index). */
        val flagsStart: Int = start + 1,
        /** Offset just past the width/precision, before any length modifier. */
        val widthEnd: Int = start + 1,
    )

    private const val CONVERSIONS = "@diufFeEgGxXos"
    private const val FLAGS = "-+#0'" // no space: rule 1 above
    private val LENGTHS = listOf("hh", "ll", "h", "l", "q", "z", "t", "j", "L")

    private fun javaFor(c: Char): Char = when (c) {
        '@' -> 's'          // the whole reason this file exists
        'i', 'u' -> 'd'     // Java has neither %i nor %u
        'F' -> 'f'          // Java has no %F
        else -> c
    }

    /**
     * Every conversion in [s], in order. `%%` is not a conversion and is not returned.
     *
     * Unrecognised percent sequences are not errors here — [toJava] escapes them. This
     * function answers only "how many arguments does this string take, and of what kind".
     */
    fun scan(s: String): List<Spec> {
        val out = ArrayList<Spec>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '%') { i++; continue }
            val parsed = parseAt(s, i)
            if (parsed == null) { i++; continue }
            if (parsed.conversion != '%') out.add(parsed)
            i = parsed.end
        }
        return out
    }

    /**
     * The number of arguments [s] consumes.
     *
     * Positional (`%1$@`) and sequential specs do not mix in any shipped catalog, but if
     * they did, the answer is the larger of "how many sequential specs" and "the highest
     * explicit index" — which is what `String.format` would demand.
     */
    fun arity(s: String): Int {
        val specs = scan(s)
        val sequential = specs.count { it.argIndex == null }
        val highest = specs.mapNotNull { it.argIndex }.maxOrNull() ?: 0
        return maxOf(sequential, highest)
    }

    /**
     * Rewrite [s] as a Java format string.
     *
     * Every recognised conversion is mapped; every other percent becomes `%%`. The result
     * is always accepted by `String.format` given [arity] arguments — which is not a
     * claim, it is what `IosFormatTest.every_shipped_string_converts_and_formats` proves
     * over all 130,000 shipped strings.
     */
    fun toJava(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '%') { sb.append(c); i++; continue }
            val spec = parseAt(s, i)
            if (spec == null) { sb.append("%%"); i++; continue }
            if (spec.conversion == '%') { sb.append("%%"); i = spec.end; continue }
            // Re-emit: % [index$] flags width.precision javaConversion — with the length
            // modifier DROPPED, because Java has no %ld.
            sb.append('%')
            spec.argIndex?.let { sb.append(it).append('$') }
            sb.append(s, spec.flagsStart, spec.widthEnd)
            sb.append(spec.javaConversion)
            i = spec.end
        }
        return sb.toString()
    }

    /**
     * Format [s] with [args], converting first.
     *
     * @throws IllegalArgumentException when the argument count does not match the string.
     *   Deliberately loud: a translation that dropped a `%@` is a defect in the catalog,
     *   and the desktop finds it here rather than printing a sentence with a hole in it.
     */
    fun format(s: String, vararg args: Any?): String {
        val need = arity(s)
        require(need == args.size) {
            "format string takes $need argument(s), got ${args.size}: ${s.take(120)}"
        }
        // The formatter runs even at arity 0, because a string AUTHORED as a format string
        // can still contain `%%` — `location.saved_progress` is "Saved %.0f%%" and its
        // zero-argument cousins exist too. Skipping the formatter would leave the escape
        // undoubled on screen. This is safe for prose because [toJava] has already turned
        // every unrecognised percent into `%%`, so "100% open source" survives verbatim.
        //
        // The literal-not-format case is [Strings.get], which never comes through here.
        return String.format(toJava(s), *args)
    }

    // ---------------------------------------------------------------- the grammar

    /**
     * Parse one percent sequence starting at [at], or null if it is not a conversion.
     *
     * Grammar: `%` [ digits `$` ] flags* width? ( `.` precision )? length? conversion
     */
    private fun parseAt(s: String, at: Int): Spec? {
        var i = at + 1
        if (i >= s.length) return null
        if (s[i] == '%') return Spec(at, i + 1, null, '%', '%')

        // explicit argument index: digits followed by '$'
        var argIndex: Int? = null
        run {
            var j = i
            while (j < s.length && s[j].isDigit()) j++
            if (j > i && j < s.length && s[j] == '$') {
                argIndex = s.substring(i, j).toIntOrNull()
                if (argIndex == null || argIndex == 0) return null
                i = j + 1
            }
        }

        val flagsStart = i
        while (i < s.length && s[i] in FLAGS) i++
        while (i < s.length && s[i].isDigit()) i++            // width
        if (i < s.length && s[i] == '.') {                     // precision
            i++
            while (i < s.length && s[i].isDigit()) i++
        }
        val widthEnd = i

        for (len in LENGTHS) {                                 // length modifier, dropped
            if (s.startsWith(len, i)) { i += len.length; break }
        }
        if (i >= s.length) return null
        val conv = s[i]
        if (conv !in CONVERSIONS) return null
        return Spec(at, i + 1, argIndex, conv, javaFor(conv), flagsStart, widthEnd)
    }
}
