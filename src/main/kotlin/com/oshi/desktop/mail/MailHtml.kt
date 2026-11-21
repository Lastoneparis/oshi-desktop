package com.oshi.desktop.mail

/**
 * __MAIL_READER_2026_11_16__ Received HTML mail, read the way the phones now read it (Android
 * 6060c358, iOS cb5f9875): the text with its paragraphs, bold and links, the images the
 * message CARRIES (`cid:` parts) in place, and every REMOTE image blocked — counted, never
 * fetched until the reader asks for it.
 *
 * Compose Desktop has no web view, and a mail is the last thing that should get one (scripts,
 * tracking pixels, CSS that hides text). So this is a small, total converter from HTML to a
 * list of [Block]s: it never throws, never follows anything, and drops what it does not know
 * (`<script>`, `<style>`, `<head>`, comments, forms). Pure, so `MailHtmlTest` pins it.
 */
object MailHtml {

    /** One run of text, with [bold] and an optional [href] (http/https/mailto only). */
    data class Span(val text: String, val bold: Boolean = false, val href: String? = null)

    sealed class Block {
        /** A paragraph-ish run of spans (a `<p>`, a `<div>`, a list item, a heading…). */
        data class Paragraph(val spans: List<Span>, val heading: Boolean = false) : Block()
        /** An image carried by the message itself: `src="cid:<contentId>"`. */
        data class InlineImage(val contentId: String, val alt: String) : Block()
        /** An image on somebody's server. Shown only after "Load images". */
        data class RemoteImage(val url: String, val alt: String) : Block()
    }

    data class Rendered(val blocks: List<Block>) {
        val remoteImageCount: Int get() = blocks.count { it is Block.RemoteImage }
        val plainText: String get() = blocks.filterIsInstance<Block.Paragraph>().joinToString("\n\n") { p -> p.spans.joinToString("") { it.text } }
    }

    private val DROP_WITH_CONTENT = setOf("script", "style", "head", "title", "noscript", "template", "svg", "object", "iframe")
    private val BREAKS_BLOCK = setOf(
        "p", "div", "br", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "section", "article", "header", "footer", "hr", "center", "pre",
    )
    private val HEADINGS = setOf("h1", "h2", "h3")
    private val TAG = Regex("<!--.*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")

    fun render(html: String): Rendered {
        val blocks = mutableListOf<Block>()
        var spans = mutableListOf<Span>()
        var heading = false
        var bold = 0
        var href: String? = null
        var dropping: String? = null

        fun flush() {
            val cleaned = normaliseSpans(spans)
            if (cleaned.isNotEmpty()) blocks += Block.Paragraph(cleaned, heading)
            spans = mutableListOf()
            heading = false
        }
        fun text(raw: String) {
            if (raw.isEmpty()) return
            val t = decodeEntities(raw).replace(Regex("[ \t\r\n\u000B\u000C]+"), " ")
            if (t.isNotEmpty()) spans += Span(t, bold > 0, href)
        }

        var pos = 0
        for (m in TAG.findAll(html)) {
            if (dropping == null) text(html.substring(pos, m.range.first))
            pos = m.range.last + 1
            if (m.value.startsWith("<!--")) continue
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (dropping != null) {
                if (closing && name == dropping) dropping = null
                continue
            }
            if (!closing && name in DROP_WITH_CONTENT && !attrs.trimEnd().endsWith("/")) { dropping = name; continue }
            when (name) {
                "b", "strong" -> bold = (bold + if (closing) -1 else 1).coerceAtLeast(0)
                "a" -> href = if (closing) null else safeHref(attr(attrs, "href"))
                "img" -> if (!closing) {
                    val src = attr(attrs, "src")?.trim().orEmpty()
                    val alt = decodeEntities(attr(attrs, "alt").orEmpty()).trim()
                    when {
                        src.startsWith("cid:", true) -> { flush(); blocks += Block.InlineImage(src.substring(4).trim('<', '>', ' '), alt) }
                        // __MAIL_IMAGE_REDIRECT_2026_11_20__ https only: a plaintext fetch tells
                        // the network what the person is reading, even after "Load images".
                        src.startsWith("https://", true) -> {
                            // 1×1 trackers are still remote images: they count, they are never fetched.
                            flush(); blocks += Block.RemoteImage(src, alt)
                        }
                        // data: URIs and anything else are dropped (a data: image could be shown,
                        // but it is also the classic way to smuggle a huge payload into a view).
                    }
                }
                in BREAKS_BLOCK -> {
                    if (name == "br" && !closing) { spans += Span("\n", bold > 0, null); continue }
                    flush()
                    if (!closing && name in HEADINGS) heading = true
                    if (!closing && name == "li") spans += Span("• ")
                }
            }
        }
        if (dropping == null && pos < html.length) text(html.substring(pos))
        flush()
        return Rendered(blocks)
    }

    /** Collapse whitespace across span edges and trim each paragraph; merge equal neighbours. */
    private fun normaliseSpans(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        var prevSpace = true
        for (s in spans) {
            var t = s.text
            if (prevSpace) t = t.trimStart(' ')
            if (t.isEmpty()) continue
            prevSpace = t.endsWith(' ') || t.endsWith('\n')
            val last = out.lastOrNull()
            if (last != null && last.bold == s.bold && last.href == s.href) out[out.lastIndex] = last.copy(text = last.text + t)
            else out += s.copy(text = t)
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().let { it.copy(text = it.text.trimEnd(' ', '\n')) }
        if (out.isNotEmpty() && out.first().text.isBlank()) out.removeAt(0)
        return out.filter { it.text.isNotEmpty() }
    }

    private fun attr(attrs: String, name: String): String? =
        ATTR.findAll(attrs).firstOrNull { it.groupValues[1].equals(name, true) }?.let { m ->
            m.groups[3]?.value ?: m.groups[4]?.value ?: m.groups[5]?.value
        }

    /** Only links a person can follow safely from a mail: no javascript:, no file:, no data:. */
    fun safeHref(raw: String?): String? {
        val h = raw?.let(::decodeEntities)?.trim() ?: return null
        val lower = h.lowercase()
        return if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")) h else null
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "ecirc" to "ê", "ocirc" to "ô",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "euro" to "€", "copy" to "©", "reg" to "®",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "laquo" to "«", "raquo" to "»", "middot" to "·", "bull" to "•", "zwnj" to "", "zwj" to "", "shy" to "",
    )
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[e] ?: NAMED[e.lowercase()] ?: m.value
        }
    }

    /** The list's grey line: the server's sealed snippet, else one opened here (Android `MailDisplay.listSnippet`). */
    fun listSnippet(envelopeSnippet: String?, openedBody: String?): String? =
        envelopeSnippet?.takeIf { it.isNotBlank() }
            ?: openedBody?.replace(Regex("\\s+"), " ")?.trim()?.take(SNIPPET_MAX)?.takeIf { it.isNotEmpty() }

    const val SNIPPET_MAX = 140

    /** Android `MailDisplay.attachmentDescription`, for the row's accessible text and tooltip. */
    fun attachmentDescription(count: Int, hasImages: Boolean): String? = when {
        count <= 0 && !hasImages -> null
        count <= 0 -> "Contains images"
        count == 1 -> if (hasImages) "1 attachment, contains images" else "1 attachment"
        else -> if (hasImages) "$count attachments, contains images" else "$count attachments"
    }
}
