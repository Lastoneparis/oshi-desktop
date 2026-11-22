package com.oshi.desktop.ui

import java.awt.FileDialog
import java.awt.Frame

/**
 * __FILE_DIALOGS_OPENED_BEHIND_2026_11_20__ The window file dialogs belong to.
 *
 * Twelve save/open dialogs were built with `FileDialog(null as Frame?, …)`. With no owner, a
 * Windows native dialog can open BEHIND the OSHI window (or as a separate taskbar button), is
 * not modal, and the person sees nothing happen after clicking "Save". An owned FileDialog is
 * modal to, and centred on, its window. The main window and the first-run window register
 * themselves here while they are shown.
 */
object DialogOwner {
    @Volatile var current: Frame? = null

    fun fileDialog(title: String, mode: Int): FileDialog =
        current?.takeIf { it.isDisplayable }?.let { FileDialog(it, title, mode) }
            ?: FileDialog(null as Frame?, title, mode)
}
