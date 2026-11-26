package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.i18n.t
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.update.UpdateController
import com.oshi.desktop.update.UpdateController.Phase

/**
 * __DESKTOP_AUTO_UPDATE_2026_11_21__ "OSHI X.Y.Z is available": Update now / Later, then the
 * download, the install and — when the machine will not let OSHI install it — what to do by
 * hand. Every state of [UpdateController] that the person can be in while the dialog is open
 * is drawn here; nothing about the update is ever shown anywhere else except Settings → About.
 */
@Composable
fun UpdatePrompt(updates: UpdateController, onCopy: (String) -> Unit) {
    val s = updates.state
    if (!s.prompt) return
    val m = s.manifest ?: return
    val version = m.version.toString()
    val active = s.phase in UpdateController.ACTIVE
    OshiAlertDialog(
        onDismissRequest = { if (!active) updates.dismiss() },
        title = { Text(t("desktop.update.title", version), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.widthIn(min = 360.dp, max = 460.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                when (s.phase) {
                    Phase.DOWNLOADING -> {
                        Text(t("desktop.update.downloading", version), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                        val fraction = if (s.total > 0) (s.downloaded.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = OshiTheme.brand)
                        Text(megabytes(s.downloaded, s.total), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                    Phase.INSTALLING -> {
                        Text(t("desktop.update.installing.linux"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = OshiTheme.brand)
                    }
                    Phase.RESTARTING -> Text(
                        if (DesktopPaths.isWindows) t("desktop.update.restarting.windows") else t("desktop.update.restarting.linux"),
                        style = OshiTheme.typography.bodyMedium, color = Ink.strong,
                    )
                    Phase.VERIFY_FAILED -> Text(t("desktop.update.verify_failed"), style = OshiTheme.typography.bodyMedium, color = OshiTheme.danger)
                    Phase.DOWNLOAD_FAILED -> Text(t("desktop.update.download_failed"), style = OshiTheme.typography.bodyMedium, color = OshiTheme.danger)
                    Phase.UNSUPPORTED -> Text(t("desktop.update.unsupported"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                    Phase.MANUAL -> {
                        val cmd = s.manualCommand
                        if (cmd.isNullOrBlank()) {
                            Text(t("desktop.update.manual.windows"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                        } else {
                            Text(t("desktop.update.manual.linux"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                            SelectionContainer {
                                Text(cmd, fontFamily = FontFamily.Monospace, style = OshiTheme.typography.bodySmall, color = Ink.strong)
                            }
                        }
                    }
                    else -> {
                        Text(t("desktop.update.body", updates.current?.toString() ?: "—"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                        m.notesFor(Strings.activeTag)?.let { notes ->
                            Spacer(Modifier.height(OshiTheme.xxs))
                            Text(t("update.whats_new"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
                            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                Text(notes, style = OshiTheme.typography.bodySmall, color = Ink.strong)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (s.phase) {
                Phase.DOWNLOADING, Phase.INSTALLING, Phase.RESTARTING -> {}
                Phase.UNSUPPORTED, Phase.VERIFY_FAILED -> TextButton(onClick = { updates.openDownloadPage(); updates.dismiss() }) {
                    Text(t("desktop.update.open_page"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
                }
                Phase.DOWNLOAD_FAILED -> TextButton(onClick = { updates.updateNow() }) {
                    Text(t("common.retry"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
                }
                Phase.MANUAL -> {
                    val cmd = s.manualCommand
                    if (!cmd.isNullOrBlank()) TextButton(onClick = { onCopy(cmd) }) {
                        Text(t("common.copy"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> TextButton(onClick = { updates.updateNow() }) {
                    Text(t("update.update_now"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            when (s.phase) {
                Phase.DOWNLOADING -> TextButton(onClick = { updates.cancelDownload() }) {
                    Text(t("common.cancel"), color = OshiTheme.textPrimary)
                }
                Phase.INSTALLING, Phase.RESTARTING -> {}
                Phase.AVAILABLE, Phase.IDLE, Phase.UP_TO_DATE, Phase.CHECKING, Phase.CHECK_FAILED -> TextButton(onClick = { updates.later() }) {
                    Text(t("update.update_later"), color = OshiTheme.textPrimary)
                }
                else -> TextButton(onClick = { updates.dismiss() }) { Text(t("common.close"), color = OshiTheme.textPrimary) }
            }
        },
    )
}

private fun megabytes(done: Long, total: Long): String {
    fun mb(b: Long) = String.format(java.util.Locale.ROOT, "%.1f", b / 1_000_000.0)
    return if (total > 0) "${mb(done)} / ${mb(total)} MB" else "${mb(done)} MB"
}

/**
 * Settings → About: the version, what the checker last found, and "Check for updates".
 * Not drawn on macOS, nor in a build without a comparable version (a source run).
 */
@Composable
fun UpdatesSettingsCard(updates: UpdateController) {
    if (!updates.supported) return
    val s = updates.state
    val current = updates.current?.toString().orEmpty()
    val latest = s.manifest?.version?.toString()
    val detail = when (s.phase) {
        Phase.CHECKING -> t("desktop.update.settings.checking")
        Phase.UP_TO_DATE -> t("desktop.update.settings.uptodate", current)
        Phase.CHECK_FAILED -> t("desktop.update.settings.failed")
        Phase.IDLE -> "${t("settings.version")} $current"
        else -> if (latest != null) t("desktop.update.settings.available", latest) else "${t("settings.version")} $current"
    }
    SettingsCard(title = t("desktop.update.settings.title")) {
        SettingsLinkRow(SettingsIcon.IMPORT, t("desktop.update.settings.check"), detail) {
            if (s.manifest != null && s.phase !in UpdateController.ACTIVE && s.phase != Phase.CHECKING) updates.openPrompt() else updates.checkNow()
        }
        SettingsCardBody {
            if (s.lastInstallFailed) SettingsNotice(t("desktop.update.settings.last_failed"), error = true)
            Text(
                if (updates.automaticChecksPaused()) t("desktop.update.settings.paused") else t("desktop.update.settings.auto"),
                style = OshiTheme.typography.bodySmall, color = Ink.soft,
                modifier = Modifier.padding(top = OshiTheme.xxs),
            )
            if (updates.isTestChannel) {
                Text(t("desktop.update.settings.test"), style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
            }
        }
    }
}
