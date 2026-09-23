package com.oshi.desktop.i18n

/**
 * The strings this WINDOW says that no phone has ever said.
 *
 * ============================================================ WHY THIS EXISTS AT ALL
 *
 * The window's chrome reuses iOS keys wherever the phone already has the sentence — that is
 * the whole point of extracting thousands of keys × 34 locales instead of writing new copy. But a
 * desktop client says things a phone cannot:
 *
 *  * REPL instructions (`/qr`, `/send`, `./oshi.sh run --args="--client"`);
 *  * the "what this client will not do" screen, which is a list of PARITY.md rows;
 *  * "delivery stops when this window closes" — there is no push on desktop (row 2.3);
 *  * the desktop's at-rest message-history posture.
 *
 * None of those has an iOS counterpart, because none of them is true of iOS.
 *
 * ============================================================ WHY NOT JUST ADD THEM TO en.json
 *
 * `src/main/resources/i18n/Localizable/en.json` is GENERATED from `OSHI/en.lproj` and
 * `CatalogFreshnessTest` asserts it is byte-identical to a fresh extraction. A key added
 * there is deleted by the next `./gradlew i18nExtract` and red in the suite until it is —
 * and the test would be RIGHT: that file is a copy of the iOS asset, and editing a copy is
 * how a copy starts lying about its source.
 *
 * So the desktop's own strings live in a SEPARATE file, `i18n/Desktop/en.json`, which the
 * extractor neither writes nor prunes (`CatalogExtractor.writeTo` only deletes stale files
 * under `Localizable/` and `InfoPlist/`). The shared catalogs stay a faithful copy of the
 * phone's, and this file stays the desktop's.
 *
 * ============================================================ THE FALLBACK ORDER, AND WHY
 *
 *     shared catalog chain (34 locales)  →  this overlay (English only)  →  ⟦key⟧
 *
 * Shared FIRST, deliberately. If a string here is ever translated and lands in the iOS
 * tree under the same key, the translation starts winning the moment it is extracted, with
 * no code change and no entry to delete. The reverse order would pin the app to English
 * for every key this file has ever held.
 *
 * ============================================================ WHAT THIS IS NOT
 *
 * **It is not a translation.** Every value here is English and is served to all 34 locales.
 * A French user sees French for everything that came from the phone and English for the
 * strings in this file. That is a known, counted hole — see the report in the commit that
 * introduced it — and the fix is translation into the iOS tree, not inventing translations
 * here. This project extracts translations; it does not write them.
 *
 * **It is not on the shared audit's books.** `CatalogAuditTest` asks "does English have
 * every key the source uses" and "does every locale serve it from its OWN catalog". Both
 * questions are about the SHARED catalogs, and both would be answered "no" for every key
 * here — correctly, and uselessly. So the keys here are reached through [dt] rather than
 * through `t`, and `SourceKeys.ACCESSORS` does not know about [dt], which keeps the shared
 * audit's scope exactly what its assertions mean.
 *
 * That is a hole in the audit, not a fact about the code, so the seams a test needs are
 * public and named: [overlayKeys], [misfiledKeys], [shadowedKeys] here, and
 * `SourceKeys.usedDesktop` next door. `CatalogAuditTest` compares their exact sets, so a
 * window-only marker, an unnecessary English override, and stale desktop copy all fail CI.
 */
object DesktopStrings {

    /** Where the desktop's own catalog lives on the classpath. Not under `Localizable/`. */
    const val RESOURCE = "/i18n/Desktop/en.json"

    /**
     * Every key in this catalog starts with it.
     *
     * Not cosmetic: the shared catalogs hold 4854 keys across 34 locales and an unprefixed
     * desktop key could collide with one of them — at which point the shared value silently
     * wins (see the fallback order above) and the desktop's sentence is replaced by a
     * phone's, in every language, with nothing red. [misfiledKeys] is what notices.
     */
    const val PREFIX = "desktop."

    private val overlay: Catalog by lazy {
        val stream = DesktopStrings::class.java.getResourceAsStream(RESOURCE)
            ?: error(
                "no $RESOURCE on the classpath. This file is CHECKED IN, not generated; if it " +
                    "is gone the window renders ⟦desktop.…⟧ markers everywhere rather than " +
                    "falling back to anything, so this fails loudly instead.",
            )
        Catalog.read("en", stream)
    }

    // ------------------------------------------------------------------ audit seams

    /** The keys this overlay carries. Public so an audit can compare them to the source. */
    val overlayKeys: Set<String> get() = overlay.keys

    /** Overlay keys that do not carry [PREFIX] — see its doc for why that is a defect. */
    fun misfiledKeys(): Set<String> = overlay.keys.filterNot { it.startsWith(PREFIX) }.toSet()

    /**
     * Overlay keys the SHARED catalogs also define.
     *
     * Dead weight by construction: the shared chain is consulted first, so the value here
     * can never be served. Either the shared one is the right string and this line should
     * go, or they disagree and one of them is wrong.
     */
    fun shadowedKeys(): Set<String> =
        overlay.keys.filter { Strings.catalog(Strings.BASE_TAG)?.get(it) != null }.toSet()

    // ------------------------------------------------------------------ lookup

    /** The raw string for [key]: shared catalogs first, then this overlay. `null` if neither. */
    fun lookup(key: String, tag: String = Strings.activeTag): String? =
        Strings.lookup(key, tag) ?: overlay[key]

    /**
     * The string for [key], or the same VISIBLE marker `Strings.get` uses.
     *
     * A miss goes through `Strings.onMissingKey` like any other, so one audit counts both
     * catalogs' holes rather than each having its own silence.
     */
    fun get(key: String, tag: String = Strings.activeTag): String {
        lookup(key, tag)?.let { return it }
        Strings.onMissingKey?.invoke(key, tag)
        return Strings.missingMarker(key)
    }

    /**
     * [get] with arguments, formatted after iOS→Java conversion.
     *
     * Same arity discipline as `Strings.format`: a string that takes the wrong number of
     * arguments is reported and rendered as a marker rather than throwing inside a paint.
     * There is no per-locale fallback to try here — the overlay is English and English only
     * — so a mismatch is a source bug rather than a translator's.
     */
    fun format(key: String, vararg args: Any?, tag: String = Strings.activeTag): String {
        val raw = lookup(key, tag) ?: run {
            Strings.onMissingKey?.invoke(key, tag)
            return Strings.missingMarker(key)
        }
        if (IosFormat.arity(raw) != args.size) {
            Strings.onMissingKey?.invoke("$key (arity ${IosFormat.arity(raw)} != ${args.size})", tag)
            return Strings.missingMarker(key)
        }
        return IosFormat.format(raw, *args)
    }
}

/**
 * `dt("desktop.menu.quit")` — the desktop's own strings. **Deliberately not `t`.**
 *
 * The name is different because the SCOPE is different, and `SourceKeys.ACCESSORS`
 * deliberately does not match it. `CatalogAuditTest` uses that list to decide which keys
 * every one of the 34 catalogs must serve from its own file; a `desktop.` key is in exactly
 * one catalog by design, so folding it into that list would turn a true assertion about the
 * shared asset into a permanently red one about a deliberate choice.
 *
 * The cost is real and is recorded in [DesktopStrings]' doc: these keys are audited by
 * nothing on the shared-catalog path. `CatalogAuditTest` audits it through
 * `SourceKeys.usedDesktop` instead.
 *
 * Choose [dt] ONLY when the shared catalogs have no sentence for this. If they do, use `t` —
 * a `desktop.` key where an iOS key existed ships English to 33 locales for nothing.
 */
fun dt(key: String): String = DesktopStrings.get(key)

/** [dt] with arguments. */
fun dt(key: String, vararg args: Any?): String = DesktopStrings.format(key, *args)
