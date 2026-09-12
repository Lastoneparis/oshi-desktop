package com.oshi.desktop.app

import com.oshi.desktop.ai.DesktopAi
import com.oshi.desktop.ai.DesktopLlmManager
import com.oshi.desktop.ai.LlamaEngine
import com.oshi.desktop.ai.LlamaNative
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/ai` is REACHABLE — PARITY.md row 2.5.
 *
 * The recurring defect in this repository is a package that is unit-tested to death and
 * cannot be reached from the client. `ClientWiringTest` says it plainly: if a test here
 * would still pass with the wiring removed, it does not belong here. So none of these
 * assert a prompt template or a state transition — [com.oshi.desktop.ai.OshiPromptTest]
 * and [com.oshi.desktop.ai.DesktopLlmManagerTest] do that. These assert that typing the
 * command reaches the manager and that what comes back is printed.
 */
class AiWiringTest {

    private val dirs = ArrayList<File>()
    private val fx = WiringFixture()

    @After
    fun tearDown() {
        DesktopAi.replaceForTest(null)
        fx.close()
        dirs.forEach { it.deleteRecursively() }
    }

    private class FakeEngine(val reply: String = "Paris") : LlamaEngine {
        var loaded: String? = null
        var prompts = ArrayList<String>()
        override fun loadModel(path: String): Boolean { loaded = path; return true }
        override fun generate(prompt: String, maxTokens: Int): String { prompts += prompt; return reply }
        override fun unloadModel() {}
    }

    private fun install(engine: LlamaEngine?): DesktopLlmManager {
        val dir = Files.createTempDirectory("oshi-ai-wiring").toFile().also { dirs += it }
        val m = DesktopLlmManager(
            stateDir = dir,
            loader = {
                if (engine == null) LlamaNative.Load.Absent(LlamaNative.diagnose("test: no library"), "test")
                else LlamaNative.Load.Ready(engine)
            },
        )
        DesktopAi.replaceForTest(m)
        return m
    }

    private fun gguf(): File {
        val dir = Files.createTempDirectory("oshi-ai-model").toFile().also { dirs += it }
        return File(dir, "m.gguf").apply { writeBytes(DesktopLlmManager.GGUF_MAGIC + ByteArray(32)) }
    }

    @Test
    fun `the help text offers the command`() {
        for (line in listOf("/ai <prompt>", "/ai model <path>", "/ai status")) {
            assertTrue("`$line` is not in /help, so nobody will find it", ClientCommands.HELP.contains(line))
        }
    }

    @Test
    fun `slash ai model reaches the manager and is remembered`() {
        install(FakeEngine())
        val model = gguf()
        val me = fx.client("me", publish = false)
        val out = fx.repl(me, "/ai model ${model.absolutePath}")
        assertTrue("the command printed nothing", out.isNotEmpty())
        assertTrue("the model was not accepted: $out", out.any { it.contains("model set") })
        assertTrue(fx.repl(me, "/ai status").any { it.contains(model.absolutePath) })
    }

    @Test
    fun `slash ai with a prompt reaches llama and prints the answer`() {
        val eng = FakeEngine("Paris")
        install(eng)
        val me = fx.client("me", publish = false)
        fx.repl(me, "/ai model ${gguf().absolutePath}")

        val out = fx.repl(me, "/ai what is the capital of France")
        assertTrue("the prompt never reached the engine", eng.prompts.size == 1)
        assertTrue(
            "the question was not put through the ChatML template on the way",
            eng.prompts[0].contains("<|im_start|>user\nwhat is the capital of France<|im_end|>"),
        )
        assertTrue("the answer was not printed: $out", out.any { it.contains("Paris") })
    }

    @Test
    fun `slash ai status is reachable without configuring anything and never claims more than it has`() {
        install(null)
        val out = fx.repl(fx.client("me", publish = false), "/ai status")
        assertTrue(out.any { it.contains("engine") })
        assertTrue(out.any { it.contains("no inference has ever been verified") })
    }

    @Test
    fun `with no native library the REPL says NO ANSWER, and prints no blank reply`() {
        install(null)
        val me = fx.client("me", publish = false)
        fx.repl(me, "/ai model ${gguf().absolutePath}")
        val out = fx.repl(me, "/ai hello")

        assertTrue("the client answered with silence: $out", out.isNotEmpty())
        assertTrue("nothing said the question went unanswered: $out", out.any { it.contains("NO ANSWER") })
        assertFalse(
            "a line here reads as the model's reply. Android returns 'the offline AI model is " +
                "installed and ready' as the ANSWER in this exact state — that sentence is why " +
                "this test exists.",
            out.any { it.contains("ready", ignoreCase = true) && !it.contains("NO ANSWER") },
        )
    }

    @Test
    fun `slash ai forget is reachable`() {
        install(FakeEngine())
        val me = fx.client("me", publish = false)
        fx.repl(me, "/ai model ${gguf().absolutePath}")
        fx.repl(me, "/ai hello")
        assertTrue(fx.repl(me, "/ai forget").any { it.contains("2 remembered turn") })
    }

    @Test
    fun `a bare slash ai explains itself instead of asking the model an empty question`() {
        install(FakeEngine())
        val out = fx.repl(fx.client("me", publish = false), "/ai")
        assertTrue(out.any { it.contains("usage:") })
    }
}
