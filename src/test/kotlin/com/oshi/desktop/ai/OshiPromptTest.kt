package com.oshi.desktop.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The prompt, and the tripwire under the re-derivation — PARITY.md row 2.5.
 *
 * [OshiPrompt] is the one place in this row that is a COPY rather than shared source, and
 * a copy is a second implementation to keep in sync. `LocalLLMManager.kt` cannot be
 * compiled here (Hilt, `Context`, OkHttp, `ActivityManager`), so instead of pretending the
 * copy will stay honest, these tests read the Android source off disk and compare.
 *
 * Absence of the Android tree FAILS here rather than skipping, for the reason
 * `SharedSourceTripwireTest` gives: the desktop client cannot compile without that tree,
 * so any machine running this test has it, and a skip would only ever mean the root
 * property points somewhere else — silently deleting the guard.
 */
class OshiPromptTest {

    private val androidRoot = File(System.getProperty("oshi.android.root") ?: "../OSHI-Android")
    private val localLlm = File(androidRoot, "app/src/main/java/com/oshi/messenger/service/LocalLLMManager.kt")

    @Before
    fun theAndroidSourceMustBeReadable() {
        assertTrue(
            "LocalLLMManager.kt is not at ${localLlm.absolutePath}. This is not skippable: " +
                "OshiPrompt is a re-derivation of that file and this test is the only thing " +
                "keeping the two in step. Pass -PoshiAndroidRoot=/path/to/OSHI-Android.",
            localLlm.isFile,
        )
    }

    /** Every `"..."` string literal in [block], concatenated and un-escaped. */
    private fun literals(block: String): String =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(block)
            .joinToString("") { it.groupValues[1] }
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

    // ===================================================== the copy matches the original

    @Test
    fun `the system turn is character-for-character what the Android client sends`() {
        val src = localLlm.readText()
        val block = src
            .substringAfter("append(\"<|im_start|>system\\n\")")
            .substringBefore("append(\"\\n\\n/no_think")
        assertTrue("could not find the system turn in LocalLLMManager.kt — the prompt was restructured", block.isNotEmpty())

        assertEquals(
            "OshiPrompt.SYSTEM_TURN has drifted from LocalLLMManager.kt. Do not 'fix' the copy " +
                "by editing this expectation: the phone's system prompt is what makes /no_think " +
                "and the whole strip pipeline behave, and a desktop that quietly prompts " +
                "differently answers differently for reasons nobody will be able to attribute.",
            OshiPrompt.SYSTEM_TURN, literals(block),
        )
    }

    @Test
    fun `the Android client still uses ChatML, no_think and an assistant tag`() {
        val src = localLlm.readText()
        // If any of these three goes, the desktop template is talking to a model that has
        // been re-templated on the phone, and the test above would not necessarily notice.
        for (marker in listOf("<|im_start|>system", "/no_think<|im_end|>", "<|im_start|>assistant")) {
            assertTrue("LocalLLMManager.kt no longer emits '$marker'", src.contains(marker))
        }
    }

    // ===================================================== the template itself

    @Test
    fun `a single question produces exactly the phone's prompt`() {
        val expected = "<|im_start|>system\n" + OshiPrompt.SYSTEM_TURN + "\n\n/no_think<|im_end|>\n" +
            "<|im_start|>user\nwhat is the capital of France<|im_end|>\n" +
            "<|im_start|>assistant\n"
        assertEquals(expected, OshiPrompt.chatML("what is the capital of France"))
    }

    @Test
    fun `the prompt ends on the assistant tag with nothing after it`() {
        // A newline or a space after `<|im_start|>assistant\n` is a token the model has to
        // account for, and it is the classic reason a small model opens with junk.
        assertTrue(OshiPrompt.chatML("hi").endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `history is rendered turn by turn and capped at ten`() {
        val turns = (1..14).map {
            OshiPrompt.Turn(if (it % 2 == 1) OshiPrompt.ROLE_USER else OshiPrompt.ROLE_ASSISTANT, "t$it")
        }
        val p = OshiPrompt.chatML(turns)
        assertFalse("turn 4 should have fallen off the ten-turn window", p.contains("\nt4<|im_end|>"))
        assertTrue("turn 5 is inside the window", p.contains("\nt5<|im_end|>"))
        assertTrue("the newest turn must be present", p.contains("\nt14<|im_end|>"))
        assertEquals("one system turn, ten conversation turns", 10, Regex("<\\|im_end\\|>").findAll(p).count() - 1)
    }

    @Test
    fun `a corrupted assistant turn is dropped from history instead of priming the next reply`() {
        val runaway = "x".repeat(200) + "y".repeat(200) + "x".repeat(200)  // a repeated tail
        assertTrue("fixture is not actually corrupted — the test would be vacuous", OshiPrompt.looksCorrupted(runaway))
        val p = OshiPrompt.chatML(
            listOf(
                OshiPrompt.Turn(OshiPrompt.ROLE_USER, "first"),
                OshiPrompt.Turn(OshiPrompt.ROLE_ASSISTANT, runaway),
                OshiPrompt.Turn(OshiPrompt.ROLE_USER, "second"),
            ),
        )
        assertFalse("a looping reply was fed back as context", p.contains(runaway))
        assertTrue(p.contains("\nfirst<|im_end|>"))
        assertTrue(p.contains("\nsecond<|im_end|>"))
    }

    @Test
    fun `a corrupted USER turn is kept — only the model's own output is filtered`() {
        // Dropping a user turn would silently delete something a person typed. The Android
        // filter is on `role == "assistant"` alone and that asymmetry is deliberate.
        val loop = "z".repeat(400)
        assertTrue(OshiPrompt.looksCorrupted(loop))
        assertTrue(OshiPrompt.chatML(listOf(OshiPrompt.Turn(OshiPrompt.ROLE_USER, loop))).contains(loop))
    }

    // ===================================================== the cleanup pipeline

    @Test
    fun `ChatML tokens never reach the user`() {
        val out = OshiPrompt.clean("<|im_start|>assistant\nParis<|im_end|>", "capital of France")
        assertEquals("Paris", out)
    }

    @Test
    fun `a closed think block is removed and the answer survives`() {
        assertEquals("Paris", OshiPrompt.clean("<think>the user wants a city</think>Paris", "capital?"))
    }

    @Test
    fun `an UNCLOSED think block truncates — half a thought is not an answer`() {
        // The budget ran out mid-thought. Everything after the tag is reasoning, not a
        // reply, and showing it is how a user concludes the model is broken.
        assertEquals("", OshiPrompt.clean("<think>hmm, let me consider", "capital?"))
    }

    @Test
    fun `a hallucinated code block goes when no code was asked for, and stays when it was`() {
        val reply = "Here:\n```python\nprint(1)\n```"
        assertFalse(OshiPrompt.clean(reply, "what is the capital of France").contains("```"))
        assertTrue(
            "the user asked for python and the answer was stripped of its code",
            OshiPrompt.clean(reply, "write me some python").contains("print(1)"),
        )
    }

    @Test
    fun `a greeting-plus-question preamble is dropped only when the model played the user`() {
        val fake = "Bonjour, je cherche la capitale ?\n\nParis."
        assertEquals("Paris.", OshiPrompt.clean(fake, "capitale ?"))
        // A greeting that is NOT a fake user turn is the model's own wording and is kept.
        val real = "Bonjour.\n\nParis."
        assertEquals(real, OshiPrompt.clean(real, "capitale ?"))
    }

    @Test
    fun `leading filler lines are dropped`() {
        assertEquals("Paris", OshiPrompt.clean("||\n---\n\nParis", "capital?"))
    }

    @Test
    fun `a repeated tail is what marks a runaway, and a long non-repeating answer is not one`() {
        val prose = (1..40).joinToString(" ") { "sentence number $it about something different" }
        assertFalse("a long, varied answer was called corrupted", OshiPrompt.looksCorrupted(prose))
        assertTrue(OshiPrompt.looksCorrupted(prose.take(400) + prose.take(200)))
    }

    @Test
    fun `code detection is by the user's words, in both languages the phone handles`() {
        assertTrue(OshiPrompt.userAskedForCode("écris une fonction"))
        assertTrue(OshiPrompt.userAskedForCode("Write a Python SCRIPT"))
        assertFalse(OshiPrompt.userAskedForCode("quelle est la capitale de la France"))
    }
}
