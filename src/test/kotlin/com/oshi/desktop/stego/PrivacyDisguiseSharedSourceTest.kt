package com.oshi.desktop.stego

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __PRIVACY_DISGUISE_2026_11_18__ The STEGO-v1 codec and the cover wire format are the
 * ANDROID files, compiled here (build.gradle.kts include list) — not copies. This states the
 * rule so the failure names its cause: they must stay pure JVM, and the build must keep
 * including them, or the desktop silently grows its own and the four clients can drift.
 */
class PrivacyDisguiseSharedSourceTest {

    private val androidRoot = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android")
    private val shared = listOf(
        "app/src/main/java/com/oshi/messenger/service/stego/StegoV1.kt",
        "app/src/main/java/com/oshi/messenger/service/stego/StegoPng.kt",
        "app/src/main/java/com/oshi/messenger/service/CoverCodec.kt",
        "app/src/main/java/com/oshi/messenger/network/v2/CoverPresentation.kt",
    )

    @Test fun `the shared privacy-disguise sources exist and have no platform dependency`() {
        val banned = listOf("import android.", "import androidx.", "import dagger.", "import javax.inject", "BuildConfig")
        val offences = shared.flatMap { rel ->
            val f = File(androidRoot, rel)
            assertTrue("missing shared source $rel under ${androidRoot.absolutePath}", f.isFile)
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "$rel contains '$it'" }
        }
        assertEquals(emptyList<String>(), offences)
    }

    @Test fun `the desktop compiles them rather than a copy`() {
        val gradle = File("build.gradle.kts").readText()
        for (n in listOf("StegoV1", "StegoPng", "CoverCodec", "CoverPresentation")) {
            assertTrue("build.gradle.kts must include **/$n.kt", gradle.contains("\"**/$n.kt\""))
        }
        val own = File("src/main/kotlin").walkTopDown().filter { it.name in setOf("StegoV1.kt", "StegoPng.kt", "CoverCodec.kt") }.toList()
        assertEquals("no desktop fork of the shared codec", emptyList<File>(), own)
    }

    @Test fun `the compiled classes come from the shared files`() {
        // Same FQCN as Android: a desktop re-implementation would have to live elsewhere.
        assertEquals("com.oshi.messenger.service.stego.StegoV1", com.oshi.messenger.service.stego.StegoV1::class.java.name)
        assertEquals("com.oshi.messenger.service.CoverCodec", com.oshi.messenger.service.CoverCodec::class.java.name)
    }
}
