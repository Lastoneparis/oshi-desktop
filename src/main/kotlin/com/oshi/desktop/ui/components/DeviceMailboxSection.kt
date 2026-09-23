package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.oshi.desktop.i18n.dt
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
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        if (s.removedAlert) {
            Column(
                Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated, RoundedCornerShape(12.dp)).padding(OshiTheme.lg),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                Text(dt("desktop.devices.mailbox.removed.title"), color = Ink.strong, fontWeight = FontWeight.SemiBold)
                Text(dt("desktop.devices.mailbox.removed.body"), color = Ink.soft)
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                    PrimaryButton(dt("desktop.devices.mailbox.relink")) { Thread { mailbox.relink() }.start() }
                    TextAction(dt("desktop.devices.mailbox.dismiss")) { mailbox.dismissRemovedAlert() }
                }
            }
        }
        if (s.newDevices.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated, RoundedCornerShape(12.dp)).padding(OshiTheme.lg),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                Text(dt("desktop.devices.mailbox.newDevice"), color = Ink.strong, fontWeight = FontWeight.SemiBold)
                TextAction(dt("desktop.devices.mailbox.dismiss")) { mailbox.dismissNewDeviceAlerts() }
            }
        }
        Text(dt("desktop.devices.mailbox.title"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Text(
            when (s.status) {
                DeviceMailbox.Status.OFF -> dt("desktop.devices.mailbox.off")
                DeviceMailbox.Status.REGISTERED -> dt("desktop.devices.mailbox.registered")
                DeviceMailbox.Status.NEEDS_APPROVAL -> dt("desktop.devices.mailbox.needsApproval")
                DeviceMailbox.Status.BAD_APPROVAL -> dt("desktop.devices.mailbox.badApproval")
                DeviceMailbox.Status.TOO_MANY_DEVICES -> dt("desktop.devices.mailbox.tooMany")
                DeviceMailbox.Status.CONFLICT -> dt("desktop.devices.mailbox.conflict")
                DeviceMailbox.Status.REMOVED -> dt("desktop.devices.mailbox.removed.body")
                DeviceMailbox.Status.ERROR -> dt("desktop.devices.mailbox.error")
                DeviceMailbox.Status.UNREGISTERED -> dt("desktop.devices.mailbox.unregistered")
            },
            color = Ink.soft,
        )
        if (s.active) {
            Text(
                if (s.holdsAccountBundle) dt("desktop.devices.mailbox.holder") else dt("desktop.devices.mailbox.notHolder"),
                color = Ink.soft, fontSize = 12.sp,
            )
        }
        if (s.active && s.devices.isNotEmpty()) {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (d in s.devices) {
                val self = d.deviceId == s.deviceId
                Row(
                    Modifier.fillMaxWidth().background(OshiTheme.surfaceElevated, RoundedCornerShape(12.dp)).padding(OshiTheme.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.deviceId.take(8) + if (self) " · " + dt("desktop.devices.mailbox.thisDevice") else "",
                            color = Ink.strong, fontWeight = FontWeight.SemiBold,
                        )
                        Text(fmt.format(Date(d.lastSeenAt)), color = Ink.soft, fontSize = 12.sp)
                    }
                    if (!self) TextAction(dt("desktop.devices.mailbox.remove")) { Thread { mailbox.unregister(d.deviceId) }.start() }
                }
            }
        }
    }
}
