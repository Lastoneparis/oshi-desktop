package com.oshi.desktop.i18n

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * __SYSTEM_LANGUAGE_LIST_2027__ The language the window is drawn in when the person has not
 * chosen one: the FIRST language of the system's preference list that OSHI has a catalog for,
 * which is what macOS itself does for a native app (`Bundle.preferredLocalizations`).
 *
 * `Locale.getDefault()` alone was not that. The JVM keeps only the FIRST entry of macOS's
 * `AppleLanguages` (measured: `-AppleLanguages '(fr-FR)'` → `fr_FR`, `(en-US)` → `en_US`), so a
 * Mac whose list is `(gsw-CH, fr-CH)` — a language OSHI has no catalog for, then French — came
 * up in English, where every native app on the same Mac is French. On Linux the JVM ignores
 * `LANGUAGE`, the colon list GNOME and KDE actually write (`LANGUAGE=fr:en`).
 *
 * The list is therefore read in full:
 *  1. macOS: the JVM's own answer first — it honours a per-app language set in System
 *     Settings › Language & Region › Applications (the app's own `AppleLanguages`) — then the
 *     global `AppleLanguages` list;
 *  2. Linux and other Unixes: `LANGUAGE` / `LC_ALL` / `LC_MESSAGES` / `LANG`, then the JVM;
 *  3. the first tag that resolves to a real catalog wins; English only when nothing does, or
 *     when English genuinely comes first.
 *
 * What it deliberately does NOT do: pick French because the REGION is France. A Mac set to
 * "English (US)" with region "France" (`AppleLanguages = (en-US)`, `AppleLocale = fr_FR`) has
 * asked for English text with French dates, and every native app shows it English. The
 * first-run screen carries a language button for exactly that person.
 */
object SystemLanguage {

    /**
     * The first [candidates] entry that has a catalog in [available], or English.
     * Pure, so the whole rule is testable without a Mac.
     */
    fun pick(candidates: List<String>, available: Set<String>): String {
        for (raw in candidates) {
            val tag = Strings.normalize(raw)
            if (tag.isBlank() || tag.equals("C", true) || tag.equals("POSIX", true)) continue
            val chain = Strings.resolve(tag, available)
            val first = chain.firstOrNull() ?: continue
            // `resolve` always ends at English. English is a real answer only when the
            // candidate IS English; otherwise this candidate has no catalog — try the next.
            if (first != Strings.BASE_TAG || tag.substringBefore('-').equals("en", ignoreCase = true)) return first
        }
        return Strings.BASE_TAG
    }

    /**
     * `defaults read -g AppleLanguages` prints an old-style plist array:
     * `(\n    "en-US",\n    "fr-FR"\n)` — quoted when the tag has a hyphen, bare when not (`en`).
     */
    fun parseAppleLanguages(output: String): List<String> {
        val body = output.substringAfter('(', "").substringBeforeLast(')', "")
        return body.split(',')
            .map { it.trim().trim('"').trim() }
            .filter { it.isNotEmpty() }
    }

    /** `LANGUAGE=fr:de` then `LC_ALL` / `LC_MESSAGES` / `LANG`, in glibc's own order. */
    fun unixCandidates(env: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        env["LANGUAGE"]?.split(':')?.filter { it.isNotBlank() }?.let(out::addAll)
        for (k in listOf("LC_ALL", "LC_MESSAGES", "LANG")) env[k]?.takeIf { it.isNotBlank() }?.let { out += it; }
        return out
    }

    /** The whole preference list for this machine, most preferred first. */
    fun candidates(
        jvmDefault: String = Locale.getDefault().toLanguageTag(),
        os: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
        appleLanguages: () -> List<String> = ::readAppleLanguages,
    ): List<String> {
        val lower = os.lowercase(Locale.ROOT)
        return when {
            // The JVM first: on macOS it carries a per-app language the global list does not.
            lower.contains("mac") -> listOf(jvmDefault) + appleLanguages()
            lower.contains("win") -> listOf(jvmDefault)
            // On Linux the JVM's value is derived from LANG alone; LANGUAGE outranks it.
            else -> unixCandidates(env) + jvmDefault
        }.distinct()
    }

    /** The tag to draw in when no language has been chosen. Never throws. */
    fun detect(available: Set<String> = Strings.availableTags): String =
        runCatching { pick(candidates(), available) }.getOrDefault(Strings.BASE_TAG)

    private fun readAppleLanguages(): List<String> = runCatching {
        val p = ProcessBuilder("/usr/bin/defaults", "read", "-g", "AppleLanguages")
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return emptyList() }
        if (p.exitValue() != 0) emptyList() else parseAppleLanguages(text)
    }.getOrDefault(emptyList())
}
