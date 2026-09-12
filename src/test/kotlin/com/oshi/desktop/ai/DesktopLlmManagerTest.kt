package com.oshi.desktop.ai

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manager's states — PARITY.md row 2.5.
 *
 * **What these tests are worth, stated up front.** They drive a FAKE [LlamaEngine]. There
 * is exactly one real implementation and it is `external fun`s into a shared library that
 * does not exist on this machine, so no test in this file has ever executed a line of
 * llama.cpp and none of them ever will. What they can prove is the part that is ours: that
 * every failure produces a NAMED outcome instead of an empty answer, that the engine is
 * touched once rather than per question, that a model file is validated before llama.cpp
 * has to fail obscurely, and that a timeout does not let a second thread into a
 * non-reentrant native context. A fake cannot find a bug inside the only real
 * implementation; the `native-llama` CI job is where that gets exercised.
 *
 * [LlamaNativeTest] is the other half, and it uses NO fake: on a machine with no library
 * the real path is the absent path, and that one can be tested for real.
 */
class DesktopLlmManagerTest {

    private val dirs = ArrayList<File>()

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private val managers = ArrayList<DesktopLlmManager>()

    private fun tmpDir(): File =
        Files.createTempDirectory("oshi-ai").toFile().also { dirs += it }

    /** A file that passes every check [DesktopLlmManager.validate] makes. Not a model. */
    private fun fakeGguf(dir: File = tmpDir(), name: String = "m.gguf"): File =
        File(dir, name).apply { writeBytes(DesktopLlmManager.GGUF_MAGIC + ByteArray(64)) }

    private class FakeEngine(
        val onGenerate: (String, Int) -> String = { _, _ -> "an answer" },
        val loadResult: () -> Boolean = { true },
    ) : LlamaEngine {
        val loads = AtomicInteger()
        val unloads = AtomicInteger()
        @Volatile var lastPrompt: String? = null
        override fun loadModel(path: String): Boolean { loads.incrementAndGet(); return loadResult() }
        override fun generate(prompt: String, maxTokens: Int): String {
            lastPrompt = prompt
            return onGenerate(prompt, maxTokens)
        }
        override fun unloadModel() { unloads.incrementAndGet() }
    }

    private fun manager(
        engine: LlamaEngine? = FakeEngine(),
        dir: File = tmpDir(),
        // 60 s, not the manager's own default and not a tight number: this machine runs
        // ~20 sessions in parallel and a wall-clock budget in seconds LIES under load. No
        // test here is about how fast a fake returns; the one test that is about the
        // timeout sets its own.
        timeoutMs: Long = 60_000,
        absent: String = "no native library (test)",
    ): DesktopLlmManager = DesktopLlmManager(
        stateDir = dir,
        loader = { if (engine == null) LlamaNative.Load.Absent(absent, "test") else LlamaNative.Load.Ready(engine) },
        timeoutMs = timeoutMs,
    ).also { managers += it }

    // ================================================================ absent library

    @Test
    fun `with no native library a question is refused with an explanation, never with silence`() {
        val m = manager(engine = null)
        m.configureModel(fakeGguf().absolutePath)
        val a = m.generate("hello")
        assertTrue("a missing engine must not look like an answer: $a", a is DesktopLlmManager.Answer.NoNativeLibrary)
        val text = (a as DesktopLlmManager.Answer.NoNativeLibrary).explain
        assertTrue("the explanation is empty — that is the silent failure this row exists to avoid", text.isNotBlank())
        // And what a user is shown must SAY so, in words, on its first line.
        assertTrue(AiConsole.render(a).first().contains("NO ANSWER"))
    }

    @Test
    fun `the library is probed once and the same diagnosis is repeated, never re-thrown`() {
        val probes = AtomicInteger()
        val m = DesktopLlmManager(
            stateDir = tmpDir(),
            loader = { probes.incrementAndGet(); LlamaNative.Load.Absent("gone", "test") },
        ).also { managers += it }
        m.configureModel(fakeGguf().absolutePath)
        repeat(4) { m.generate("q$it") }
        // Re-probing is not harmless: touching the real LlamaCpp class after its
        // initialiser failed throws a DIFFERENT error, so a manager that retried would
        // report a different problem each time for one underlying cause.
        assertEquals("the loader was consulted more than once", 1, probes.get())
    }

    @Test
    fun `status does not probe the native library unless asked`() {
        val probes = AtomicInteger()
        val m = DesktopLlmManager(
            stateDir = tmpDir(),
            loader = { probes.incrementAndGet(); LlamaNative.Load.Absent("gone", "test") },
        ).also { managers += it }
        m.status()
        assertEquals(
            "status probed the engine. Touching the real class is IRREVERSIBLE — a failed " +
                "initialiser poisons it for the life of the JVM — so a user who typed /ai status " +
                "before fixing their library path could never load it in that session.",
            0, probes.get(),
        )
        m.status(probeNative = true)
        assertEquals(1, probes.get())
    }

    // ================================================================ model file

    @Test
    fun `no model configured is its own answer, not a generic failure`() {
        assertEquals(DesktopLlmManager.Answer.NoModel, manager().generate("hello"))
    }

    @Test
    fun `a file that is not a GGUF is rejected by name, with the bytes it actually starts with`() {
        val f = File(tmpDir(), "truncated.gguf").apply { writeText("<!DOCTYPE html><html>404") }
        val o = manager().configureModel(f.absolutePath)
        assertTrue("an HTML error page saved as .gguf was accepted: $o", o is DesktopLlmManager.ModelOutcome.NotGguf)
        assertEquals("3c 21 44 4f", (o as DesktopLlmManager.ModelOutcome.NotGguf).firstBytes)
    }

    @Test
    fun `every unusable file gets its own outcome`() {
        val m = manager()
        val dir = tmpDir()
        assertTrue(m.configureModel(File(dir, "nope.gguf").absolutePath) is DesktopLlmManager.ModelOutcome.NotFound)
        assertTrue(m.configureModel(dir.absolutePath) is DesktopLlmManager.ModelOutcome.NotAFile)
        val empty = File(dir, "empty.gguf").apply { createNewFile() }
        assertTrue(m.configureModel(empty.absolutePath) is DesktopLlmManager.ModelOutcome.Empty)
    }

    @Test
    fun `a rejected file does not become the configured model`() {
        val m = manager()
        val good = fakeGguf()
        m.configureModel(good.absolutePath)
        m.configureModel(File(tmpDir(), "nope.gguf").absolutePath)
        assertEquals("a failed /ai model wiped the working one", good.absolutePath, m.status().modelPath)
    }

    @Test
    fun `the configured model survives a restart, and a deleted one does not come back`() {
        val dir = tmpDir()
        val model = fakeGguf(dir)
        manager(dir = dir).configureModel(model.absolutePath)

        assertEquals(model.absolutePath, manager(dir = dir).status().modelPath)

        model.delete()
        assertNull(
            "a pointer to a model that is gone was trusted — the failure would then surface " +
                "as an obscure load error at the first question instead of 'no model'",
            manager(dir = dir).status().modelPath,
        )
    }

    @Test
    fun `a model deleted while the client runs reports ModelGone, not a load failure`() {
        val model = fakeGguf()
        val m = manager()
        m.configureModel(model.absolutePath)
        model.delete()
        val a = m.generate("hello")
        assertTrue(a is DesktopLlmManager.Answer.ModelGone)
        assertTrue((a as DesktopLlmManager.Answer.ModelGone).outcome is DesktopLlmManager.ModelOutcome.NotFound)
    }

    // ================================================================ generation

    @Test
    fun `the weights are loaded once, not once per question`() {
        val eng = FakeEngine()
        val m = manager(eng)
        m.configureModel(fakeGguf().absolutePath)
        repeat(3) { m.generate("q$it") }
        assertEquals("llama.cpp re-loaded the model per question", 1, eng.loads.get())
    }

    @Test
    fun `switching models unloads the previous one before pointing at the new one`() {
        val eng = FakeEngine()
        val m = manager(eng)
        val dir = tmpDir()
        m.configureModel(fakeGguf(dir, "a.gguf").absolutePath)
        m.generate("hello")
        assertTrue(m.status().modelLoadedInNative)

        m.configureModel(fakeGguf(dir, "b.gguf").absolutePath)
        assertEquals("the old weights stayed mapped", 1, eng.unloads.get())
        assertFalse(m.status().modelLoadedInNative)

        m.generate("hello again")
        assertEquals("the second model was never loaded — answers came from the OLD weights", 2, eng.loads.get())
    }

    @Test
    fun `a load that returns false is reported as a load failure`() {
        val m = manager(FakeEngine(loadResult = { false }))
        m.configureModel(fakeGguf().absolutePath)
        assertTrue(m.generate("hello") is DesktopLlmManager.Answer.ModelLoadFailed)
    }

    @Test
    fun `an UnsatisfiedLinkError from inside the native call is an outcome, not a crash`() {
        // The realistic cause: a shim that loaded but was built against a different
        // signature. It is an Error, it arrives wrapped in an ExecutionException, and if it
        // escaped it would take the REPL down mid-session.
        val m = manager(FakeEngine(onGenerate = { _, _ -> throw UnsatisfiedLinkError("no generate(Ljava/lang/String;I)") }))
        m.configureModel(fakeGguf().absolutePath)
        val a = m.generate("hello")
        assertTrue("$a", a is DesktopLlmManager.Answer.NativeCallFailed)
        assertTrue((a as DesktopLlmManager.Answer.NativeCallFailed).detail.contains("UnsatisfiedLinkError"))
    }

    @Test
    fun `an empty generation is EmptyOutput, and it distinguishes nothing-at-all from all-stripped`() {
        val nothing = manager(FakeEngine(onGenerate = { _, _ -> "" }))
        nothing.configureModel(fakeGguf().absolutePath)
        assertEquals(DesktopLlmManager.Answer.EmptyOutput(0), nothing.generate("hello"))

        val thoughtOnly = manager(FakeEngine(onGenerate = { _, _ -> "<think>still thinking" }))
        thoughtOnly.configureModel(fakeGguf().absolutePath)
        val a = thoughtOnly.generate("hello")
        assertEquals(
            "an answer made entirely of chain-of-thought must not read as 'the model said nothing' — " +
                "the fix is a bigger token budget, and only rawChars can say so",
            DesktopLlmManager.Answer.EmptyOutput(21), a,
        )
    }

    @Test
    fun `a real answer carries the cleaned text and the raw length`() {
        val m = manager(FakeEngine(onGenerate = { _, _ -> "<|im_start|>assistant\nParis<|im_end|>" }))
        m.configureModel(fakeGguf().absolutePath)
        val a = m.generate("capital of France") as DesktopLlmManager.Answer.Generated
        assertEquals("Paris", a.text)
        assertEquals(37, a.rawChars)
    }

    @Test
    fun `a blank prompt is refused without touching the engine`() {
        val eng = FakeEngine()
        val m = manager(eng)
        m.configureModel(fakeGguf().absolutePath)
        assertEquals(DesktopLlmManager.Answer.EmptyOutput(0), m.generate("   "))
        assertEquals(0, eng.loads.get())
    }

    // ================================================================ context

    @Test
    fun `the previous turns reach the model, and forget removes them`() {
        val eng = FakeEngine()
        val m = manager(eng)
        m.configureModel(fakeGguf().absolutePath)
        m.generate("first question")
        m.generate("second question")
        assertTrue("the first turn never made it into the prompt", eng.lastPrompt!!.contains("first question"))
        assertTrue(eng.lastPrompt!!.contains("an answer"))

        assertEquals("two questions and two replies", 4, m.forget())
        m.generate("third question")
        assertFalse("forget() did not clear the context", eng.lastPrompt!!.contains("first question"))
    }

    @Test
    fun `a refused generation does not enter the context`() {
        val eng = FakeEngine(onGenerate = { _, _ -> "" })
        val m = manager(eng)
        m.configureModel(fakeGguf().absolutePath)
        m.generate("a question with no answer")
        assertEquals("an unanswered turn was remembered as if it had been a conversation", 0, m.status().historyTurns)
    }

    @Test
    fun `changing the model clears the context`() {
        val m = manager()
        val dir = tmpDir()
        m.configureModel(fakeGguf(dir, "a.gguf").absolutePath)
        m.generate("hello")
        assertEquals(2, m.status().historyTurns)
        m.configureModel(fakeGguf(dir, "b.gguf").absolutePath)
        assertEquals("context from one model was carried into another", 0, m.status().historyTurns)
    }

    // ================================================================ timeout and busy

    @Test
    fun `a generation that overruns is a timeout, and the next question is REFUSED not queued`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val m = manager(
            FakeEngine(onGenerate = { _, _ -> entered.countDown(); release.await(10, TimeUnit.SECONDS); "late" }),
            timeoutMs = 150,
        )
        m.configureModel(fakeGguf().absolutePath)

        val a = m.generate("slow one")
        assertTrue("$a", a is DesktopLlmManager.Answer.TimedOut)
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // THE POINT OF THIS TEST. llama.cpp is still inside its non-reentrant context. A
        // second call must not enter it, and it must not silently wait either — the user
        // has to be told the client cannot answer right now.
        assertEquals(DesktopLlmManager.Answer.Busy, m.generate("another one"))

        release.countDown()
        val freed = (1..400).any { Thread.sleep(50); !m.status().generating }
        assertTrue("the busy flag was never released after the native call returned", freed)
        assertTrue(m.generate("now then") is DesktopLlmManager.Answer.Generated)
    }

    @Test
    fun `a concurrent question is refused while one is in flight`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val m = manager(FakeEngine(onGenerate = { _, _ -> entered.countDown(); release.await(10, TimeUnit.SECONDS); "ok" }))
        m.configureModel(fakeGguf().absolutePath)

        val t = Thread { m.generate("first") }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(
            "two threads were allowed towards one ggml context — the real symptom is a SIGSEGV, " +
                "not an exception, so this guard is the only thing standing between a user and a crash",
            DesktopLlmManager.Answer.Busy, m.generate("second"),
        )
        release.countDown()
        t.join(5_000)
    }

    // ================================================================ status rendering

    @Test
    fun `status says plainly whether a question asked now would reach the engine`() {
        val m = manager(engine = null)
        assertFalse(m.status().canGenerate)
        val lines = AiConsole.render(m.status())
        assertTrue(lines.any { it.contains("would produce NO ANSWER") })
        assertTrue("status must never imply inference has been verified", lines.any { it.contains("no inference has ever been verified") })
    }

    @Test
    fun `status reports the library file the shared LlamaCpp actually asks for`() {
        val s = manager().status()
        assertEquals(System.mapLibraryName(LlamaNative.LIBRARY), s.libraryFile)
        assertNotNull(s.librarySearchPath)
    }
}
