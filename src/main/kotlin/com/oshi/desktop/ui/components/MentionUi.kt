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
}

/**
 * A bubble's body. Plain [Text] when nothing is mentioned (the common case costs nothing);
 * otherwise the highlighted tokens, and a click opens a small card naming the member.
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
    val spans = remember(text, mentions) { MentionWire.spans(text, mentions) }
    if (spans.isEmpty()) {
        Text(text, style = style, fontStyle = fontStyle, color = color)
        return
    }
    var shown by remember { mutableStateOf<MentionWire.Span?>(null) }
    Box {
        Text(
            mentionAnnotated(text, spans, tint) { shown = it },
            style = style,
            fontStyle = fontStyle,
            color = color,
        )
        DropdownMenu(expanded = shown != null, onDismissRequest = { shown = null }) {
            shown?.let { s ->
                Column(Modifier.padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)) {
                    Text("@" + s.name, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                    Text(shortKey(s.publicKey), fontSize = 11.sp, color = Ink.soft)
                }
            }
        }
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

/** The picker: one row per candidate, above the composer. */
@Composable
fun MentionPicker(
    candidates: List<MentionWire.Mention>,
    onPick: (MentionWire.Mention) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .background(OshiTheme.surface)
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        for (c in candidates) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(c) }
                    .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
            ) {
                Text("@" + c.name, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                Text(shortKey(c.publicKey), fontSize = 11.sp, color = Ink.soft)
            }
        }
    }
}

/** Composer highlight for the tokens of members picked in this draft. */
class MentionHighlight(private val picked: List<MentionWire.Mention>, private val tint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = MentionWire.spans(text.text, picked)
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            append(text)
            for (s in spans) addStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold), s.start, s.endExclusive)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is MentionHighlight && other.picked == picked && other.tint == tint
    override fun hashCode(): Int = picked.hashCode() * 31 + tint.hashCode()
}

private fun shortKey(key: String): String = if (key.length <= 12) key else key.take(12) + "…"
