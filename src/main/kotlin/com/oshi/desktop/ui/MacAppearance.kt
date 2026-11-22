package com.oshi.desktop.ui

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * __MAC_TITLE_BAR_FOLLOWS_THEME_2026_11_21__ `NSApp.appearance` from the app's own theme choice.
 *
 * AWT has no per-window switch for this on OpenJDK 21: `apple.awt.windowAppearance` (a JetBrains
 * Runtime client property) is ignored — tried on the running app, the bar stayed white — and the
 * system property `apple.awt.application.appearance` is read once, before the first window, so a
 * theme change would only show after a restart. So this sends the one Objective-C message itself
 * through JNA (already a dependency): `[NSApp performSelectorOnMainThread:@selector(setAppearance:)
 * withObject:<NSAppearance> waitUntilDone:NO]` — AppKit state, so it is applied on the main
 * thread, never from the caller's.
 *
 * [dark] null = nil appearance = follow the system (the "System" theme). Any failure (no objc
 * runtime, a selector missing on an old macOS) leaves the bar as it was: cosmetic only.
 */
internal object MacAppearance {
    private val objc: NativeLibrary? by lazy { runCatching { NativeLibrary.getInstance("objc") }.getOrNull() }

    fun apply(dark: Boolean?) {
        val lib = objc ?: return
        runCatching {
            val send = lib.getFunction("objc_msgSend")
            val cls = { n: String -> lib.getFunction("objc_getClass").invokePointer(arrayOf<Any>(n)) }
            val sel = { n: String -> lib.getFunction("sel_registerName").invokePointer(arrayOf<Any>(n)) }
            val appearance: Pointer? = dark?.let {
                val name = send.invokePointer(
                    arrayOf<Any>(cls("NSString"), sel("stringWithUTF8String:"), if (it) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"),
                )
                send.invokePointer(arrayOf<Any>(cls("NSAppearance"), sel("appearanceNamed:"), name))
            }
            val app = send.invokePointer(arrayOf<Any>(cls("NSApplication"), sel("sharedApplication")))
            send.invoke(
                Void::class.java,
                arrayOf<Any?>(app, sel("performSelectorOnMainThread:withObject:waitUntilDone:"), sel("setAppearance:"), appearance, 0),
            )
        }
    }
}
