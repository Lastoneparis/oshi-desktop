package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.group.MentionWire
import com.oshi.desktop.ui.OshiTheme

/**
 * `@name` person mentions — __MENTIONS_2026_09_23__. Wire and trust rules live in
 * [MentionWire]; this file only draws them: the "@" pill in the chat list, the highlighted
 * token in a bubble (a click names the member), the composer highlight, and the picker that
 * opens while an `@query` is being typed in a group.
 */

/** Chat-list pill: an unseen message in this group mentions you. */
@Composable
fun MentionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(OshiTheme.brandGradient, OshiTheme.pill)
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs),
    ) {
        Text("@", color = Ink.onBrandStrong, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** [text] with every admitted mention bold and tinted; [onClick] receives the clicked one. */
fun mentionAnnotated(
    text: String,
    spans: List<MentionWire.Span>,
    tint: Color,
    /** __DESKTOP_INLINE_MARKUP_2026_10_29__ formatting runs over the SAME [text]. */
    markup: List<com.oshi.desktop.msg.InlineMarkup.Span> = emptyList(),
    onClick: (MentionWire.Span) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (s in spans) {
        if (s.start < at) continue
        append(text.substring(at, s.start))
        withLink(LinkAnnotation.Clickable("mention:" + s.publicKey) { onClick(s) }) {
            withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold, background = tint.copy(alpha = 0.14f))) {
                append(text.substring(s.start, s.endExclusive))
            }
        }
        at = s.endExclusive
    }
    append(text.substring(at))
    // Applied as STYLES over the finished string rather than by nesting, because a formatting
    // run and a mention can overlap (`*hello @bob*`) and nesting would have to choose one.
    // `addStyle` composes instead: the mention keeps its colour and the run still goes bold.
    for (m in markup) addStyle(styleFor(m.kind), m.location, (m.location + m.length).coerceAtMost(length))
}

/** One formatting run as an `AnnotatedString` style. */
private fun styleFor(kind: com.oshi.desktop.msg.InlineMarkup.Kind): SpanStyle = when (kind) {
    com.oshi.desktop.msg.InlineMarkup.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    com.oshi.desktop.msg.InlineMarkup.Kind.ITALIC -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    com.oshi.desktop.msg.InlineMarkup.Kind.STRIKETHROUGH ->
        SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
    com.oshi.desktop.msg.InlineMarkup.Kind.CODE ->
        SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

/** Formatting only, for a body with no mentions in it. */
fun markupAnnotated(
    text: String,
    markup: List<com.oshi.desktop.msg.InlineMarkup.Span>,
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (m in markup) addStyle(styleFor(m.kind), m.location, (m.location + m.length).coerceAtMost(length))
}

/**
 * A bubble's body. Plain [Text] when nothing is mentioned and no ticker is written (the
 * common case costs nothing); otherwise the highlighted tokens — a click on a person opens a
 * small card naming the member, a click on a ticker opens its chart.
 *
 * __TICKER_INLINE_2026_11_24__ `@AAPL` is rewritten to a bold `$AAPL` link in the sentence
 * itself, as both phones do ([com.oshi.desktop.ticker.TickerInline]). A picked person's
 * `@name` is never offered to the ticker parser.
 */
@Composable
fun MentionAwareText(
    text: String,
    mentions: List<MentionWire.Mention>,
    style: TextStyle,
    color: Color,
    fontStyle: FontStyle,
    tint: Color,
) {
    // __DESKTOP_INLINE_MARKUP_2026_10_29__ Markers are stripped BEFORE mentions are located,
    // so both span sets index the SAME string. Doing it the other way round would leave the
    // mention offsets pointing past the characters the strip removed.
    val markup = remember(text) { com.oshi.desktop.msg.InlineMarkup.parse(text) }
    val body = markup.text
    val spans = remember(body, mentions) { MentionWire.spans(body, mentions) }
    val inline = remember(body, spans) {
        com.oshi.desktop.ticker.TickerInline.rewrite(body, spans.map { it.start until it.endExclusive })
    }
    // __BOTS_PHASE1_2026_11_24__ a bot link in a message opens the bot's profile sheet.
    val botLinks = remember(body) { com.oshi.desktop.bot.BotLink.findIn(body) }
    val openBot = LocalOpenBotLink.current
    if (spans.isEmpty() && inline.tickers.isEmpty()) {
        if (botLinks.isNotEmpty()) {
            val base = markupAnnotated(body, markup.spans)
            val linked = androidx.compose.ui.text.buildAnnotatedString {
                append(base)
                for ((r, link) in botLinks) {
                    addLink(LinkAnnotation.Clickable("bot:" + link.username) { openBot(link) }, r.first, r.last + 1)
                    addStyle(SpanStyle(color = tint, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), r.first, r.last + 1)
                }
            }
            Text(linked, style = style, fontStyle = fontStyle, color = color)
        } else if (markup.spans.isEmpty()) {
            Text(body, style = style, fontStyle = fontStyle, color = color)
        } else {
            Text(markupAnnotated(body, markup.spans), style = style, fontStyle = fontStyle, color = color)
        }
        return
    }
    var shown by remember { mutableStateOf<MentionWire.Span?>(null) }
    Box {
        Text(
            bodyAnnotated(inline, spans, tint, markup.spans, onMention = { shown = it }, onTicker = { com.oshi.desktop.ui.openInBrowser(it.url) },
                botLinks = botLinks, onBotLink = openBot),
            style = style,
            fontStyle = fontStyle,
            color = color,
        )
        OshiDropdownMenu(expanded = shown != null, onDismissRequest = { shown = null }) {
            shown?.let { s ->
                Column(Modifier.padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)) {
                    Text("@" + s.name, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                    Text(shortKey(s.publicKey), fontSize = 11.sp, color = Ink.soft)
                }
            }
        }
    }
}

/**
 * The body once tickers are rewritten: mentions (tinted pill, clickable), tickers (bold link
 * colour, clickable) and the formatting runs, every span pushed through the rewrite's offset
 * map so a `*bold*` after `@NASDAQ:TSLA` still lands on the right characters.
 */
fun bodyAnnotated(
    inline: com.oshi.desktop.ticker.TickerInline.Rewritten,
    spans: List<MentionWire.Span>,
    tint: Color,
    markup: List<com.oshi.desktop.msg.InlineMarkup.Span> = emptyList(),
    onMention: (MentionWire.Span) -> Unit = {},
    onTicker: (com.oshi.desktop.ticker.TickerLinks.Match) -> Unit = {},
    botLinks: List<Pair<IntRange, com.oshi.desktop.bot.BotLink>> = emptyList(),
    onBotLink: (com.oshi.desktop.bot.BotLink) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    val text = inline.text
    append(text)
    for ((r, link) in botLinks) {
        val a = inline.map(r.first)
        val b = inline.map(r.last + 1)
        if (a >= b || b > text.length) continue
        addLink(LinkAnnotation.Clickable("bot:" + link.username) { onBotLink(link) }, a, b)
        addStyle(SpanStyle(color = tint, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), a, b)
    }
    for (s in spans) {
        val a = inline.map(s.start)
        val b = inline.map(s.endExclusive)
        if (a >= b) continue
        addLink(LinkAnnotation.Clickable("mention:" + s.publicKey) { onMention(s) }, a, b)
        addStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold, background = tint.copy(alpha = 0.14f)), a, b)
    }
    for (t in inline.tickers) {
        addLink(LinkAnnotation.Clickable("ticker:" + t.match.symbol) { onTicker(t.match) }, t.start, t.end)
        addStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold), t.start, t.end)
    }
    for (m in markup) {
        val a = inline.map(m.location)
        val b = inline.map((m.location + m.length).coerceAtMost(inline.originalLength))
        if (a < b) addStyle(styleFor(m.kind), a, b.coerceAtMost(length))
    }
}

/** The ranges a ticker chip must not claim: a picked person always wins over a symbol. */
fun mentionRanges(text: String, mentions: List<MentionWire.Mention>): List<IntRange> =
    MentionWire.spans(text, mentions).map { it.start until it.endExclusive }

/**
 * Members offered while `@query` is typed: case-insensitive prefix match on the name first,
 * then substring, never ourselves, at most 8.
 */
fun mentionCandidates(members: List<MentionWire.Mention>, query: String): List<MentionWire.Mention> {
    val q = query.trim().lowercase()
    val usable = members.filter { MentionWire.nameOk(it.name) }
    if (q.isEmpty()) return usable.take(8)
    val prefix = usable.filter { it.name.lowercase().startsWith(q) }
    val inner = usable.filter { it !in prefix && it.name.lowercase().contains(q) }
    return (prefix + inner).take(8)
}

/** Replace the `@query` at [atIndex]..end of [draft] with the picked member's token. */
fun insertMention(draft: String, atIndex: Int, picked: MentionWire.Mention): String =
    draft.substring(0, atIndex) + "@" + picked.name + " "

/**
 * The picker: one row per candidate (avatar, name, short key), above the composer.
 * __COMPOSER_COMPLETIONS_2026_11_24__ [selected] is the keyboard's row (Up/Down/Enter/Tab).
 */
@Composable
fun MentionPicker(
    candidates: List<MentionWire.Mention>,
    onPick: (MentionWire.Mention) -> Unit,
    modifier: Modifier = Modifier,
    selected: Int = 0,
) {
    if (candidates.isEmpty()) return
    CompletionPopup(
        rows = candidates.map {
            CompletionRow("@" + it.name, shortKey(it.publicKey), leading = CompletionLeading.Person(it.name, it.publicKey))
        },
        selected = selected,
        onPick = { i -> candidates.getOrNull(i)?.let(onPick) },
        modifier = modifier,
    )
}

/** Composer highlight for the tokens of members picked in this draft, and for typed tickers. */
class MentionHighlight(private val picked: List<MentionWire.Mention>, private val tint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = MentionWire.spans(text.text, picked)
        // __TICKER_INLINE_2026_11_24__ a typed `@AAPL` is tinted as it is typed (never rewritten
        // here: the composer must show exactly the characters that will be sent).
        val tickers = com.oshi.desktop.ticker.TickerLinks.matches(text.text)
            .filter { m -> spans.none { s -> m.start < s.endExclusive && s.start < m.end } }
        if (spans.isEmpty() && tickers.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            append(text)
            for (s in spans) addStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold), s.start, s.endExclusive)
            for (m in tickers) addStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold), m.start, m.end)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is MentionHighlight && other.picked == picked && other.tint == tint
    override fun hashCode(): Int = picked.hashCode() * 31 + tint.hashCode()
}

private fun shortKey(key: String): String = if (key.length <= 12) key else key.take(12) + "…"
