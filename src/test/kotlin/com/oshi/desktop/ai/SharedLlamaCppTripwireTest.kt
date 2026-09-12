package com.oshi.desktop.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The SEVENTH shared file — PARITY.md row 2.5.
 *
 * `service/LlamaCpp.kt` is compiled straight out of the Android tree, exactly as the six
 * crypto files are (`build.gradle.kts`, `sharedServiceSources`). It qualifies because it
 * has no imports at all. This is the alarm for the day someone adds one.
 *
 * It also guards something the crypto files do not have: **a JNI declaration is half of an
 * ABI**. The symbols the C++ shim exports are derived mechanically from this file's
 * package, class name and method names, so a rename on either side is a link failure at
 * the first call and nothing earlier. The last test below derives the expected symbols
 * from the Kotlin and looks for them in the shim, so the drift is caught at build time
 * instead of at the first question a user asks.
 */
class SharedLlamaCppTripwireTest {

    private val androidRoot = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android")
    private val serviceDir = File(androidRoot, "app/src/main/java/com/oshi/messenger/service")
    private val llamaKt = File(serviceDir, "LlamaCpp.kt")

    /** Absence is a FAILURE, never a skip — see SharedSourceTripwireTest for the reasoning. */
    @Before
    fun theSharedSourceMustBeReadable() {
        assertTrue(
            "LlamaCpp.kt is not at ${llamaKt.absolutePath}. Not skippable: build.gradle.kts " +
                "compiles that file, so a build that ran this test found it — a failure here " +
                "means 'oshi.android.root' points somewhere other than the srcDir, and this " +
                "tripwire is guarding nothing.",
            llamaKt.isFile,
        )
    }

    @Test
    fun `the shared JNI declaration has no platform dependency`() {
        val text = llamaKt.readText()
        val banned = listOf("import android.", "import androidx.", "import dagger.", "import javax.inject", "BuildConfig")
        val offences = banned.filter { text.contains(it) }
        assertEquals(
            "LlamaCpp.kt gained a platform dependency, so the desktop can no longer compile it. " +
                "Do NOT fork it — a forked JNI declaration is two ABIs that look like one.\n$offences",
            emptyList<String>(), offences,
        )
    }

    @Test
    fun `the class this project compiles really is the Android one`() {
        // Compiled, not read: this asserts the OUTPUT of the shared-source arrangement.
        val c = com.oshi.messenger.service.LlamaCpp::class.java
        assertEquals("com.oshi.messenger.service.LlamaCpp", c.name)
        val natives = c.declaredMethods
            .filter { java.lang.reflect.Modifier.isNative(it.modifiers) }
            .map { it.name }
            .sorted()
        assertEquals(listOf("generate", "loadModel", "unloadModel"), natives)
    }

    @Test
    fun `the library name in the shared source is the one this client looks for`() {
        val declared = Regex("""System\.loadLibrary\("([^"]+)"\)""").find(llamaKt.readText())?.groupValues?.get(1)
        assertEquals(
            "the Android app renamed its native library. Every diagnostic this client prints " +
                "names a file that will now never exist.",
            LlamaNative.LIBRARY, declared,
        )
    }

    /**
     * THE ABI CHECK. Derive the exported symbol names from the Kotlin, then look for them
     * in the shim this repository ships.
     *
     * `platform/llama-jni/llama_jni.cpp` has never been compiled by anybody — it is built
     * only by the `native-llama` CI job — so this is a TEXT check, not a link check. It
     * catches a rename, which is the drift that actually happens; it cannot catch a wrong
     * argument type, which only the linker and a real call can.
     */
    @Test
    fun `the JNI shim in this repository exports the symbols the shared declaration names`() {
        val shim = File("platform/llama-jni/llama_jni.cpp")
        assertTrue("the shim is missing at ${shim.absolutePath}", shim.isFile)
        val cpp = shim.readText()

        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(llamaKt.readText())!!.groupValues[1]
        val cls = "LlamaCpp"
        val methods = Regex("""external fun (\w+)\(""").findAll(llamaKt.readText()).map { it.groupValues[1] }.toList()
        assertEquals("the shared declaration no longer has three native methods", 3, methods.size)

        for (m in methods) {
            val symbol = "Java_" + pkg.replace(".", "_") + "_" + cls + "_" + m
            assertTrue(
                "the shim does not export $symbol. The Kotlin and the C++ have drifted: this " +
                    "links fine and fails with an UnsatisfiedLinkError at the first call.",
                cpp.contains(symbol),
            )
        }
    }
}
