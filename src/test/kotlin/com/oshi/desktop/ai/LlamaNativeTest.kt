package com.oshi.desktop.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The REAL native path — PARITY.md row 2.5. No fake anywhere in this file.
 *
 * On a machine with no `llama_jni` this exercises the only code path that actually
 * matters here: what happens when the shared `LlamaCpp` class cannot initialise. On a
 * machine that HAS one — the `native-llama` CI job, and nothing else so far — the same
 * test asserts the opposite, because a test that only knows how to pass when the thing is
 * missing is a test that would go green after the feature was deleted.
 *
 * `OSHI_EXPECT_LLAMA_NATIVE` is what picks between the two. It is the same device
 * `SecretStoreTest` uses for the OS key stores, and for the same reason: a runner that was
 * SUPPOSED to have built the library and did not must go red, not skip. Nothing in this
 * file ever skips.
 */
class LlamaNativeTest {

    private val expectNative: Boolean =
        System.getenv("OSHI_EXPECT_LLAMA_NATIVE")?.lowercase() in setOf("1", "true", "yes")

    @Test
    fun `the library name is the one the shared source asks for`() {
        // Not a tautology: it is the name in `System.loadLibrary(...)` inside a file this
        // project does not own, and if the Android app renames it, every diagnostic message
        // here starts naming a file nobody will ever have.
        assertEquals("llama_jni", LlamaNative.LIBRARY)
        val mapped = LlamaNative.mappedFileName()
        assertTrue(
            "mapped name '$mapped' is not what this OS loads",
            mapped == "libllama_jni.so" || mapped == "llama_jni.dll" || mapped == "libllama_jni.dylib",
        )
    }

    /**
     * THE NORMAL CASE ON EVERY MACHINE THIS PROJECT HAS EVER RUN ON.
     *
     * `System.loadLibrary` fails in a companion `init`, and this must be an ordinary,
     * describable state — not an exception escaping into the REPL loop.
     */
    @Test
    fun `loading either succeeds or explains itself, and never throws`() {
        val load = LlamaNative.load()
        if (expectNative) {
            assertTrue(
                "OSHI_EXPECT_LLAMA_NATIVE is set, so this runner was supposed to have built " +
                    "${LlamaNative.mappedFileName()} and put its directory on java.library.path " +
                    "(${LlamaNative.searchPath()}). It did not load. Refusing to pass: a green " +
                    "native job that measured nothing is the failure this flag exists to prevent.\n" +
                    (load as? LlamaNative.Load.Absent)?.explain.orEmpty(),
                load is LlamaNative.Load.Ready,
            )
        } else {
            assertTrue(
                "a native llama_jni was loaded on a machine that did not declare one. That is not " +
                    "a failure of the library, it is a failure of this test's premise — set " +
                    "OSHI_EXPECT_LLAMA_NATIVE=1 so the run asserts the loaded behaviour instead.",
                load is LlamaNative.Load.Absent,
            )
        }
    }

    @Test
    fun `a second attempt gives the SAME answer — the JVM never retries a failed initialiser`() {
        val first = LlamaNative.load()
        val second = LlamaNative.load()
        assertEquals(first::class, second::class)
        if (first is LlamaNative.Load.Absent && second is LlamaNative.Load.Absent) {
            // This is the case `LocalLLMManager` cannot survive: it catches
            // UnsatisfiedLinkError only, and the SECOND touch of a poisoned class throws
            // NoClassDefFoundError. It constructs exactly once so it never meets that; a
            // REPL where /ai status, /ai model and /ai all touch the class does.
            assertEquals(
                "the second attempt reported a different problem for the same cause",
                first.explain, second.explain,
            )
        }
    }

    @Test
    fun `the diagnosis names the file, the search path and what the JVM said`() {
        val d = LlamaNative.diagnose("UnsatisfiedLinkError: no llama_jni in java.library.path")
        assertTrue("the file being looked for is not named", d.contains(LlamaNative.mappedFileName()))
        assertTrue("java.library.path is not shown", d.contains("java.library.path"))
        assertTrue("the JVM's own words are not quoted", d.contains("UnsatisfiedLinkError"))
        assertTrue(
            "a diagnosis that does not say NOTHING WAS GENERATED lets a user read silence as an answer",
            d.contains("cannot run any"),
        )
    }

    @Test
    fun `the search path is the JVM's, parsed with the platform separator`() {
        val raw = System.getProperty("java.library.path") ?: ""
        val parsed = LlamaNative.searchPath()
        // Not an identity check: entries are trimmed and blanks dropped. What matters is
        // that a real entry survives, because the whole diagnosis rests on this list.
        val firstReal = raw.split(File.pathSeparatorChar).map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (firstReal != null) assertTrue("'$firstReal' was lost while parsing", parsed.contains(firstReal))
        assertFalse(parsed.any { it.isBlank() })
    }

    @Test
    fun `an absent library reports where it looked instead of a bare failure`() {
        val load = LlamaNative.load()
        if (load is LlamaNative.Load.Absent) {
            assertNotNull(load.cause)
            assertTrue("the cause is not the JVM's own message", load.cause.contains("Error"))
            // A user has to be able to act. Exactly one of the three remedies must appear.
            val actionable = load.explain.contains("-Djava.library.path=") ||
                load.explain.contains("native-llama") ||
                load.explain.contains("architecture")
            assertTrue("the diagnosis says what is wrong but not what to do about it", actionable)
        }
    }
}
