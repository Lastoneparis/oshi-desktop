package com.oshi.desktop.mail

import java.util.Base64

/**
 * Turns the decrypted bytes of a mail into something the UI can show.
 *
 * On a zero-access mailbox the server never holds the plaintext, so it cannot parse mail
 * for the client — this work has to happen on the device. Byte-for-byte port of Android's
 * `com.oshi.messenger.mail.MailMime` (itself mirroring `MailMessageParser.swift` and
 * `mime.js`), including the two bugs those found:
 *
 *  - a header folded across lines is ONE header and must be unfolded before matching;
 *  - the CRLF before a MIME boundary belongs to the delimiter, not to the part.
 *
 * Parsing runs over ISO-8859-1 (one char per byte) rather than UTF-8: an attachment is
 * arbitrary bytes, and decoding the whole message as text first would corrupt it before
 * the base64 is ever reached.
 */
object MailMime {

    data class Attachment(val filename: String, val mimeType: String, val bytes: ByteArray)

    data class Mail(
        val headers: Map<String, String>,
        val text: String,
        val html: String?,
        val attachments: List<Attachment>,
    ) {
        val subject: String get() = headers["subject"].orEmpty()
        val from: String get() = headers["from"].orEmpty()
        val to: String get() = headers["to"].orEmpty()
        val date: String get() = headers["date"].orEmpty()
    }

    private val LATIN1 = Charsets.ISO_8859_1

    fun parse(raw: ByteArray): Mail {
        val text = String(raw, LATIN1)
        val (headBlock, body) = splitHeaders(text)
        val headers = parseHeaders(headBlock)

        val contentType = headers["content-type"] ?: "text/plain"
        val encoding = headers["content-transfer-encoding"]

        var plain = ""
        var html: String? = null
        val attachments = mutableListOf<Attachment>()

        when {
            contentType.contains("multipart/", ignoreCase = true) -> {
                param(contentType, "boundary")?.let { boundary ->
                    val acc = Acc()
                    walk(body, boundary, acc)
                    plain = acc.text
                    html = acc.html
                    attachments += acc.attachments
                }
            }
            contentType.contains("text/html", ignoreCase = true) -> {
                html = decodeText(body, encoding, contentType)
                plain = stripHtml(html)
            }
            else -> plain = decodeText(body, encoding, contentType)
        }

        if (plain.isEmpty() && html != null) plain = stripHtml(html)
        return Mail(headers, plain.trim(), html, attachments)
    }

    private class Acc {
        var text = ""
        var html: String? = null
        val attachments = mutableListOf<Attachment>()
    }

    private fun walk(body: String, boundary: String, acc: Acc) {
        for (piece in body.split("--$boundary")) {
            if (piece.isBlank() || piece.trim() == "--") continue
            // The CRLF before a boundary belongs to the DELIMITER; keeping it appends a
            // blank line to every multipart body.
            val part = piece.removeSuffix("\n").removeSuffix("\r")

            val (headBlock, content) = splitHeaders(part)
            val headers = parseHeaders(headBlock)
            val type = headers["content-type"] ?: "text/plain"
            val encoding = headers["content-transfer-encoding"]
            val disposition = headers["content-disposition"].orEmpty()

            val filename = param(disposition, "filename") ?: param(type, "name")
            when {
                disposition.contains("attachment", true) ||
                    (filename != null && !disposition.contains("inline", true)) -> {
                    acc.attachments += Attachment(
                        filename = decodeEncodedWords(filename ?: "attachment"),
                        mimeType = type.substringBefore(';').trim(),
                        bytes = decodeBytes(content, encoding),
                    )
                }
                type.contains("multipart/", true) ->
                    param(type, "boundary")?.let { walk(content, it, acc) }
                type.contains("text/html", true) -> acc.html = decodeText(content, encoding, type)
                type.contains("text/", true) ->
                    if (acc.text.isEmpty()) acc.text = decodeText(content, encoding, type)
            }
        }
    }

    private fun splitHeaders(raw: String): Pair<String, String> {
        raw.indexOf("\r\n\r\n").let { if (it >= 0) return raw.substring(0, it) to raw.substring(it + 4) }
        raw.indexOf("\n\n").let { if (it >= 0) return raw.substring(0, it) to raw.substring(it + 2) }
        return raw to ""
    }

    private fun parseHeaders(block: String): Map<String, String> {
        // Unfold first, or every long Subject and To comes back truncated at the fold.
        val unfolded = block.replace("\r\n", "\n").replace(Regex("\n[ \t]+"), " ")
        val out = LinkedHashMap<String, String>()
        for (line in unfolded.split("\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            out[line.substring(0, colon).trim().lowercase()] =
                decodeEncodedWords(upgradeUtf8IfValid(line.substring(colon + 1).trim()))
        }
        return out
    }

    /**
     * A header can carry RAW UTF-8 with no RFC 2047 encoded-word at all — plenty of real
     * senders do this (e.g. `From: Boutique Lumière <x@y.com>`). Because the message is
     * read one-char-per-byte as ISO-8859-1 (so attachment bytes survive byte-for-byte),
     * that raw UTF-8 sequence lands here as mojibake ("Boutique LumiÃ¨re") unless it is
     * re-decoded.
     *
     * Every char in [raw] at this point IS a byte (0x00-0xFF): re-pack them and try a
     * STRICT UTF-8 decode. If it succeeds, the header really was UTF-8 and the decoded
     * text is correct. If it fails ([CharacterCodingException] on a malformed or
     * unmappable sequence), this was genuine Latin-1 (or plain ASCII) and must be left
     * exactly as it was — a lone 0xE9 ("é" in Latin-1) is not valid UTF-8 on its own, and
     * upgrading it blindly would mangle a header that was already correct.
     */
    private fun upgradeUtf8IfValid(raw: String): String {
        if (raw.all { it.code < 0x80 }) return raw // pure ASCII: nothing to upgrade, and never wrong.
        val bytes = ByteArray(raw.length) { (raw[it].code and 0xFF).toByte() }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            raw
        }
    }

    private fun param(header: String?, name: String): String? =
        header?.let { Regex("""$name\s*=\s*"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }

    /** RFC 2047 encoded-words — how every non-ASCII subject and filename travels. */
    fun decodeEncodedWords(value: String): String {
        if (!value.contains("=?")) return value
        // Whitespace BETWEEN two encoded-words is padding, not content.
        val collapsed = value.replace(Regex("""(=\?[^?]+\?[BbQq]\?[^?]*\?=)\s+(?==\?)"""), "$1")
        return Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""").replace(collapsed) { m ->
            val (charset, enc, text) = m.destructured
            val bytes = if (enc.uppercase() == "B") decodeBase64(text)
            else decodeQuotedPrintable(text.replace('_', ' '))
            // Any IANA charset, not just utf-8 and latin-1.
            String(bytes, charsetFor(charset))
        }
    }

    private fun decodeBase64(s: String): ByteArray =
        runCatching { Base64.getMimeDecoder().decode(s.filter { !it.isWhitespace() }) }
            .getOrDefault(ByteArray(0))

    private fun decodeQuotedPrintable(s: String): ByteArray {
        val joined = s.replace(Regex("=\r?\n"), "")
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < joined.length) {
            val c = joined[i]
            if (c == '=' && i + 2 < joined.length) {
                val hex = joined.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    /**
     * Resolve a declared charset name to a real [java.nio.charset.Charset].
     *
     * A hand-written table of "the charsets we expect" was wrong twice: a substring test
     * for "8859-1" also matches iso-8859-15, so its euro sign (0xA4 there) came out as the
     * currency sign ¤; and any label outside the table was not decoded at all, so a
     * windows-1252 subject — what Outlook emits by default — was shown to the user as its
     * raw "=?windows-1252?Q?…?=" source. The JVM already maps the whole IANA registry.
     */
    private fun charsetFor(name: String?): java.nio.charset.Charset {
        // RFC 2231 permits a language suffix on the charset ("utf-8*fr").
        val cs = name?.trim()?.trim('"', '\'')?.substringBefore('*').orEmpty()
        if (cs.isEmpty()) return Charsets.UTF_8
        return try {
            if (java.nio.charset.Charset.isSupported(cs)) java.nio.charset.Charset.forName(cs) else Charsets.UTF_8
        } catch (e: IllegalArgumentException) {
            // Unknown or malformed label: UTF-8 is the best guess for modern mail and
            // degrades to readable ASCII for anything else. Never lose the message.
            Charsets.UTF_8
        }
    }

    private fun decodeBytes(body: String, encoding: String?): ByteArray =
        when (encoding?.trim()?.lowercase()) {
            "base64" -> decodeBase64(body)
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> body.toByteArray(LATIN1)
        }

    private fun decodeText(body: String, encoding: String?, contentType: String?): String {
        val bytes = decodeBytes(body, encoding)
        val declared = param(contentType, "charset")
        // No charset at all: the bytes are whatever the sender emitted. Read them as
        // one-char-per-byte, then upgrade if they turn out to be valid UTF-8.
        if (declared.isNullOrBlank()) return upgradeUtf8IfValid(String(bytes, LATIN1))
        return String(bytes, charsetFor(declared))
    }

    fun stripHtml(html: String): String = html
        .replace(Regex("<(script|style|head)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        // EVERY block element ends a line, not just </p> — `<h1>Merci !</h1><p>Votre
        // commande…</p>` used to run on as "Merci !Votre commande…" because only </p> was
        // treated as a break.
        .replace(
            Regex(
                "</(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|ul|ol|table)>",
                RegexOption.IGNORE_CASE,
            ),
            "\n\n",
        )
        .replace(Regex("<(li|tr)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** "Jane Doe <jane@x.com>" -> "Jane Doe"; a bare address stays as it is. */
    fun displayName(address: String): String {
        val t = address.trim()
        val lt = t.indexOf('<')
        if (lt > 0) {
            val name = t.substring(0, lt).trim().trim('"', '\'')
            if (name.isNotEmpty()) return name
        }
        return emailAddress(t)
    }

    fun emailAddress(address: String): String =
        Regex("<([^>]+)>").find(address)?.groupValues?.get(1) ?: address.trim()
}
