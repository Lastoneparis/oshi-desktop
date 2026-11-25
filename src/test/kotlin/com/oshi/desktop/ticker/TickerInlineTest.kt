package com.oshi.desktop.ticker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import com.oshi.desktop.group.MentionWire
import com.oshi.desktop.msg.InlineMarkup
import com.oshi.desktop.ui.components.CompletionKeys
import com.oshi.desktop.ui.components.bodyAnnotated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __TICKER_INLINE_2026_11_24__ `@AAPL` reads `$AAPL` in the sentence, as on both phones. */
class TickerInlineTest {

    @Test
    fun `a ticker is rewritten to its dollar form and placed in the new text`() {
        val r = TickerInline.rewrite("buy @AAPL now")
        assertEquals("buy \$AAPL now", r.text)
        assertEquals(1, r.tickers.size)
        assertEquals("\$AAPL", r.text.substring(r.tickers[0].start, r.tickers[0].end))
    }

    @Test
    fun `an exchange prefix shortens the text and the map follows`() {
        val src = "@NASDAQ:TSLA and @gold"
        val r = TickerInline.rewrite(src)
        assertEquals("\$TSLA and \$GOLD", r.text)
        // " and " started at 12 in the input and at 5 after the rewrite.
        assertEquals(5, r.map(12))
        assertEquals(r.text.length, r.map(src.length))
        assertEquals(listOf("TSLA", "GOLD"), r.tickers.map { it.match.symbol })
    }

    @Test
    fun `mentions, first names and emails are left alone`() {
        assertEquals("hi @marie", TickerInline.rewrite("hi @marie").text)
        assertEquals("mail hugo@ACME", TickerInline.rewrite("mail hugo@ACME").text)
        // A picked person called "AMD" stays a person.
        val r = TickerInline.rewrite("ping @AMD", exclude = listOf(5 until 9))
        assertEquals("ping @AMD", r.text)
        assertTrue(r.tickers.isEmpty())
    }

    @Test
    fun `no ticker is the identity`() {
        val r = TickerInline.rewrite("plain text")
        assertEquals("plain text", r.text)
        for (i in 0..10) assertEquals(i, r.map(i))
    }

    @Test
    fun `the bubble string carries the mention, the ticker and bold markup at shifted offsets`() {
        val parsed = InlineMarkup.parse("@NASDAQ:TSLA up, *ask @Alice*")
        val body = parsed.text
        val spans = MentionWire.spans(body, listOf(MentionWire.Mention("k", "Alice")))
        val inline = TickerInline.rewrite(body, spans.map { it.start until it.endExclusive })
        val a = bodyAnnotated(inline, spans, Color.Red, parsed.spans)
        assertEquals("\$TSLA up, ask @Alice", a.text)
        val links = a.getLinkAnnotations(0, a.length).map { a.text.substring(it.start, it.end) }
        assertEquals(listOf("@Alice", "\$TSLA").sorted(), links.sorted())
        val bold = a.spanStyles.filter { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }
            .map { a.text.substring(it.start, it.end) }
        assertTrue(bold.toString(), "ask @Alice" in bold && "\$TSLA" in bold)
    }

    @Test
    fun `completion keys move, wrap, pick and dismiss`() {
        assertEquals(CompletionKeys.Action.Move(1), CompletionKeys.reduce(Key.DirectionDown, 0, 3))
        assertEquals(CompletionKeys.Action.Move(0), CompletionKeys.reduce(Key.DirectionDown, 2, 3))
        assertEquals(CompletionKeys.Action.Move(2), CompletionKeys.reduce(Key.DirectionUp, 0, 3))
        assertEquals(CompletionKeys.Action.Pick(1), CompletionKeys.reduce(Key.Enter, 1, 3))
        assertEquals(CompletionKeys.Action.Pick(2), CompletionKeys.reduce(Key.Tab, 2, 3))
        assertEquals(CompletionKeys.Action.Dismiss, CompletionKeys.reduce(Key.Escape, 0, 3))
        assertEquals(CompletionKeys.Action.None, CompletionKeys.reduce(Key.A, 0, 3))
        assertEquals(CompletionKeys.Action.None, CompletionKeys.reduce(Key.Enter, 0, 0))
    }
}
