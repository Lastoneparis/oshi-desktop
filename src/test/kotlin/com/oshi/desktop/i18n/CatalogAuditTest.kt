package com.oshi.desktop.i18n

import com.oshi.desktop.i18n.tools.SourceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The audit. Every test here exists because a localisation test of the OBVIOUS shape
 * would have passed while the app was broken.
 */
class CatalogAuditTest {

    private val mainSource = File("src/main/kotlin/com/oshi/desktop")

    private val usedKeys: Set<String> by lazy {
        val k = SourceKeys.used(mainSource)
        assertTrue(
            "the source scan found ${k.size} keys ($k). A key-usage audit that finds nothing passes " +
                "every assertion below vacuously, which is the exact failure this file exists " +
                "to prevent, so it fails instead.",
            k.size >= 10,
        )
        k
    }

    private fun catalogs(): Map<String, Catalog> =
        Strings.availableTags.associateWith { Strings.catalog(it)!! }

    // ==================================================================== TRAP 1
    // "compare every locale to en" is blind when EN is missing the key too.

    @Test
    fun `every key the source asks for exists in English`() {
        // This is the comparison that has teeth: SOURCE against catalog, not locale
        // against locale. If a key is in nobody's catalog, a 33-way parity test against
        // English is perfectly green and the app renders a hole.
        val en = Strings.catalog("en")!!
        val missing = usedKeys.filter { en[it] == null }.sorted()
        val elsewhere = missing.associateWith { k -> catalogs().filter { it.value[k] != null }.keys.sorted() }
        assertTrue(
            "keys used in desktop source that English does not have:\n" +
                missing.joinToString("\n") { "   $it   (present in: ${elsewhere[it]?.ifEmpty { listOf("NO LOCALE AT ALL") }})" } +
                "\nA key present in some locales but not English is the WORST case: a parity " +
                "test comparing locales to English finds nothing wrong, because English is the " +
                "one with the hole.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `English is measurably NOT the superset, and the size of that hole is on record`() {
        // The fact that makes the test above necessary rather than theoretical. Asserted
        // as a CEILING so that "English caught up" is a visible change and "English fell
        // further behind" is a failure, instead of both being invisible.
        val all = catalogs()
        val union = all.values.flatMap { it.keys }.toSet()
        val en = all.getValue("en").keys
        val absentFromEn = union - en
        val carriers = absentFromEn.associateWith { k -> all.filter { it.value[k] != null }.keys }

        println("[i18n] union across 34 locales = ${union.size} keys; en = ${en.size}; " +
            "absent from en = ${absentFromEn.size}")
        val byCarrier = absentFromEn.flatMap { k -> carriers.getValue(k) }.groupingBy { it }.eachCount()
        println("[i18n] locales carrying keys English lacks: " +
            byCarrier.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" })

        assertEquals("34 catalogs expected", 34, all.size)
        assertTrue(
            "English is missing ${absentFromEn.size} keys that other locales carry; the recorded " +
                "figure is 1057. If this grew, a translator added keys nobody wired up; if it " +
                "shrank, English caught up. Either way update the number deliberately.",
            absentFromEn.size <= 1057,
        )
        // and the shape of it: almost all of it is two locales
        val onlyPlFa = absentFromEn.count { carriers.getValue(it).all { t -> t == "pl" || t == "fa" } }
        assertTrue("expected the bulk of the hole to be pl+fa only, got $onlyPlFa", onlyPlFa >= 900)
    }

    // ==================================================================== TRAP 2
    // "no English words appear in locale X" passes or fails on the JVM's default locale.
    // The assertion here is STRUCTURAL: did the REQUESTED catalog serve this key, or did
    // the chain fall through? No word lists, no language detection, no Locale.getDefault.

    @Test
    fun `every locale serves every key the desktop uses from its OWN catalog`() {
        val cats = catalogs()
        val gaps = LinkedHashMap<String, List<String>>()
        for ((tag, cat) in cats.toSortedMap()) {
            val missing = usedKeys.filter { cat[it] == null }
            if (missing.isNotEmpty()) gaps[tag] = missing
        }
        assertTrue(
            "locales that would fall back for a key the desktop uses:\n" +
                gaps.entries.joinToString("\n") { "   ${it.key}: ${it.value.size} → ${it.value.take(6)}" },
            gaps.isEmpty(),
        )
    }

    @Test
    fun `the structural assertion does not move with the JVM default locale`() {
        // The word-based version of the test above ("assert no English appears in fr")
        // depends on what the JVM thinks the default locale is, which is a property of the
        // machine. Run the structural one under four hostile defaults and demand one answer.
        val saved = Locale.getDefault()
        try {
            val answers = listOf(Locale.US, Locale.GERMANY, Locale.JAPAN, Locale.forLanguageTag("ar-EG")).map {
                Locale.setDefault(it)
                Strings.resetForTest()
                Strings.availableTags.toSortedSet().associateWith { tag ->
                    usedKeys.count { k -> Strings.catalog(tag)!![k] != null }
                }
            }
            assertEquals("coverage moved with Locale.getDefault(): $answers", 1, answers.toSet().size)
        } finally {
            Locale.setDefault(saved)
            Strings.resetForTest()
        }
    }

    // ==================================================================== TRAP 3
    // an audit that greps for one call shape is blind to custom wrappers.

    /**
     * String literals that ARE catalog keys but are deliberately not localised.
     *
     * A ratchet, not an off switch: every entry names the file and the reason, and a
     * literal that is not on this list fails the suspect scan. Nothing may be added here
     * to silence a genuine defect.
     */
    private val notLocalisation = mapOf(
        // GroupModels.kt — GroupType.ADMIN_ONLY's RAW WIRE VALUE. iOS decodes it with a
        // plain `container.decode(GroupType.self)`, so a translated spelling would throw
        // and take the whole group definition with it. It collides with the catalog key
        // `admin_only` = "Admin Only" by pure coincidence.
        "admin_only" to "GroupType wire value — must never be translated",
    )

    @Test
    fun `no catalog key reaches the UI outside a registered accessor`() {
        // Not "trust the ACCESSORS list": find string literals that ARE catalog keys and
        // do NOT go through it. An unregistered wrapper shows up here as a pile of hits.
        val known = catalogs().values.flatMap { it.keys }.toSet()
        val suspects = SourceKeys.suspects(known, mainSource).filter { it.key !in notLocalisation }
        // and the ratchet must not rot: an allowlisted literal that is no longer in the
        // source is a line nobody removed, and it hides the next collision on that key.
        val stale = notLocalisation.keys - SourceKeys.suspects(known, mainSource).map { it.key }.toSet()
        assertTrue("allowlist entries that no longer match anything in the source: $stale", stale.isEmpty())
        assertTrue(
            "string literals that are real catalog keys but never reach the catalog — either a " +
                "wrapper that SourceKeys.ACCESSORS does not know about, or a key used as a raw " +
                "string:\n" +
                suspects.joinToString("\n") { "   ${it.key}  at ${it.file.name}:${it.line}" },
            suspects.isEmpty(),
        )
    }

    // ==================================================================== DESKTOP OVERLAY
    // `desktop.` strings are deliberately not in the extracted iOS catalogs. They need a
    // separate audit rather than an exception to the shared-catalog assertions above.

    @Test
    fun `desktop-only catalog exactly matches the desktop source`() {
        val used = SourceKeys.usedDesktop(mainSource)
        assertTrue(
            "the desktop-only scan found ${used.size} keys ($used). An empty scan would make " +
                "the exact-set assertion below pass while every dt(…) call had stopped being " +
                "recognised, so fail before accepting that vacuous result.",
            used.size >= 10,
        )

        val nonDesktopUses = used.filterNot { it.startsWith(DesktopStrings.PREFIX) }
        assertTrue(
            "dt(…) is for desktop-only keys. These calls would bypass an existing translation " +
                "or render a marker instead of using t(…): $nonDesktopUses",
            nonDesktopUses.isEmpty(),
        )
        assertTrue("desktop overlay keys outside ${DesktopStrings.PREFIX}: ${DesktopStrings.misfiledKeys()}",
            DesktopStrings.misfiledKeys().isEmpty())
        assertTrue(
            "desktop overlay keys shadowed by an extracted iOS key: ${DesktopStrings.shadowedKeys()}",
            DesktopStrings.shadowedKeys().isEmpty(),
        )

        val missing = used - DesktopStrings.overlayKeys
        val stale = DesktopStrings.overlayKeys - used
        assertTrue(
            "desktop-only keys reached from source but absent from i18n/Desktop/en.json: $missing\n" +
                "An absent key becomes a visible ⟦desktop.…⟧ marker in the window.",
            missing.isEmpty(),
        )
        assertTrue(
            "desktop-only keys left in i18n/Desktop/en.json with no dt(…) call: $stale\n" +
                "Remove them or wire the screen they describe; retaining English-only dead copy " +
                "makes the catalog look more localised than the window is.",
            stale.isEmpty(),
        )
    }

    // ==================================================================== TRAP 4
    // InfoPlist.strings is a SEPARATE catalog. 100% on one says nothing about the other.

    @Test
    fun `InfoPlist is a separate catalog and is counted separately`() {
        val info = Strings.availableTags.associateWith { tag ->
            Catalog.read(tag, javaClass.getResourceAsStream("/i18n/InfoPlist/$tag.json")!!)
        }
        assertEquals(34, info.size)
        val enInfo = info.getValue("en")
        assertEquals("en InfoPlist is 16 keys, against Localizable's 3987", 16, enInfo.size)
        assertTrue("the two catalogs must not share keys", (enInfo.keys intersect Strings.catalog("en")!!.keys).isEmpty())

        // every locale has all 16, and none is blank
        val gaps = info.filterValues { it.size != 16 || it.blankKeys().isNotEmpty() }
        assertTrue("InfoPlist gaps: ${gaps.mapValues { it.value.size }}", gaps.isEmpty())

        // ---- WHAT INFOPLIST MAPS TO ON THE DESKTOP: nothing. Asserted, not asserted-away.
        //
        // All 16 keys are `NS…UsageDescription` — iOS permission-prompt copy. Windows and
        // Linux present no OS grant dialog for camera, microphone, Bluetooth, location,
        // photos, calendars, Face ID, local network or speech, so there is no string for
        // any of them to be. Checked here rather than described, because "not applicable"
        // is a claim: if a non-prompt key ever appears (a CFBundleDisplayName, say) it
        // WOULD have a desktop counterpart and this test is what notices.
        val prompts = enInfo.keys.filter { it.startsWith("NS") && it.endsWith("UsageDescription") }
        assertEquals(
            "every InfoPlist key was a permission prompt; a new kind appeared: " +
                (enInfo.keys - prompts.toSet()),
            16, prompts.size,
        )
        // The desktop's real installer metadata is jpackage's --name/--description, and
        // it is NOT in this catalog — it is hardcoded English in build.gradle.kts. So
        // InfoPlist being 100% in 34 languages buys the desktop client exactly zero, and
        // jpackage has no localised-metadata mechanism to spend it on even if it did:
        // one --name per invocation, one installer per language or none.
        val build = File("build.gradle.kts").readText()
        assertTrue("jpackage --name is expected to be a single hardcoded value",
            build.contains("\"--name\", appName"))
        assertTrue("the display name is not in InfoPlist.strings", "CFBundleDisplayName" !in enInfo.keys)
        println("[i18n] InfoPlist: 16 keys × 34 locales, ALL of them NS*UsageDescription. " +
            "Desktop equivalent: NONE — Windows/Linux have no permission-prompt strings. " +
            "Installer/shortcut metadata (jpackage --name/--description/--win-menu-group) is " +
            "hardcoded English in build.gradle.kts and has no key in this catalog; jpackage 17 " +
            "takes one --name per invocation and cannot localise it.")
    }

    // ==================================================================== the report

    @Test
    fun `report per-locale coverage of the keys the desktop actually uses`() {
        val cats = catalogs()
        println()
        println("[i18n] ============ DESKTOP KEY COVERAGE ============")
        println("[i18n] keys reached from desktop source through a registered accessor: ${usedKeys.size}")
        usedKeys.forEach { k -> println("[i18n]     $k") }
        println("[i18n] ---------------------------------------------")
        println("[i18n] %-9s %6s %8s   %s".format("locale", "have", "pct", "missing"))
        var full = 0
        for ((tag, cat) in cats.toSortedMap()) {
            val have = usedKeys.count { cat[it] != null }
            val missing = usedKeys.filter { cat[it] == null }
            if (have == usedKeys.size) full++
            println("[i18n] %-9s %3d/%-3d %7.1f%%   %s".format(
                tag, have, usedKeys.size, 100.0 * have / usedKeys.size,
                if (missing.isEmpty()) "—" else missing.joinToString(),
            ))
        }
        println("[i18n] $full of ${cats.size} locales serve every key the desktop uses.")
        println("[i18n] NOTE: this is the DESKTOP's coverage, over ${usedKeys.size} keys — not iOS's " +
            "coverage over 3987. Reporting the latter as the former would claim a translated " +
            "desktop client on the strength of a translated phone.")
        println("[i18n] =============================================")
    }

    @Test
    fun `no locale has a blank value where the desktop needs a string`() {
        // A translator leaving "" is 100% "present" and a hole on screen. Catalog.get
        // treats blank as absent; this is what proves it matters here.
        val bad = catalogs().mapValues { (_, c) -> c.blankKeys() intersect usedKeys }.filterValues { it.isNotEmpty() }
        assertTrue("blank values where the desktop needs a string: $bad", bad.isEmpty())
    }

    @Test
    fun `argument counts agree across every locale for every key the desktop uses`() {
        // A translation that dropped a %@ crashes String.format in that language only.
        val en = Strings.catalog("en")!!
        val bad = ArrayList<String>()
        for (k in usedKeys) {
            val want = IosFormat.arity(en[k] ?: continue)
            for ((tag, cat) in catalogs()) {
                val v = cat[k] ?: continue
                val got = IosFormat.arity(v)
                if (got != want) bad.add("$k: en takes $want, $tag takes $got — ${v.take(60)}")
            }
        }
        assertTrue("argument-count disagreements:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `there are no plural catalogs to port, and that is measured`() {
        // iOS's ONLY plural mechanism is .stringsdict. There are zero in the tree, so the
        // shipped app has no plural rules and the desktop must not invent any.
        val iosRoot = System.getProperty("oshi.ios.root")?.let { File(it) }
        org.junit.Assume.assumeTrue("no iOS tree on this machine", iosRoot?.isDirectory == true)
        val dicts = iosRoot!!.walkTopDown()
            .filter { it.isFile && it.extension == "stringsdict" && !it.path.contains("xcarchive") }
            .map { it.path }.toList()
        assertTrue("a .stringsdict appeared — the desktop now needs plural support: $dicts", dicts.isEmpty())
    }
}
