package com.oshi.desktop.ui

/**
 * What macOS does to this window's menus, MEASURED on macOS 15 (aarch64, JDK 21, Compose
 * 1.9.0) with `System Events` reading the live menu bar out of the running process.
 *
 * It is written down because three plausible "macOS adaptations" were tried here and two of
 * them did nothing. A property that is read once at toolkit start and then ignored leaves no
 * warning and no log line, so an inert fix looks exactly like a working one — and shipping
 * one is worse than shipping nothing, because the next person reads it as covered.
 *
 * **1. `apple.laf.useScreenMenuBar` — NOT needed.** The usual advice is that an AWT/Swing
 * menu bar is drawn inside the window on macOS unless this is true. Compose 1.9 does not
 * behave that way. Launched with the property *forced off*
 * (`-Dapple.laf.useScreenMenuBar=false`), the menu bar still read:
 *
 *     Apple, MainKt, OSHI, Conversation
 *
 * i.e. the app's own `OSHI` and `Conversation` menus were in the SCREEN menu bar regardless.
 * Setting it changed nothing, so it is not set.
 *
 * **2. `apple.awt.application.name` — does NOT title the application menu.** With it set
 * from inside `main`, before anything touched AWT, the bold menu beside the Apple logo was
 * still `MainKt`. It is not settable from inside the process.
 *
 * **3. `-Xdock:name=OSHI` — this is the one that works**, and it is a JVM LAUNCH argument,
 * so it belongs on the command line and not in any Kotlin file. With it:
 *
 *     Apple, OSHI, OSHI, Conversation
 *
 * It is set for `./gradlew run` in `build.gradle.kts`. The packaged `.app` never needed it:
 * `CFBundleName` answers first there. So this only ever affected developers running the
 * window from Gradle — which is everyone who works on it.
 *
 * **4. macOS supplies its own Quit, and it works.** The application menu is the system's,
 * not ours, and it already contains `About OSHI, Services, Hide OSHI, Hide Others, Show
 * All, Quit OSHI, Quit and Keep Windows`. Clicking `Quit OSHI` through System Events ended
 * the process. That is why [menuShortcut] does NOT put Command-Q on this app's own Quit
 * item: the chord is already bound by the OS to a working Quit, and a second binding for
 * the same chord in the same process is at best redundant and at worst a race between two
 * shutdown paths.
 */
object MacDesktopIntegration {

    /**
     * True on macOS. Read from `os.name`, the same test `build.gradle.kts` uses for its
     * packaging decisions, so the build and the app cannot disagree about what a Mac is.
     *
     * This is the whole of this object's runtime behaviour. Everything else it knows is in
     * the documentation above, which is the point: the finding was that there was nothing
     * to do in code.
     */
    val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().let {
        it.contains("mac") || it.contains("darwin")
    }
}
