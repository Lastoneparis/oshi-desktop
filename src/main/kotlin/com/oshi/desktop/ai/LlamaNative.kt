package com.oshi.desktop.ai

import java.io.File

/**
 * The one place that touches [com.oshi.messenger.service.LlamaCpp] — PARITY.md row 2.5.
 *
 * That class is SHARED SOURCE out of the Android tree (see `build.gradle.kts`,
 * `sharedServiceSources`). It is four `external fun`s and a companion `init` that calls
 * `System.loadLibrary("llama_jni")`, and on a machine with no native library that init
 * fails. Everything below exists because failing there is the NORMAL case here and it has
 * two shapes, not one:
 *
 *  1. **The first touch throws `UnsatisfiedLinkError`.** A static initialiser that
 *     completes abruptly with an `Error` propagates that `Error` unwrapped (JLS 12.4.2),
 *     and `UnsatisfiedLinkError` is an `Error`. So the first `LlamaCpp()` throws exactly
 *     what `LocalLLMManager.kt` catches.
 *  2. **Every touch after that throws `NoClassDefFoundError`** — "Could not initialize
 *     class com.oshi.messenger.service.LlamaCpp". The JVM marks a class whose initialiser
 *     failed as erroneous for the life of the classloader; it never retries.
 *
 *     `LocalLLMManager` catches `UnsatisfiedLinkError` ONLY, which is sound there because
 *     it constructs exactly once in `init`. A desktop REPL touches this on `/ai status`,
 *     on `/ai model` and on every `/ai <prompt>`, so catching only case 1 would take the
 *     client down with an uncaught `NoClassDefFoundError` the second time the user asked
 *     a question. Both are `LinkageError`; that is what is caught, and the first failure
 *     is remembered so the second answer is the same sentence as the first rather than a
 *     different-looking crash.
 *
 * **`System.load` cannot rescue this.** Pointing at an absolute path is the obvious fix
 * and it does not work: the `loadLibrary` call is inside the SHARED file's companion
 * init, it runs before any code here can intervene, and `ClassLoader.loadLibrary` only
 * ever walks `java.library.path` — a library already loaded from an absolute path is not
 * consulted. So the directory holding `llama_jni` must be ON `java.library.path` at JVM
 * start. That is a launcher concern, and the diagnosis below says so in those words
 * instead of printing "AI unavailable".
 */
object LlamaNative {

    /** The name in the source file's `System.loadLibrary`. Not ours to choose. */
    const val LIBRARY: String = "llama_jni"

    /** `llama_jni.dll` on Windows, `libllama_jni.so` on Linux, `libllama_jni.dylib` on macOS. */
    fun mappedFileName(): String = System.mapLibraryName(LIBRARY)

    /** The directories `System.loadLibrary` will actually search, in order. */
    fun searchPath(): List<String> =
        (System.getProperty("java.library.path") ?: "")
            .split(File.pathSeparatorChar)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** The first directory on `java.library.path` that really holds the mapped file, if any. */
    fun onSearchPath(): File? {
        val name = mappedFileName()
        return searchPath().asSequence().map { File(it, name) }.firstOrNull { it.isFile }
    }

    /**
     * A directory the user pointed us at, which is NOT the same thing as a directory the
     * JVM will search. `OSHI_LLAMA_LIB_DIR` exists so `/ai status` can tell the difference
     * between "you have not built the native library" and "you built it and the JVM cannot
     * see it" — two problems with completely different fixes.
     */
    fun hintedDir(): File? =
        System.getenv("OSHI_LLAMA_LIB_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    /** The mapped file inside [hintedDir], if it is really there. */
    fun inHintedDir(): File? = hintedDir()?.let { File(it, mappedFileName()) }?.takeIf { it.isFile }

    /** What a load attempt found. Never an exception, and never a bare boolean. */
    sealed interface Load {
        /** The library is loaded and the JNI methods are callable. */
        data class Ready(val engine: LlamaEngine) : Load

        /**
         * It is not, and [explain] is a paragraph a person can act on: what file was
         * looked for, where, whether it exists somewhere the JVM does not search, and
         * what the JVM actually threw.
         */
        data class Absent(val explain: String, val cause: String) : Load
    }

    @Volatile private var firstFailure: String? = null

    /**
     * Try to reach the native library.
     *
     * Repeatable and idempotent: after a failure the JVM will never re-run the class
     * initialiser, so this returns the SAME diagnosis rather than a new, stranger error.
     */
    @Synchronized
    fun load(): Load {
        firstFailure?.let { return Load.Absent(diagnose(it), it) }
        return try {
            val cpp = com.oshi.messenger.service.LlamaCpp()
            Load.Ready(SharedLlamaCppEngine(cpp))
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError on the first touch, NoClassDefFoundError on every one
            // after it, ExceptionInInitializerError if the shared file ever grows a
            // non-Error failure in its init. All three are LinkageError, all three mean
            // the same thing to a user, and none of them may reach the REPL loop.
            val cause = "${e::class.java.simpleName}: ${e.message ?: "(no message)"}"
            firstFailure = cause
            Load.Absent(diagnose(cause), cause)
        } catch (e: RuntimeException) {
            val cause = "${e::class.java.simpleName}: ${e.message ?: "(no message)"}"
            firstFailure = cause
            Load.Absent(diagnose(cause), cause)
        }
    }

    /**
     * The sentence that makes the failure actionable.
     *
     * Three genuinely different situations, and collapsing them into "the AI model is not
     * available" is what makes a feature look broken instead of unconfigured.
     */
    fun diagnose(cause: String): String {
        val name = mappedFileName()
        val path = searchPath()
        val found = onSearchPath()
        val hinted = inHintedDir()
        val sb = StringBuilder()
        sb.append("The llama.cpp JNI library is not loaded, so this client cannot run any\n")
        sb.append("inference at all. Nothing was generated and nothing was truncated.\n")
        sb.append("Looked for: $name\n")
        when {
            found != null -> {
                // The file is on the search path and the load STILL failed: not a missing
                // file, a broken one — wrong architecture, or its own dependencies
                // (libllama, libggml) are not resolvable next to it.
                sb.append("Found on java.library.path at: ${found.absolutePath}\n")
                sb.append("It is there and it did not load. That is usually the wrong CPU\n")
                sb.append("architecture, or libllama/libggml not being resolvable beside it.\n")
            }
            hinted != null -> {
                sb.append("Found at: ${hinted.absolutePath} (OSHI_LLAMA_LIB_DIR)\n")
                sb.append("but that directory is NOT on java.library.path, and the shared\n")
                sb.append("LlamaCpp.kt loads by NAME — an absolute path cannot be substituted.\n")
                sb.append("Relaunch with -Djava.library.path=${hinted.parentFile?.absolutePath}\n")
            }
            else -> {
                sb.append("Not present in any searched directory. Build it — see the\n")
                sb.append("`native-llama` job in .github/workflows/desktop-ci.yml — then relaunch\n")
                sb.append("with -Djava.library.path=<the directory holding $name>.\n")
            }
        }
        sb.append("java.library.path: ")
        sb.append(if (path.isEmpty()) "(empty)" else path.joinToString(File.pathSeparator))
        sb.append("\nJVM said: $cause")
        return sb.toString()
    }

    /** Test seam ONLY — forget a remembered failure. Does not un-poison the JVM's class. */
    internal fun resetForTest() {
        firstFailure = null
    }
}

/**
 * The four calls the manager needs, behind an interface.
 *
 * **Read this before trusting a green suite.** There is exactly ONE real implementation
 * ([SharedLlamaCppEngine]) and it is a straight delegation to the shared JNI class. A fake
 * standing in for it can prove the manager's state machine and nothing whatever about
 * llama.cpp — no fake has ever found a bug inside the only real implementation. The real
 * one is exercised by the `native-llama` CI job and by nothing on this machine.
 */
interface LlamaEngine {
    fun loadModel(path: String): Boolean
    fun generate(prompt: String, maxTokens: Int): String
    fun unloadModel()
}

private class SharedLlamaCppEngine(private val cpp: com.oshi.messenger.service.LlamaCpp) : LlamaEngine {
    override fun loadModel(path: String): Boolean = cpp.loadModel(path)
    override fun generate(prompt: String, maxTokens: Int): String = cpp.generate(prompt, maxTokens)
    override fun unloadModel() = cpp.unloadModel()
}
