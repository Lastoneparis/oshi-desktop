package com.oshi.desktop.store

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * __MARK_OF_THE_WEB_2026_11_20__ A file that came from someone else is marked as such before
 * the OS sees it — the mark every browser and mail client sets.
 *
 * Before: attachments opened with "Open in another app" (decrypted to `media-tmp/`) and copies
 * saved with "Save a copy" were ordinary local files. Without the mark, Office opens a `.doc`
 * / `.xls` with macros outside Protected View and its internet-macro block, SmartScreen does
 * not check an `.exe` extracted from a received `.zip`, and macOS Gatekeeper does not check a
 * received app/script. [AttachmentOpenPolicy] refuses direct executables; this covers what
 * hides inside documents and archives.
 *
 *  - Windows: NTFS stream `Zone.Identifier`, `ZoneId=3` (Internet).
 *  - macOS: `com.apple.quarantine` via `/usr/bin/xattr`.
 *  - Linux: there is no equivalent the desktops honour; nothing is claimed.
 */
object DownloadMark {
    const val ZONE_STREAM = "Zone.Identifier"

    fun zoneIdentifier(): String = "[ZoneTransfer]\r\nZoneId=3\r\n"

    /** macOS quarantine value: flags;hex-time;agent; (0081 = downloaded, user approval pending). */
    fun quarantineValue(nowSeconds: Long = System.currentTimeMillis() / 1000): String =
        "0081;${java.lang.Long.toHexString(nowSeconds)};OSHI;"

    /** Best effort: true when the mark was written. Never throws. */
    fun mark(file: File): Boolean = runCatching {
        when {
            DesktopPaths.isWindows -> {
                File(file.absolutePath + ":" + ZONE_STREAM).writeText(zoneIdentifier(), Charsets.US_ASCII)
                true
            }
            DesktopPaths.isMac -> {
                val p = ProcessBuilder("/usr/bin/xattr", "-w", "com.apple.quarantine", quarantineValue(), file.absolutePath)
                    .redirectErrorStream(true).start()
                p.outputStream.close()
                p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
            }
            else -> false
        }
    }.getOrDefault(false)
}
