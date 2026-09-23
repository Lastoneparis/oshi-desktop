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
    val s = snap
    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface).verticalScroll(rememberScrollState()).padding(OshiTheme.xxl),
    ) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
            Text("Linked devices", style = OshiTheme.typography.headlineLarge, color = Ink.strong)
            Text(
                if (s?.enabled == true) "Direct sync is on: your history moves device to device, over the local network or a live relay that stores nothing."
                else "Direct sync is off on this machine (the server has not enabled it). You can force it on below.",
                style = OshiTheme.typography.bodyLarge, color = Ink.soft,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                Text("Override: ${when (s?.override) { true -> "forced on"; false -> "forced off"; else -> "follow the server" }}", color = Ink.strong)
                TextAction("Force on") { devSync.setOverride(true) }
                TextAction("Follow server") { devSync.setOverride(null) }
            }
            s?.deviceId?.let { Text("This machine: device ${it.take(8)}", color = Ink.soft, fontSize = 12.sp) }

            if (!s?.pending.isNullOrEmpty()) {
                Spacer(Modifier.height(OshiTheme.sm))
                Text("Waiting for approval", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                for (p in s!!.pending) {
                    Column(
                        Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated, RoundedCornerShape(12.dp)).padding(OshiTheme.lg),
                        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
                    ) {
                        Text(
                            if (p.thisDeviceIsNew) "Approve this machine on your other device, after checking it shows the same code."
                            else "${p.name} (${p.platform}) wants to receive your history. Check that its screen shows the same code.",
                            color = Ink.strong,
                        )
                        Text(p.code.chunked(3).joinToString(" "), fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Ink.strong)
                        if (p.peerApproved) Text("The other device already approved. Approve here to finish.", color = Ink.soft)
                        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                            PrimaryButton("Allow") { devSync.approve(p.peerId, true) }
                            TextAction("Deny") { devSync.approve(p.peerId, false) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(OshiTheme.sm))
            Text("Linked", style = OshiTheme.typography.titleMedium, color = Ink.strong)
            val linked = s?.linked.orEmpty()
            if (linked.isEmpty()) Text("No device linked yet. Open OSHI on your phone while this window is open.", color = Ink.soft)
            for (d in linked) {
                val live = s?.sessions?.firstOrNull { it.peerId == d.deviceId }
                Row(
                    Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated, RoundedCornerShape(12.dp)).padding(OshiTheme.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${d.name} · ${d.platform}", color = Ink.strong, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                live != null -> "Connected over ${live.transport} (${live.state.name.lowercase()})"
                                d.lastSyncAtMs != null -> "Last sync " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.lastSyncAtMs!!))
                                else -> "Never synced"
                            },
                            color = Ink.soft, fontSize = 12.sp,
                        )
                    }
                    TextAction("Remove") { devSync.revoke(d.deviceId) }
                }
            }
            Spacer(Modifier.height(OshiTheme.sm))
            PrimaryButton("Sync now", enabled = s?.enabled == true) { devSync.syncNow() }
            if (mailbox != null) {
                Spacer(Modifier.height(OshiTheme.md))
                DeviceMailboxSection(mailbox)
            }
        }
    }
}
