package com.oshi.desktop.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `%@` → Java story, and the traps around it.
 *
 * The headline claim of this file is not any one of the small cases: it is
 * [every_shipped_string_converts_and_formats_without_throwing], which runs the converter
 * over every string in all 34 checked-in catalogs and formats each one. That is what
 * "prove it" means for 130,000 strings.
 */
class IosFormatTest {

    // ------------------------------------------------------------------ the conversion

    @Test
    fun `at-sign becomes a Java string conversion`() {
        assertEquals("%s is typing", IosFormat.toJava("%@ is typing"))
        assertEquals("Ada is typing", IosFormat.format("%@ is typing", "Ada"))
    }

    @Test
    fun `raw at-sign is not a Java conversion at all`() {
        // The reason this file exists. Without conversion the JDK throws.
        val e = runCatching { String.format("%@ is typing", "Ada") }.exceptionOrNull()
        assertNotNull("if the JDK ever accepts %@, this whole file is obsolete", e)
        assertTrue(e is java.util.UnknownFormatConversionException)
    }

    @Test
    fun `length modifiers are dropped because Java has none`() {
        assertEquals("%d bytes", IosFormat.toJava("%ld bytes"))
        assertEquals("%d bytes", IosFormat.toJava("%lld bytes"))
        assertEquals("%d bytes", IosFormat.toJava("%lu bytes"))
        assertEquals("%d bytes", IosFormat.toJava("%zd bytes"))
        assertEquals("7 bytes", IosFormat.format("%lld bytes", 7L))
        // and the unconverted form really does fail
        assertTrue(runCatching { String.format("%lld bytes", 7L) }.isFailure)
    }

    @Test
    fun `i and u become d, F becomes f`() {
        assertEquals("%d", IosFormat.toJava("%i"))
        assertEquals("%d", IosFormat.toJava("%u"))
        assertEquals("%f", IosFormat.toJava("%F"))
    }

    @Test
    fun `width and precision survive`() {
        assertEquals("Saved %.0f%%", IosFormat.toJava("Saved %.0f%%"))
        assertEquals("Saved 42%", IosFormat.format("Saved %.0f%%", 42.0))
        assertEquals("%-8s|", IosFormat.toJava("%-8@|"))
    }

    @Test
    fun `positional specifiers keep their index`() {
        val s = "%1\$@ added %2\$@ to %1\$@"
        assertEquals("%1\$s added %2\$s to %1\$s", IosFormat.toJava(s))
        assertEquals(2, IosFormat.arity(s))
        assertEquals("A added B to A", IosFormat.format(s, "A", "B"))
    }

    @Test
    fun `double percent is a literal and takes no argument`() {
        assertEquals(0, IosFormat.arity("100%% sure"))
        assertEquals("100%% sure", IosFormat.toJava("100%% sure"))
        assertEquals("100% sure", IosFormat.format("100%% sure"))
    }

    // -------------------------------------------------- the trap: percent as a WORD

    @Test
    fun `a bare percent in prose is a literal percent, not an octal conversion`() {
        // Six real strings in en.lproj look like this. `% o` IS a valid C conversion
        // (space flag + octal), which is why a by-the-book converter breaks them.
        val s = "OSHI is **100% open source**, built with transparency"
        assertEquals("this string takes no arguments", 0, IosFormat.arity(s))
        assertEquals(s, IosFormat.format(s))
        // and the converted form is safe to hand to the formatter
        assertEquals(s, String.format(IosFormat.toJava(s)))
    }

    @Test
    fun `the named-placeholder dialect in the same catalog is left as text`() {
        // settings.webSessions.revokeMessage really is "This will log out %browser% on
        // %platform%." — a different substitution scheme sharing the file. %b and %p and
        // %c must NOT be read as conversions: Java has %b and %c, so reading them would
        // produce silent garbage rather than an error.
        val s = "This will log out %browser% on %platform%."
        assertEquals(0, IosFormat.arity(s))
        assertEquals(s, String.format(IosFormat.toJava(s)))
        assertEquals(0, IosFormat.arity("This will log you out from all %count% computers."))
    }

    @Test
    fun `pointer and write-through conversions are not honoured`() {
        // %n in Java is a newline. Honouring it would silently inject line breaks.
        assertEquals(0, IosFormat.arity("100%never"))
        assertEquals("100%%never", IosFormat.toJava("100%never"))
    }

    // ------------------------------------------------------------------ arity discipline

    @Test
    fun `format refuses the wrong number of arguments instead of guessing`() {
        val e = runCatching { IosFormat.format("%@ sent %@", "a") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("takes 2 argument"))
    }

    // ------------------------------------------------- the claim, over the whole asset

    @Test
    fun `every shipped string converts and formats without throwing`() {
        var strings = 0
        var withArgs = 0
        val failures = ArrayList<String>()
        for (tag in Strings.availableTags) {
            val cat = Strings.catalog(tag)!!
            for ((k, v) in cat.asMap()) {
                strings++
                val n = IosFormat.arity(v)
                if (n > 0) withArgs++
                val args = Array<Any?>(n) { i -> dummyFor(v, i) }
                try {
                    IosFormat.format(v, *args)
                } catch (e: Exception) {
                    failures.add("$tag/$k (arity $n): ${e.javaClass.simpleName}: ${e.message} :: ${v.take(90)}")
                }
            }
        }
        println("[i18n] format check: $strings strings across ${Strings.availableTags.size} locales, " +
            "$withArgs take at least one argument, ${failures.size} failed")
        assertTrue("expected the whole shipped asset, got $strings strings", strings > 120_000)
        assertTrue("strings that will throw when formatted:\n" + failures.take(30).joinToString("\n"),
            failures.isEmpty())
    }

    /**
     * An argument of the right JAVA type for spec [i] of [v].
     *
     * `%d` will not take a String and `%f` will not take an Int — using a String for
     * everything would make this test pass by never exercising a numeric conversion.
     */
    private fun dummyFor(v: String, i: Int): Any {
        val specs = IosFormat.scan(v)
        val spec = specs.firstOrNull { it.argIndex == i + 1 }
            ?: specs.filter { it.argIndex == null }.getOrNull(i)
            ?: return "x"
        return when (spec.javaConversion) {
            'd', 'o', 'x', 'X' -> 7
            'f', 'e', 'E', 'g', 'G' -> 1.5
            else -> "x"
        }
    }
}
