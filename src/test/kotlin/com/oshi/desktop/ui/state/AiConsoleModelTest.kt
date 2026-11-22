package com.oshi.desktop.ui.state

import com.oshi.desktop.ai.DesktopLlmManager
import com.oshi.desktop.ai.LlamaEngine
import com.oshi.desktop.ai.LlamaNative
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI screen's own logic — PARITY.md row 2.5, the WINDOW half.
 *
 * `AiConsoleModel`'s header states its testability as a design property: "everything that
 * could be WRONG here — which answer maps to which explanation, whether a second question
 * can be asked while one is running — is decided in plain Kotlin where a headless runner
 * can hold it to account". Nothing was holding it to account: the only file in the test
 * tree that even named this class was `ScreenRenderer`, which draws it rather than
 * asserting anything about it. Row 2.5 was closed on the REPL path and an inference run,
 * and the model behind the window's composer had no test at all.
 *
 * Driven here with a real [DesktopLlmManager] over a fake engine — the same seam
 * `DesktopLlmManagerTest` uses — so the chain under test is the shipped one, minus only
 * llama.cpp itself.
 */
class AiConsoleModelTest {

    private val dirs = ArrayList<File>()
    private val managers = ArrayList<DesktopLlmManager>()

    @After fun tearDown() {
        managers.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tmpDir(): File = Files.createTempDirectory("oshi-ai-ui").toFile().also { dirs += it }

    private fun gguf(): File =
        File(tmpDir(), "m.gguf").apply { writeBytes(DesktopLlmManager.GGUF_MAGIC + ByteArray(64)) }

    private class FakeEngine(val onGenerate: (String, Int) -> String = { _, _ -> "blue" }) : LlamaEngine {
        val loads = AtomicInteger()
        override fun loadModel(path: String): Boolean { loads.incrementAndGet(); return true }
        override fun generate(prompt: String, maxTokens: Int): String = onGenerate(prompt, maxTokens)
        override fun unloadModel() = Unit
    }

    private fun manager(engine: LlamaEngine? = FakeEngine()): DesktopLlmManager =
        DesktopLlmManager(
            stateDir = tmpDir(),
            loader = { if (engine == null) LlamaNative.Load.Absent("no native (test)", "test") else LlamaNative.Load.Ready(engine) },
            timeoutMs = 60_000,
        ).also { managers += it }

    /** Same thread, so `ask` has finished by the time it returns — no sleeping in a test. */
    private val inline = Executor { it.run() }

    @Test
    fun `a question reaches the engine and its answer becomes a turn`() {
        val m = manager()
        m.configureModel(gguf().absolutePath)
        val model = AiConsoleModel(m, inline)
        model.ask("name one colour")
        val turns = model.state.turns
        assertEquals(1, turns.size)
        assertEquals("name one colour", turns[0].question)
        assertTrue("the engine's words must reach the screen: ${turns[0].answer}", turns[0].answer.contains("blue"))
        assertTrue(turns[0].ok)
        assertFalse("the screen must not be left busy", model.state.busy)
    }

    @Test
    fun `an empty question is refused without troubling the engine`() {
        val engine = FakeEngine { _, _ -> throw AssertionError("the engine must not be called") }
        val m = manager(engine)
        m.configureModel(gguf().absolutePath)
        val model = AiConsoleModel(m, inline)
        model.ask("   ")
        assertEquals(0, model.state.turns.size)
        assertTrue("the user is told why", model.state.notice?.text?.isNotBlank() == true)
    }

    @Test
    fun `a thrown engine becomes a named failure on screen, never an exception out of ask`() {
        val m = manager(FakeEngine { _, _ -> throw IllegalStateException("boom") })
        m.configureModel(gguf().absolutePath)
        val model = AiConsoleModel(m, inline)
        model.ask("anything")           // must not throw
        val turns = model.state.turns
        assertEquals(1, turns.size)
        assertFalse("a failure must not be dressed as an answer", turns[0].ok)
        // The ANSWER stays empty on purpose and the words go in `detail`: a failed turn
        // must not put a sentence where the model's output goes, or a screenshot of it
        // reads as something the model said.
        assertEquals("", turns[0].answer)
        assertTrue("the explanation belongs in detail: ${turns[0].detail}", turns[0].detail?.isNotBlank() == true)
        assertFalse(model.state.busy)
    }

    /**
     * The composer is offered only when a question could actually be answered — the
     * distinction the header calls "no native library" versus "the model file moved".
     */
    /**
     * The composer is offered on a model and a KNOWN-GOOD native — and the native is
     * deliberately not probed to find out.
     *
     * `DesktopLlmManager.status(probeNative = false)` is the default on purpose, and the
     * reason is written beside it: loading the `LlamaCpp` class is IRREVERSIBLE, because a
     * failed class initialiser poisons the class for the life of the JVM. A screen that
     * probed on open would mean a user who glanced at the AI tab before setting their
     * library path could never load it again in that session. So a cold screen offers the
     * composer, and the refusal arrives as a named turn with its own sentence.
     *
     * I expected the opposite and wrote the assertion the other way round first. It is
     * pinned in this direction now so nobody "fixes" the cold-start composer into an
     * eager probe without meeting that comment.
     */
    @Test
    fun `the composer needs a model, and the native is not probed to find out`() {
        val noModel = AiConsoleModel(manager(), inline)
        assertFalse("no model configured ⇒ no composer", noModel.state.canAsk)

        val cold = manager(engine = null)
        cold.configureModel(gguf().absolutePath)
        val screen = AiConsoleModel(cold, inline)
        assertTrue("a cold screen does NOT probe, so it still offers the composer", screen.state.canAsk)

        // Asking is what probes. Once the native is known missing, the composer goes.
        screen.ask("anything")
        assertFalse("after a refusal the problem is known: ${screen.state.nativeProblem}", screen.state.canAsk)
        assertTrue("and it is named", screen.state.nativeProblem?.isNotBlank() == true)
    }

    @Test
    fun `forget clears the screen's turns`() {
        val m = manager()
        m.configureModel(gguf().absolutePath)
        val model = AiConsoleModel(m, inline)
        model.ask("one")
        assertEquals(1, model.state.turns.size)
        model.forget()
        assertEquals(0, model.state.turns.size)
    }
}
