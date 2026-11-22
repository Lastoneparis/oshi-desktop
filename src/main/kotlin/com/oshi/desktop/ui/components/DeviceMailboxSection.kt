package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.net.DeviceMailbox
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ More → Linked devices: this computer's own mailbox
 * (ServerPatches/per_device_mailbox/CLIENT_SPEC.md §3.7). Shows the registration state, the
 * two UNPROMPTED alerts (this computer was removed; a device appeared that this one did not
 * approve), and the server's device list with Remove. Names and platforms are not on the server,
 * so rows are ids; the devsync list above carries the names.
 */
@Composable
fun DeviceMailboxSection(mailbox: DeviceMailbox) {
    var snap by remember { mutableStateOf<DeviceMailbox.Snapshot?>(null) }
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(mailbox) {
        mailbox.onChange { tick++ }
        onDispose { }
    }
    LaunchedEffect(mailbox, tick) {
        while (true) {
            snap = withContext(Dispatchers.IO) { runCatching { mailbox.snapshot() }.getOrNull() }
            delay(2_000)
        }
    }
    val s = snap ?: return
    // __DESKTOP_SETTINGS_2027__ drawn with the Settings kit, in the shared catalogs.
    if (s.removedAlert) {
        SettingsCard {
            SettingsCardBody {
                Text(t("desktop.devices.mailbox.removed.title"), style = OshiTheme.typography.bodyLarge, color = Ink.strong, fontWeight = FontWeight.SemiBold)
                Text(t("desktop.devices.mailbox.removed.body"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Spacer(Modifier.height(OshiTheme.sm))
                SettingsButtonRow {
                    SettingsButton(SettingsIcon.LINK, t("desktop.devices.mailbox.relink"), primary = true) { Thread { mailbox.relink() }.start() }
                    SettingsButton(null, t("chat.peer_outdated.dismiss")) { mailbox.dismissRemovedAlert() }
                }
            }
        }
    }
    if (s.newDevices.isNotEmpty()) {
        SettingsCard {
            SettingsCardBody {
                Text(t("desktop.devices.mailbox.newDevice"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.sm))
                SettingsButton(null, t("chat.peer_outdated.dismiss")) { mailbox.dismissNewDeviceAlerts() }
            }
        }
    }
    SettingsCard(
        title = t("desktop.devices.mailbox.title"),
        footer = if (s.active) {
            if (s.holdsAccountBundle) t("desktop.devices.mailbox.holder") else t("desktop.devices.mailbox.notHolder")
        } else null,
    ) {
        SettingsCardBody {
            Text(
                when (s.status) {
                    DeviceMailbox.Status.OFF -> t("desktop.devices.mailbox.off")
                    DeviceMailbox.Status.REGISTERED -> t("desktop.devices.mailbox.registered")
                    DeviceMailbox.Status.NEEDS_APPROVAL -> t("desktop.devices.mailbox.needsApproval")
                    DeviceMailbox.Status.BAD_APPROVAL -> t("desktop.devices.mailbox.badApproval")
                    DeviceMailbox.Status.TOO_MANY_DEVICES -> t("desktop.devices.mailbox.tooMany")
                    DeviceMailbox.Status.CONFLICT -> t("desktop.devices.mailbox.conflict")
                    DeviceMailbox.Status.REMOVED -> t("desktop.devices.mailbox.removed.body")
                    DeviceMailbox.Status.ERROR -> t("desktop.devices.mailbox.error")
                    DeviceMailbox.Status.UNREGISTERED -> t("desktop.devices.mailbox.unregistered")
                },
                style = OshiTheme.typography.bodyMedium,
                color = Ink.strong,
            )
        }
        if (s.active && s.devices.isNotEmpty()) {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, java.util.Locale.forLanguageTag(com.oshi.desktop.i18n.Strings.activeTag))
            for (d in s.devices) {
                val self = d.deviceId == s.deviceId
                SettingsDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIconBadge(SettingsIcon.DEVICES, if (self) OshiTheme.success else OshiTheme.brand)
                    Spacer(Modifier.width(OshiTheme.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.deviceId.take(8) + if (self) " · " + t("desktop.devices.mailbox.thisDevice") else "",
                            style = OshiTheme.typography.bodyLarge,
                            color = Ink.strong,
                        )
                        Text(fmt.format(Date(d.lastSeenAt)), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                    if (!self) SettingsButton(SettingsIcon.DELETE, t("common.remove"), danger = true) { Thread { mailbox.unregister(d.deviceId) }.start() }
                }
            }
        }
    }
}
