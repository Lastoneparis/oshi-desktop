package com.oshi.desktop.ui

import java.awt.Color
import java.awt.Graphics2D
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Best-effort local notifications for the running desktop client.
 *
 * This intentionally contains no sender name or message text. A desktop toast can be
 * photographed, recorded, or shown during a screen share; the relay has already seen less
 * metadata than a notification preview would disclose locally. The window itself contains
 * the authenticated sender and decrypted content after the user opens it.
 *
 * AWT's tray API is available on Windows and on Linux desktops that expose a tray. It is
 * absent on headless Linux, many Wayland sessions and minimal window managers, so every
 * operation is best-effort: inability to show a toast must never interrupt polling or
 * message persistence.
 */
class DesktopNotifier {
    private var icon: TrayIcon? = null

    /**
     * __DESKTOP_BACKGROUND_2026_09_23__ The tray/menu-bar icon is also the way BACK into a
     * window that was closed to the background: Open OSHI brings it back, Quit ends the process
     * (and with it call and message delivery — which is why closing the window no longer does).
     * Returns whether an icon is actually showing, because a background mode with no icon and
     * no window would be a process nobody can reach.
     */
    fun install(openLabel: String = "Open OSHI", quitLabel: String = "Quit OSHI", onOpen: () -> Unit = {}, onQuit: (() -> Unit)? = null): Boolean {
        if (icon != null) return true
        if (!SystemTray.isSupported()) return false
        return runCatching {
            val trayIcon = TrayIcon(iconImage(), "OSHI").apply { isImageAutoSize = true }
            if (onQuit != null) {
                trayIcon.popupMenu = PopupMenu().apply {
                    add(MenuItem(openLabel).apply { addActionListener { onOpen() } })
                    addSeparator()
                    add(MenuItem(quitLabel).apply { addActionListener { onQuit() } })
                }
            }
            // Windows: double-click; macOS/Linux trays that deliver a primary action.
            trayIcon.addActionListener { onOpen() }
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            true
        }.getOrDefault(false)
    }

    fun notifyIncomingMessage() {
        // Do not create a tray icon from the poll thread: desktop environments often
        // require tray setup to happen on the UI thread. If it was unavailable at startup,
        // the conversation still updates normally and there is simply no toast. Desktop
        // shells can also reject a notification after a suspend, tray restart or session
        // change; that is a presentation failure, never a message-processing failure.
        if (MacNotification.notifyIfAvailable("New encrypted message")) return
        icon?.let { trayIcon ->
            runCatching {
                trayIcon.displayMessage("OSHI", "New encrypted message", TrayIcon.MessageType.INFO)
            }
            return
        }
        LinuxDesktopNotification.notifyIfAvailable()
    }

    /**
     * __DESKTOP_CALL_UI_2026_09_23__ An incoming call while the window is not focused. The
     * same privacy rule as a message: the body is generic (the localized "Incoming Voice Call"),
     * never the caller — a ringing name would land in OS notification history.
     */
    fun notifyIncomingCall(body: String) {
        if (MacNotification.notifyIfAvailable(body, sound = "Submarine")) return
        icon?.let { trayIcon ->
            runCatching { trayIcon.displayMessage("OSHI", body, TrayIcon.MessageType.INFO) }
            return
        }
        LinuxDesktopNotification.notifyIfAvailable(body, urgency = "critical")
    }

    /**
     * __CALL_PARITY_2026_09_23__ A call rang out unanswered while nobody was looking — what
     * CallKit's Recents gives an iPhone. Same privacy rule as [notifyIncomingCall]: the body
     * is the generic localized "Missed call", never the caller (the chat's history row, inside
     * the app, says who). Clicking the tray balloon opens the window like any other.
     */
    fun notifyMissedCall(body: String) {
        if (MacNotification.notifyIfAvailable(body)) return
        icon?.let { trayIcon ->
            runCatching { trayIcon.displayMessage("OSHI", body, TrayIcon.MessageType.INFO) }
            return
        }
        LinuxDesktopNotification.notifyIfAvailable(body)
    }

    fun close() {
        val current = icon ?: return
        runCatching { SystemTray.getSystemTray().remove(current) }
        icon = null
    }

    private fun iconImage(): Image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics: Graphics2D = image.createGraphics()
        try {
            graphics.color = Color(86, 22, 236)
            graphics.fillOval(1, 1, 30, 30)
            graphics.color = Color.WHITE
            graphics.fillOval(12, 12, 8, 8)
        } finally {
            graphics.dispose()
        }
    }
}

/**
 * __DESKTOP_BACKGROUND_2026_09_23__ macOS: AWT's `TrayIcon.displayMessage` rides the
 * `NSUserNotification` API Apple deprecated in macOS 11, and on current systems it posts
 * nothing at all — a menu-bar icon that never says a call is ringing. `osascript`'s
 * `display notification` reaches Notification Center on every supported macOS without a
 * signed bundle. The text travels as an ARGUMENT (`item 1 of argv`), never spliced into the
 * script, so no string can become AppleScript; and it is only ever the same generic,
 * metadata-free sentence the tray path shows.
 *
 * Known cost: Notification Center attributes these to "Script Editor", and the first one asks
 * the user to allow that. A signed .app with its own `UNUserNotificationCenter` would not.
 */
internal object MacNotification {
    private val osascript = Path.of("/usr/bin/osascript")

    internal fun command(osName: String, executable: Boolean, body: String, sound: String?): List<String>? {
        if (!osName.lowercase(Locale.ROOT).contains("mac") || !executable) return null
        val script = if (sound == null) "display notification (item 2 of argv) with title (item 1 of argv)"
        else "display notification (item 2 of argv) with title (item 1 of argv) sound name (item 3 of argv)"
        return listOf("/usr/bin/osascript", "-e", "on run argv", "-e", script, "-e", "end run", "OSHI", body) +
            listOfNotNull(sound)
    }

    fun notifyIfAvailable(body: String, sound: String? = null): Boolean {
        val args = command(System.getProperty("os.name", ""), Files.isExecutable(osascript), body, sound) ?: return false
        return runCatching {
            ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
    }
}

/**
 * Wayland sessions often deliberately expose no AWT tray, while still providing the standard
 * freedesktop notification service. This small fallback is intentionally Linux-only and does
 * not use a shell: a received message must never turn arbitrary message text into a command.
 *
 * It is a notification for an already running process, not a push service. The generic body is
 * deliberate: sender, conversation name and plaintext remain out of OS-level notification
 * history and screen recordings, just as they do in [DesktopNotifier]'s tray path.
 */
internal object LinuxDesktopNotification {
    private val notifySend = Path.of("/usr/bin/notify-send")

    internal fun command(
        osName: String,
        executable: Boolean,
        body: String = "New encrypted message",
        urgency: String = "normal",
    ): List<String>? {
        if (!osName.lowercase(Locale.ROOT).contains("linux") || !executable) return null
        return listOf(
            "/usr/bin/notify-send",
            "--app-name=OSHI",
            "--urgency=$urgency",
            "OSHI",
            body,
        )
    }

    fun notifyIfAvailable(body: String = "New encrypted message", urgency: String = "normal") {
        val args = command(System.getProperty("os.name", ""), Files.isExecutable(notifySend), body, urgency) ?: return
        // Do not wait on the desktop notification daemon from the polling thread. Its response
        // (or absence in a minimal session) must never delay durable message storage.
        runCatching {
            ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }
}
