package com.oshi.desktop.app

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CONSOLE_CHARSET_ON_WINDOWS_2026_11_20__ The Windows console REPL read a UTF-8 pipe as
 * windows-1252: `/sendfile <path with été>` said "no such file" (PC smoke run on the
 * published 1.5.0, windows-latest). Reproduced here by forcing a JVM whose default and
 * stdout charsets are windows-1252, exactly what the shipped JDK 17 runtime has there.
 */
class ConsoleIoTest {

    @Test
    fun `no terminal means UTF-8, a terminal keeps its own code page`() {
        assertEquals(Charsets.UTF_8, ConsoleIo.pick(null))
        val cp850 = Charset.forName("IBM850")
        assertEquals(cp850, ConsoleIo.pick(cp850))
    }

    @Test
    fun `a UTF-8 pipe survives a JVM whose default charset is windows-1252`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(
            java,
            "-Dfile.encoding=windows-1252", "-Dsun.stdout.encoding=windows-1252", "-Dstdout.encoding=windows-1252",
            "-cp", System.getProperty("java.class.path"),
            ConsoleIoEcho::class.java.name,
        ).redirectErrorStream(true).start()
        val line = "/sendfile x C:\\Mes documents été\\Facture n°12 (copie) 😀.txt"
        p.outputStream.use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        assertTrue("child did not exit", p.waitFor(60, TimeUnit.SECONDS))
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("decoded wrongly: $out", out.contains("SAME"))
        assertTrue("echo not UTF-8: $out", out.contains(line))
    }
}

/** The child: what the REPL does with one stdin line, then prints whether it decoded exactly. */
object ConsoleIoEcho {
    @JvmStatic
    fun main(args: Array<String>) {
        val stdin = ConsoleIo.install()
        val got = stdin.readLine()
        val want = "/sendfile x C:\\Mes documents \u00e9t\u00e9\\Facture n\u00b012 (copie) \uD83D\uDE00.txt"
        println(if (got == want) "SAME" else "DIFFERENT")
        println(got)
    }
}
