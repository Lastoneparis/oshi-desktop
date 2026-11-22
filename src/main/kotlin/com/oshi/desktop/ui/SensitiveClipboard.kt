package com.oshi.desktop.ui

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * __CLIPBOARD_HISTORY_LEAK_2026_11_20__ Text copied out of OSHI (a message, an address, a
 * revealed hidden message, a Cashu token) went onto the clipboard as a plain StringSelection:
 * on Windows that lands in Clipboard history (Win+V), may sync to the cloud clipboard, and is
 * offered to every clipboard monitor; on every OS it stayed there indefinitely.
 *
 * Now each copy also carries the three registered Windows formats Microsoft documents for
 * exactly this ("Cloud Clipboard and Clipboard History Formats"):
 *  - `ExcludeClipboardContentFromMonitorProcessing` — history, cloud sync and monitors skip it;
 *  - `CanIncludeInClipboardHistory` = DWORD 0;
 *  - `CanUploadToCloudClipboard` = DWORD 0.
 * AWT registers an unknown native name with RegisterClipboardFormat and writes the stream's
 * bytes as its HGLOBAL. Other OSes ignore the extra flavors.
 *
 * And after [CLEAR_AFTER_MS] the clipboard is emptied — only if it still holds OUR copy
 * (ownership not lost), so something the person copied elsewhere since is never touched.
 *
 * What this cannot stop: a keylogger or a program that polls the clipboard during those 60 s.
 */
object SensitiveClipboard {
    const val CLEAR_AFTER_MS = 60_000L

    val WINDOWS_EXCLUSION_FORMATS = listOf(
        "ExcludeClipboardContentFromMonitorProcessing",
        "CanIncludeInClipboardHistory",
        "CanUploadToCloudClipboard",
    )

    private val flavors: List<Pair<DataFlavor, ByteArray>> by lazy {
        val map = SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap
        WINDOWS_EXCLUSION_FORMATS.mapIndexed { i, native ->
            val flavor = DataFlavor("application/x-oshi-clipboard-exclusion-$i; class=java.io.InputStream", native)
            runCatching {
                map?.addUnencodedNativeForFlavor(flavor, native)
                map?.addFlavorForUnencodedNative(native, flavor)
            }
            flavor to ByteArray(4) // DWORD 0 (little-endian); content is ignored for the first
        }
    }

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "oshi-clipboard-clear").apply { isDaemon = true } }

    /** The transferable a copy puts on the clipboard. Exposed for tests. */
    fun selection(text: String): Selection = Selection(text, flavors)

    fun copy(text: String, clipboard: Clipboard? = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()) {
        val cb = clipboard ?: return
        val sel = selection(text)
        runCatching { cb.setContents(sel, sel) }.onFailure {
            runCatching { cb.setContents(StringSelection(text), null) }
            return
        }
        timer.schedule({ clearIfStillOurs(cb, sel) }, CLEAR_AFTER_MS, TimeUnit.MILLISECONDS)
    }

    fun clearIfStillOurs(cb: Clipboard, sel: Selection) {
        if (sel.owned) runCatching { cb.setContents(StringSelection(""), null) }
    }

    class Selection internal constructor(
        private val text: String,
        private val extra: List<Pair<DataFlavor, ByteArray>>,
    ) : Transferable, ClipboardOwner {
        @Volatile var owned = true
            private set

        override fun getTransferDataFlavors(): Array<DataFlavor> =
            (listOf(DataFlavor.stringFlavor) + extra.map { it.first }).toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = transferDataFlavors.any { it == flavor }

        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor == DataFlavor.stringFlavor) return text
            extra.firstOrNull { it.first == flavor }?.let { return ByteArrayInputStream(it.second) }
            throw UnsupportedFlavorException(flavor)
        }

        override fun lostOwnership(clipboard: Clipboard?, contents: Transferable?) { owned = false }
    }
}
