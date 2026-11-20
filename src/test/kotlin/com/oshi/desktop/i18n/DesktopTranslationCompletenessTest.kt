package com.oshi.desktop.i18n

import com.oshi.desktop.i18n.tools.SourceKeys
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __DESKTOP_TRANSLATION_COMPLETE_2027__ Every string the desktop window can show exists, and is
 * actually translated, in all 34 languages.
 *
 * `CatalogAuditTest` already asks "does every locale serve the keys `t()` reaches from its OWN
 * catalog". Three holes were left beside it, and this test closes them:
 *
 *  1. **The English-only overlay.** Keys reached through `dt()` (i18n/Desktop/en.json) were, by
 *     design, English in every language; nothing failed. Here they are held to the same bar
 *     as everything else: present in each of the 34 catalogs.
 *  2. **Present but untranslated.** A translator (or a script) that copies the English
 *     sentence into fr.lproj makes every "is the key there" test green while a French user
 *     reads English. A value identical to English fails when the English value is a phrase
 *     (two or more words), because a phrase identical in another language is a copy, not a
 *     cognate. Single words ("Video", "Hotel", "Port", "OK") are left alone: they genuinely
 *     are the same in many languages, and a word list per language would be a guess.
 *     [SAME_IN_EVERY_LANGUAGE] holds the few phrases that are names, not words.
 *  3. **The English catalog itself.** A key in the English catalog that some locale does not
 *     have falls back to English in that locale. The desktop's own keys (`conv.`, `pc.`,
 *     `desktop.`) must exist everywhere; the phone-only keys that the iOS tree has not
 *     translated yet are a recorded, NAMED set ([PHONE_ONLY_UNTRANSLATED]) which may shrink
 *     but not grow, and none of them may be used by the desktop.
 */
class DesktopTranslationCompletenessTest {

    private val mainSource = File("src/main/kotlin/com/oshi/desktop")

    private val tags: List<String> by lazy { Strings.availableTags.sorted() }
    private val en: Catalog by lazy { Strings.catalog("en")!! }

    /** Every key the desktop source reaches, through `t()` and through `dt()`. */
    private val desktopKeys: Set<String> by lazy {
        val shared = SourceKeys.used(mainSource)
        val overlay = SourceKeys.usedDesktop(mainSource)
        assertTrue("source scan found only ${shared.size} t() keys — a vacuous scan passes everything", shared.size >= 500)
        shared + overlay + DesktopStrings.overlayKeys
    }

    /** The desktop's own namespaces in the English catalog. */
    private val desktopNamespaceKeys: Set<String> by lazy {
        en.keys.filter { k -> DESKTOP_NAMESPACES.any { k.startsWith(it) } }.toSet()
    }

    @Test
    fun `all 34 languages are present`() {
        assertTrue("expected 34 catalogs, got ${tags.size}: $tags", tags.size == 34)
    }

    @Test
    fun `every desktop key exists in every language`() {
        val keys = (desktopKeys + desktopNamespaceKeys).sorted()
        val gaps = LinkedHashMap<String, MutableList<String>>()
        for (tag in tags) {
            val cat = Strings.catalog(tag)!!
            for (k in keys) if (cat[k] == null) gaps.getOrPut(k) { mutableListOf() } += tag
        }
        assertTrue(
            "${gaps.size} desktop keys are missing (or blank) in at least one of the 34 catalogs — " +
                "that language falls back to English, or to a ⟦key⟧ marker:\n" +
                gaps.entries.joinToString("\n") { (k, v) ->
                    "   $k  missing in ${if (v.size >= 33) "${v.size} languages" else v.toString()}" +
                        (en[k] ?: DesktopStrings.lookup(k))?.let { "  (en: \"${it.take(60)}\")" }.orEmpty()
                } +
                "\nAdd the translation to OSHI/<lang>.lproj/Localizable.strings and run ./gradlew i18nExtract. " +
                "A desktop-only (dt) key moves to the .lproj files and out of i18n/Desktop/en.json.",
            gaps.isEmpty(),
        )
    }

    @Test
    fun `no desktop phrase is left identical to English`() {
        val keys = (desktopKeys + desktopNamespaceKeys).sorted()
        val copies = LinkedHashMap<String, MutableList<String>>()
        for (tag in tags) {
            if (tag == "en") continue
            val cat = Strings.catalog(tag)!!
            for (k in keys) {
                val english = en[k] ?: continue
                val local = cat[k] ?: continue
                if (local == english && isPhrase(english) && english !in SAME_IN_EVERY_LANGUAGE) {
                    copies.getOrPut(k) { mutableListOf() } += tag
                }
            }
        }
        assertTrue(
            "${copies.size} desktop phrases are identical to English in some language (copied, not translated):\n" +
                copies.entries.joinToString("\n") { (k, v) -> "   $k = \"${en[k]!!.take(60)}\"  in $v" },
            copies.isEmpty(),
        )
    }

    @Test
    fun `every key of the English catalog exists in every language, except the recorded phone-only set`() {
        val missing = LinkedHashMap<String, MutableList<String>>()
        for (tag in tags) {
            val cat = Strings.catalog(tag)!!
            for (k in en.keys) if (cat[k] == null) missing.getOrPut(k) { mutableListOf() } += tag
        }
        val unexpected = missing.keys - PHONE_ONLY_UNTRANSLATED
        assertTrue(
            "keys of the English catalog missing in some language, and not on the recorded phone-only list:\n" +
                unexpected.sorted().joinToString("\n") { "   $it  missing in ${missing.getValue(it)}" },
            unexpected.isEmpty(),
        )
        val usedByDesktop = PHONE_ONLY_UNTRANSLATED intersect desktopKeys
        assertTrue(
            "the desktop now uses keys that are untranslated in some language: $usedByDesktop — translate them",
            usedByDesktop.isEmpty(),
        )
        // A ratchet that cannot rot: a key that got translated (or removed) leaves the list.
        val stale = PHONE_ONLY_UNTRANSLATED - missing.keys
        assertTrue("translated now, remove from PHONE_ONLY_UNTRANSLATED: ${stale.sorted()}", stale.isEmpty())
        println("[i18n] English catalog: ${en.keys.size} keys; phone-only keys still untranslated somewhere: ${missing.size}")
    }

    @Test
    fun `the redesigned screens draw no hard-coded English`() {
        // A string literal handed straight to a drawing call never reaches any catalog, so
        // every assertion above is blind to it. These are the screens redesigned in the
        // 2027 parity pass (messages, settings, tools) plus the media viewer; a literal with
        // a word in it, given to a text-drawing call or a text-ish named argument, fails.
        val ui = File(mainSource, "ui")
        val files = REDESIGNED_SCREENS.map { File(ui, it) }
        val absent = files.filterNot { it.isFile }
        assertTrue("redesigned screen files moved or renamed, update the list: $absent", absent.isEmpty())
        val hits = mutableListOf<String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                for (re in listOf(DRAW_CALL, TEXT_ARGUMENT)) {
                    for (m in re.findAll(line)) {
                        val literal = m.groupValues[2]
                        val words = INTERPOLATION.replace(literal, "")
                        if (literal !in NOT_PROSE && Regex("""\p{L}{3,}""").containsMatchIn(words)) {
                            hits += "${f.name}:${i + 1}  ${m.value.take(90)}"
                        }
                    }
                }
            }
        }
        assertTrue(
            "hard-coded text in redesigned screens (put it in the catalogs and draw it with t(…)):\n" +
                hits.joinToString("\n") { "   $it" },
            hits.isEmpty(),
        )
    }

    @Test
    fun `send outcomes, notices and notifications carry no hard-coded English`() {
        // __DESKTOP_NOTICES_TRANSLATED_2027__ The screens scan above cannot see these: the
        // model builds the sentence ("NOT SENT — …", "Attach failed: …") and a screen draws
        // whatever string it is handed. So the builders themselves are scanned: a literal
        // with a word in it handed to Notice / SendResult / ComposerState, or returned as a
        // reachability label, fails; and the notifier may not hold a sentence of its own.
        val hits = mutableListOf<String>()
        for ((name, res) in MODEL_FILES) {
            val f = File(mainSource, name)
            assertTrue("model file moved or renamed, update the list: $f", f.isFile)
            f.readLines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                for (re in res) for (m in re.findAll(line)) {
                    val literal = m.groupValues.last()
                    // a catalog key ("pc.notify.new_message") or a property name is not prose
                    if (Regex("""^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$""").matches(literal)) continue
                    val words = INTERPOLATION.replace(literal, "")
                    if (isPhrase(words)) {
                        hits += "$name:${i + 1}  ${m.value.take(90)}"
                    }
                }
            }
        }
        assertTrue(
            "hard-coded English in a notice, send outcome or notification (use t(…)):\n" +
                hits.joinToString("\n") { "   $it" },
            hits.isEmpty(),
        )
    }

    @Test
    fun `every row of the limits ledger is translated in all 34 languages`() {
        val rows = com.oshi.desktop.ui.state.DesktopLimits.MISSING + com.oshi.desktop.ui.state.DesktopLimits.NOT_IN_THIS_WINDOW
        assertTrue(rows.size >= 10)
        val gaps = mutableListOf<String>()
        for (row in rows) for (key in listOf(row.titleKey, row.whyKey)) {
            assertTrue("ledger key outside the desktop namespaces: $key", DESKTOP_NAMESPACES.any { key.startsWith(it) })
            for (tag in tags) {
                val v = Strings.catalog(tag)!![key]
                if (v == null) gaps += "$key missing in $tag"
                else if (tag != "en" && v == en[key] && isPhrase(v) && v !in SAME_IN_EVERY_LANGUAGE) gaps += "$key identical to English in $tag"
            }
        }
        assertTrue(gaps.joinToString("\n"), gaps.isEmpty())
    }

    @Test
    fun `the identical-to-English check is not vacuous`() {
        // The phrase rule must fire on a sentence and leave cognates alone.
        assertTrue(isPhrase("Show in folder"))
        assertTrue(isPhrase("Could not open %@"))
        assertTrue(!isPhrase("Video"))
        assertTrue(!isPhrase("System (%@)"))
        assertTrue(!isPhrase("%d/%d"))
        assertTrue(!isPhrase("https://oshi-messenger.com/terms"))
        // and the hard-coded-text scan must see the shapes it exists for.
        assertTrue(DRAW_CALL.containsMatchIn("TextAction(\"Rename\") { editing = true }"))
        assertTrue(TEXT_ARGUMENT.containsMatchIn("ToolCard(title = \"Linked devices\")"))
        assertTrue(!DRAW_CALL.containsMatchIn("Text(t(\"common.rename\"))"))
    }

    private companion object {
        val DESKTOP_NAMESPACES = listOf("conv.", "pc.", "desktop.")

        /** Relative to src/main/kotlin/com/oshi/desktop/ui. */
        val REDESIGNED_SCREENS = listOf(
            "DesktopWindow.kt", "FirstRunScreen.kt", "AppearanceSettings.kt", "StartupFailureWindow.kt",
            "components/ContactsPane.kt", "components/ConversationListPane.kt", "components/ThreadPane.kt",
            "components/ThreadChrome.kt", "components/MessageBubble.kt", "components/MorePane.kt",
            "components/SettingsPane.kt", "components/SettingsKit.kt", "components/ToolsKit.kt",
            "components/MailPane.kt", "components/PlacesPane.kt", "components/OshiTileMap.kt",
            "components/AiPane.kt", "components/LoRaPane.kt", "components/CallOverlay.kt",
            "components/ScheduledPane.kt", "components/PeersPane.kt", "components/LinkedDevicesPane.kt",
            "components/DeviceMailboxSection.kt", "components/CovertPane.kt", "components/Panes.kt",
            "components/MediaViewer.kt", "components/MediaPresentation.kt",
            // __DESKTOP_MAC_PARITY_B_2027__
            "components/AppLockUi.kt", "components/HelpPane.kt", "components/DiscoverGroupsPane.kt", "components/CameraMarkupUi.kt",
        )

        private val DRAW_CALL = Regex(
            """\b(Text|TextAction|ToolButton|ToolHint|ToolCard|ToolRow|ToolScaffold|ToolNotice|ToolEmptyState|ToolPill|""" +
                """ToolValue|ToolField|SettingsButton|SettingsRow|SettingsLinkRow|SettingsToggleRow|SettingsCard|""" +
                """SettingsPageHeader|PaneHeading|PrimaryButton|Item|Menu|Notice|SendResult)\(\s*"((?:[^"\\]|\\.)*)"""",
        )
        private val TEXT_ARGUMENT = Regex(
            """\b(title|subtitle|label|placeholder|contentDescription|description|hint|footer|headline|body)\s*=\s*"((?:[^"\\]|\\.)*)"""",
        )
        private val INTERPOLATION = Regex("""\$\{[^}]*\}|\$[A-Za-z_]+""")

        /** Literals that are not prose: a URL-scheme hint in an empty field, an animation label. */
        val NOT_PROSE = setOf("https://", "composer-pad", "PARITY.md · ")

        /** Relative to src/main/kotlin/com/oshi/desktop: where notices and notifications are built. */
        private val NOTICE_CALL = Regex(
            """\b(Notice|SendResult|ComposerState)\((?:[^"()]*,\s*)?"((?:[^"\\]|\\.)*)"""",
        )
        private val REACH_LABEL = Regex("""Reach\.[A-Z_]+\s*->\s*"((?:[^"\\]|\\.)*)"""")
        val MODEL_FILES: List<Pair<String, List<Regex>>> = listOf(
            "ui/state/ChatShellModel.kt" to listOf(NOTICE_CALL, REACH_LABEL),
            // The notifier: every literal is suspect, except the app name and the OS helpers'
            // own command lines (osascript / notify-send arguments are not shown to anyone).
            "ui/DesktopNotifier.kt" to listOf(Regex(""""((?!display notification|on run|end run|/usr/bin|--)(?:[^"\\]|\\.)*)"""")),
        )

        private val PLACEHOLDER = Regex("""%(?:\d+\$)?(?:@|d|ld|lld|s|f|\.\d+f)|%%""")
        private val WORD = Regex("""\p{L}{2,}""")

        /** Two or more words once placeholders, URLs and numbers are removed. */
        fun isPhrase(s: String): Boolean {
            if (s.startsWith("http://") || s.startsWith("https://")) return false
            return WORD.findAll(PLACEHOLDER.replace(s, " ")).count() >= 2
        }

        /** Names, not words: the same in every language. Keep this short. */
        val SAME_IN_EVERY_LANGUAGE = setOf(
            "OSHI Messenger",
            "Apple Intelligence",
            "OSHI Desktop",
            "Apple Watch",
        )

        /**
         * Keys the iOS tree added to en.lproj and has not translated everywhere yet, none of
         * them used by the desktop (alive check-in, LoRa channel sharing, Apple-only AI and
         * map strings). Recorded 2026-11-19 at 90 keys; it may only shrink.
         */
        val PHONE_ONLY_UNTRANSLATED = setOf(
            "ai.error_busy", "ai.error_context_window", "ai.error_generic", "ai.error_guardrail",
            "ai.error_language", "ai.not_ready_model_downloading", "ai.not_ready_not_enabled",
            "alive.a11y.confirmed", "alive.a11y.neutral", "alive.a11y.overdue", "alive.action.im_alive",
            "alive.bar.open_settings", "alive.duration.days_hours", "alive.duration.hours_minutes",
            "alive.duration.minutes", "alive.duration.now", "alive.interval.24h", "alive.interval.48h",
            "alive.interval.72h", "alive.interval.custom", "alive.interval.custom_hours", "alive.interval.every",
            "alive.lane.you", "alive.note.delivery", "alive.note.no_location", "alive.notification.mine_due.body",
            "alive.notification.mine_due.title", "alive.notification.overdue.body", "alive.notification.overdue.title",
            "alive.sheet.explainer", "alive.sheet.interval", "alive.sheet.mine_switch", "alive.sheet.stop",
            "alive.sheet.their_none", "alive.sheet.their_section", "alive.state.due_in", "alive.state.not_set",
            "alive.state.overdue_by", "alive.title", "alive.unknown_peer", "forward.no_match",
            "forward.search_prompt", "lora.channel.key_hint", "lora.channel.last", "lora.channels.footer",
            "lora.channels.no_key", "lora.channels.none", "lora.channels.primary", "lora.channels.private_key",
            "lora.channels.public_key", "lora.channels.secondary", "lora.channels.section", "lora.chile.apply",
            "lora.chile.confirm.apply", "lora.chile.confirm.body", "lora.chile.confirm.title",
            "lora.chile.detail", "lora.configure.hide", "lora.configure.show", "lora.hero.channel",
            "lora.hero.heard_present", "lora.interop.unverified.detail", "lora.join.apply", "lora.join.done",
            "lora.join.footer", "lora.join.invalid", "lora.join.preview_many", "lora.join.preview_one",
            "lora.join.replaces", "lora.join.section", "lora.nodes.me_badge", "lora.nodes.on_map",
            "lora.nodes.range_estimate", "lora.nodes.signal_a11y", "lora.pairing.noscreen.hint",
            "lora.routing.no_route_warning", "lora.routing.prefer_radio.note", "lora.share.copied",
            "lora.share.copy", "lora.share.footer", "lora.share.section", "map_background_apple",
            "map_background_apple_detail", "map_background_footer", "map_background_oshi", "map_background_oshi_detail",
            "map_background_title", "settings.security.privacyCover", "settings.security.privacyCover.footer",
            "settings.webSessions.outdatedPage",
        )
    }
}
