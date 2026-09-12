package com.oshi.desktop.ai

import com.oshi.desktop.store.DesktopPaths
import java.io.File

/**
 * The one [DesktopLlmManager] in this process — PARITY.md row 2.5.
 *
 * A singleton rather than a field on `OshiClient`, and the reason is not convenience:
 *
 *  * **llama.cpp is a process-global resource.** One ggml context, not reentrant, and two
 *    of them would mean two copies of a multi-gigabyte model mapped at once. Hanging it
 *    off a per-account object would let a second account in the same process open a
 *    second engine, which is a crash or an out-of-memory, not a feature.
 *  * **Nothing about it is per-identity.** The model file and the native library are
 *    properties of the MACHINE. Neither is a secret and neither belongs in the vault: the
 *    model path is a filesystem path to a public download, and the conversation history is
 *    in memory only and is never persisted, never sent, and never entered into the message
 *    store. Offline AI is the one feature in this client that puts nothing on any wire.
 *
 * The state directory is `<data dir>/ai`, which holds exactly one file: the path of the
 * configured model.
 */
object DesktopAi {

    @Volatile private var mgr: DesktopLlmManager? = null

    @Synchronized
    fun manager(): DesktopLlmManager =
        mgr ?: DesktopLlmManager(File(DesktopPaths.dataDir, "ai")).also { mgr = it }

    /**
     * Test seam. Closes whatever is installed and installs [m] — pass null to reset.
     *
     * It exists because the ONLY real engine is a JNI class that cannot be constructed on
     * a machine with no native library, so a test that wants to prove `/ai` reaches the
     * manager has to substitute one. That substitution proves the WIRING and nothing about
     * llama.cpp; see the warning on [LlamaEngine].
     */
    @Synchronized
    fun replaceForTest(m: DesktopLlmManager?) {
        mgr?.let { runCatching { it.close() } }
        mgr = m
    }
}
