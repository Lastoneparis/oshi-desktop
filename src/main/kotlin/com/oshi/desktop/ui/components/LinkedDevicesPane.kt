package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import com.oshi.desktop.i18n.t
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.devsync.DesktopDevSync
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * __DEVSYNC_DIRECT_2026_09_22__ More → Linked devices (docs/OSHI_DEVICE_SYNC_DIRECT.md §4.3–§4.4):
 * this machine's device id, devices waiting for approval with the SIX-DIGIT code to compare, the
 * linked list with Remove, "Sync now", and the local override of the server flag.
 *
 * English only for now: the 34-language catalog is regenerated from the iOS tree, which does not
 * have these strings yet.
 */
@Composable
fun LinkedDevicesPane(
    devSync: DesktopDevSync,
    /** __PER_DEVICE_MAILBOX_2026_09_23__ this computer's own mailbox (null = section hidden). */
    mailbox: com.oshi.desktop.net.DeviceMailbox? = null,
) {
    SettingsPageFrame {
        SettingsPageHeader(SettingsIcon.DEVICES, t("devsync.title"), t("desktop.settings.devices.subtitle"))
        LinkedDevicesSection(devSync, mailbox)
    }
}

/** The live half: polls [DesktopDevSync] once a second and draws [LinkedDevicesContent]. */
@Composable
fun LinkedDevicesSection(
    devSync: DesktopDevSync,
    mailbox: com.oshi.desktop.net.DeviceMailbox? = null,
) {
    var snap by remember { mutableStateOf<DesktopDevSync.Snapshot?>(null) }
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(devSync) {
        devSync.onChange { tick++ }
        onDispose { }
    }
    LaunchedEffect(devSync, tick) {
        while (true) {
            snap = withContext(Dispatchers.IO) { runCatching { devSync.snapshot() }.getOrNull() }
            delay(1_000)
        }
    }
    LinkedDevicesContent(
        s = snap,
        onOverride = { devSync.setOverride(it) },
        onApprove = { id, ok -> devSync.approve(id, ok) },
        onRevoke = { devSync.revoke(it) },
        onSyncNow = { devSync.syncNow() },
        mailbox = { if (mailbox != null) DeviceMailboxSection(mailbox) },
    )
}

/**
 * __DESKTOP_SETTINGS_2027__ Stateless, so it renders without a sync engine. The phones' own
 * `devsync.*` words, so it is translated everywhere; the switch replaces the old "Force on /
 * Follow server" links: on when the server enables it, and switching it to the server's own
 * value goes back to following the server.
 */
@Composable
fun LinkedDevicesContent(
    s: DesktopDevSync.Snapshot?,
    onOverride: (Boolean?) -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onRevoke: (String) -> Unit,
    onSyncNow: () -> Unit,
    mailbox: @Composable () -> Unit = {},
) {
    val enabled = s?.enabled == true
    SettingsCard(
        footer = s?.deviceId?.let { t("desktop.settings.devices.thisDevice", it.take(8)) },
    ) {
        SettingsToggleRow(
            SettingsIcon.DEVICES,
            t("desktop.settings.devices.direct"),
            if (enabled) t("devsync.footer") else t("devsync.disabled"),
            checked = enabled,
        ) { want -> onOverride(if (s != null && want == s.serverEnabled) null else want) }
    }

    if (!s?.pending.isNullOrEmpty()) {
        SettingsCard(title = t("devsync.pending.section")) {
            for (p in s!!.pending) {
                SettingsCardBody {
                    Text(
                        if (p.thisDeviceIsNew) t("devsync.pending.waiting", "").trim()
                        else t("devsync.pending.message", p.name, p.platform, "").trim(),
                        style = OshiTheme.typography.bodyMedium,
                        color = Ink.strong,
                    )
                    Spacer(Modifier.height(OshiTheme.sm))
                    Text(
                        p.code.chunked(3).joinToString(" "),
                        fontSize = 34.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Ink.strong,
                    )
                    // __MERGE_2027__ from the tools branch: say when the other side already allowed it.
                    if (p.peerApproved) {
                        Spacer(Modifier.height(OshiTheme.xs))
                        Text(t("pc.devices.peerApproved"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                    SettingsButtonRow {
                        SettingsButton(SettingsIcon.CHECK, t("devsync.allow"), primary = true) { onApprove(p.peerId, true) }
                        SettingsButton(null, t("devsync.deny"), danger = true) { onApprove(p.peerId, false) }
                    }
                }
            }
        }
    }

    val linked = s?.linked.orEmpty()
    SettingsCard(title = t("desktop.settings.devices.linked")) {
        if (linked.isEmpty()) {
            MascotEmptyState("wave", t("desktop.settings.devices.empty.title"), t("devsync.noDevices"))
        }
        linked.forEachIndexed { i, d ->
            if (i > 0) SettingsDivider()
            val live = s?.sessions?.firstOrNull { it.peerId == d.deviceId }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIconBadge(SettingsIcon.DEVICES, if (live != null) OshiTheme.success else OshiTheme.brand)
                Spacer(Modifier.width(OshiTheme.md))
                Column(Modifier.weight(1f)) {
                    Text(listOf(d.name, d.platform).bidiJoin(), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                    Text(
                        when {
                            live != null -> t(
                                "desktop.settings.devices.connected",
                                if (live.transport.contains("lan", ignoreCase = true)) t("devsync.transport.lan") else t("devsync.transport.relay"),
                            )
                            d.lastSyncAtMs != null -> t(
                                "devsync.lastSync",
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, java.util.Locale.forLanguageTag(com.oshi.desktop.i18n.Strings.activeTag)).format(Date(d.lastSyncAtMs!!)),
                            )
                            else -> t("devsync.neverSynced")
                        },
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                }
                SettingsButton(SettingsIcon.DELETE, t("devsync.remove"), danger = true) { onRevoke(d.deviceId) }
            }
        }
        SettingsCardBody {
            SettingsButtonRow {
                SettingsButton(SettingsIcon.CHECK, t("devsync.syncNow"), primary = true, enabled = enabled) { onSyncNow() }
            }
        }
    }
    mailbox()
}
