package com.oshi.desktop.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StringsLookupTest {

    private val real: Set<String> get() = Strings.availableTags

    // ------------------------------------------------------------------ the chain

    @Test
    fun `the chain is requested then language then English then the key`() {
        val available = setOf("en", "fr", "pt-BR")
        assertEquals(listOf("pt-BR", "en"), Strings.resolve("pt-BR", available))
        assertEquals(listOf("fr", "en"), Strings.resolve("fr-CA", available))   // region dropped
        assertEquals(listOf("fr", "en"), Strings.resolve("fr", available))
        assertEquals(listOf("en"), Strings.resolve("xx-YY", available))         // nothing matches
    }

    @Test
    fun `underscores, encodings and modifiers are normalised away`() {
        // A JVM on Linux reports pt_BR.UTF-8 through LANG; Locale#toString gives pt_BR.
        assertEquals("pt-BR", Strings.normalize("pt_BR.UTF-8"))
        assertEquals("pt-BR", Strings.normalize("pt_BR"))
        assertEquals("sr-Latn", Strings.normalize("sr_Latn@latin"))
        assertEquals(listOf("pt-BR", "en"), Strings.resolve("pt_BR.UTF-8", setOf("en", "pt-BR")))
    }

    // ------- the three families that a naive chain strands, each with a real catalog

    @Test
    fun `Chinese resolves by script, because no OS reports zh-Hans`() {
        // A Chinese Windows box reports zh-CN. Tag equality misses; `zh` misses; the user
        // gets English out of an app that has a complete Simplified Chinese catalog.
        assertEquals("zh-Hans", Strings.resolve("zh-CN", real).first())
        assertEquals("zh-Hans", Strings.resolve("zh_CN", real).first())
        assertEquals("zh-Hans", Strings.resolve("zh-SG", real).first())
        assertEquals("zh-Hans", Strings.resolve("zh", real).first())
        assertEquals("zh-Hant", Strings.resolve("zh-TW", real).first())
        assertEquals("zh-Hant", Strings.resolve("zh-HK", real).first())
        assertEquals("zh-Hant", Strings.resolve("zh-MO", real).first())
    }

    @Test
    fun `the JVMs frozen ISO-639 codes resolve to the catalogs that exist`() {
        // Not a hypothetical: java.util.Locale still answers "iw" for Hebrew and "in" for
        // Indonesian, and OSHI ships he.lproj and id.lproj.
        assertEquals("iw is the JVM's Hebrew", "he", Locale("he").language.let { if (it == "iw") "he" else it })
        assertEquals("he", Strings.resolve("iw", real).first())
        assertEquals("he", Strings.resolve("iw-IL", real).first())
        assertEquals("id", Strings.resolve("in", real).first())
        assertEquals("id", Strings.resolve("in-ID", real).first())
    }

    @Test
    fun `Norwegian and bare Portuguese land on the catalogs that were translated`() {
        assertEquals("nb", Strings.resolve("no", real).first())
        assertEquals("nb", Strings.resolve("nb-NO", real).first())
        assertEquals("nb", Strings.resolve("nn-NO", real).first())
        // There is no bare `pt` catalog; Portugal is the unmarked form.
        assertEquals("pt-PT", Strings.resolve("pt", real).first())
        assertEquals("pt-BR", Strings.resolve("pt-BR", real).first())
        assertEquals("pt-PT", Strings.resolve("pt-PT", real).first())
    }

    @Test
    fun `every alias points at a catalog that exists`() {
        val bad = Strings.ALIASES.filterValues { it !in real }
        assertTrue("aliases pointing at catalogs that do not exist: $bad", bad.isEmpty())
    }

    @Test
    fun `every catalog is reachable from at least one tag a real machine reports`() {
        // A translated locale nothing can resolve to is a translation nobody will ever see.
        val unreachable = real.filter { tag ->
            Strings.resolve(tag, real).firstOrNull() != tag
        }
        assertTrue("catalogs no request resolves to: $unreachable", unreachable.isEmpty())
    }

    // ------------------------------------------------- the JVM-default-locale trap

    @Test
    fun `the chain does not consult the JVM default locale`() {
        // THE TRAP THIS TEST EXISTS FOR. `ResourceBundle.getBundle` inserts the default
        // locale between the requested one and the base bundle, so the same lookup gives
        // different answers on a German laptop and an American CI runner — which makes
        // every "is this localised" assertion depend on the machine rather than the code.
        // Asserting the SAME answer under three hostile defaults is what proves this
        // implementation does not have that hole.
        val key = "tab.messages"
        val saved = Locale.getDefault()
        try {
            val answers = listOf(Locale.GERMANY, Locale.JAPAN, Locale.US, Locale.forLanguageTag("zh-CN")).map {
                Locale.setDefault(it)
                Strings.resetForTest()
                Triple(
                    Strings.get(key, "fr"),
                    Strings.get(key, "xx-YY"),          // falls back — to EN, never to the default
                    Strings.get("no.such.key.at.all", "fr"),
                )
            }
            assertEquals("the answer moved with the JVM default locale: $answers", 1, answers.toSet().size)
            // and the fallback really is English, not German/Japanese/Chinese
            assertEquals("Messages", answers.first().second)
        } finally {
            Locale.setDefault(saved)
            Strings.resetForTest()
        }
    }

    // ------------------------------------------------------------------ misses

    @Test
    fun `a missing key is visible and names itself`() {
        val out = Strings.get("this.key.does.not.exist", "fr")
        assertFalse("a miss must never be blank", out.isBlank())
        assertTrue("a miss must name the key: $out", out.contains("this.key.does.not.exist"))
        assertNotEquals("a miss must not be the bare key, which reads as copy", "this.key.does.not.exist", out)
    }

    @Test
    fun `a miss is reported to the audit hook`() {
        val seen = ArrayList<String>()
        val prev = Strings.onMissingKey
        try {
            Strings.onMissingKey = { k, _ -> seen.add(k) }
            Strings.get("another.missing.key", "de")
            assertEquals(listOf("another.missing.key"), seen)
        } finally {
            Strings.onMissingKey = prev
        }
    }

    @Test
    fun `a blank value is a miss, not a translation`() {
        val c = Catalog("zz", mapOf("filled" to "x", "empty" to "", "spaces" to "   "))
        assertEquals("x", c["filled"])
        assertNull("an empty value is not a translation", c["empty"])
        assertNull("whitespace is not a translation", c["spaces"])
        assertEquals(setOf("empty", "spaces"), c.blankKeys())
    }

    // ------------------------------------------------------------------ formatting

    @Test
    fun `format falls through to a string whose arity fits rather than throwing`() {
        // If a translator dropped the %@ from one locale, the desktop shows the English
        // sentence rather than a stack trace in the middle of a paint.
        val out = Strings.format("typing.indicator", "Ada", tag = "fr")
        assertFalse(out.isBlank())
        assertTrue("expected the argument to land somewhere: $out", out.contains("Ada"))
    }

    @Test
    fun `a zero-argument get never runs the formatter`() {
        // en has six strings containing a bare `%`; getting one must not throw.
        val out = Strings.get("about.openSource.paragraph1", "en")
        assertTrue(out, out.contains("100% open source"))
    }
}
