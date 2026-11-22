package com.oshi.desktop.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemLanguageTest {
    private val available = Strings.availableTags

    @Test
    fun `a French Mac is drawn in French`() {
        val list = SystemLanguage.candidates("fr-FR", "Mac OS X", emptyMap()) { listOf("fr-FR", "en-US") }
        assertEquals("fr", SystemLanguage.pick(list, available))
    }

    @Test
    fun `a first language with no catalog falls through to the next one, not to English`() {
        // The JVM keeps only `gsw-CH`; macOS's own rule goes on to French.
        val list = SystemLanguage.candidates("gsw-CH", "Mac OS X", emptyMap()) { listOf("gsw-CH", "fr-CH", "en") }
        assertEquals("fr", SystemLanguage.pick(list, available))
    }

    @Test
    fun `English first is English, even with a French region`() {
        // AppleLanguages = (en-US), AppleLocale = fr_FR — the measured state of the dev Mac.
        val list = SystemLanguage.candidates("en-US", "Mac OS X", emptyMap()) { listOf("en-US") }
        assertEquals("en", SystemLanguage.pick(list, available))
    }

    @Test
    fun `a per-app language the JVM reports wins over the global list`() {
        val list = SystemLanguage.candidates("de-DE", "Mac OS X", emptyMap()) { listOf("fr-FR") }
        assertEquals("de", SystemLanguage.pick(list, available))
    }

    @Test
    fun `script and region variants reach their catalog`() {
        assertEquals("zh-Hant", SystemLanguage.pick(listOf("zh-Hant-TW"), available))
        assertEquals("pt-BR", SystemLanguage.pick(listOf("pt-BR"), available))
        assertEquals("he", SystemLanguage.pick(listOf("iw-IL"), available))
    }

    @Test
    fun `Linux LANGUAGE list is read in order, and C is skipped`() {
        val env = mapOf("LANGUAGE" to "gsw:it:en", "LANG" to "C.UTF-8")
        val list = SystemLanguage.candidates("en-US", "Linux", env) { error("not a Mac") }
        // The JVM reports en-US for LANG=C; LANGUAGE says Italian after a language we lack.
        assertEquals("it", SystemLanguage.pick(list, available))
    }

    @Test
    fun `defaults output parses quoted and bare tags`() {
        val out = "(\n    \"en-US\",\n    fr,\n    \"zh-Hans-CN\"\n)\n"
        assertEquals(listOf("en-US", "fr", "zh-Hans-CN"), SystemLanguage.parseAppleLanguages(out))
        assertEquals(emptyList<String>(), SystemLanguage.parseAppleLanguages("garbage"))
    }

    @Test
    fun `nothing usable means English`() {
        assertEquals("en", SystemLanguage.pick(listOf("", "C", "xx-YY"), available))
    }
}
