package com.oshi.desktop.i18n.tools

import java.io.File

/**
 * Which catalog keys the desktop client's SOURCE actually asks for.
 *
 * This exists because of a specific way a localisation audit lies. The usual parity test
 * is "for every locale, does it have every key `en` has". It is blind in exactly the case
 * that matters: **when `en` is missing the key too**, the key is in nobody's catalog, the
 * comparison of 33 locales against English is perfectly green, and the app renders a hole.
 * That has happened in this codebase's family before — 9 keys in ZERO catalogs and 123
 * tests passing. The only way to catch it is to compare against what the SOURCE asks for,
 * which is what [used] produces and what `CatalogAuditTest` compares every locale to.
 *
 * ## Wrappers
 *
 * The second way this kind of audit lies is by grepping for one call shape. Add a helper
 * — `t(key)`, or a row widget that takes a `textKey` — and every key that flows through it
 * is invisible to the grep, which then reports full coverage of the keys it can see. Also
 * a recorded failure here: an audit blind to `FeatureRow(textKey:)` passed while the
 * paywall was English in 34 languages.
 *
 * Two defences, because a list of known accessors cannot defend itself:
 *
 * 1. [ACCESSORS] is the registered list, and it includes the `t()` wrapper.
 * 2. [suspects] is the one that does not trust the list: it finds every string literal in
 *    the source that IS a real catalog key but does NOT reach the catalog through a
 *    registered accessor. An unregistered wrapper shows up as a pile of suspects. So the
 *    audit fails when someone adds a wrapper and forgets to register it, instead of
 *    quietly shrinking its own scope.
 */
object SourceKeys {

    /**
     * The call shapes that take a catalog key as their first argument.
     *
     * Add a wrapper, add it here. `SourceKeyScanTest` proves each entry actually matches
     * by scanning a fixture that uses it — an accessor pattern that matches nothing is
     * the same as not being in the list at all.
     */
    val ACCESSORS: List<Regex> = listOf(
        // t("key") / t("key", arg) — the short wrapper in Strings.kt
        Regex("""(?<![A-Za-z0-9_.])t\s*\(\s*"([^"\\]+)""""),
        Regex("""Strings\s*\.\s*(?:get|format|lookup)\s*\(\s*"([^"\\]+)""""),
        // Named-argument spellings, e.g. `Strings.get(key = "chat.send")`.
        //
        // The lookbehind is what makes this an ARGUMENT rather than an assignment, and it
        // is not optional. Without it the pattern also matches `val key = "…"` — an
        // ordinary local variable — and the call transport has two of those formatting a
        // socket address:
        //
        //     HolePunch.kt:314    val key = "$ip:$port"
        //     MediaSocket.kt:418  val key = "${from.address?.hostAddress…}:${from.port}"
        //
        // Both were reported as keys missing from all 34 catalogs AND from English, which
        // is the audit's loudest failure ("a key present in some locales but not English
        // is the WORST case"). A scanner that over-matches costs exactly what one that
        // under-matches costs: the next real hole is buried under noise nobody can act on.
        //
        // A `$` can never appear in a catalog key either — the keys are literals and this
        // is a Kotlin template — so the shape test below would also have rejected these.
        // The lookbehind is the narrower fix and keeps that test as a second line.
        Regex("""(?<=[(,])\s*(?:key|textKey|titleKey|labelKey)\s*=\s*"([^"\\]+)""""),
    )

    /**
     * The call shapes that take a DESKTOP-ONLY key — `dt("desktop.menu.quit")`.
     *
     * A separate list, and it is separate on purpose. [ACCESSORS] is what `CatalogAuditTest`
     * uses to decide which keys every one of the 34 shipped catalogs must serve from its own
     * file. A `desktop.` key lives in `i18n/Desktop/en.json` and in no other catalog by
     * design — see [com.oshi.desktop.i18n.DesktopStrings] — so putting `dt` in [ACCESSORS]
     * would turn a true statement about the extracted iOS asset into a permanently red one
     * about a deliberate choice, and the only way anyone would make it green again is by
     * machine-translating the desktop-only overlay this project has no business inventing.
     *
     * [com.oshi.desktop.i18n.CatalogAuditTest] uses [usedDesktop] to assert the three
     * things that would otherwise be unchecked:
     *
     *  1. every key reached by `dt` exists in `DesktopStrings.overlayKeys` (else the window
     *     draws `⟦desktop.…⟧`, which is visible but only to whoever opens that screen);
     *  2. no key reached by `dt` is one the SHARED catalogs already have — that is the
     *     expensive mistake, an English string shipped to 33 locales that were already
     *     translated (`DesktopStrings.shadowedKeys` is the other half of it);
     *  3. no overlay key is unreached, which is how a string nobody removed outlives the
     *     screen that said it.
     */
    val DESKTOP_ACCESSORS: List<Regex> = listOf(
        // `ConversationFilter` is deliberately plain Kotlin, so it calls the helper by its
        // fully-qualified name rather than importing a Compose-facing package. Match that
        // spelling too: a scanner that recognised only bare `dt` would label live copy stale.
        // The lookbehind rejects the tail of an identifier ending in `dt` without rejecting a
        // package separator.
        Regex("""(?<![A-Za-z0-9_])(?:[A-Za-z_][A-Za-z0-9_]*\.)*dt\s*\(\s*"([^"\\]+)""""),
        Regex("""DesktopStrings\s*\.\s*(?:get|format|lookup)\s*\(\s*"([^"\\]+)""""),
    )

    /** A string literal that looks like a catalog key rather than prose. */
    private val KEYISH = Regex("""^[a-z][A-Za-z0-9]*(?:[._][A-Za-z0-9]+)+$""")

    private val STRING_LITERAL = Regex(""""([^"\\\n$]{2,})"""")

    data class Use(val key: String, val file: File, val line: Int)

    fun kotlinFiles(vararg roots: File): List<File> =
        roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }

    /**
     * Blank out comments, preserving every offset so line numbers stay right.
     *
     * A key NAMED IN A DOC COMMENT is not a key the app uses — this file's own doc comment
     * names three, and `BotTriggers.kt` explains iOS's `"bot.template.faq.response"` in
     * prose. Counting those as uses would inflate the audit's scope with keys no code
     * asks for; counting them as suspects would report a defect that is a sentence.
     *
     * String-aware on purpose: a naive `//` strip eats the rest of the line on every
     * `"https://…"` in the source, which silently HIDES real call sites after it.
     */
    fun stripComments(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        fun blank(from: Int, to: Int) { for (j in from until to) if (sb[j] != '\n') sb[j] = ' ' }
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) text.length else end + 3
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    i = j + 1
                }
                text[i] == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '\'') { if (text[j] == '\\') j++; j++ }
                    i = j + 1
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    blank(i, end); i = end
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    blank(i, end); i = end
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    /**
     * Every key reached through a registered accessor, with where it was reached.
     *
     * [ignoreDirs] excludes the i18n package itself by default. Not cosmetic: `Strings.kt`
     * documents the wrapper with `t("chat.send")` and `SourceKeys.kt` carries the accessor
     * patterns as string literals, and counting those made the audit demand that the
     * catalog contain `chat.send` and `key` — two keys that exist in NO LOCALE, because
     * they are examples. An audit whose scope includes its own documentation measures
     * itself.
     */
    fun uses(vararg roots: File, ignoreDirs: List<String> = listOf("/i18n/")): List<Use> {
        val out = ArrayList<Use>()
        for (f in kotlinFiles(*roots)) {
            if (ignoreDirs.any { f.path.replace('\\', '/').contains(it) }) continue
            val text = stripComments(f.readText())
            for (re in ACCESSORS) {
                for (m in re.findAll(text)) {
                    out.add(Use(m.groupValues[1], f, lineOf(text, m.range.first)))
                }
            }
        }
        return out
    }

    fun used(vararg roots: File, ignoreDirs: List<String> = listOf("/i18n/")): Set<String> =
        uses(*roots, ignoreDirs = ignoreDirs).map { it.key }.toSortedSet()

    /** [uses], for [DESKTOP_ACCESSORS]. See that list's doc for what a test would do with it. */
    fun desktopUses(vararg roots: File, ignoreDirs: List<String> = listOf("/i18n/")): List<Use> {
        val out = ArrayList<Use>()
        for (f in kotlinFiles(*roots)) {
            if (ignoreDirs.any { f.path.replace('\\', '/').contains(it) }) continue
            val text = stripComments(f.readText())
            for (re in DESKTOP_ACCESSORS) {
                for (m in re.findAll(text)) out.add(Use(m.groupValues[1], f, lineOf(text, m.range.first)))
            }
        }
        return out
    }

    fun usedDesktop(vararg roots: File, ignoreDirs: List<String> = listOf("/i18n/")): Set<String> =
        desktopUses(*roots, ignoreDirs = ignoreDirs).map { it.key }.toSortedSet()

    /**
     * String literals that ARE catalog keys but never reach a registered accessor.
     *
     * [knownKeys] is the union of every catalog's keys — a literal that matches one is
     * either a key being used through an unregistered wrapper, or a coincidence. Both are
     * worth a human look, which is why this returns them instead of deciding.
     *
     * Files under [ignoreDirs] are skipped: the i18n package itself names keys in doc
     * comments and test fixtures, and the shared Android crypto sources are not ours.
     */
    fun suspects(
        knownKeys: Set<String>,
        vararg roots: File,
        ignoreDirs: List<String> = listOf("/i18n/"),
    ): List<Use> {
        val out = ArrayList<Use>()
        for (f in kotlinFiles(*roots)) {
            if (ignoreDirs.any { f.path.replace('\\', '/').contains(it) }) continue
            val text = stripComments(f.readText())
            val claimed = ACCESSORS.flatMap { re -> re.findAll(text).map { it.groups[1]!!.range } }.toSet()
            for (m in STRING_LITERAL.findAll(text)) {
                val lit = m.groupValues[1]
                if (!KEYISH.matches(lit)) continue
                if (lit !in knownKeys) continue
                if (m.groups[1]!!.range in claimed) continue
                out.add(Use(lit, f, lineOf(text, m.range.first)))
            }
        }
        return out
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset).count { it == '\n' } + 1
}
