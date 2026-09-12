package com.oshi.desktop.ai

/**
 * What `/ai` prints — PARITY.md row 2.5.
 *
 * Separate from [DesktopLlmManager] and from `ClientCli` so the wording is testable
 * without a terminal AND without a native library. The rule this file exists to enforce
 * is one sentence: **an outcome that produced no text must never be printed as an answer.**
 * A blank line, a "…", or a cheerful placeholder are all the same defect — the user
 * believes the model spoke.
 *
 * Note what is deliberately NOT here: Android's
 * `"The offline AI model is installed and ready. Full AI responses will be available in
 * the next update."`, which `LocalLLMManager.runGenerate` returns as the ANSWER when the
 * native library is missing. It is returned in the same channel as a real reply, so the
 * chat shows it as the assistant's words, and it says "ready" about an engine that cannot
 * run. That is the exact shape this row is supposed to avoid, so the desktop says what is
 * wrong and where, and it says it as client output rather than as the model's voice.
 */
object AiConsole {

    /** Indent matching every other REPL command in `ClientCli`. */
    private const val I = "   "

    fun render(answer: DesktopLlmManager.Answer): List<String> = when (answer) {
        is DesktopLlmManager.Answer.Generated ->
            answer.text.split("\n").map { I + it } +
                listOf("$I(${answer.elapsedMs} ms, ${answer.rawChars} raw chars)")

        is DesktopLlmManager.Answer.NoNativeLibrary ->
            listOf("${I}NO ANSWER — the native engine is not loaded.") +
                answer.explain.split("\n").map { I + it }

        DesktopLlmManager.Answer.NoModel -> listOf(
            "${I}NO ANSWER — no model is configured.",
            "$I  /ai model <path-to-a-.gguf>",
            "${I}Nothing is downloaded automatically; a model is 0.6-2.5 GB and it is",
            "${I}fetched by hand. See DesktopLlmManager.MODEL_SOURCES for the three the",
            "${I}Android client offers, so a phone and this client can share one file.",
        )

        is DesktopLlmManager.Answer.ModelGone -> listOf(
            "${I}NO ANSWER — the configured model is no longer usable:",
        ) + render(answer.outcome).map { it }

        is DesktopLlmManager.Answer.ModelLoadFailed -> listOf(
            "${I}NO ANSWER — llama.cpp refused to load ${answer.path}.",
            "${I}The file is a GGUF; the engine still would not take it. Usual causes are a",
            "${I}GGUF version newer than the llama.cpp this shim was built from, or not",
            "${I}enough free RAM for the quantisation.",
        )

        is DesktopLlmManager.Answer.NativeCallFailed -> listOf(
            "${I}NO ANSWER — the native call failed: ${answer.detail}",
            "${I}The library loaded and the call did not complete. If this is an",
            "${I}UnsatisfiedLinkError naming a method, the JNI shim was built against a",
            "${I}different signature than the LlamaCpp.kt this client compiled.",
        )

        is DesktopLlmManager.Answer.TimedOut -> listOf(
            "${I}NO ANSWER — still running after ${answer.afterMs} ms, so this gave up waiting.",
            "${I}llama.cpp is NOT interruptible from the JVM, so it is still working in the",
            "${I}background and /ai will refuse further questions until it returns.",
        )

        DesktopLlmManager.Answer.Busy -> listOf(
            "${I}NO ANSWER — a previous generation is still inside llama.cpp.",
            "${I}Refused rather than queued: two threads in that engine is a crash, not a wait.",
        )

        is DesktopLlmManager.Answer.EmptyOutput ->
            if (answer.rawChars == 0) listOf("${I}NO ANSWER — the model returned nothing at all (0 chars).")
            else listOf(
                "${I}NO ANSWER — the model emitted ${answer.rawChars} chars and none of it survived",
                "${I}cleanup: it was chain-of-thought or ChatML tokens only. Usually the token",
                "${I}budget ran out mid-<think>.",
            )
    }

    fun render(outcome: DesktopLlmManager.ModelOutcome): List<String> = when (outcome) {
        is DesktopLlmManager.ModelOutcome.Accepted -> listOf(
            "${I}model set: ${outcome.file.absolutePath}",
            "$I  ${"%,d".format(outcome.bytes)} bytes, GGUF header verified",
            "${I}Not loaded yet — the weights are mapped on the first /ai question.",
        )
        is DesktopLlmManager.ModelOutcome.NotFound -> listOf("${I}no such file: ${outcome.path}")
        is DesktopLlmManager.ModelOutcome.NotAFile -> listOf("${I}that is a directory, not a model file: ${outcome.path}")
        is DesktopLlmManager.ModelOutcome.Unreadable -> listOf("${I}not readable by this user: ${outcome.path}")
        is DesktopLlmManager.ModelOutcome.Empty -> listOf("${I}the file is empty: ${outcome.path}")
        is DesktopLlmManager.ModelOutcome.NotGguf -> listOf(
            "${I}not a GGUF file: ${outcome.path}",
            "$I  first bytes: ${outcome.firstBytes}  (expected 47 47 55 46, \"GGUF\")",
            "${I}An interrupted download or an HTML error page saved under a .gguf name is",
            "${I}what this normally is. Checked here so llama.cpp does not have to fail",
            "${I}mysteriously later.",
        )
    }

    fun render(status: DesktopLlmManager.Status): List<String> {
        val out = ArrayList<String>()
        out += "${I}engine   : ${if (status.nativeReady) "llama.cpp JNI loaded" else "NOT loaded"}"
        out += "$I  library: ${status.libraryFile}"
        val found = LlamaNative.onSearchPath()
        out += if (found != null) "$I  found  : ${found.absolutePath}"
        else "$I  found  : NOWHERE on java.library.path"
        out += "$I  path   : " + (status.librarySearchPath.joinToString(java.io.File.pathSeparator).ifEmpty { "(empty)" })
        status.nativeProblem?.let { p -> out += p.split("\n").map { "$I  $it" } }

        out += "${I}model    : ${status.modelPath ?: "none configured  (/ai model <path>)"}"
        if (status.modelPath != null) {
            out += "$I  bytes  : ${"%,d".format(status.modelBytes)}"
            out += "$I  loaded : ${if (status.modelLoadedInNative) "yes, in native memory" else "no — mapped on the first question"}"
            status.modelProblem?.let { out += render(it) }
        }
        out += "${I}context  : ${status.historyTurns} turn(s) remembered"
        out += "${I}busy     : ${if (status.generating) "yes — a generation is in flight" else "no"}"
        out += if (status.canGenerate) {
            "${I}A question asked now would reach llama.cpp."
        } else {
            "${I}A question asked now would produce NO ANSWER, and would say why."
        }
        // Said on every status, because it is the single most likely thing to be assumed:
        // this client has never generated a token, on any machine, and the ledger row says
        // so too.
        out += "${I}NOTE: no inference has ever been verified from this client — there is no"
        out += "${I}native library in this repository and no model is shipped. See PARITY.md 2.5."
        return out
    }
}
