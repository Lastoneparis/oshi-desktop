package com.oshi.desktop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __FIRST_RUN_EXITED_THE_PROCESS_2026_10_29__ The chooser windows must hand an answer back.
 *
 * Compose's `application { }` defaults `exitProcessOnExit` to TRUE, so `exitApplication()`
 * ends the composition and then calls `exitProcess(0)`. `restoreIdentityWindow` and
 * `firstRunIdentityWindow` both exist to RETURN something that `UiLauncher` reads on the
 * very next line to build the client and open the real window — so with the default, that
 * next line was never reached. Clicking "Create New Identity" on a fresh profile made the
 * whole app vanish: exit status 0, nothing on stdout, nothing on stderr. A new user on
 * macOS could not get past the first screen, and the silence is why it reads as a crash
 * rather than a bug.
 *
 * ============================================================ WHY THIS IS A SOURCE TEST
 *
 * This asserts the shape of a call rather than the behaviour, which this repository is
 * rightly suspicious of — a test that checks a gabarit does not constrain what the code
 * does. It is the honest instrument here anyway, and the reason is the defect itself: what
 * went wrong happens AFTER the composition ends, in what the process does next. There is no
 * composition left to assert against and no JVM left to ask. The real proof is recorded in
 * PARITY row 1.1: the assembled app was driven with a real mouse on a throwaway `OSHI_HOME`,
 * died on that click, and after this one-word change survived it and printed its address.
 *
 * So: a guard, not a proof. It cannot tell you the first run works; it can only tell you
 * nobody has put the default back.
 */
class FirstRunDoesNotExitTest {

    private val source = File("src/main/kotlin/com/oshi/desktop/ui/IdentityRestoreWindow.kt")
    private val launcher = File("src/main/kotlin/com/oshi/desktop/ui/DesktopWindow.kt")

    @Test
    fun `the source this guards is where it is expected to be`() {
        // A guard that silently reads nothing is worse than no guard: it stays green after
        // the file is renamed, which is the exact moment it was supposed to speak up.
        assertTrue("IdentityRestoreWindow.kt not found at ${source.absolutePath}", source.isFile)
        assertTrue("DesktopWindow.kt not found at ${launcher.absolutePath}", launcher.isFile)
    }

    /**
     * Comments are stripped before matching, and the first run of this test is why: the
     * comment explaining the fix says "Compose's `application { }` defaults that flag to
     * TRUE", and the guard counted that sentence as a defect. A guard that reads prose
     * reports the explanation of a bug as the bug.
     */
    private fun code(f: File): String =
        f.readLines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `both chooser windows refuse to kill the process`() {
        val text = code(source)
        val bare = Regex("""(?<!\w)application\s*\{""").findAll(text).count()
        val guarded = Regex("""application\s*\(\s*exitProcessOnExit\s*=\s*false\s*\)\s*\{""")
            .findAll(text).count()
        assertEquals(
            "a chooser window calls `application { }` with the default exitProcessOnExit=true; " +
                "it would end the PROCESS instead of returning its answer to UiLauncher, and the " +
                "first run would die silently on the first click",
            0, bare,
        )
        assertEquals("both chooser windows must pass exitProcessOnExit = false", 2, guarded)
    }

    /**
     * The main window is the opposite case and must stay that way: when the person closes
     * the real window there is nothing left to return to, and quitting is correct. Pinned
     * so that "fix" is never applied here by analogy.
     */
    @Test
    fun `the main window still exits the process when it closes`() {
        val text = code(launcher)
        assertTrue(
            "DesktopWindow should keep the default application { } — closing the real window " +
                "has nothing to hand back, so quitting is right",
            Regex("""(?<!\w)application\s*\{""").containsMatchIn(text),
        )
        assertTrue(
            "the main window must NOT be given exitProcessOnExit = false",
            !text.contains("exitProcessOnExit"),
        )
    }
}
