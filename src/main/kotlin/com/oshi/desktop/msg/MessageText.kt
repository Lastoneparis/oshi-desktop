package com.oshi.desktop.msg

/**
 * __DESKTOP_LINE_BREAKS_2026_10_29__ Outgoing prose, with every line break spelled the one
 * way the other clients can render — iOS `MessageTextSplitter.normalizeLineBreaks`, Android
 * `MessageSendPolicy.normalizeLineBreaks`.
 *
 * ## Why this existed everywhere except here
 *
 * Both phones normalise every plain-prose message before sending it, and the desktop did
 * not. Nothing in this tree spelled ` ` or `\u000B` at all. So text PASTED into the
 * desktop composer — from Word, from a classic-Mac document, from a PDF, from anything that
 * separates paragraphs with something other than LF — went out verbatim, and Android says
 * in its own comment what happens at the other end:
 *
 * > "Android's text layout breaks paragraphs on LF only, so these rendered as one run-on
 * > block here while the iPhone that sent them showed the paragraphs."
 *
 * A desktop user pasting a multi-paragraph quote therefore sent a wall of text to a phone,
 * and saw paragraphs in their own window while doing it. That is the shape of bug nobody
 * reports precisely: the sender's copy looks right.
 *
 * ## The characters, and why each one
 *
 * CR (Word and classic Mac paragraphs), VT (Word's manual line break), NEL (EBCDIC-derived
 * text and some Windows tooling), U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR —
 * the set Android lists, matched exactly rather than guessed at, because a set that differs
 * between two clients normalises a character on one platform and not the other, which is
 * worse than normalising nowhere.
 *
 * CRLF collapses to ONE LF rather than two: the pair is one break.
 *
 * ## What is NOT touched
 *
 * No trimming, and blank lines are kept — a deliberately empty line is content. Control
 * payloads never reach [normalizedForSend]: `OshiClient.send` refuses them before this is
 * called. A reply's body is normalised BEFORE the `💬REPLY💬` envelope wraps it, never
 * after, because rewriting raw characters inside a JSON string literal is how an envelope
 * stops decoding.
 */
object MessageText {

    /** Line/paragraph separators other than LF that pasted text carries. */
    internal fun isForeignLineBreak(c: Char): Boolean =
        c == '\r' || c == '\u000B' || c == '\u0085' || c == ' ' || c == ' '

    /** Every line break becomes one LF (CRLF → one LF). No trimming, blank lines kept. */
    fun normalizeLineBreaks(text: String): String {
        if (text.none(::isForeignLineBreak)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                out.append('\n'); i += 2; continue
            }
            out.append(if (isForeignLineBreak(c)) '\n' else c)
            i++
        }
        return out.toString()
    }

    /**
     * [content] normalised for sending — plain user prose only.
     *
     * The control check is the caller's (`OshiClient.send` refuses a control payload
     * outright), so this guards the one case that reaches it: a body that is already an
     * envelope must pass through untouched.
     */
    fun normalizedForSend(content: String): String =
        if (ControlPrefix.isControl(content)) content else normalizeLineBreaks(content)
}
