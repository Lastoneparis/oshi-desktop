package com.oshi.desktop.ai

/**
 * The ChatML prompt the phones send to llama.cpp, and the cleanup they run on what comes
 * back — PARITY.md row 2.5.
 *
 * **Why this is re-derived here and not shared.** `LocalLLMManager.kt` is the file that
 * owns these strings on Android and it cannot be compiled off this tree: it is
 * `@Singleton`/`@Inject`, takes an `@ApplicationContext Context`, and reaches
 * `ActivityManager` and OkHttp. What is portable about it is the four hundred characters
 * of prompt and eight small pure functions, so those are reproduced here CHARACTER FOR
 * CHARACTER and `OshiPromptTest` asserts the reproduction against the literal Android
 * source read off disk. If someone edits the Android prompt, that test goes red — which
 * is the closest thing to a tripwire a re-derivation can have.
 *
 * The prompt matters more than it looks. `/no_think` is what stops Qwen3 emitting a long
 * `<think>` block for a one-line factual question, and the system turn's three "do not"
 * clauses are why [stripRolePreamble] has anything left to do at all. Getting one of them
 * wrong does not fail loudly; it makes the model worse in a way nobody can attribute.
 *
 * NOT verified: any of this against a real model. No inference has been run on this
 * platform — there is no native library and no GGUF file. These are string transforms
 * tested as string transforms.
 */
object OshiPrompt {

    /** Qwen3 ChatML roles. `system` is emitted by [chatML] itself. */
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    /**
     * The system turn, verbatim from `LocalLLMManager.runGenerate`.
     *
     * Kept as ONE constant rather than four `append`s so the test can compare it to the
     * Android source as a single string, and so nobody "tidies" a trailing space away —
     * the three fragments are concatenated without separators on Android and each of the
     * first two ends in a space that is load-bearing.
     */
    const val SYSTEM_TURN: String =
        "You are OSHI, a helpful and privacy-focused AI assistant. " +
            "Give clear, accurate, concise answers. Respond in the same language the user writes in. " +
            "Answer the user directly. Do not greet (no \"bonjour\", \"hello\"). " +
            "Do not restate the question. Never speak as the user. " +
            "Only produce code when the user explicitly asks for code — " +
            "never answer a factual question with a code block."

    /** One turn of a conversation. */
    data class Turn(val role: String, val content: String)

    /**
     * The full prompt for a single question, with no history. Byte-identical to what
     * `LocalLLMManager` builds when `conversationHistory` is null or empty.
     */
    fun chatML(prompt: String): String = chatML(listOf(Turn(ROLE_USER, prompt)))

    /**
     * The full prompt for a conversation.
     *
     * **The list must already END with the user's turn.** That is not a style choice, it
     * is Android's actual behaviour and it is a trap worth naming: `runGenerate` takes
     * both a `prompt` and a `conversationHistory`, and when the history is non-empty it
     * appends the HISTORY and drops `prompt` on the floor. A caller that passes history
     * plus a new question, expecting both, silently asks the model to continue the last
     * turn instead. Rather than reproduce a two-argument function with one argument that
     * sometimes does nothing, this takes the turns and only the turns.
     *
     * The last ten turns are kept, as on Android, and an assistant turn that
     * [looksCorrupted] is dropped so a runaway reply does not prime the next one.
     */
    fun chatML(turns: List<Turn>): String = buildString {
        append("<|im_start|>system\n")
        append(SYSTEM_TURN)
        append("\n\n/no_think<|im_end|>\n")
        for (t in turns.takeLast(10)) {
            if (t.role == ROLE_ASSISTANT && looksCorrupted(t.content)) continue
            append("<|im_start|>${t.role}\n")
            append(t.content)
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    /** Remove ChatML special tokens so they never reach a user's screen. */
    fun stripChatMLTokens(text: String): String {
        // Order matters and it is Android's: the three role-qualified forms go first, so
        // `<|im_start|>assistant` does not decay into a stray "assistant" line when the
        // bare `<|im_start|>` is removed ahead of it.
        val tokens = listOf(
            "<|im_start|>assistant", "<|im_start|>user", "<|im_start|>system",
            "<|im_start|>", "<|im_end|>", "<|im_end|",
            "<|endoftext|>", "<|end|>",
        )
        var result = text
        for (token in tokens) result = result.replace(token, "")
        return result
    }

    /**
     * Drop Qwen3 chain-of-thought blocks.
     *
     * An UNCLOSED `<think>` truncates at the tag: the model was still thinking when the
     * token budget ran out, so everything after it is half a thought, not an answer.
     */
    fun stripThinkBlocks(text: String): String {
        var s = text
        while (true) {
            val open = s.indexOf("<think>")
            if (open < 0) break
            val close = s.indexOf("</think>", startIndex = open + 7)
            s = if (close >= 0) s.removeRange(open, close + 8) else s.substring(0, open)
        }
        return s
    }

    /** Drop leading filler lines small models emit (`||`, `---`, `***`, blank). */
    fun stripLeadingNoise(text: String): String {
        val lines = text.split("\n").toMutableList()
        val junkChars = " \t|-*_.=~`>".toSet()
        while (lines.isNotEmpty()) {
            val first = lines.first().trim()
            if (first.isEmpty() || first.all { it in junkChars }) lines.removeAt(0) else break
        }
        return lines.joinToString("\n")
    }

    /** Drop a hallucinated greeting-then-question preamble where the model played the user. */
    fun stripRolePreamble(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        val greetings = listOf(
            "bonjour", "bonsoir", "salut", "hello", "hi ", "hey ",
            "hola", "ciao", "hallo", "olá", "oi ",
        )
        if (greetings.none { lower.startsWith(it) }) return trimmed
        val breakIdx = trimmed.indexOf("\n\n")
        if (breakIdx < 0) return trimmed
        val preamble = trimmed.substring(0, breakIdx)
        val rest = trimmed.substring(breakIdx + 2).trim()
        val fakeUserTurn = preamble.contains("?") ||
            preamble.contains("pouvez-vous", ignoreCase = true) ||
            preamble.contains("pouvez vous", ignoreCase = true) ||
            preamble.contains("je cherche", ignoreCase = true) ||
            preamble.contains("can you", ignoreCase = true) ||
            preamble.contains("could you", ignoreCase = true)
        return if (fakeUserTurn && rest.isNotEmpty()) rest else trimmed
    }

    /** True when the tail repeats earlier in the text — the model is looping. */
    fun hasRepeatedTail(text: String, window: Int = 160): Boolean {
        if (text.length < window * 2) return false
        val tail = text.takeLast(window)
        val head = text.dropLast(window)
        return head.contains(tail)
    }

    /** Did the user explicitly ask for code? */
    fun userAskedForCode(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return listOf(
            "code", "python", "javascript", "swift", "kotlin", "java",
            "script", "function", "algorithm", "snippet", "écris", "écrire",
            "implémente", "fonction", "algorithme",
        ).any { lower.contains(it) }
    }

    /** Remove fenced code blocks the user did not ask for. */
    fun stripHallucinatedCode(text: String): String {
        var s = text
        while (true) {
            val open = s.indexOf("```")
            if (open < 0) break
            val close = s.indexOf("```", startIndex = open + 3)
            s = if (close >= 0) s.removeRange(open, close + 3)
            // An UNTERMINATED fence at the very start means the whole reply is a code
            // block that was never closed: nothing survives. Anywhere else, the prose
            // before it is real and the loop stops rather than eating it.
            else if (open == 0) "" else break
        }
        return stripLeadingNoise(s)
    }

    /** A runaway or corrupted reply that must not be fed back as history. */
    fun looksCorrupted(text: String): Boolean {
        if (text.length > 1500) {
            val fenceCount = text.split("```").size - 1
            if (fenceCount >= 4) return true
        }
        return hasRepeatedTail(text)
    }

    /**
     * The whole cleanup pipeline, in Android's order and with Android's conditional.
     *
     * The nesting order is not interchangeable: ChatML tokens come off first so a
     * `<|im_end|>` glued to a `</think>` cannot hide the closing tag from
     * [stripThinkBlocks], and the leading-noise pass runs LAST of the four so it can see
     * the blank lines the earlier passes leave behind.
     */
    fun clean(response: String, userPrompt: String): String {
        var cleaned = stripLeadingNoise(
            stripRolePreamble(
                stripThinkBlocks(
                    stripChatMLTokens(response),
                ),
            ),
        ).trim()
        if (!userAskedForCode(userPrompt)) cleaned = stripHallucinatedCode(cleaned).trim()
        return cleaned
    }
}
