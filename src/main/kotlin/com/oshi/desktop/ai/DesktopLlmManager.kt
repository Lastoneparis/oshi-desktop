package com.oshi.desktop.ai

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline AI on the desktop — PARITY.md row 2.5. The half of it that can exist here.
 *
 * **The fork in the road, stated once.** The iOS app PREFERS Apple's on-device
 * `SystemLanguageModel` (FoundationModels, iOS 26+). That is an Apple framework on Apple
 * silicon; there is no Windows or Linux equivalent and there will not be one. What both
 * phones fall back to is portable: llama.cpp running a GGUF model, reached over JNI on
 * Android through `service/LlamaCpp.kt`. So the desktop implements the FALLBACK, which
 * means a desktop answer will differ from an iPhone's answer for the same question. That
 * is not a defect to be fixed, it is the shape of the row, and it belongs in any UI copy
 * this ever gets: the phone may be running a different model entirely.
 *
 * **What this class is for.** It owns three things and refuses to guess about any of
 * them: whether the native library is reachable, whether a model file is configured, and
 * whether the last generation actually produced text. Every one of those has a failure
 * mode that reads as success if you let it — an absent library that returns "" looks
 * exactly like a model with nothing to say — so every outcome below is a distinct type
 * carrying its own explanation and NONE of them is an empty string.
 *
 * **The state that matters is not in this file.** The engine is `external fun`s into a
 * shared library. There is no such library on the machine this was written on, and there
 * is no GGUF model either (models are gigabytes; see [MODEL_SOURCES] for where one comes
 * from and how to point this at it — nothing is downloaded, ever, without the user doing
 * it deliberately). So: **no inference has been run.** The state machine below is tested;
 * llama.cpp is not, by anything here.
 *
 * Thread safety: llama.cpp's ggml context is NOT safe for concurrent inference — Android
 * serialises with a `Mutex` for exactly this reason, and the symptom of getting it wrong
 * is a SIGSEGV inside `ggml_compute_forward_mul_mat`, not an exception. One generation at
 * a time, and a second concurrent request is REFUSED rather than queued: queueing behind a
 * call that may still be running after a timeout is how you end up with two threads in the
 * native code anyway.
 */
class DesktopLlmManager(
    /** Where the configured model path is remembered between runs. */
    private val stateDir: File,
    /** Seam: how the native library is reached. Overridden only by tests. */
    private val loader: () -> LlamaNative.Load = { LlamaNative.load() },
    /** How long one generation may take before this reports a timeout. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : AutoCloseable {

    companion object {
        /**
         * Where a model comes from. **Nothing here downloads one**, and no GGUF file is
         * checked into this repository — the smallest of the three is 610 MB and the
         * largest is 2.4 GB.
         *
         * These are the three tiers the shipped Android client offers, taken from
         * `LocalLLMManager.MODELS` so the desktop and the phone can be pointed at the same
         * file. Fetch one by hand and pass it to `/ai model <path>`:
         *
         *   OSHI AI Pro       Qwen3-4B-Q4_K_M.gguf      2.50 GB
         *     https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf
         *   OSHI AI Standard  Qwen3-1.7B-Q4_K_M.gguf    1.11 GB
         *     https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf
         *   OSHI AI Lite      Qwen3-0.6B-Q8_0.gguf      639 MB
         *     https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf
         *
         * The prompt in [OshiPrompt] is Qwen3 ChatML with `/no_think`. A GGUF of some other
         * family will load and produce words; it will not honour that template, and the
         * cleanup pipeline will not recognise its special tokens. Nothing here detects
         * that, which is worth knowing before blaming the model.
         */
        const val MODEL_SOURCES: String = "see the KDoc on DesktopLlmManager.MODEL_SOURCES"

        /**
         * Android tiers this by device RAM (128/192/256) because a phone that overruns its
         * 90 s watchdog shows a dead spinner. A desktop is not RAM-tiered the same way and
         * the timeout below is the real guard, so the budget is the phone's top tier.
         */
        const val DEFAULT_MAX_TOKENS: Int = 256

        /** Android uses 90 s on a phone. A desktop CPU is not necessarily faster. */
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L

        /** Every GGUF file begins with these four bytes. Version 1 onwards. */
        val GGUF_MAGIC: ByteArray = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

        /** Turns/2 kept as context. Android keeps the last 10 entries. */
        const val HISTORY_TURNS: Int = 10
    }

    private val modelPointer = File(stateDir, "model.path")

    /** The model file the user configured, or null. Re-read from disk on construction. */
    @Volatile private var modelFile: File? = null

    @Volatile private var engine: LlamaEngine? = null
    @Volatile private var nativeProblem: String? = null
    @Volatile private var modelLoadedInNative = false

    private val history = ArrayList<OshiPrompt.Turn>()
    private val inFlight = AtomicBoolean(false)
    private val lock = Object()

    // ------------------------------------------------------------------ read-only status
    //
    // The window's AI screen has to report, BEFORE offering a composer, whether a model is
    // configured and whether the native library loaded — those are two different problems
    // with two different fixes, and a screen that collapsed them into "AI unavailable"
    // would send a user to neither. These are the read side of the two fields above and
    // nothing else; they configure nothing and they load nothing.

    /** The configured GGUF's path, or null when none has been accepted. */
    fun modelPath(): String? = modelFile?.absolutePath

    /**
     * Why `llama_jni` could not be loaded, or null.
     *
     * NON-NULL ONLY AFTER A LOAD HAS BEEN ATTEMPTED. `engineOrProblem()` is what sets it and
     * it runs on the first `generate`, so a fresh process reports null here whether or not
     * the library exists. The screen must therefore read this as "no known problem", never
     * as "the native is present" — which is why it asks for a model first and lets the first
     * answer be where a missing native surfaces, with `NoNativeLibrary` carrying the detail.
     */
    fun nativeProblem(): String? = nativeProblem

    private val worker = Executors.newSingleThreadExecutor(
        ThreadFactory { r -> Thread(r, "oshi-llm").apply { isDaemon = true } },
    )

    init {
        // A remembered path is re-VALIDATED, never trusted: a model can be deleted or moved
        // between runs, and a pointer to a file that is gone must read as "no model", not as
        // a configured one that mysteriously fails at generate time.
        runCatching {
            if (modelPointer.isFile) {
                val p = modelPointer.readText().trim()
                if (p.isNotEmpty() && validate(File(p)) is ModelOutcome.Accepted) modelFile = File(p)
            }
        }
    }

    // ------------------------------------------------------------------ model

    /** What `/ai model <path>` did. Each case is a different problem with a different fix. */
    sealed interface ModelOutcome {
        data class Accepted(val file: File, val bytes: Long) : ModelOutcome
        data class NotFound(val path: String) : ModelOutcome
        data class NotAFile(val path: String) : ModelOutcome
        data class Unreadable(val path: String) : ModelOutcome
        data class Empty(val path: String) : ModelOutcome

        /**
         * The file exists and is not a GGUF. Almost always an interrupted download — an
         * HTML error page or a truncated blob — which llama.cpp would otherwise report as
         * a load failure with no clue as to why.
         */
        data class NotGguf(val path: String, val firstBytes: String) : ModelOutcome
    }

    /** Validate without changing anything. Public so `/ai status` can re-check on demand. */
    fun validate(f: File): ModelOutcome = when {
        !f.exists() -> ModelOutcome.NotFound(f.path)
        f.isDirectory -> ModelOutcome.NotAFile(f.path)
        !f.canRead() -> ModelOutcome.Unreadable(f.path)
        f.length() == 0L -> ModelOutcome.Empty(f.path)
        else -> {
            val head = ByteArray(4)
            val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(-1)
            if (n == 4 && head.contentEquals(GGUF_MAGIC)) ModelOutcome.Accepted(f, f.length())
            else ModelOutcome.NotGguf(
                f.path,
                head.take(maxOf(n, 0)).joinToString(" ") { b -> "%02x".format(b) }.ifEmpty { "(unreadable)" },
            )
        }
    }

    /**
     * Point at a GGUF file. Persisted, so a restart does not lose it.
     *
     * Switching models UNLOADS the previous one first. Leaving it resident would keep
     * gigabytes mapped for a model nothing can reach any more, and — worse — the next
     * `generate` would run against the OLD weights, because `loadModel` is only called
     * when nothing is loaded.
     */
    fun configureModel(path: String): ModelOutcome = synchronized(lock) {
        val outcome = validate(File(path).absoluteFile)
        if (outcome is ModelOutcome.Accepted) {
            unloadLocked()
            history.clear()
            modelFile = outcome.file
            runCatching {
                stateDir.mkdirs()
                modelPointer.writeText(outcome.file.absolutePath)
                com.oshi.desktop.store.DesktopPaths.makePrivate(modelPointer)
            }
        }
        outcome
    }

    /** Forget the conversation context without touching the loaded weights. */
    fun forget(): Int = synchronized(lock) { history.size.also { history.clear() } }

    // ------------------------------------------------------------------ generate

    /** The result of asking a question. Never a bare String, and never a silent "". */
    sealed interface Answer {
        data class Generated(val text: String, val elapsedMs: Long, val rawChars: Int) : Answer

        /** No native library. [explain] is [LlamaNative.diagnose] — several lines, on purpose. */
        data class NoNativeLibrary(val explain: String) : Answer

        /** No model configured. */
        object NoModel : Answer

        /** The model file went away since it was configured. */
        data class ModelGone(val outcome: ModelOutcome) : Answer

        /** llama.cpp returned false from `loadModel`. */
        data class ModelLoadFailed(val path: String) : Answer

        /** The native call threw — a missing symbol, or llama.cpp itself failing. */
        data class NativeCallFailed(val detail: String) : Answer

        /** Still running after [afterMs]. The native thread is NOT killed; see [Busy]. */
        data class TimedOut(val afterMs: Long) : Answer

        /** A previous generation is still inside llama.cpp. Refused, not queued. */
        object Busy : Answer

        /**
         * The model ran and produced nothing usable. Distinguished from [Generated] with an
         * empty string on purpose: `rawChars` says whether the model emitted nothing at all
         * or emitted only think-blocks and ChatML tokens that the cleanup removed — two
         * different problems, and both of them look like "the AI is broken" otherwise.
         */
        data class EmptyOutput(val rawChars: Int) : Answer
    }

    fun generate(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Answer {
        if (prompt.isBlank()) return Answer.EmptyOutput(0)
        val model = modelFile ?: return Answer.NoModel
        val stillThere = validate(model)
        if (stillThere !is ModelOutcome.Accepted) return Answer.ModelGone(stillThere)

        val eng = when (val e = engineOrProblem()) {
            is Either.Right -> e.value
            is Either.Left -> return Answer.NoNativeLibrary(e.value)
        }

        if (!inFlight.compareAndSet(false, true)) return Answer.Busy
        // Set only on the timeout return, which hands ownership of `inFlight` to a watcher
        // task. Every other path clears the flag in the `finally`.
        var handedOff = false
        try {
            synchronized(lock) {
                if (!modelLoadedInNative) {
                    val ok = runCatching { eng.loadModel(model.absolutePath) }
                    ok.exceptionOrNull()?.let { return Answer.NativeCallFailed(describe(it)) }
                    if (ok.getOrThrow() != true) return Answer.ModelLoadFailed(model.absolutePath)
                    modelLoadedInNative = true
                }
            }

            val turns = synchronized(lock) {
                (history + OshiPrompt.Turn(OshiPrompt.ROLE_USER, prompt)).takeLast(HISTORY_TURNS)
            }
            val formatted = OshiPrompt.chatML(turns)

            val t0 = System.currentTimeMillis()
            val future: Future<String> = worker.submit<String> { eng.generate(formatted, maxTokens) }
            val raw = try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Deliberately NOT cancelled. `Future.cancel` interrupts a Java thread; it
                // cannot interrupt a thread already inside a JNI call, so cancelling would
                // mark this request done while llama.cpp keeps running — and the next
                // request would then enter a context that is not reentrant. Instead the
                // flag stays set and is released by a task queued BEHIND the stuck one on
                // the same single thread, so it can only run once llama.cpp has returned.
                // Until then every further request is refused with Busy, which is the
                // truth: this process cannot generate anything right now.
                handedOff = true
                worker.execute { inFlight.set(false) }
                return Answer.TimedOut(System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                // An Error thrown inside the task — an UnsatisfiedLinkError from a shim
                // that loaded but does not export the symbol the shared `external fun`
                // names — arrives here wrapped in an ExecutionException, so unwrapping is
                // what catches it. Nothing native may escape as an uncaught Error.
                return Answer.NativeCallFailed(describe(e.cause ?: e))
            }

            val elapsed = System.currentTimeMillis() - t0
            val cleaned = OshiPrompt.clean(raw, prompt)
            if (cleaned.isBlank()) return Answer.EmptyOutput(raw.length)

            synchronized(lock) {
                history += OshiPrompt.Turn(OshiPrompt.ROLE_USER, prompt)
                history += OshiPrompt.Turn(OshiPrompt.ROLE_ASSISTANT, cleaned)
                while (history.size > HISTORY_TURNS) history.removeAt(0)
            }
            return Answer.Generated(cleaned, elapsed, raw.length)
        } finally {
            if (!handedOff) inFlight.set(false)
        }
    }

    // ------------------------------------------------------------------ native

    private sealed interface Either<out L, out R> {
        data class Left<L>(val value: L) : Either<L, Nothing>
        data class Right<R>(val value: R) : Either<Nothing, R>
    }

    private fun engineOrProblem(): Either<String, LlamaEngine> = synchronized(lock) {
        engine?.let { return Either.Right(it) }
        nativeProblem?.let { return Either.Left(it) }
        when (val l = loader()) {
            is LlamaNative.Load.Ready -> { engine = l.engine; Either.Right(l.engine) }
            is LlamaNative.Load.Absent -> { nativeProblem = l.explain; Either.Left(l.explain) }
        }
    }

    /** Free the weights. Safe to call when nothing is loaded. */
    fun unload(): Boolean = synchronized(lock) { unloadLocked() }

    private fun unloadLocked(): Boolean {
        if (!modelLoadedInNative) return false
        modelLoadedInNative = false
        runCatching { engine?.unloadModel() }
        return true
    }

    override fun close() {
        synchronized(lock) { unloadLocked() }
        worker.shutdownNow()
    }

    // ------------------------------------------------------------------ status

    data class Status(
        val nativeReady: Boolean,
        val nativeProblem: String?,
        val libraryFile: String,
        val librarySearchPath: List<String>,
        val modelPath: String?,
        val modelBytes: Long,
        val modelProblem: ModelOutcome?,
        val modelLoadedInNative: Boolean,
        val generating: Boolean,
        val historyTurns: Int,
    ) {
        /** True only when a question asked right now could reach llama.cpp. */
        val canGenerate: Boolean get() = nativeReady && modelPath != null && modelProblem == null
    }

    /**
     * Read the state WITHOUT loading anything.
     *
     * `probeNative` is false by default and that is a real decision: touching the shared
     * `LlamaCpp` class is irreversible — a failed class initialiser poisons the class for
     * the life of the JVM — so a status command that probes by default would mean a user
     * who ran `/ai status` before setting up their library path could never load it in
     * that session. Nothing is lost by not probing: [LlamaNative.onSearchPath] answers the
     * question from the filesystem.
     */
    fun status(probeNative: Boolean = false): Status = synchronized(lock) {
        if (probeNative && engine == null && nativeProblem == null) engineOrProblem()
        val model = modelFile
        val problem = model?.let { validate(it).takeIf { o -> o !is ModelOutcome.Accepted } }
        Status(
            nativeReady = engine != null,
            nativeProblem = nativeProblem,
            libraryFile = LlamaNative.mappedFileName(),
            librarySearchPath = LlamaNative.searchPath(),
            modelPath = model?.absolutePath,
            modelBytes = model?.length() ?: 0L,
            modelProblem = problem,
            modelLoadedInNative = modelLoadedInNative,
            generating = inFlight.get(),
            historyTurns = history.size,
        )
    }

    private fun describe(t: Throwable?): String =
        if (t == null) "(no exception)" else "${t::class.java.simpleName}: ${t.message ?: "(no message)"}"
}
