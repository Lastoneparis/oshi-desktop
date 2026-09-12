package com.oshi.desktop.ui.state

import com.oshi.desktop.ai.DesktopLlmManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The AI destination's state — PARITY.md row 2.5, and the row says "no inference has ever run".
 *
 * ============================================================ WHAT THIS SCREEN IS FOR
 *
 * The shipped app's AI tab runs a local model on the phone. The same is possible here and
 * the plumbing is shared source — `service/LlamaCpp.kt` is compiled straight out of the
 * Android tree so the desktop and the handset load a JNI shim built from one header — but
 * two things have to be true before a single token is generated: a `llama_jni` native has to
 * be on `java.library.path`, and a GGUF file has to be pointed at. **Neither is shipped**,
 * and on this machine neither has ever been present at the same time.
 *
 * So the honest screen is not a chat box that silently does nothing. It is a screen that
 * reports, in order: whether the native loaded, whether a model is configured, and only then
 * a composer. Every refusal `DesktopLlmManager` can return has its own sentence here, because
 * "no native library" and "the model file moved" send a user to two different places and a
 * shared "AI unavailable" would send them to neither.
 *
 * ============================================================ NOTHING LEAVES THE MACHINE
 *
 * This is stated on screen and it is a property of the code rather than a promise: the only
 * call this model makes is [DesktopLlmManager.generate], which is `System.loadLibrary` and a
 * JNI call into llama.cpp. There is no HTTP client anywhere in this path — no relay, no
 * vendor, no telemetry. A local model is the only kind OSHI has ever had.
 *
 * ============================================================ NO COMPOSE IMPORTS
 *
 * Same rule as [ChatShellModel] and for the same reason: everything that could be WRONG here
 * — which answer maps to which explanation, whether a second question can be asked while one
 * is running — is decided in plain Kotlin where a headless runner can hold it to account.
 */
class AiConsoleModel(
    private val manager: DesktopLlmManager,
    private val worker: Executor = defaultWorker(),
) {

    data class Turn(val question: String, val answer: String, val ok: Boolean, val detail: String?)

    data class AiState(
        val modelPath: String?,
        val nativeProblem: String?,
        val turns: List<Turn>,
        val busy: Boolean,
        val notice: Notice?,
    ) {
        /** A composer is only offered when a question could actually be answered. */
        val canAsk: Boolean get() = modelPath != null && nativeProblem == null && !busy
    }

    private val lock = Any()
    private val turns = ArrayList<Turn>()
    private var busy = false
    private var notice: Notice? = null

    var onChange: (AiState) -> Unit = {}

    @Volatile
    var state: AiState = snapshot()
        private set

    /** Point the manager at a GGUF file. Validation is the manager's, verbatim. */
    fun useModel(path: String) {
        val outcome = manager.configureModel(path.trim())
        synchronized(lock) {
            notice = when (outcome) {
                is DesktopLlmManager.ModelOutcome.Accepted -> Notice(
                    "Model accepted: ${outcome.file.name} (${outcome.bytes} bytes). Nothing was uploaded.",
                    Severity.OK,
                )
                else -> Notice(modelProblem(outcome), Severity.ERROR)
            }
        }
        publish()
    }

    fun ask(question: String) {
        val q = question.trim()
        val go = synchronized(lock) {
            when {
                q.isEmpty() -> { notice = Notice("Nothing to ask.", Severity.INFO); false }
                busy -> false
                else -> { busy = true; notice = null; true }
            }
        }
        if (!go) { publish(); return }

        worker.execute {
            val answer = try {
                manager.generate(q)
            } catch (e: Exception) {
                DesktopLlmManager.Answer.NativeCallFailed("${e.javaClass.simpleName}: ${e.message}")
            }
            synchronized(lock) {
                busy = false
                turns += turnFor(q, answer)
            }
            publish()
        }
    }

    /** Drop the conversation the manager is keeping, and this screen's copy of it. */
    fun forget() {
        val dropped = manager.forget()
        synchronized(lock) {
            turns.clear()
            notice = Notice("Cleared $dropped stored turn(s). The model keeps no other memory.", Severity.OK)
        }
        publish()
    }

    // ---------------------------------------------------------------- rendering the answer

    /**
     * Every `Answer` the manager can return, each with its own sentence.
     *
     * This `when` is deliberately EXHAUSTIVE over a sealed hierarchy rather than falling
     * back on an `else`. When somebody adds a new failure mode to `DesktopLlmManager`, this
     * file must stop compiling — an unhandled refusal quietly rendered as "AI unavailable"
     * is precisely the outcome the row's honesty is meant to prevent.
     */
    private fun turnFor(q: String, a: DesktopLlmManager.Answer): Turn = when (a) {
        is DesktopLlmManager.Answer.Generated ->
            Turn(q, a.text, true, "${a.elapsedMs} ms, ${a.rawChars} raw characters, entirely on this machine.")
        is DesktopLlmManager.Answer.NoNativeLibrary ->
            Turn(q, "", false, "No llama.cpp native library is loaded. ${a.explain}")
        DesktopLlmManager.Answer.NoModel ->
            Turn(q, "", false, "No model file is configured. Point this screen at a .gguf first.")
        is DesktopLlmManager.Answer.ModelGone ->
            Turn(q, "", false, "The configured model is no longer usable: " + modelProblem(a.outcome))
        is DesktopLlmManager.Answer.ModelLoadFailed ->
            Turn(q, "", false, "llama.cpp refused to load ${a.path}. It is a GGUF file the engine would not open.")
        is DesktopLlmManager.Answer.NativeCallFailed ->
            Turn(q, "", false, "The native call failed: ${a.detail}")
        is DesktopLlmManager.Answer.TimedOut ->
            Turn(q, "", false, "Gave up after ${a.afterMs} ms. Nothing partial is shown — half an answer from a local model is not an answer.")
        DesktopLlmManager.Answer.Busy ->
            Turn(q, "", false, "The engine is already generating. One question at a time.")
        is DesktopLlmManager.Answer.EmptyOutput ->
            Turn(q, "", false, "The model produced ${a.rawChars} characters and none of them survived cleanup.")
    }

    private fun modelProblem(o: DesktopLlmManager.ModelOutcome): String = when (o) {
        is DesktopLlmManager.ModelOutcome.Accepted -> "accepted"
        is DesktopLlmManager.ModelOutcome.NotFound -> "No file at ${o.path}."
        is DesktopLlmManager.ModelOutcome.NotAFile -> "${o.path} is not a file."
        is DesktopLlmManager.ModelOutcome.Unreadable -> "${o.path} cannot be read by this process."
        is DesktopLlmManager.ModelOutcome.Empty -> "${o.path} is zero bytes."
        is DesktopLlmManager.ModelOutcome.NotGguf ->
            "${o.path} does not start with the GGUF magic — it begins ${o.firstBytes}. A renamed file is the usual cause."
    }

    // ---------------------------------------------------------------- plumbing

    private fun snapshot() = AiState(
        modelPath = manager.modelPath(),
        nativeProblem = manager.nativeProblem(),
        turns = synchronized(lock) { turns.toList() },
        busy = synchronized(lock) { busy },
        notice = synchronized(lock) { notice },
    )

    private fun publish() {
        val next = snapshot()
        state = next
        onChange(next)
    }

    companion object {
        private fun defaultWorker(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "oshi-ui-ai").apply { isDaemon = true }
        }
    }
}
