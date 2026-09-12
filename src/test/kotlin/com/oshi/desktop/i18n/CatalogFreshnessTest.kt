package com.oshi.desktop.i18n

import com.oshi.desktop.i18n.tools.CatalogExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The checked-in catalogs still match the iOS tree they came from.
 *
 * Generated files that are committed drift. This is the only thing standing between
 * `src/main/resources/i18n/` and the state this project has a memory of — a shipped
 * artifact months behind the source tree, where nothing in the build was red because
 * nothing in the build was comparing.
 *
 * It SKIPS where the iOS tree is absent, which is every Windows and Linux runner, and
 * that is the correct behaviour: those machines are where the checked-in files have to
 * work, not where they are authored.
 */
class CatalogFreshnessTest {

    private val iosRoot: File? =
        System.getProperty("oshi.ios.root")?.let { File(it) }?.takeIf { it.isDirectory }

    private val resources = File("src/main/resources")

    @Test
    fun `the checked-in catalogs are byte-identical to a fresh extraction`() {
        assumeTrue("no iOS tree on this machine — the checked-in files are the contract here", iosRoot != null)
        val fresh = CatalogExtractor.render(iosRoot!!)
        val differences = ArrayList<String>()
        for ((rel, body) in fresh) {
            val f = File(resources, rel)
            if (!f.isFile) differences.add("$rel — MISSING from src/main/resources")
            else CatalogDiff.describe(rel, f.readText(Charsets.UTF_8), body)?.let(differences::add)
        }
        // and nothing stale: a locale removed upstream must not linger here
        for (sub in listOf("Localizable", "InfoPlist")) {
            File(resources, "i18n/$sub").listFiles()?.forEach {
                if ("i18n/$sub/${it.name}" !in fresh) differences.add("i18n/$sub/${it.name} — STALE, not in the iOS tree")
            }
        }
        assertTrue(
            "src/main/resources/i18n is out of date with ${iosRoot.path}. Run:\n" +
                "  ./gradlew i18nExtract -PoshiIosRoot=${iosRoot.path}\n" +
                differences.take(20).joinToString("\n"),
            differences.isEmpty(),
        )
    }

    @Test
    fun `the shipped resources hold 34 locales and are loadable from the CLASSPATH`() {
        // Not from the file system: the packaged .msi has no src/ directory, and reading
        // the catalogs off disk in a test would pass on a developer's machine and prove
        // nothing about the installer. Everything here goes through getResourceAsStream.
        assertEquals(34, Strings.availableTags.size)
        for (tag in Strings.availableTags) {
            val c = Strings.catalog(tag)
            assertTrue("$tag did not load from the classpath", c != null)
            assertTrue("$tag loaded with only ${c!!.size} keys", c.size > 3000)
            assertTrue("$tag has no InfoPlist beside it",
                javaClass.getResource("/i18n/InfoPlist/$tag.json") != null)
        }
        assertTrue("en must be present — it is the end of every fallback chain",
            Strings.BASE_TAG in Strings.availableTags)
    }

    @Test
    fun `the catalogs are inside the jar, not merely on the source tree`() {
        // The requirement is that the 34 locales ship INSIDE the packaged app. The thing
        // that makes that true is these files being in src/main/resources, which Gradle
        // puts in the jar and jpackage stages. Assert the location, because a catalog
        // read from a sibling iOS checkout works perfectly for everyone who has one.
        val url = javaClass.getResource("/i18n/Localizable/fr.json")!!.toString()
        assertTrue("fr.json resolved from an unexpected place: $url",
            url.contains("/build/") || url.startsWith("jar:"))
        assertTrue("a catalog must never resolve out of the iOS tree: $url",
            !url.contains("OSHI/") || url.contains("OSHI-Desktop") || url.contains("/loc/"))
    }

    // ---- the difference reporter itself -------------------------------------
    // These run on every machine: they need no iOS tree, and the case they cover is
    // precisely the one that only ever appears on a machine this suite is not run on.

    @Test
    fun `a line-ending difference is NAMED, never reported as line 0`() {
        val lf = "{\n  \"a\": \"1\"\n}\n"
        val crlf = lf.replace("\n", "\r\n")
        val d = CatalogDiff.describe("i18n/Localizable/fr.json", crlf, lf)
        assertTrue("a CRLF-vs-LF difference must be reported at all", d != null)
        assertTrue("it must name line endings, not print a null line: $d", d!!.contains("LINE ENDINGS"))
        assertTrue("it must count the carriage returns: $d", d.contains("3 CR"))
        assertTrue("it must not claim a line number it does not have: $d", !d.contains("line 0"))
        assertTrue("it must not print a null line: $d", !d.contains("null"))
    }

    @Test
    fun `identical text is not a difference`() {
        assertEquals(null, CatalogDiff.describe("x.json", "{\n  \"a\": \"1\"\n}\n", "{\n  \"a\": \"1\"\n}\n"))
    }

    @Test
    fun `a real content difference still names its line and both sides`() {
        val d = CatalogDiff.describe("x.json", "{\n  \"a\": \"1\"\n}\n", "{\n  \"a\": \"2\"\n}\n")
        assertTrue("a content drift must still be located: $d", d != null && d.contains("differs at line 2"))
        assertTrue("both sides must be shown: $d", d!!.contains("\"1\"") && d.contains("\"2\""))
    }

    @Test
    fun `a trailing-newline difference is named rather than left as a null line`() {
        val d = CatalogDiff.describe("x.json", "{\n  \"a\": \"1\"\n}\n", "{\n  \"a\": \"1\"\n}")
        assertTrue("a trailing-newline difference must be reported: $d", d != null)
        assertTrue("it must say so: $d", d!!.contains("TRAILING NEWLINE"))
        assertTrue("it must not print a null line: $d", !d.contains("null"))
    }

    @Test
    fun `a locale removed upstream would be removed here too`() {
        // writeTo deletes stale files. Proven on a scratch directory rather than on
        // src/main/resources, which this test is not allowed to damage.
        assumeTrue("no iOS tree on this machine", iosRoot != null)
        val scratch = createTempDir("i18n-stale")
        try {
            val stale = File(scratch, "i18n/Localizable/xx.json")
            stale.parentFile.mkdirs()
            stale.writeText("{}")
            CatalogExtractor.writeTo(iosRoot!!, scratch)
            assertTrue("a stale locale file survived regeneration", !stale.exists())
            assertTrue(File(scratch, "i18n/Localizable/fr.json").isFile)
        } finally {
            scratch.deleteRecursively()
        }
    }
}

/**
 * Names the difference between a checked-in catalog and a fresh render.
 *
 * A line-ending difference is INVISIBLE to a line-by-line diff: `lines()` folds
 * "\r\n", "\r" and "\n" into the same list, so two texts that differ in every
 * terminator and in nothing else compare equal line-for-line. The first version of
 * this report took `indexOfFirst`'s -1 for an index and printed
 *
 *     ar.json — differs at line 0: checked-in=null fresh=null
 *
 * for all 68 catalogs, which is how a `core.autocrlf` checkout on the Windows runner
 * cost a full CI cycle to identify. Every branch here exists to name a difference the
 * naive diff renders as `null`.
 */
internal object CatalogDiff {

    private const val BOM = '\uFEFF'

    fun describe(rel: String, checkedIn: String, fresh: String): String? {
        if (checkedIn == fresh) return null

        val a = checkedIn.lines()
        val b = fresh.lines()
        val at = a.zip(b).indexOfFirst { it.first != it.second }
        if (at >= 0) {
            return "$rel — differs at line ${at + 1}: checked-in=${a[at].take(80)} fresh=${b[at].take(80)}"
        }

        // The lines agree. Whatever differs is something `lines()` throws away.
        val notes = ArrayList<String>()

        val crIn = checkedIn.count { it == '\r' }
        val crFresh = fresh.count { it == '\r' }
        if (crIn != crFresh) {
            notes.add("LINE ENDINGS: checked-in carries $crIn CR, a fresh render carries $crFresh. " +
                "The bytes differ and the text does not — a checkout rewrote the terminators. " +
                "Pin the path in .gitattributes with `text eol=lf` rather than regenerating")
        }
        if (checkedIn.startsWith(BOM) != fresh.startsWith(BOM)) {
            notes.add("BYTE-ORDER MARK: present on ${if (checkedIn.startsWith(BOM)) "the checked-in file" else "the fresh render"} only")
        }
        if (a.size != b.size) {
            notes.add("TRAILING NEWLINE: checked-in ends with ${a.size} line(s), fresh with ${b.size}")
        }
        if (notes.isEmpty()) {
            notes.add("bytes differ but every line matches: ${checkedIn.length} chars checked-in vs ${fresh.length} fresh")
        }
        return "$rel — ${notes.joinToString("; ")}"
    }
}
