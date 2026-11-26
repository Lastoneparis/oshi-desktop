package com.oshi.desktop.msg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DESKTOP_INLINE_MARKUP_2026_10_29__ The message formatting parser.
 *
 * Most of these are REFUSALS, and that ratio is the point. This runs on every message
 * anybody sends, so the dangerous failure is not "bold did not render" — it is that ordinary
 * text containing punctuation gets characters silently deleted from it. Each refusal below
 * names the real message it protects.
 */
class InlineMarkupTest {

    private fun plain(s: String) = InlineMarkup.parse(s).text
    private fun spans(s: String) = InlineMarkup.parse(s).spans

    @Test
    fun `the four markers are recognised and removed`() {
        assertEquals("bold", plain("*bold*"))
        assertEquals("italic", plain("_italic_"))
        assertEquals("struck", plain("~struck~"))
        assertEquals("code", plain("`code`"))
        assertEquals(InlineMarkup.Kind.BOLD, spans("*bold*").single().kind)
        assertEquals(InlineMarkup.Kind.ITALIC, spans("_italic_").single().kind)
        assertEquals(InlineMarkup.Kind.STRIKETHROUGH, spans("~struck~").single().kind)
        assertEquals(InlineMarkup.Kind.CODE, spans("`code`").single().kind)
    }

    /** The span must index the RETURNED text, not the input — the markers are gone. */
    @Test
    fun `spans point into the stripped text`() {
        val p = InlineMarkup.parse("say *hi* now")
        assertEquals("say hi now", p.text)
        val s = p.spans.single()
        assertEquals(4, s.location)
        assertEquals(2, s.length)
        assertEquals("hi", p.text.substring(s.location, s.location + s.length))
    }

    /**
     * THE ARITHMETIC MESSAGE. `2 * 3 * 4 = 24` has two asterisks that DO pair, with " 3 "
     * between them. A naive parser renders `2  3  4 = 24` — characters deleted from an
     * ordinary message. The "no leading or trailing whitespace" rule is what stops it.
     */
    @Test
    fun `an arithmetic message is left completely alone`() {
        val input = "2 * 3 * 4 = 24"
        assertEquals(input, plain(input))
        assertTrue(spans(input).isEmpty())
    }

    /** `snake_case_name` must not become italic: a marker inside a word is part of the word. */
    @Test
    fun `underscores inside a word are not formatting`() {
        assertEquals("file_name_with_underscores", plain("file_name_with_underscores"))
        assertTrue(spans("file_name_with_underscores").isEmpty())
        assertEquals("a*b*c", plain("a*b*c"))
        // ISOLATES THE OPENING RULE. `a*b*c` is refused twice over — the opening `*` follows
        // a letter AND the closing `*` precedes one — so it survives a mutation that removes
        // either check alone, which is exactly what happened: deleting the opening boundary
        // rule left this test green. Here the closing marker ends the string, so its own rule
        // is satisfied and only the opening one can refuse it.
        assertEquals("a marker opening inside a word is literal", "a*b*", plain("a*b*"))
        assertTrue(spans("a*b*").isEmpty())
    }

    /** A lone marker is a character, not the start of formatting that swallows the rest. */
    @Test
    fun `an unmatched marker is literal`() {
        assertEquals("2 * 3", plain("2 * 3"))
        assertEquals("a _ b", plain("a _ b"))
        assertEquals("*start of a thought", plain("*start of a thought"))
    }

    /** Formatting does not cross a line: an unclosed marker cannot swallow a paragraph. */
    @Test
    fun `a marker does not pair across a newline`() {
        val input = "*one\ntwo*"
        assertEquals(input, plain(input))
        assertTrue(spans(input).isEmpty())
    }

    @Test
    fun `an empty run is not formatting`() {
        assertEquals("**", plain("**"))
        assertEquals("``", plain("``"))
    }

    @Test
    fun `a run may not begin or end with a space`() {
        assertEquals("* lead*", plain("* lead*"))
        assertEquals("*trail *", plain("*trail *"))
    }

    @Test
    fun `several runs in one message`() {
        val p = InlineMarkup.parse("*a* and _b_ and `c`")
        assertEquals("a and b and c", p.text)
        assertEquals(3, p.spans.size)
        assertEquals(listOf(InlineMarkup.Kind.BOLD, InlineMarkup.Kind.ITALIC, InlineMarkup.Kind.CODE), p.spans.map { it.kind })
        assertEquals("a", p.text.substring(p.spans[0].location, p.spans[0].location + p.spans[0].length))
        assertEquals("c", p.text.substring(p.spans[2].location, p.spans[2].location + p.spans[2].length))
    }

    /** Text with no marker at all takes the cheap path and comes back untouched. */
    @Test
    fun `ordinary prose is returned identically`() {
        val s = "Hello there, how are you? I'll be late — sorry!"
        assertEquals(s, plain(s))
        assertTrue(spans(s).isEmpty())
        assertFalse(InlineMarkup.hasFormatting(s))
        assertTrue(InlineMarkup.hasFormatting("*yes*"))
    }

    /**
     * Offsets survive emoji. A span's location and length are in UTF-16 units on both
     * platforms — Kotlin's `String.length` IS the UTF-16 count — so an astral character
     * before a run must shift it by TWO, exactly as iOS's `NSRange` would.
     */
    @Test
    fun `offsets are utf16 units, so emoji shift a span by two`() {
        val p = InlineMarkup.parse("😀 *hi*")
        assertEquals("😀 hi", p.text)
        val s = p.spans.single()
        assertEquals("one astral char (2 units) plus a space", 3, s.location)
        assertEquals("hi", p.text.substring(s.location, s.location + s.length))
    }

    @Test
    fun `stripped is the text as it will be seen`() {
        assertEquals("gras", InlineMarkup.stripped("*gras*"))
        assertEquals("a reply to gras", InlineMarkup.stripped("a reply to *gras*"))
        assertEquals("2 * 3", InlineMarkup.stripped("2 * 3"))
    }
}
