package com.oshi.desktop.i18n

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * A parser for Apple `.strings` files, in pure Kotlin.
 *
 * WHY NOT `plutil`. `plutil -p` is the right way to INSPECT one of these by hand and it is
 * what was used to establish what the 68 shipped files actually are (see
 * [StringsFile.detectEncoding]'s doc). It is not available to the build: the extraction
 * step has to run on a Linux and a Windows runner, where there is no `plutil` and no
 * CoreFoundation. So the format is parsed here, and the parser is checked against
 * `plutil`'s own answer for all 68 shipped files by `StringsFileTest`.
 *
 * WHAT THE FORMAT IS. A sequence of `"key" = "value";` with C and C++ comments allowed
 * anywhere between tokens. Keys and values may be unquoted when they contain no
 * whitespace or punctuation. That is why this is a tokenizer and not a line regex: the
 * very first thing in OSHI's own `en.lproj/Localizable.strings` is a block comment whose
 * body contains `"Identity"` and `"Wallet"` — a line-oriented `"(.*)" = "(.*)";` reader
 * finds a quoted pair inside it and emits a key that does not exist.
 *
 * TWO ENCODING TRAPS, both live in the shipped tree:
 *
 *  1. **UTF-16.** The classic Apple `.strings` encoding is UTF-16 with a BOM, and Xcode
 *     still writes it that way for some files. Read as UTF-8 it is a field of NUL bytes.
 *  2. **A UTF-8 BOM.** `zh-Hans.lproj/Localizable.strings` in the OSHI tree has one. Left
 *     in place it does not fail — it silently prefixes U+FEFF to the FIRST KEY ONLY, so
 *     the catalog loads, reports 3820 keys, and `a11y.attach_media` is missing in Chinese
 *     and nothing else is. A missing-key audit finds that; an "it parsed" check does not.
 *
 * And one trap that is NOT live but is one Xcode change away: a `.strings` compiled into
 * an `.app` bundle is a **binary plist**, not text. None of the 34 source files are
 * ([detectEncoding] is what says so, not an assumption), and if one ever becomes one this
 * parser REFUSES it by name rather than reading `bplist00…` as a stream of unquoted
 * tokens and returning an empty map that looks like an empty locale.
 */
object StringsFile {

    /** What [read] found at the head of a file. */
    enum class Encoding { UTF8, UTF8_BOM, UTF16LE, UTF16BE, BINARY_PLIST }

    class UnreadableStringsFile(message: String) : IllegalArgumentException(message)

    /**
     * Classify a file by its first bytes alone.
     *
     * The answer for all 68 shipped OSHI files, taken by running this over them, is in
     * `StringsFileTest.every_shipped_file_is_text`: 67 UTF8, 1 UTF8_BOM (zh-Hans), zero
     * UTF16, zero BINARY_PLIST. That test is the record; this doc comment is not.
     */
    fun detectEncoding(bytes: ByteArray): Encoding = when {
        bytes.size >= 8 && String(bytes, 0, 8, StandardCharsets.US_ASCII) == "bplist00" -> Encoding.BINARY_PLIST
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Encoding.UTF16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Encoding.UTF16BE
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Encoding.UTF8_BOM
        else -> Encoding.UTF8
    }

    /** Decode to text, stripping any BOM. Throws on a binary plist rather than guessing. */
    fun decode(bytes: ByteArray, whereForErrors: String): String = when (detectEncoding(bytes)) {
        Encoding.BINARY_PLIST -> throw UnreadableStringsFile(
            "$whereForErrors is a BINARY PLIST (starts with 'bplist00'), not a text .strings file. " +
                "That is what a .strings looks like AFTER Xcode compiles it into an .app bundle. " +
                "Inspect it with `plutil -p`, and extract from the SOURCE .lproj tree instead — " +
                "this parser will not guess at it, because reading it as text yields an almost-empty " +
                "catalog that is indistinguishable from an untranslated locale.",
        )
        // dropping the BOM char, not the bytes, so the decoder handles surrogates
        Encoding.UTF16LE -> String(bytes, StandardCharsets.UTF_16LE).removePrefix("﻿")
        Encoding.UTF16BE -> String(bytes, StandardCharsets.UTF_16BE).removePrefix("﻿")
        Encoding.UTF8, Encoding.UTF8_BOM -> String(bytes, StandardCharsets.UTF_8).removePrefix("﻿")
    }

    /**
     * Decode, then FLATTEN line endings to `\n`.
     *
     * A `.strings` value that spans lines with a RAW newline (not a `\n` escape) carries
     * whatever byte the file uses to end a line — and git hands the same file back with
     * different line endings on different platforms. On the first Windows CI run this
     * project had (2026-08-25), `git`'s `core.autocrlf` checked the iOS `.lproj` sources
     * out as CRLF, a raw newline inside a quoted value came through as `\r\n`, the CR
     * survived into the generated JSON, and `CatalogFreshnessTest` compared it against the
     * LF-committed catalogs and failed — 993 tests green, this one red, on a difference
     * that is not in the content at all. Normalising here makes the extraction a pure
     * function of the file's TEXT, not of the checkout that produced it. The `\r` ESCAPE
     * (the two characters `\r`) is built later in `readQuoted` and is untouched, so a
     * value that genuinely wants a carriage return still gets one.
     */
    fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    fun read(file: File): Map<String, String> =
        parse(normalizeNewlines(decode(file.readBytes(), file.path)), file.path)

    /**
     * Parse a decoded `.strings` document.
     *
     * Duplicate keys: LAST WINS, matching CoreFoundation. Silently, because the shipped
     * files have none — [duplicateKeys] is what an audit calls to find out, so that
     * "there are no duplicates" is measured rather than assumed.
     */
    fun parse(text: String, whereForErrors: String = "<string>"): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        forEachEntry(text, whereForErrors) { k, v -> out[k] = v }
        return out
    }

    /** Every key that appears more than once, in order of first appearance. */
    fun duplicateKeys(text: String, whereForErrors: String = "<string>"): List<String> {
        val seen = LinkedHashMap<String, Int>()
        forEachEntry(text, whereForErrors) { k, _ -> seen[k] = (seen[k] ?: 0) + 1 }
        return seen.filterValues { it > 1 }.keys.toList()
    }

    private fun forEachEntry(text: String, where: String, emit: (String, String) -> Unit) {
        var i = 0
        val n = text.length

        fun fail(msg: String): Nothing {
            val line = text.take(i).count { it == '\n' } + 1
            throw UnreadableStringsFile("$where:$line: $msg")
        }

        fun skipTrivia() {
            while (i < n) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                        while (i < n && text[i] != '\n') i++
                    }
                    c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                        val end = text.indexOf("*/", i + 2)
                        if (end < 0) fail("unterminated block comment")
                        i = end + 2
                    }
                    else -> return
                }
            }
        }

        fun readQuoted(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= n) fail("unterminated quoted string")
                when (val c = text[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= n) fail("trailing backslash")
                        when (val e = text[i]) {
                            'n' -> { sb.append('\n'); i++ }
                            'r' -> { sb.append('\r'); i++ }
                            't' -> { sb.append('\t'); i++ }
                            '0' -> { sb.append(' '); i++ }
                            'a' -> { sb.append(''); i++ }
                            'b' -> { sb.append('\b'); i++ }
                            'f' -> { sb.append(''); i++ }
                            'v' -> { sb.append(''); i++ }
                            // Apple writes \U0041; the JSON/Java spelling is A. Both.
                            'U', 'u' -> {
                                if (i + 4 >= n) fail("truncated \\$e escape")
                                val hex = text.substring(i + 1, i + 5)
                                val cp = hex.toIntOrNull(16) ?: fail("bad \\$e escape '\\$e$hex'")
                                sb.append(cp.toChar()); i += 5
                            }
                            else -> { sb.append(e); i++ } // \" \\ \' and anything else: literal
                        }
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        // An unquoted token: CoreFoundation allows bare keys/values made of "plain" chars.
        fun readBare(): String {
            val start = i
            while (i < n && (text[i].isLetterOrDigit() || text[i] in "_$:./-")) i++
            if (i == start) fail("expected a string, found '${text[i]}'")
            return text.substring(start, i)
        }

        while (true) {
            skipTrivia()
            if (i >= n) return
            val key = if (text[i] == '"') readQuoted() else readBare()
            skipTrivia()
            // `"key";` with no value is legal and means key == value.
            if (i < n && text[i] == ';') { i++; emit(key, key); continue }
            if (i >= n || text[i] != '=') fail("expected '=' after key '$key'")
            i++
            skipTrivia()
            if (i >= n) fail("expected a value after '${key}' =")
            val value = if (text[i] == '"') readQuoted() else readBare()
            skipTrivia()
            if (i >= n || text[i] != ';') fail("expected ';' after the value of '$key'")
            i++
            emit(key, value)
        }
    }
}
