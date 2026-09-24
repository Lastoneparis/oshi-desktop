package com.oshi.desktop.i18n

import com.oshi.desktop.i18n.tools.CatalogExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class StringsFileTest {

    private val iosRoot: File? =
        System.getProperty("oshi.ios.root")?.let { File(it) }?.takeIf { it.isDirectory }

    // ------------------------------------------------------------------ the grammar

    @Test
    fun `a block comment containing quotes does not produce a key`() {
        // This is not hypothetical: en.lproj/Localizable.strings opens with a comment
        // whose body reads: Uses "Identity" instead of "Wallet". A line-oriented
        // "(.*)" = "(.*)"; reader finds a pair in there.
        val doc = """
            /*
               Localizable.strings (English)
               APPLE STORE COMPLIANT VERSION - Uses "Identity" instead of "Wallet"
            */
            "tab.messages" = "Messages";
        """.trimIndent()
        assertEquals(mapOf("tab.messages" to "Messages"), StringsFile.parse(doc))
    }

    @Test
    fun `line comments and blank lines are skipped`() {
        val doc = """
            // a note
            "a" = "1";

            // another
            "b" = "2"; // trailing
        """.trimIndent()
        assertEquals(mapOf("a" to "1", "b" to "2"), StringsFile.parse(doc))
    }

    @Test
    fun `escapes are decoded`() {
        val doc = """"k" = "line\nbreak, a \"quote\", and \U00E9 accented";"""
        assertEquals("line\nbreak, a \"quote\", and é accented", StringsFile.parse(doc)["k"])
    }

    @Test
    fun `a percent sign in a value survives parsing untouched`() {
        // The parser must not be clever about % — that is IosFormat's problem, and only
        // at format time.
        assertEquals("100% open source", StringsFile.parse(""""k" = "100% open source";""")["k"])
    }

    @Test
    fun `unquoted keys and values are accepted, and a bare key means key equals value`() {
        assertEquals(mapOf("plain" to "value"), StringsFile.parse("plain = value;"))
        assertEquals(mapOf("solo" to "solo"), StringsFile.parse(""""solo";"""))
    }

    @Test
    fun `duplicate keys are reported, last one wins`() {
        val doc = """"a" = "1"; "a" = "2"; "b" = "3";"""
        assertEquals("2", StringsFile.parse(doc)["a"])
        assertEquals(listOf("a"), StringsFile.duplicateKeys(doc))
    }

    // ------------------------------------------------------------------ encodings

    @Test
    fun `a UTF-8 BOM does not become part of the first key`() {
        // zh-Hans.lproj/Localizable.strings in the shipped tree HAS one. Left in place it
        // does not fail — it corrupts exactly one key, the first, and the catalog still
        // reports its full size. That is a one-key hole no "did it parse" check finds.
        val bytes = "﻿\"a11y.attach_media\" = \"添加媒体\";".toByteArray(StandardCharsets.UTF_8)
        assertEquals(StringsFile.Encoding.UTF8_BOM, StringsFile.detectEncoding(bytes))
        val parsed = StringsFile.parse(StringsFile.decode(bytes, "t"))
        assertTrue("BOM leaked into the key: ${parsed.keys}", "a11y.attach_media" in parsed)
        assertFalse("﻿a11y.attach_media" in parsed)
    }

    @Test
    fun `UTF-16 with a BOM decodes, in both byte orders`() {
        for (cs in listOf(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            val body = "﻿\"k\" = \"vàl\";"
            val bytes = body.toByteArray(cs)
            val parsed = StringsFile.parse(StringsFile.decode(bytes, "t"))
            assertEquals("as $cs", mapOf("k" to "vàl"), parsed)
        }
    }

    @Test
    fun `a binary plist is refused by name, not read as an empty catalog`() {
        // A .strings compiled into an .app bundle is a bplist. Read as text it yields a
        // near-empty map, which is indistinguishable from an untranslated locale.
        val bplist = "bplist00".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0xD1.toByte(), 1, 2)
        assertEquals(StringsFile.Encoding.BINARY_PLIST, StringsFile.detectEncoding(bplist))
        val e = runCatching { StringsFile.decode(bplist, "zz.lproj/Localizable.strings") }.exceptionOrNull()
        assertTrue("expected refusal, got $e", e is StringsFile.UnreadableStringsFile)
        assertTrue("the message must name the file and plutil: ${e!!.message}",
            e.message!!.contains("zz.lproj") && e.message!!.contains("plutil"))
    }

    @Test
    fun `the same file with CRLF and with LF parses to identical values`() {
        // A value with a RAW newline in it — not a \\n escape. This is the shape that
        // made the first Windows CI run fail: git checked the iOS sources out as CRLF,
        // the CR rode into the generated JSON, and the byte-comparison against the
        // LF-committed catalogs failed on a difference that is not content.
        val lf = "\"multi\" = \"line one\nline two\";\n\"plain\" = \"x\";\n"
        val crlf = lf.replace("\n", "\r\n")
        val fromLf = StringsFile.parse(StringsFile.normalizeNewlines(lf))
        val fromCrlf = StringsFile.parse(StringsFile.normalizeNewlines(crlf))
        assertEquals(fromLf, fromCrlf)
        assertEquals("the raw newline is an LF on every platform", "line one\nline two", fromCrlf["multi"])
        assertFalse("no carriage return survives", fromCrlf["multi"]!!.contains('\r'))
    }

    @Test
    fun `the backslash-r ESCAPE still yields a carriage return`() {
        // Normalisation flattens RAW line endings; it must not touch the two-character
        // \\r escape, which is the only intended way to put a CR in a value.
        val parsed = StringsFile.parse(StringsFile.normalizeNewlines("\"k\" = \"a\\rb\";"))
        assertEquals("a\rb", parsed["k"])
    }

    // ------------------------------------------------------------------ the real files

    @Test
    fun `every shipped strings file is text, and none is a binary plist`() {
        assumeTrue("no iOS tree on this machine", iosRoot != null)
        val dirs = CatalogExtractor.localeDirs(iosRoot!!)
        assertEquals("expected 34 .lproj directories", 34, dirs.size)
        var files = 0
        val byEncoding = HashMap<StringsFile.Encoding, MutableList<String>>()
        for (d in dirs) for (n in listOf("Localizable", "InfoPlist")) {
            val f = File(d, "$n.strings")
            assertTrue("${f.path} is missing", f.isFile)
            files++
            byEncoding.getOrPut(StringsFile.detectEncoding(f.readBytes())) { ArrayList() }.add(f.name + " in " + d.name)
        }
        assertEquals(68, files)
        println("[i18n] encodings across $files shipped files: " +
            byEncoding.entries.sortedBy { it.key.name }.joinToString { "${it.key}=${it.value.size}" })
        assertTrue("a binary plist appeared in the SOURCE tree: ${byEncoding[StringsFile.Encoding.BINARY_PLIST]}",
            StringsFile.Encoding.BINARY_PLIST !in byEncoding)
    }

    @Test
    fun `every shipped strings file parses, and the counts are the ones on record`() {
        assumeTrue("no iOS tree on this machine", iosRoot != null)
        val all = CatalogExtractor.extractAll(iosRoot!!)
        assertEquals(34, all.size)
        val en = all.single { it.tag == "en" }
        // THIS NUMBER MOVES, and it moves in the SAME COMMIT as a catalog regeneration —
        // never on its own. It is a tripwire, not a fact: when the iOS tree gains or loses
        // keys this goes red, which is the signal that `./gradlew i18nExtract` is owed.
        // Bumping it without regenerating silences the alarm and ships stale catalogs.
        // 3824 → 3840 on 2026-09-11: sixteen keys added upstream, chat.load_earlier_messages
        // among them.
        // 3840 → 3960 on 2026-09-17, regenerated in this same change: 82 mail.*, 21
        // premium.* (the new StoreKit paywall), 10 about.*, 8 bot.*, 5 settings.*, and a
        // handful more. The about.* ones are a correction, not an addition — iOS stopped
        // describing the encryption as "military-grade" and now names Double Ratchet, and
        // the checked-in catalogs were still shipping the old claim in all 34 languages.
        // 3960 → 3983 on 2026-09-20: GIF, short-video, LoRa-media and offline
        // navigation copy was added in the iOS source and the checked-in desktop mirror
        // was regenerated in the same change.
        // 3983 → 3987 on 2026-09-22: nickname-edit copy was added in the iOS source and
        // the checked-in desktop fallback catalog was regenerated in the same change.
        // 3987 → 4003 on 2026-09-22: __CALL_RATING_2026_09_22__, the post-call quality
        // rating, added 16 keys to the iOS source in all 34 languages — the sheet on the
        // phones and the `/rate` prompt here — and the catalogs were regenerated with
        // `./gradlew i18nExtract` in the same change.
        // 4003 → 4011 on 2026-09-22: __ENCRYPTED_EXPORT_2026_09_22__, the encrypted
        // message export/import, added 8 keys (settings.data.importMessages[.done] and six
        // export.error.*) to the iOS source in all 34 languages, and the catalogs were
        // regenerated with `./gradlew i18nExtract` in the same change.
        // 4011 → 4012 on 2026-09-22: __SHARED_NICKNAME_2026_09_22__, `contact.shared_nickname`
        // ("Nickname: %@", the peer's own name shown under a local alias) was added to the
        // iOS source in all 34 languages and the catalogs were regenerated in the same change.
        // 4012 → 4046 on 2026-09-23: __GROUP_E2E_V2_2026_09_23__ re-extraction for
        // `group.member_must_update` (GROUP_E2E_V2_SPEC §6), which also brought in the other keys
        // the iOS source gained since the last extraction.
        // 4046 → 4053 on 2026-09-24: re-extraction after the iOS map-app preference
        // (`map_open_with_title`, rewritten `map_open_preference`) and the call-diagnostics /
        // report strings landed in all 34 languages.
        // 4053 → 4054 on 2026-09-24: `call.error.unavailable` (__BLOCKED_BY_PEER_2026_09_24__).
        // 4054 → 4074 on 2026-09-24: __VIDEO_NOTE_2026_09_24__ the 20 `videonote.*` keys of the
        // round video messages (docs/VIDEO_NOTE_SPEC.md), added to the iOS source in all 34
        // languages; catalogs regenerated with `./gradlew i18nExtract` in the same change.
        assertEquals("en Localizable key count", 4074, en.localizable.size)
        assertEquals("en InfoPlist key count", 16, en.infoPlist.size)
        assertEquals("Messages", en.localizable["tab.messages"])
        // Spot-check a value with an escape and one with a bare percent.
        assertTrue(en.localizable["about.openSource.paragraph1"]!!.contains("100% open source"))
        for (e in all) {
            assertTrue("${e.tag} parsed to an empty Localizable catalog", e.localizable.size > 3000)
            assertTrue("${e.tag} parsed to an empty InfoPlist catalog", e.infoPlist.isNotEmpty())
        }
    }
}
