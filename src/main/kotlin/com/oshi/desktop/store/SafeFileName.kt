package com.oshi.desktop.store

import java.text.Normalizer

/**
 * __RECEIVED_NAMES_MANGLED_2026_11_19__ A received attachment's name, made safe for EVERY
 * desktop filesystem without throwing the person's words away.
 *
 * Until 1.5.0 every inbound file went through `[^A-Za-z0-9._-] → _`, so « Facture été.pdf »
 * was shown and offered for saving as `Facture__t_.pdf`, and a name in Arabic, Russian or
 * Japanese became a row of underscores. Only what a filesystem actually forbids is replaced:
 * path separators and `: * ? " < > |` (Windows), control characters, bidi overrides (the
 * `cod‮fdp.exe` spoof), trailing dots and spaces (Windows drops them, which turns
 * `x.exe.` into `x.exe`), and the Windows device names (CON, NUL, COM1 …).
 * Callers still prefix the message id, so a name can never be `..` or collide.
 */
object SafeFileName {
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val RESERVED = Regex("^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(\\..*)?$", RegexOption.IGNORE_CASE)

    fun sanitize(name: String?, maxChars: Int = 120): String {
        var s = Normalizer.normalize(name.orEmpty(), Normalizer.Form.NFC)
        s = s.replace(FORBIDDEN, "_").trim().trimEnd('.', ' ')
        if (s.isEmpty() || s.all { it == '.' }) return "file"
        if (RESERVED.matches(s)) s = "_$s"
        if (s.codePointCount(0, s.length) > maxChars) {
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 16) s.substring(dot) else ""
            val base = s.substring(0, s.length - ext.length)
            val keep = (maxChars - ext.length).coerceAtLeast(1)
            s = base.substring(0, base.offsetByCodePoints(0, minOf(keep, base.codePointCount(0, base.length)))) + ext
            s = s.trimEnd('.', ' ').ifEmpty { "file" }
        }
        return s
    }

    private val ID_PREFIX = Regex("^[0-9A-Fa-f]{8}-(import-)?")

    /** What to SHOW and to suggest in a Save dialog: the on-disk name minus our `<msgid8>-` prefix. */
    fun displayName(storedName: String): String = storedName.replaceFirst(ID_PREFIX, "").ifBlank { storedName }
}
