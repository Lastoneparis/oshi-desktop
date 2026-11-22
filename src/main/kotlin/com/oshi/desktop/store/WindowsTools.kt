package com.oshi.desktop.store

import java.io.File

/**
 * __WINDOWS_BARE_EXE_HIJACK_2026_11_20__ Absolute paths for the Windows system tools this app
 * starts. A bare `reg` in ProcessBuilder is CreateProcess with no application name: Windows looks
 * in the CURRENT directory before System32. A link launch inherits the browser's or mail
 * client's working directory (often Downloads), so a `reg.exe` / `powershell.exe` /
 * `rundll32.exe` dropped there ran with this user's rights on the next protocol registration,
 * login-item check or chart link. [SecretStore] already resolved PowerShell this way.
 */
object WindowsTools {
    fun systemRoot(env: (String) -> String? = System::getenv): String? =
        (env("SystemRoot") ?: env("WINDIR"))?.takeIf { it.isNotBlank() }

    /** `%SystemRoot%\System32\<exe>`, or the bare name when the environment has no SystemRoot. */
    fun system32(exe: String, root: String? = systemRoot()): String =
        root?.let { File(File(it, "System32"), exe).path } ?: exe

    val REG: String get() = system32("reg.exe")
    val RUNDLL32: String get() = system32("rundll32.exe")
    val POWERSHELL: String get() = systemRoot()?.let { File(it, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path } ?: "powershell.exe"
}
