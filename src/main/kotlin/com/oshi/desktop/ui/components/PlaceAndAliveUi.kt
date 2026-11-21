package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.msg.AliveCheckInPayload
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.AliveState
import com.oshi.desktop.ui.state.Coordinates
import com.oshi.desktop.ui.state.LocationCardRow

// ============================================================================ a shared place

/**
 * __DESKTOP_1_6_PARITY__ A `📍LOCATION📍` message as a card: a small map with the pin, the
 * address the sender gave (or "Location"), the coordinates, and two actions — copy them, or
 * open them on OpenStreetMap. 1.5.1 printed an English sentence instead ("📍 Location: …").
 *
 * The map is not interactive here, so the mouse wheel keeps scrolling the conversation; its
 * tiles are the phones' own (`MapTiles`), and nothing is fetched while traffic is disguised.
 */
@Composable
fun LocationCard(card: LocationCardRow, fromMe: Boolean, ink: Color, inkSoft: Color) {
    val camera = remember(card.latitude, card.longitude) { MapCamera(card.latitude, card.longitude, 15) }
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val coords = Coordinates.format(card.latitude, card.longitude)
    Column(Modifier.widthIn(min = 240.dp, max = 300.dp)) {
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(OshiTheme.radiusSm)
                .border(Metrics.hairline, ink.copy(alpha = 0.12f), OshiTheme.radiusSm)
                .semantics { contentDescription = if (fromMe) t("location.map_mine") else t("location.map_theirs") },
        ) {
            OshiTileMap(camera, emptyList(), Modifier.fillMaxSize(), interactive = false)
            // The camera is centred on the place, so the pin's tip stands on the centre.
            MapPinMark(Modifier.align(Alignment.Center).offset(y = (-14).dp))
            if (card.live) {
                Text(
                    t("location.live") + (card.remainingMs?.let { " · " + aliveSpanText(it) } ?: ""),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(OshiTheme.xs)
                        .clip(OshiTheme.pill).background(Color(0xFF1FA45A)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(OshiTheme.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.MAP_PIN, ink, 15.dp)
            Spacer(Modifier.width(OshiTheme.xxs + 2.dp))
            Text(card.address ?: t("location.shared_location"), style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 2)
        }
        Text(coords, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = inkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            CardAction(t("location.open_in_maps"), ink) { runCatching { uri.openUri(Coordinates.osmUrl(card.latitude, card.longitude)) } }
            CardAction(t("common.copy"), ink) { clipboard.setText(AnnotatedString(coords)) }
        }
    }
}

@Composable
private fun CardAction(label: String, ink: Color, onClick: () -> Unit) {
    Text(
        label,
        style = OshiTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = ink,
        modifier = Modifier.padding(top = OshiTheme.xs).clip(OshiTheme.pill).background(ink.copy(alpha = 0.10f))
            .focusRing(OshiTheme.pill).clickable(onClick = onClick).padding(horizontal = OshiTheme.md, vertical = 4.dp),
    )
}

/**
 * __DESKTOP_1_6_PARITY__ "Ping a place" — iOS `LocationPingPickerView` on a computer: drag the
 * map under a FIXED pin (never a long-press drop nobody is told about), or paste coordinates
 * or a map link, optionally name the place, send. What goes out is the one-shot location both
 * phones already render; there is no "my location" here because there is no GPS.
 */
@Composable
fun LocationPingDialog(title: String, onSend: (lat: Double, lon: Double, label: String?) -> Unit, onDismiss: () -> Unit) {
    val camera = remember { MapCamera(PingMemory.lat, PingMemory.lon, PingMemory.zoom) }
    var pasted by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val parsed = remember(pasted) { Coordinates.parse(pasted) }
    LaunchedEffect(parsed) { parsed?.let { (la, lo) -> camera.flyTo(la, lo, maxOf(camera.zoom, 15)) } }
    val lat = camera.centerLat
    val lon = camera.centerLon
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary)
                Text(t("location.ping.pick_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Box(Modifier.fillMaxWidth().height(320.dp).clip(OshiTheme.radiusMd).border(Metrics.hairline, OshiTheme.separator, OshiTheme.radiusMd)) {
                    OshiTileMap(camera, emptyList(), Modifier.fillMaxSize())
                    // The pin stands ON the centre: its tip is the point that will be sent.
                    MapPinMark(Modifier.align(Alignment.Center).offset(y = (-16).dp), 34.dp)
                    Text(
                        t("location.ping.map_hint"),
                        style = OshiTheme.typography.labelSmall,
                        color = OshiTheme.textPrimary,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(OshiTheme.sm)
                            .clip(OshiTheme.pill).background(OshiTheme.background.copy(alpha = 0.82f)).padding(horizontal = OshiTheme.md, vertical = 3.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("location.ping.this_spot") + "  ", style = OshiTheme.typography.labelMedium, color = Ink.soft)
                    Text(Coordinates.format(lat, lon), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.strong)
                }
                DialogField(pasted, t("pc.location.paste_hint")) { pasted = it.take(2_000) }
                if (pasted.isNotBlank() && parsed == null) {
                    Text(t("pc.location.paste_invalid"), style = OshiTheme.typography.bodySmall, color = OshiTheme.warning)
                }
                DialogField(label, t("pc.location.label_hint")) { label = it.take(120) }
            }
        },
        confirmButton = {
            PrimaryButton(t("location.ping.send")) {
                PingMemory.remember(lat, lon, camera.zoom)
                onSend(lat, lon, label.trim().ifEmpty { null })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel"), color = OshiTheme.textPrimary) } },
    )
}

/** Where the last ping was made, so the next one opens there (this run only, never on disk). */
internal object PingMemory {
    var lat = 46.5
    var lon = 6.6
    var zoom = 5
    fun remember(la: Double, lo: Double, z: Int) { lat = la; lon = lo; zoom = z }
}

@Composable
private fun DialogField(value: String, placeholder: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusSm).background(OshiTheme.surface)
            .border(Metrics.hairline, OshiTheme.separator.atMostAlpha(0.6f), OshiTheme.radiusSm)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
    ) {
        if (value.isEmpty()) Text(placeholder, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
            cursorBrush = SolidColor(OshiTheme.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================ safety check-in

/** `2d 4h`, `3h 12m`, `45m`, `under a minute` — each key literal, for the catalog audit. */
@Composable
internal fun aliveSpanText(ms: Long): String {
    val s = AliveState.span(ms)
    return when {
        s.days > 0 -> t("alive.duration.days_hours", s.days, s.hours)
        s.hours > 0 -> t("alive.duration.hours_minutes", s.hours, s.minutes)
        s.minutes > 0 -> t("alive.duration.minutes", s.minutes)
        else -> t("alive.duration.now")
    }
}

@Composable
private fun laneText(lane: AliveState.Lane?, now: Long): String = when {
    lane == null -> t("alive.state.not_set")
    lane.overdue(now) -> t("alive.state.overdue_by", aliveSpanText(-lane.remainingMs(now)))
    else -> t("alive.state.due_in", aliveSpanText(lane.remainingMs(now)))
}

/** A clock for the countdowns: ticks once a minute while the composable is on screen. */
@Composable
private fun minuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * __DESKTOP_1_6_PARITY__ The thin bar under a 1:1's header while either side has a check-in
 * running — the phones' alive bar: my countdown with an "I'm alive" button, theirs with how
 * long it has been overdue. Overdue is drawn as a warning, never as an alarm: no answer is
 * not proof of harm (the payload's own policy).
 */
@Composable
fun AliveBar(state: AliveState, peerName: String, onConfirm: () -> Unit, onOpen: () -> Unit) {
    if (state.mine == null && state.theirs == null) return
    val now = minuteClock()
    val theirsOverdue = state.theirs?.overdue(now) == true
    val mineOverdue = state.mine?.overdue(now) == true
    Row(
        Modifier.fillMaxWidth()
            .background(if (theirsOverdue || mineOverdue) OshiTheme.warning.copy(alpha = 0.14f) else OshiTheme.brand.copy(alpha = 0.08f))
            .clickable(onClickLabel = t("alive.bar.open_settings"), onClick = onOpen)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        GlyphIcon(Glyph.HEART, if (theirsOverdue || mineOverdue) OshiTheme.warning else OshiTheme.brand, 16.dp)
        Column(Modifier.weight(1f)) {
            state.mine?.let {
                Text(t("alive.lane.you") + " · " + laneText(it, now), style = OshiTheme.typography.bodySmall, color = if (mineOverdue) OshiTheme.warning else Ink.strong, maxLines = 1)
            }
            state.theirs?.let {
                Text(peerName + " · " + laneText(it, now), style = OshiTheme.typography.bodySmall, color = if (theirsOverdue) OshiTheme.warning else Ink.strong, maxLines = 1)
            }
        }
        if (state.mine != null) {
            Text(
                t("alive.action.im_alive"),
                style = OshiTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Ink.onBrandStrong,
                modifier = Modifier.clip(OshiTheme.pill).background(OshiTheme.brandGradient).focusRing(OshiTheme.pill)
                    .clickable(onClick = onConfirm).padding(horizontal = OshiTheme.md, vertical = 4.dp),
            )
        }
    }
}

/**
 * __DESKTOP_1_6_PARITY__ The check-in sheet (iOS `AliveCheckInSheet`, Android
 * `AliveCheckInUi`): my schedule with this person — on/off, how often, "I'm alive" — and
 * theirs with me, read-only. Every string is the phones' own, already in 34 languages.
 */
@Composable
fun AliveSheet(
    state: AliveState,
    peerName: String,
    onSend: (AliveCheckInPayload.Kind, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = minuteClock()
    var hours by remember(state.mine) {
        mutableLongStateOf(state.mine?.let { it.intervalSeconds / 3_600 }?.takeIf { it > 0 } ?: AliveState.PRESET_HOURS.first())
    }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("alive.title"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Text(t("alive.sheet.explainer", peerName), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                // ------------------------------------------------ mine
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("alive.sheet.mine_switch", peerName), style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                        Text(laneText(state.mine, now), style = OshiTheme.typography.bodySmall,
                            color = if (state.mine?.overdue(now) == true) OshiTheme.warning else Ink.soft)
                    }
                    androidx.compose.material3.Switch(
                        checked = state.mine != null,
                        onCheckedChange = { on ->
                            if (on) onSend(AliveCheckInPayload.Kind.ENABLED, hours * 3_600)
                            else onSend(AliveCheckInPayload.Kind.DISABLED, 0)
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = OshiTheme.brand,
                            checkedThumbColor = if (OshiTheme.palette.isDark) Color(0xFF14082E) else Color.White,
                        ),
                    )
                }
                Text(t("alive.sheet.interval"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                    for (h in AliveState.PRESET_HOURS) {
                        val selected = hours == h
                        Text(
                            t("alive.interval.custom_hours", h.toInt()),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) OshiTheme.brand else Ink.strong,
                            modifier = Modifier.clip(OshiTheme.pill)
                                .background(if (selected) OshiTheme.brand.copy(alpha = 0.16f) else OshiTheme.surface)
                                .focusRing(OshiTheme.pill)
                                .clickable {
                                    hours = h
                                    // Changing the schedule of a running check-in re-announces it,
                                    // as the phones do: the peer's countdown follows.
                                    if (state.mine != null) onSend(AliveCheckInPayload.Kind.ENABLED, h * 3_600)
                                }
                                .padding(horizontal = OshiTheme.md, vertical = 5.dp),
                        )
                    }
                }
                if (state.mine != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(t("alive.action.im_alive")) { onSend(AliveCheckInPayload.Kind.CONFIRMED, hours * 3_600) }
                        TextButton(onClick = { onSend(AliveCheckInPayload.Kind.DISABLED, 0) }) {
                            Text(t("alive.sheet.stop"), color = OshiTheme.danger)
                        }
                    }
                }
                Hairline()
                // ------------------------------------------------ theirs
                Text(t("alive.sheet.their_section", peerName), style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                val theirs = state.theirs
                if (theirs == null) {
                    Text(t("alive.sheet.their_none", peerName), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                } else {
                    Text(
                        laneText(theirs, now) + " · " + t("alive.interval.every", aliveSpanText(theirs.intervalSeconds * 1000)),
                        style = OshiTheme.typography.bodySmall,
                        color = if (theirs.overdue(now)) OshiTheme.warning else Ink.soft,
                    )
                }
                Text(t("alive.note.no_location"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Text(t("pc.alive.note.desktop"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.done"), color = OshiTheme.textPrimary, fontWeight = FontWeight.SemiBold) } },
    )
}

/** A filled pin with a white rim and dot, readable on any tile and on the blank grid. */
@Composable
internal fun MapPinMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val brand = OshiTheme.brand
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0.5f * s, 0.96f * s)
            cubicTo(0.26f * s, 0.68f * s, 0.14f * s, 0.52f * s, 0.14f * s, 0.38f * s)
            cubicTo(0.14f * s, 0.18f * s, 0.30f * s, 0.04f * s, 0.5f * s, 0.04f * s)
            cubicTo(0.70f * s, 0.04f * s, 0.86f * s, 0.18f * s, 0.86f * s, 0.38f * s)
            cubicTo(0.86f * s, 0.52f * s, 0.74f * s, 0.68f * s, 0.5f * s, 0.96f * s); close()
        }
        drawPath(path, Color.Black.copy(alpha = 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.10f))
        drawPath(path, brand)
        drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.05f))
        drawCircle(Color.White, radius = s * 0.12f, center = androidx.compose.ui.geometry.Offset(0.5f * s, 0.38f * s))
    }
}
