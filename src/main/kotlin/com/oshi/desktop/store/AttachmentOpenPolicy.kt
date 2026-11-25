package com.oshi.desktop.store

import com.oshi.desktop.i18n.t

/**
 * Whether a received attachment may be handed to the operating system.
 *
 * __ATTACHMENT_TYPE_CONFUSION_2026_11_02__ Found auditing OSHI against the
 * 2025–26 messenger CVEs rather than reported by anyone, and it is the same
 * shape as CVE-2025-30401 (WhatsApp for Windows): **the type a person sees and
 * the type the operating system acts on came from two different places.**
 *
 * The mechanism here:
 *  * the `filename` travels on the encrypted file-key message and is chosen by
 *    the SENDER (`V2FileKeyMessage`, `OshiClient.sendFile`);
 *  * `MediaVault.decryptToScratch` sanitises the CHARACTERS of that name but
 *    keeps its extension, by design, so the file opens in the right app;
 *  * `MediaExport.openExternally` then calls `java.awt.Desktop.open`, which is
 *    the shell. On Windows the shell runs `.exe`, `.scr`, `.bat`, `.cmd`,
 *    `.hta`, `.lnk`, `.js`, `.vbs`, …; on macOS it runs `.app` and `.command`;
 *    on Linux a `.desktop` file is an executable launcher.
 *
 * So a contact could send a row that the bubble renders as "📷 holiday.jpg" —
 * because `kind` is a separate wire field — while the file on disk is
 * `holiday.jpg.exe`, and "Open in another app" would EXECUTE it. The user
 * clicks once, on an attachment their own messenger told them was a photo.
 *
 * WHAT THIS DOES NOT CLAIM TO FIX. This is one door, not the class. OSHI still
 * hands images, PDFs and video to the platform's decoders and viewers, exactly
 * as every messenger does, and a bug in one of those is a bug OSHI cannot
 * prevent. What this removes is the part OSHI chose: letting a remote party
 * pick the extension the shell will act on, while showing the user a different
 * type.
 *
 * It is a POLICY and not a sniffer on purpose. Content sniffing would be a
 * second source of truth and the whole defect is having two; refusing the
 * dangerous cases outright is a rule that can be read, tested and explained in
 * a sentence on screen.
 */
object AttachmentOpenPolicy {

    enum class Verdict {
        /** Safe to hand to the shell. */
        OPEN,
        /** The extension is one the OS would EXECUTE. */
        REFUSE_EXECUTABLE,
        /** The displayed kind and the extension disagree — we do not know what this is. */
        REFUSE_MISMATCH,
    }

    /** [reason] is a finished sentence for the UI, or null when there is nothing to say. */
    data class Decision(val verdict: Verdict, val reason: String? = null) {
        val allowed: Boolean get() = verdict == Verdict.OPEN
    }

    /**
     * Extensions a desktop shell treats as "run this", across the three
     * platforms OSHI ships on. Deliberately generous: the cost of a false
     * positive is one extra click through "Save a copy", where the file
     * manager and the user decide; the cost of a false negative is code
     * execution from a chat message.
     */
    val EXECUTABLE = setOf(
        // Windows
        "exe", "com", "scr", "pif", "bat", "cmd", "msi", "msp", "mst", "cpl",
        "dll", "ocx", "sys", "drv", "hta", "jse", "js", "vbs", "vbe", "wsf",
        "wsh", "ps1", "ps1xml", "psc1", "psm1", "reg", "lnk", "url", "scf",
        "inf", "chm", "jar", "appref-ms", "application", "gadget", "msc",
        // macOS
        "app", "command", "osascript", "scpt", "workflow", "pkg", "mpkg",
        "dmg", "terminal",
        // Linux / shells / interpreters
        "desktop", "sh", "bash", "zsh", "csh", "ksh", "run", "bin", "appimage",
        "py", "pyc", "pl", "rb", "php", "lua", "deb", "rpm", "snap", "flatpakref",
        // mobile packages a desktop may still be asked to open
        "apk", "ipa", "xapk",
        // __WINDOWS_OPEN_POLICY_GAPS_2026_11_19__ Windows shell types the list above missed:
        // script components, shell/search/library descriptors, Office add-ins, package
        // installers, troubleshooters, disk images that mount (and drop Mark-of-the-Web),
        // macro documents, and pages a browser would open from file:// with script.
        "vb", "wsc", "sct", "settingcontent-ms", "library-ms", "search-ms", "searchconnector-ms",
        "xll", "xbap", "website", "msix", "msixbundle", "appx", "appxbundle", "appinstaller",
        "diagcab", "iso", "img", "vhd", "vhdx", "docm", "dotm", "xlsm", "xltm", "xlam", "pptm",
        "potm", "ppam", "sldm", "htm", "html", "xhtml", "mht", "mhtml", "svg", "hlp", "cab",
        // __OPEN_POLICY_GAPS_2026_11_20__ Java Web Start, Python GUI/zipapp, Access
        // (macros), shell scraps, Windows Script, VSTO add-ins, IE/ISP settings, Excel add-in,
        // and the macOS location files Finder follows on open.
        "jnlp", "pyw", "pyz", "pyzw", "mde", "mda", "accde", "ade", "adp", "accdr", "shs", "shb", "ws",
        "vsto", "ins", "isp", "xla", "fileloc", "webloc", "inetloc", "action", "plugin", "kext",
    )

    /** Extension → the family a user would expect it to be, for the mismatch rule. */
    private val FAMILY: Map<String, String> = buildMap {
        for (e in listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "avif")) put(e, "image")
        for (e in listOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "mpg", "mpeg")) put(e, "video")
        for (e in listOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "aiff", "caf", "amr")) put(e, "audio")
        for (e in listOf("pdf", "txt", "md", "rtf", "doc", "docx", "odt", "xls", "xlsx", "ods",
                         "ppt", "pptx", "odp", "csv", "json", "xml", "zip", "7z", "gz", "tar", "rar")) put(e, "document")
    }

    /** The last extension, lowercased — which is the only one a shell looks at. */
    fun extensionOf(filename: String): String =
        // Windows drops trailing dots and spaces when it creates the file, so `invoice.exe.`
        // lands on disk as `invoice.exe` and runs. Judge the name the OS will actually use.
        filename.trimEnd('.', ' ').substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 20 } ?: ""

    /**
     * @param filename the name the file carries on disk, i.e. the SENDER's.
     * @param displayedKind the wire `kind` the bubble rendered: "image",
     *        "video", "audio", "document", or anything else for "unknown".
     */
    fun decide(filename: String, displayedKind: String?): Decision {
        val ext = extensionOf(filename)
        if (ext.isEmpty()) {
            // No extension: the shell has nothing to act on and will ask the
            // user what to use. Nothing is being misrepresented.
            return Decision(Verdict.OPEN)
        }
        if (ext in EXECUTABLE) {
            // __ATTACHMENT_REFUSAL_I18N_2026_11_20__ was English-only for every language.
            return Decision(Verdict.REFUSE_EXECUTABLE, t("desktop.attach.refuse.executable", filename, ext))
        }
        val expected = FAMILY[ext]
        val shown = displayedKind?.lowercase()?.takeIf { it in setOf("image", "video", "audio", "document") }
        // Only a disagreement between two KNOWN families is a mismatch. An
        // unknown extension contradicts nothing, and neither does an unknown
        // kind — refusing those would block ordinary files for no gain.
        if (expected != null && shown != null && expected != shown) {
            return Decision(
                Verdict.REFUSE_MISMATCH,
                t("desktop.attach.refuse.mismatch", kindWord(shown), filename, kindWord(expected)),
            )
        }
        return Decision(Verdict.OPEN)
    }

    private fun kindWord(kind: String): String = when (kind) {
        "image" -> t("desktop.attach.kind.image")
        "video" -> t("desktop.attach.kind.video")
        "audio" -> t("desktop.attach.kind.audio")
        else -> t("desktop.attach.kind.document")
    }
}
