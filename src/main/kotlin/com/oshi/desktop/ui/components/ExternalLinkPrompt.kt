package com.oshi.desktop.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.oshi.desktop.i18n.t
import com.oshi.desktop.pairing.ExternalLinkGate

/**
 * __DEEP_LINK_DRIVE_BY_2026_11_20__ The confirmation an `oshi://` link from outside the app
 * needs before it adds a contact or sends a join request. See [ExternalLinkGate].
 * Self-contained: the window only places it and says what "yes" does.
 */
@Composable
fun ExternalLinkPrompt(onAccept: (String) -> Unit) {
    val p = ExternalLinkGate.pending ?: return
    // __GROUP_INFO_SETTINGS_2026_11_20__ a public group link is confirmed by its own join dialog
    // (name, description, member count from the registry) — see Pending.PublicJoin.
    if (p is ExternalLinkGate.Pending.PublicJoin || p is ExternalLinkGate.Pending.Bot || p is ExternalLinkGate.Pending.Channel) {
        androidx.compose.runtime.LaunchedEffect(p) { ExternalLinkGate.accept()?.let(onAccept) }
        return
    }
    val body = when (p) {
        is ExternalLinkGate.Pending.Join -> t("desktop.link.confirm.join", p.groupName, p.inviterShort)
        is ExternalLinkGate.Pending.AddContact -> t("desktop.link.confirm.add", p.addressShort)
        is ExternalLinkGate.Pending.PublicJoin, is ExternalLinkGate.Pending.Bot, is ExternalLinkGate.Pending.Channel -> return
    }
    AlertDialog(
        onDismissRequest = { ExternalLinkGate.dismiss() },
        title = { Text(t("desktop.link.confirm.title")) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { ExternalLinkGate.accept()?.let(onAccept) }) { Text(t("common.continue")) }
        },
        dismissButton = {
            TextButton(onClick = { ExternalLinkGate.dismiss() }) { Text(t("common.cancel")) }
        },
    )
}
