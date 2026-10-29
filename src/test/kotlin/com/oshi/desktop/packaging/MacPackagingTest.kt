package com.oshi.desktop.packaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The macOS bundle carries the two TCC usage strings, and the build actually HANDS THEM
 * to jpackage.
 *
 * Both halves are needed, and only the second one was ever missing. `platform/macos/
 * packaging/Info.plist` has sat in this repository since 2026-09-23 with a comment saying
 * why it exists — *"Without the camera key, macOS TCC refuses (or kills) the packaged app
 * the first time a video call opens the camera"* — and until 2026-10-28 no jpackage
 * command line ever mentioned the directory it is in. `resourceDirArgs` had exactly two
 * call sites, `"windows"` and `"linux"`; `packageDmg` passed `--mac-package-identifier`
 * and nothing else, and `packageAppImage` passed nothing at all.
 *
 * MEASURED, not inferred: jpackage 21's own `Info-lite.plist.template` was extracted from
 * the JDK's module image and it has **no `NSCameraUsageDescription` key whatsoever** (its
 * microphone string is the generic *"The application OSHI is requesting access to the
 * microphone."*). So every macOS bundle this project produced before that date would have
 * been terminated by the OS on its first video call, with a green build and a green suite.
 *
 * That is the failure this file is here to keep from coming back, and it is EXACTLY the
 * failure mode `BrandingTest` was written for on Windows — an override matched by exact
 * filename, ignored in silence when it does not match. The difference is that an unbranded
 * Start-menu entry is ugly, while a missing usage string is a kill.
 *
 * Nothing here needs a Mac: these are assertions about files in the repository and about
 * the build script's wiring, so the Linux and Windows runners check the macOS packaging
 * too — which matters, because this is the override no CI leg builds.
 */
class MacPackagingTest {

    private val packagingDir = File("platform/macos/packaging")
    private val plist = File(packagingDir, "Info.plist")
    private val lprojDir = File(packagingDir, "lproj")

    /**
     * jpackage looks this override up by EXACT filename. `Info-lite.plist` or
     * `info.plist` is a green build and a stock plist.
     */
    @Test
    fun `the macOS override is named exactly what jpackage looks up`() {
        assertTrue(
            "missing ${plist.path}. jpackage matches --resource-dir overrides by exact " +
                "filename and ignores anything else without a word.",
            plist.isFile,
        )
        assertTrue(
            "platform/macos/packaging holds no OSHI.icns — the bundle wears jpackage's " +
                "default icon in the Dock and in Finder.",
            File(packagingDir, "OSHI.icns").isFile,
        )
    }

    /** `icns` in ASCII, big-endian length second. A renamed PNG passes every other check. */
    @Test
    fun `the icns is really an icns`() {
        val b = File(packagingDir, "OSHI.icns").readBytes()
        assertTrue("OSHI.icns is shorter than its own header", b.size > 8)
        assertTrue(
            "OSHI.icns does not start with the 'icns' magic — a renamed PNG installs and " +
                "shows nothing.",
            b.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "icns",
        )
    }

    /**
     * The camera key is the whole reason this override exists; the microphone key is
     * overridden because jpackage's own string names the LAUNCHER and says nothing about
     * encryption, which is a poor thing to show in a permission prompt.
     */
    @Test
    fun `both TCC usage strings are present and are ours, not jpackage's`() {
        val text = plist.readText()
        for (key in listOf("NSCameraUsageDescription", "NSMicrophoneUsageDescription")) {
            assertTrue(
                "$key is missing from ${plist.path}. macOS terminates a process that opens " +
                    "the camera or the microphone with no usage description, so this is a " +
                    "crash on the first video call, not a missing label.",
                text.contains("<key>$key</key>"),
            )
        }
        assertFalse(
            "the microphone string is still jpackage's generic template text — overriding " +
                "the plist to keep the stock wording is the one outcome that is worse than " +
                "not overriding it, because it looks deliberate.",
            text.contains("is requesting access to the microphone"),
        )
    }

    /**
     * The override is jpackage's template PLUS two keys, so it must keep the substitution
     * tokens. Hardcoding any of them ships a bundle whose name, version or identifier is
     * frozen at whatever the author typed — and `CFBundleIdentifier` is what TCC keys its
     * permission grants on, so freezing that one hands every build the same consent record.
     */
    @Test
    fun `the DEPLOY tokens jpackage substitutes are still in place`() {
        val text = plist.readText()
        for (token in listOf(
            "DEPLOY_LAUNCHER_NAME",
            "DEPLOY_ICON_FILE",
            "DEPLOY_BUNDLE_IDENTIFIER",
            "DEPLOY_BUNDLE_NAME",
            "DEPLOY_BUNDLE_SHORT_VERSION",
            "DEPLOY_BUNDLE_CFBUNDLE_VERSION",
        )) {
            assertTrue(
                "$token was replaced by a literal in ${plist.path}. jpackage substitutes " +
                    "these; a literal freezes that field for every future build.",
                text.contains(token),
            )
        }
    }

    // ------------------------------------------------- the 33 translated prompts

    /**
     * macOS shows the camera/microphone prompt in the user's own language.
     *
     * Those sentences were English-only in the Mac bundle while the phone this is a port of
     * had all 34 — and PARITY row 1.5 said `InfoPlist` "maps to NOTHING here", which is true
     * of Windows and Linux (no permission dialogs) and false of the platform the bundle is
     * FOR. The translations already existed in the iOS tree and were extracted verbatim.
     *
     * jpackage cannot install them: `--resource-dir` matches by exact FILENAME, so an
     * `fr.lproj` directory in it is ignored. MEASURED — a probe file was placed there, an
     * app image was built, and `Contents/Resources` came back holding `OSHI.icns` alone. So
     * `localiseMacBundle` copies them in afterwards, and this test guards the inputs.
     */
    @Test
    fun `every language the desktop ships has a localised permission prompt, except English`() {
        val locales = File("src/main/resources/i18n/Localizable")
            .listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .map { it.name.removeSuffix(".json") }
            .filterNot { it == "en" }
            .sorted()
        assertTrue("no desktop catalogs found — this test is measuring nothing", locales.size > 20)

        val missing = locales.filterNot { File(lprojDir, "$it.lproj/InfoPlist.strings").isFile }
        assertTrue(
            "these languages ship a UI catalog but would get an ENGLISH permission prompt: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * __MAC_DEEP_LINK_2026_10_29__ The bundle must CLAIM `oshi://`, or a clicked link goes
     * nowhere.
     *
     * The desktop has parsed these since pairing landed — but only when pasted. macOS
     * routes a clicked link only to an app whose Info.plist declares the scheme, so
     * without this the one half of pairing the Mac could not do was the half a user is
     * most likely to try: tapping the link somebody sent them.
     *
     * Asserted on the TEMPLATE because that is what jpackage copies; `plutil` cannot lint
     * it (the `DEPLOY_*` tokens are not valid XML until substituted), which is exactly why
     * a test reads it instead.
     */
    @Test
    fun `the bundle claims the oshi URL scheme`() {
        val text = plist.readText()
        assertTrue("Info.plist must declare CFBundleURLTypes", text.contains("CFBundleURLTypes"))
        assertTrue("and the scheme itself", Regex("""<key>CFBundleURLSchemes</key>\s*<array>\s*<string>oshi</string>""").containsMatchIn(text))
        // The URL NAME is the bundle id, not a display string: macOS uses it to tell
        // competing claimants apart, and a human-readable name is how two builds of the
        // same app end up fighting over one scheme.
        assertTrue("CFBundleURLName should be the bundle identifier token",
            Regex("""<key>CFBundleURLName</key>\s*<string>DEPLOY_BUNDLE_IDENTIFIER</string>""").containsMatchIn(text))
    }

    /**
     * English is deliberately absent. `Info.plist` holds it, macOS falls back to that when no
     * `.lproj` matches, and two English sources would drift — which is the whole failure this
     * file exists to prevent, one directory along.
     */
    @Test
    fun `there is no en lproj to drift from Info plist`() {
        assertFalse(
            "en.lproj duplicates the English already in Info.plist",
            File(lprojDir, "en.lproj").exists(),
        )
    }

    @Test
    fun `each translation carries both keys, non-empty, and is not just English`() {
        val english = plist.readText()
        fun englishValue(key: String) =
            Regex("""<key>$key</key>\s*<string>([^<]*)</string>""").find(english)!!.groupValues[1]

        val dirs = lprojDir.listFiles { f -> f.isDirectory && f.name.endsWith(".lproj") }.orEmpty()
        assertTrue("no .lproj directories at all", dirs.isNotEmpty())
        for (dir in dirs.sortedBy { it.name }) {
            val text = File(dir, "InfoPlist.strings").readText(Charsets.UTF_8)
            for (key in listOf("NSCameraUsageDescription", "NSMicrophoneUsageDescription")) {
                val m = Regex(""""$key"\s*=\s*"((?:[^"\\]|\\.)*)";""").find(text)
                assertTrue("${dir.name} has no $key — that language falls back to English", m != null)
                val value = m!!.groupValues[1]
                assertTrue("${dir.name}/$key is empty, which macOS shows as a blank prompt", value.isNotBlank())
                // A blanket copy of the English sentence into 33 files is the cheap way to
                // make this directory look finished, and it is indistinguishable from done
                // unless something says the values must actually differ.
                assertNotEquals(
                    "${dir.name}/$key is the English string verbatim",
                    englishValue(key),
                    value,
                )
            }
        }
    }

    /**
     * And the wiring, which is the half that was actually broken.
     *
     * Reading the build script as text is crude, and it is here because the alternative is
     * worse: the only other way to see this is to build a macOS bundle, which no CI leg
     * does and which takes 338 MB of disk. The failure being guarded is not subtle —
     * someone removes the argument and every other check in this file still passes.
     */
    @Test
    fun `the build script passes the macOS resource directory to jpackage`() {
        val script = File("build.gradle.kts").readText()
        assertTrue(
            "build.gradle.kts never calls resourceDirArgs(\"macos\"). The override in " +
                "platform/macos/packaging is then a file nobody reads: jpackage falls back " +
                "to its stock template, which has NO camera key at all.",
            script.contains("resourceDirArgs(\"macos\")"),
        )
        assertTrue(
            "packageDmg does not pass the macOS resource directory.",
            Regex("""packageDmg"[\s\S]{0,400}?macosResourceDirArgs\(\)""").containsMatchIn(script),
        )
        assertTrue(
            "packageAppImage passes no resource directory. It builds on ANY host, so on a " +
                "Mac it produces the .app bundle whose plist this is about.",
            Regex("""packageAppImage"[\s\S]{0,200}?hostResourceDirArgs\(\)""").containsMatchIn(script),
        )
        assertTrue(
            "the app image never installs the localised prompts — jpackage's --resource-dir " +
                "ignores a .lproj directory, so nothing else puts them in the bundle.",
            script.contains("localiseMacBundle("),
        )
        assertTrue(
            "packagingOverrides does not report macos. That task exists so that an override " +
                "which is silently not passed cannot look like one that was passed and " +
                "ignored — and it was blind on the only host that builds macOS bundles.",
            Regex(""""macos" to macos""").containsMatchIn(script),
        )
    }
}
