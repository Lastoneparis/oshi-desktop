package com.oshi.desktop.app

import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.Charset

/**
 * __CONSOLE_CHARSET_ON_WINDOWS_2026_11_20__ The REPL's text encoding, chosen once.
 *
 * Measured on a real Windows runner with the published 1.5.0 OSHI-Console.exe (PC smoke run,
 * 2026-11-20): `readlnOrNull()` decodes stdin with the JVM default charset and `println`
 * encodes with the platform one — windows-1252 on that machine (the shipped runtime is JDK 17,
 * before JEP 400). A UTF-8 caller's `/sendfile … Mes documents été\photo.png` arrived as
 * `Ã©tÃ©` and answered "no such file", and a UTF-8 `/send` put `Ã©` on the wire for the
 * phone to display. The round trip back through stdout hid it from the console itself.
 *
 * The rule: a real terminal keeps the terminal's own code page (that is what the keyboard
 * produces and what the window can draw); anything else — a pipe, a script, another program —
 * is UTF-8 both ways, which is what every such caller sends today.
 */
object ConsoleIo {

    /** The charset for a terminal's [consoleCharset], or UTF-8 when there is no terminal. */
    fun pick(consoleCharset: Charset?): Charset = consoleCharset ?: Charsets.UTF_8

    private fun terminalCharset(): Charset? = runCatching { System.console()?.charset() }.getOrNull()

    /**
     * Point System.out / System.err at [pick]'s charset (only when not on a terminal: the JVM
     * already uses the terminal's code page there) and return a stdin reader in the same one.
     */
    fun install(): BufferedReader {
        val terminal = terminalCharset()
        val cs = pick(terminal)
        if (terminal == null) {
            System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, cs.name()))
            System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, cs.name()))
        }
        return BufferedReader(InputStreamReader(System.`in`, cs))
    }
}
