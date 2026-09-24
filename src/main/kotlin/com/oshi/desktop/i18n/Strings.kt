package com.oshi.desktop.i18n

import java.util.Locale

/**
 * The desktop client's string lookup. Same keys as the phones.
 *
 * ## The fallback chain, in full
 *
 *     requested tag  →  its language  →  en  →  ⟦the key itself⟧
 *
 * Nothing else. In particular **not** `Locale.getDefault()`, which is what
 * `ResourceBundle` inserts between step 1 and step 3 and which makes the answer depend on
 * the machine (see [Catalog]'s doc). [resolve] is a pure function of a tag and the set of
 * catalogs that exist, so a test can assert the whole chain without touching the JVM's
 * locale — and `StringsLookupTest.chain_does_not_consult_the_jvm_default` proves it by
 * running the same lookup under three different `Locale.setDefault` values.
 *
 * ## Step 1 is not `equals`
 *
 * A naive chain looks right and strands three of the biggest catalogs OSHI paid for:
 *
 *  * **`zh-CN` is what a Chinese Windows box reports** and the catalog is filed as
 *    `zh-Hans`. Tag-equality misses, `zh` misses, and 1.1 billion people get English out
 *    of an app that has a complete Simplified Chinese translation.
 *  * **The JVM renames three languages.** `Locale("he").language` is `"iw"`,
 *    `Locale("id").language` is `"in"`, and `Locale("yi").language` is `"ji"` — ISO-639
 *    codes frozen in 1989 that `java.util.Locale` still returns for backward
 *    compatibility. OSHI ships `he.lproj` and `id.lproj`. Without [ALIASES], Hebrew and
 *    Indonesian resolve to catalogs named `iw` and `in` that do not exist.
 *  * **`no` vs `nb`.** Norwegian Bokmål is filed as `nb`; a JVM may report either.
 *
 * These are aliases, not "likely subtags": the JDK has no `addLikelySubtags` (that is
 * ICU), so the table is explicit and every row names the catalog it points at. A row for
 * a catalog that does not exist is a bug, and `StringsLookupTest.every_alias_points_at_a_real_catalog`
 * fails on one.
 *
 * ## A miss is visible
 *
 * [get] on an unknown key returns `⟦key⟧`, never "" and never the last locale's value.
 * A blank string in a UI is a bug nobody files; `⟦chat.send⟧` on a button is a bug
 * somebody screenshots. Every miss also goes to [onMissingKey] so an audit can count them.
 */
object Strings {

    /** Where the checked-in catalogs live on the classpath. */
    const val RESOURCE_ROOT = "/i18n"

    /** The catalog every chain ends at. */
    const val BASE_TAG = "en"

    /**
     * Requested-tag → catalog-tag, for tags the catalog set does not name directly.
     *
     * Keys are lowercase. Applied to the full tag first, then to the language alone.
     */
    val ALIASES: Map<String, String> = mapOf(
        // Chinese: script, not region. The catalogs are zh-Hans / zh-Hant; every JVM and
        // every OS reports a REGION.
        "zh" to "zh-Hans",
        "zh-cn" to "zh-Hans", "zh-sg" to "zh-Hans", "zh-my" to "zh-Hans", "zh-hans" to "zh-Hans",
        "zh-tw" to "zh-Hant", "zh-hk" to "zh-Hant", "zh-mo" to "zh-Hant", "zh-hant" to "zh-Hant",
        // Portuguese: there is no bare `pt` catalog. Portugal is the unmarked form.
        "pt" to "pt-PT",
        // Norwegian: `no` is the macrolanguage, `nb` is what is translated.
        "no" to "nb", "nn" to "nb", "nb-no" to "nb", "no-no" to "nb",
        // The JVM's frozen ISO-639 codes. `Locale("he").getLanguage()` really is "iw".
        // `ji`→`yi` (Yiddish) belongs to the same family and is DELIBERATELY absent:
        // there is no yi.lproj, and an alias pointing at a catalog that does not exist is
        // a chain step that silently does nothing. `every alias points at a real catalog`
        // is the test that rejected it.
        "iw" to "he", "in" to "id",
        // Serbo-Croatian-adjacent and Filipino are NOT here for the same reason: OSHI has
        // no catalog for them, so pointing at one would be inventing coverage.
    )

    /** Called for every key that is not found in ANY catalog on the chain. */
    @Volatile
    var onMissingKey: ((key: String, requestedTag: String) -> Unit)? = null

    /** How a miss is rendered. Visible by construction. */
    fun missingMarker(key: String): String = "⟦$key⟧"

    // ------------------------------------------------------------------ the chain

    /**
     * The ordered list of catalog tags to try for [requested], given [available].
     *
     * Pure: no I/O, no `Locale.getDefault()`. `available` is passed in rather than read
     * from a loaded set so the chain can be tested against a catalog set that does not
     * exist on disk.
     */
    fun resolve(requested: String, available: Set<String>): List<String> {
        val chain = LinkedHashSet<String>()
        val norm = normalize(requested)
        val lower = norm.lowercase(Locale.ROOT)

        fun offer(tag: String?) {
            if (tag != null && tag in available) chain.add(tag)
        }

        // 1. the tag as asked for, then its alias
        offer(available.firstOrNull { it.equals(norm, ignoreCase = true) })
        offer(ALIASES[lower])

        // 2. the language alone, then ITS alias
        val lang = lower.substringBefore('-')
        offer(available.firstOrNull { it.equals(lang, ignoreCase = true) })
        offer(ALIASES[lang])

        // 3. English
        offer(BASE_TAG)
        return chain.toList()
    }

    /** `pt_BR`, `PT-br`, `pt_BR.UTF-8` → `pt-BR`. */
    fun normalize(tag: String): String =
        tag.trim().substringBefore('.').substringBefore('@').replace('_', '-')

    // ------------------------------------------------------------------ loading

    private val loaded = HashMap<String, Catalog>()

    /** Tags for which a catalog file exists on the classpath. */
    val availableTags: Set<String> by lazy {
        val listing = Strings::class.java.getResourceAsStream("$RESOURCE_ROOT/locales.txt")
            ?: error(
                "no $RESOURCE_ROOT/locales.txt on the classpath. The catalogs are generated by " +
                    "CatalogExtractor into src/main/resources/i18n and CHECKED IN; if they are " +
                    "gone the app would silently run English-only, so this fails instead.",
            )
        listing.bufferedReader(Charsets.UTF_8).readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    }

    @Synchronized
    fun catalog(tag: String): Catalog? {
        loaded[tag]?.let { return it }
        if (tag !in availableTags) return null
        val stream = Strings::class.java.getResourceAsStream("$RESOURCE_ROOT/Localizable/$tag.json")
            ?: error("$tag is in locales.txt but $RESOURCE_ROOT/Localizable/$tag.json is not on the classpath")
        val c = Catalog.read(tag, stream)
        loaded[tag] = c
        return c
    }

    /** Test seam: forget what has been loaded, so a test can swap the classpath. */
    @Synchronized
    internal fun resetForTest() = loaded.clear()

    // ------------------------------------------------------------------ lookup

    /**
     * The active locale for the running app.
     *
     * This is the ONE place `Locale.getDefault()` is read — as an INPUT chosen once at
     * startup, never as a hidden step inside the chain. Settable, because a desktop app
     * has a language preference and because a test needs to drive it.
     */
    @Volatile
    var activeTag: String = normalize(Locale.getDefault().toLanguageTag())

    /** The raw string for [key] in [tag], following the chain. `null` only if truly absent. */
    fun lookup(key: String, tag: String = activeTag): String? {
        for (t in resolve(tag, availableTags)) catalog(t)?.get(key)?.let { return it }
        return null
    }

    /**
     * The string for [key], or a VISIBLE marker.
     *
     * A LABEL, not a format string: the value is returned exactly as translated and the
     * formatter is never involved, which is what iOS's `NSLocalizedString` does for the
     * same key. So `about.openSource.paragraph1` comes back with its `100% open source`
     * intact and a `%%` that a translator wrote stays `%%`. Use [format] when the call
     * site actually has arguments — that is the entry point that converts.
     */
    fun get(key: String, tag: String = activeTag): String {
        lookup(key, tag)?.let { return it }
        onMissingKey?.invoke(key, tag)
        return missingMarker(key)
    }

    /**
     * The string for [key], formatted with [args] after iOS→Java conversion.
     *
     * If the catalog's string for the ACTIVE locale takes the wrong number of arguments —
     * a translator dropped a `%@` — this falls back down the chain to a string that fits
     * rather than throwing in the middle of a paint, and reports the bad one through
     * [onMissingKey] tagged with the key. A sentence in English beats a stack trace.
     */
    fun format(key: String, vararg args: Any?, tag: String = activeTag): String {
        for (t in resolve(tag, availableTags)) {
            val raw = catalog(t)?.get(key) ?: continue
            if (IosFormat.arity(raw) == args.size) return IosFormat.format(raw, *args)
            onMissingKey?.invoke("$key (arity ${IosFormat.arity(raw)} != ${args.size} in $t)", tag)
        }
        onMissingKey?.invoke(key, tag)
        return missingMarker(key)
    }
}

/**
 * The short name every call site uses: `t("chat.send")`.
 *
 * **This is a custom wrapper, and a key-usage audit that greps for `Strings.get(` alone
 * would not see a single key that goes through it.** That is a real, recorded failure
 * mode in this project — an audit blind to `FeatureRow(textKey:)` reported a fully
 * localised paywall that was English in 34 languages. So the scanner in
 * `SourceKeys` matches on the SHAPE of a key literal reaching any of a NAMED,
 * TESTED list of key-taking functions, and `SourceKeyScanTest.audit_sees_keys_through_the_t_wrapper`
 * is the test that fails if `t` is ever dropped from that list.
 */
fun t(key: String): String = Strings.get(key)

/**
 * A catalog key held as DATA and rendered later with [t] — a call summary written on the
 * wire as `📞↗️call.outgoing|0:12`, or the reason a call ended kept in UI state. It returns
 * [key] unchanged; it exists so `SourceKeys.ACCESSORS` sees the key and the catalog audit
 * holds every locale to it, exactly as if it were a `t("…")` call.
 */
fun catalogKey(key: String): String = key

/** `t` with arguments. Same wrapper caveat, same scanner entry. */
fun t(key: String, vararg args: Any?): String = Strings.format(key, *args)
