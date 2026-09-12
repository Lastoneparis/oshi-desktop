package com.oshi.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Guards the one claim this whole project rests on: that the desktop client compiles the
 * SAME crypto sources Android does, and that they have not quietly stopped being shareable.
 *
 * These tests read the Android tree. They never write to it.
 *
 * Without them, "shared source" degrades into "shared source, until someone adds an import"
 * — and this project's whole history is features that were silently incompatible.
 */
class SharedSourceTripwireTest {

    /**
     * The Android tree. `build.gradle.kts` always passes `oshi.android.root`; the fallback
     * is for running these tests outside Gradle and is RELATIVE on purpose — the absolute
     * `/Users/...` path that used to live here existed on exactly one machine, so on any
     * CI runner it resolved to nothing and every test below assumed itself away.
     */
    private val androidRoot = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android")
    private val v2Dir = File(androidRoot, "app/src/main/java/com/oshi/messenger/network/v2")

    /**
     * Absence of the Android tree is a FAILURE here, never a skip.
     *
     * This used to be four `assumeTrue`s, and on a Windows or Linux runner that had never
     * seen the Android tree they turned the project's single most load-bearing guard into
     * four green skips. They were also unnecessary: the desktop client cannot COMPILE
     * without these sources — `KeyVault` calls `OSHICryptoV2.jsonEscape` — so any machine
     * that got as far as running this test has the tree somewhere. What the skip actually
     * hid was the other case: `oshi.android.root` pointing at a DIFFERENT place than the
     * `srcDir` the build compiled from, which silently deletes the tripwire while the
     * build stays green.
     */
    @Before
    fun theAndroidTreeMustBeReadable() {
        if (!v2Dir.isDirectory) {
            fail(
                "the shared crypto sources are not at ${v2Dir.absolutePath}.\n" +
                    "  This is not a skippable condition: the desktop client compiles those files, so a " +
                    "build that ran this test must have found them — which means 'oshi.android.root' is " +
                    "pointing somewhere other than the srcDir build.gradle.kts compiled, and this tripwire " +
                    "is guarding nothing.\n" +
                    "  Pass -PoshiAndroidRoot=/path/to/OSHI-Android (or, for a mutation run, " +
                    "-PoshiAndroidRootOverride=/path/to/a/mutated/copy)."
            )
        }
    }

    /** The exact files build.gradle.kts pulls in. Keep the two lists in step. */
    private val sharedFiles = listOf(
        "OSHICryptoV2.kt",
        "OSHICryptoV2Streaming.kt",
        "OSHIRatchetV2.kt",
        "V2Session.kt",
        "V2FileKeyMessage.kt",
        "V2RetryBudget.kt",
    )

    @Test
    fun `the shared crypto sources exist where the build expects them`() {
        for (name in sharedFiles) {
            assertTrue("missing shared source: $name", File(v2Dir, name).isFile)
        }
    }

    /**
     * THE TRIPWIRE. These six files are compiled unmodified into a desktop JVM binary.
     * The moment one of them gains an Android import, the desktop build breaks — and that
     * red build is the alarm. This test states the rule explicitly so the failure names
     * its own cause instead of surfacing as an unresolved-reference error.
     */
    @Test
    fun `no shared crypto source has an android or hilt dependency`() {
        val banned = listOf(
            "import android.", "import androidx.", "import dagger.",
            "import javax.inject", "BuildConfig",
        )
        val offences = mutableListOf<String>()
        for (name in sharedFiles) {
            val text = File(v2Dir, name).readText()
            for (b in banned) {
                if (text.contains(b)) offences += "$name contains '$b'"
            }
        }
        assertEquals(
            "A shared crypto source gained a platform dependency. Either revert it, or move " +
                "the platform-specific part behind an interface. Do NOT fork the file — a " +
                "forked crypto core is how three platforms stop agreeing.\n" +
                offences.joinToString("\n"),
            emptyList<String>(), offences,
        )
    }

    /**
     * `FetchedBundle` is one of only two hand-copies in the skeleton. Its declaration lives
     * in the Android tree inside a file we cannot compile. If someone adds a field there,
     * the desktop copy silently loses it — so compare the field lists.
     */
    @Test
    fun `the FetchedBundle copy still matches the android declaration`() {
        val src = File(v2Dir, "V2KeysClient.kt")

        // Strip line comments FIRST: the declaration's own comments contain ')' characters,
        // and cutting on the first one silently truncates the field list to one entry —
        // which would make this guard pass for the wrong reason on any future edit.
        val decl = src.readText()
            .substringAfter("data class FetchedBundle(")
            .lineSequence()
            .map { it.substringBefore("//") }
            .takeWhile { !it.trimStart().startsWith(")") }
            .joinToString("\n")
        val androidFields = Regex("""val\s+(\w+)\s*:""").findAll(decl).map { it.groupValues[1] }.toList()
        assertEquals("the declaration parser found the wrong number of fields", 6, androidFields.size)

        // `$` NAMES ARE COMPILER OUTPUT, NOT DECLARATIONS — and this filter was added
        // because a real change made this guard go red for the wrong reason.
        //
        // Adding the Compose Multiplatform plugin applies the Compose compiler to the
        // WHOLE module, and it stamps a `$stable` field onto data classes — including
        // this one. Compose does NOT mark it synthetic, so `isSynthetic` alone let it
        // through and the tripwire reported "FetchedBundle drifted" when nothing had
        // drifted: the Android declaration and the desktop copy were identical and only
        // the toolchain had changed.
        //
        // What this gives up, stated rather than hidden: a field whose name contains `$`
        // can no longer be detected as drift. In Kotlin that name is unreachable without
        // backticks, and no data class in either tree has one, so the coverage lost is
        // theoretical while the false positive was blocking every run.
        //
        // What it does NOT fix. The Compose compiler is now instrumenting bytecode the
        // Android app compiles WITHOUT it, so "the same source, compiled the same way" is
        // no longer strictly true of this module — see build.gradle.kts. The structural
        // repair is the one this assertion's own message already recommends: extract a
        // :v2-core module with no UI plugins on it and let the UI module depend on it.
        // Until that happens, this filter is a plaster over a real (if currently
        // harmless) divergence, and it is written down here so the next reader knows.
        val desktopFields = com.oshi.messenger.network.v2.FetchedBundle::class.java
            .declaredFields
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .map { it.name }

        assertEquals(
            "FetchedBundle drifted between the Android tree and the desktop copy. " +
                "Update src/main/kotlin/com/oshi/desktop/seams/FetchedBundle.kt, or better, " +
                "extract a shared :v2-core module so no copy is needed.",
            androidFields, desktopFields,
        )
    }

    /**
     * The crypto constants ARE the protocol. If any of these three strings changes, every
     * shipped iOS and Android install stops being able to talk to the new build. Assert
     * them against the Android source text so a rename cannot pass unnoticed.
     */
    @Test
    fun `the kdf info strings are unchanged in the shared source`() {
        val src = File(v2Dir, "OSHICryptoV2.kt")
        val text = src.readText()
        for (info in listOf("OSHI_X3DH", "OSHI_DR_ROOT", "OSHI_DR_MSG")) {
            assertTrue("KDF info string '$info' is missing — the protocol has changed",
                text.contains("\"$info\""))
        }
    }
}
