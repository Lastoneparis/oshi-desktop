package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.IncomingShare
import com.oshi.desktop.ui.state.NetworkBadge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The reach badge: antenna + LAN mesh peers + "LoRa N" (iOS `MeshStatusBadge`).
 *
 * __TOOLBAR_DRIFT_2026_09_22__ ALWAYS drawn, grey when nothing is reachable, and STATIC — no
 * infinite transition. On iOS the badge was inserted on the first peer and removed on the last,
 * with a repeat-forever pulse that also animated its own placement; that is what made the bar
 * look like it moved by itself. Here nothing in the header appears, disappears or animates on
 * a peer flap, and the peer count itself is debounced in `NetworkBadgeTracker`.
 */
@Composable
fun NetworkBadgeChip(badge: NetworkBadge, modifier: Modifier = Modifier) {
    val tint = if (badge.connected) OshiTheme.success else Ink.soft
    val description = buildList {
        when (badge.meshPeers) {
            0 -> Unit
            1 -> add(t("mesh.connected.one"))
            else -> add(t("mesh.connected.many", badge.meshPeers))
        }
        if (badge.loraAttached) add(t("meshtastic.title") + ", " + t("lora.node.peers") + ": " + badge.loraNodes)
    }.ifEmpty { listOf(t("mesh.no_peers")) }.joinToString(". ")

    Row(
        modifier
            .clip(OshiTheme.pill)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        Antenna(tint)
        if (badge.meshPeers > 0) {
            Text("${badge.meshPeers}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.strong)
        }
        if (badge.loraAttached) {
            Text("LoRa ${badge.loraNodes}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.strong)
        }
    }
}

/**
 * Contacts sharing their live location WITH this desktop (iOS: the user's own shares — the
 * desktop has no GPS, PARITY.md row 0.19). Absent at zero. One → a click opens that chat;
 * several → a menu, one contact per line with its end time.
 */
@Composable
fun IncomingSharesChip(shares: List<IncomingShare>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (shares.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val time = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    Box(modifier) {
        Row(
            Modifier
                .clip(OshiTheme.pill)
                // A WASH with `strong` ink on top, like the gate chip: white on `success` is
                // ~2.2:1, far under the 4.5:1 a count needs.
                .background(OshiTheme.success.copy(alpha = 0.22f))
                .focusRing(OshiTheme.pill)
                .clickable { if (shares.size == 1) onOpen(shares.single().from) else open = true }
                .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs)
                .semantics(mergeDescendants = true) {
                    contentDescription = t("location.live") + ", " + shares.joinToString { it.label }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
        ) {
            Pin(Ink.strong, hole = OshiTheme.background)
            Text("${shares.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.strong)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (share in shares) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(share.label, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                            Text(
                                t("location.expires_in") + " " +
                                    time.format(Instant.ofEpochMilli(share.endsAtMs).atZone(ZoneId.systemDefault())),
                                fontSize = 11.sp,
                                color = Ink.soft,
                            )
                        }
                    },
                    onClick = { open = false; onOpen(share.from) },
                )
            }
        }
    }
}

@Composable
private fun Antenna(tint: Color) {
    Canvas(Modifier.size(13.dp)) {
        val s = size.minDimension
        val w = s * 0.11f
        val c = Offset(s * 0.5f, s * 0.42f)
        drawCircle(tint, radius = w * 0.9f, center = c)
        drawLine(tint, c, Offset(s * 0.5f, s * 0.95f), strokeWidth = w, cap = StrokeCap.Round)
        for ((r, sweep) in listOf(0.22f to 70f, 0.40f to 70f)) {
            val tl = Offset(c.x - r * s, c.y - r * s)
            val box = Size(2 * r * s, 2 * r * s)
            drawArc(tint, 180f - sweep / 2, sweep, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(tint, -sweep / 2, sweep, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Pin(tint: Color, hole: Color) {
    Canvas(Modifier.size(11.dp)) {
        val s = size.minDimension
        drawPath(
            Path().apply {
                moveTo(s * 0.5f, s * 0.98f)
                cubicTo(s * 0.1f, s * 0.55f, s * 0.12f, s * 0.05f, s * 0.5f, s * 0.05f)
                cubicTo(s * 0.88f, s * 0.05f, s * 0.9f, s * 0.55f, s * 0.5f, s * 0.98f)
                close()
            },
            tint,
        )
        drawCircle(hole, radius = s * 0.13f, center = Offset(s * 0.5f, s * 0.38f))
    }
}
