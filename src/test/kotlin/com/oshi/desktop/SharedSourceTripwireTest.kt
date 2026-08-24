package com.oshi.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    private val androidRoot = File(
        System.getProperty("oshi.android.root")
            ?: "/Users/HUGOMORICEAU/Documents/Genesis/OSHI-Android"
    )
    private val v2Dir = File(androidRoot, "app/src/main/java/com/oshi/messenger/network/v2")

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
        assumeTrue("Android tree not present on this machine", v2Dir.isDirectory)
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
        assumeTrue("Android tree not present on this machine", v2Dir.isDirectory)
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
        assumeTrue("Android tree not present on this machine", src.isFile)

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

        val desktopFields = com.oshi.messenger.network.v2.FetchedBundle::class.java
            .declaredFields.filterNot { it.isSynthetic }.map { it.name }

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
        assumeTrue("Android tree not present on this machine", src.isFile)
        val text = src.readText()
        for (info in listOf("OSHI_X3DH", "OSHI_DR_ROOT", "OSHI_DR_MSG")) {
            assertTrue("KDF info string '$info' is missing — the protocol has changed",
                text.contains("\"$info\""))
        }
    }
}
