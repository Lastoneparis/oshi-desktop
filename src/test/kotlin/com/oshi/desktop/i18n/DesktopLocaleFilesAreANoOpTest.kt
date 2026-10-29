package com.oshi.desktop.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_LOCALE_FILES_ARE_A_NO_OP_2026_10_29__ A `Desktop/<tag>.json` would be dead.
 *
 * [DesktopStrings.RESOURCE] is hard-coded to `/i18n/Desktop/en.json` and parsed as `"en"`.
 * Nothing in this module looks up a per-locale Desktop catalog. So the obvious way to close
 * PARITY row 1.5 — "write one `<tag>.json` per locale beside `en.json`", which is exactly
 * what a localisation pass is aimed at — produces files the app never opens: thousands of
 * translated strings, every one dead, and nothing red to say so.
 *
 * This test is that red. It does not argue the design (the fallback order in
 * `DesktopStrings` does, and it is deliberate: shared chain FIRST, so a sentence that later
 * lands in the iOS tree starts winning the moment it is extracted). It only makes the no-op
 * impossible to ship silently — either those files are serving nobody and should not exist,
 * or the loader changed and this test should be rewritten along with the row.
 */
class DesktopLocaleFilesAreANoOpTest {

    private val dir = File("src/main/resources/i18n/Desktop")

    @Test
    fun `the desktop overlay directory holds English and nothing else`() {
        assertTrue("no $dir — the overlay is CHECKED IN, not generated", dir.isDirectory)
        val names = dir.listFiles { f: File -> f.name.endsWith(".json") }
            .orEmpty().map { it.name }.sorted()
        assertEquals(
            "a per-locale Desktop catalog is DEAD WEIGHT: DesktopStrings reads only " +
                "${DesktopStrings.RESOURCE} and parses it as \"en\", so nothing here is ever " +
                "served. Translating desktop-only copy goes through the iOS tree and " +
                "i18nExtract — see the fallback order in DesktopStrings. Found: $names",
            listOf("en.json"), names,
        )
    }

    @Test
    fun `the resource the loader reads is the English one`() {
        assertEquals("/i18n/Desktop/en.json", DesktopStrings.RESOURCE)
        assertNotNull(
            "the checked-in overlay must be on the classpath",
            DesktopStrings::class.java.getResourceAsStream(DesktopStrings.RESOURCE),
        )
    }

    /**
     * The module must not grow a per-locale Desktop lookup by accident. If one is added
     * deliberately, this test and row 1.5 both need rewriting — which is the point.
     */
    @Test
    fun `no source file looks up a per-locale Desktop catalog`() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                f.readLines().any { line ->
                    val code = line.substringBefore("//")
                    Regex("""i18n/Desktop/(?!en\.json)""").containsMatchIn(code) ||
                        Regex("""Desktop/\$\{?\w""").containsMatchIn(code)
                }
            }
            .map { it.name }
            .toList()
        assertEquals(
            "something now builds a Desktop catalog path per locale. If that is intended, " +
                "DesktopStrings' doc and PARITY row 1.5 are both out of date: $offenders",
            emptyList<String>(), offenders,
        )
    }
}
