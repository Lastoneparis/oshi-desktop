package com.oshi.desktop.ui.components

import com.oshi.desktop.i18n.catalogKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.dt
import androidx.compose.animation.core.animateFloat
import com.oshi.desktop.i18n.t
import com.oshi.desktop.msg.CallSummary
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.call.media.CallVoiceEffects
import com.oshi.desktop.ui.state.CallQuality
import com.oshi.desktop.ui.state.CallScreenModel.Audio
import com.oshi.desktop.ui.state.CallScreenModel.CallScreen
import com.oshi.desktop.ui.state.CallScreenModel.Phase
import kotlinx.coroutines.delay

/**
 * The call screen (__DESKTOP_CALL_UI_2026_09_23__): drawn OVER whatever the window shows,
 * like iOS's full-screen incoming call and `VoiceCallView`. Every word is an iOS key
 * (`call.*`, `accessibility.call.*`) except the three audio states iOS has no key for, which
 * are `desktop.call.*` (PARITY.md row 1.5).
 *
 * Nothing here decides anything — [screen] comes from `CallScreenModel`, which is tested.
 */
@Composable
fun CallOverlay(
    screen: CallScreen,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
    /** Wall clock for the duration; injectable so a render is deterministic. */
    nowMs: () -> Long = System::currentTimeMillis,
    modifier: Modifier = Modifier,
    /** PARITY.md row 2.1-v: the live call's pictures. Polled, never pushed. */
    video: () -> VideoFrames? = { null },
    onToggleCamera: () -> Unit = {},
    onAnswerVideoRequest: (accept: Boolean, shareCamera: Boolean) -> Unit = { _, _ -> },
    /** __CALL_PARITY_2026_09_23__ collapse to [CallMiniBar] (iOS `call.minimize`). */
    onMinimize: () -> Unit = {},
    /** A camera was picked in the devices menu: reopen it now. */
    onSwitchCamera: () -> Unit = {},
    /**
     * __CALL_VOICE_EFFECTS_2026_10_28__ The live voice effect was picked — iOS's
     * `VoiceEffectPickerView`, desktop-shaped. Defaulted so every existing call site and
     * every render test keeps compiling without claiming the feature.
     */
    onPickVoiceEffect: (CallVoiceEffects.Effect) -> Unit = {},
) = ProvideLocaleDirection {
    if (screen.video && screen.phase == Phase.CONNECTED) {
        VideoStage(screen, onHangUp, onToggleMute, onToggleCamera, onAnswerVideoRequest, nowMs, modifier, video, onMinimize, onSwitchCamera)
        return@ProvideLocaleDirection
    }
    Box(
        modifier
            .fillMaxSize()
            // __OLED_BLACK_2026_11_16__ the call screen was already dark; OLED takes it to #000.
            .background(Brush.verticalGradient(listOf(OshiTheme.palette.callBackdropTop, OshiTheme.palette.callBackdropBottom)))
            // Swallow clicks: nothing under a call screen is reachable by accident.
            .clickable(enabled = true, indication = null, interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }) {},
        contentAlignment = Alignment.Center,
    ) {
        // __DESKTOP_TOOLS_2027__ A full-window call screen: the security and line badges
        // across the top, the person in the middle, one control dock at the bottom.
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(OshiTheme.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            CallChip(t("call.incoming.secure"), glyph = ToolGlyph.LOCK)
            if (screen.phase == Phase.CONNECTED) {
                screen.carrier?.let { c -> CallChip(c.label, glyph = ToolGlyph.LINK, description = "${t("accessibility.call.transport")}: ${c.label}") }
                screen.quality?.takeIf { !screen.reconnecting }?.let { q ->
                    val shown = screen.latencyMs?.let { "${t(q.key)} · $it ms" } ?: t(q.key)
                    CallChip(shown, dot = callQualityColor(q), description = "${t("accessibility.call.quality")}: $shown")
                }
            }
            Spacer(Modifier.weight(1f))
            if (screen.phase == Phase.CONNECTED || screen.phase == Phase.OUTGOING) TopActions(screen, onMinimize, onSwitchCamera, Modifier)
        }
        Column(
            Modifier.widthIn(max = 560.dp).padding(OshiTheme.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PulsingAvatar(screen)
            Spacer(Modifier.height(OshiTheme.xl))
            Text(
                screen.label,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(OshiTheme.sm))
            StatusLine(screen, nowMs, onPickVoiceEffect = {})
            screen.problem?.takeIf { screen.phase == Phase.ENDED }?.let {
                Spacer(Modifier.height(OshiTheme.sm))
                Text(t("pc.call.problem", it), fontSize = 12.sp, color = Color(0xFFFFB4AE), textAlign = TextAlign.Center)
            }
            if (screen.phase == Phase.CONNECTED && screen.micBlocked) {
                Spacer(Modifier.height(OshiTheme.md))
                MicBlockedHint(screen)
            }
            if (screen.phase == Phase.CONNECTED && screen.upgradeRequested) {
                Spacer(Modifier.height(OshiTheme.lg))
                UpgradeBanner(screen, onAnswerVideoRequest)
            }
            Spacer(Modifier.height(120.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = OshiTheme.xxl)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(36.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Buttons(screen, onAnswer, onDecline, onHangUp, onToggleMute, onDismiss, onToggleCamera, spacing = 32.dp, onPickVoiceEffect = onPickVoiceEffect)
        }
    }
}

/**
 * __CALL_PARITY_2026_09_23__ The top-right corner of a live call: the devices menu (the
 * desktop's speaker/earpiece toggle and camera switch) and minimize.
 */
@Composable
private fun TopActions(screen: CallScreen, onMinimize: () -> Unit, onSwitchCamera: () -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (screen.phase == Phase.CONNECTED) CallDevicesMenu(onSwitchCamera)
        CallIconButton(ToolGlyph.MINIMIZE, t("call.minimize"), onMinimize)
    }
}

/**
 * Microphone, speaker and camera, by name ([com.oshi.desktop.call.media.CallDevices]). Audio
 * applies from the next call and the menu says so; a camera applies at once.
 */
@Composable
private fun CallDevicesMenu(onSwitchCamera: () -> Unit) {
    val devices = com.oshi.desktop.call.media.CallDevices
    var open by remember { mutableStateOf(false) }
    var mics by remember { mutableStateOf(emptyList<String>()) }
    var speakers by remember { mutableStateOf(emptyList<String>()) }
    var cameras by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var tick by remember { mutableStateOf(0) }
    Box {
        CallIconButton(ToolGlyph.SETTINGS, t("pc.call.devices")) {
            // Enumerated on open, off the UI thread's hot path only in the sense that it
            // is on demand: device lists change when a headset is plugged in.
            mics = devices.microphones(); speakers = devices.speakers(); cameras = devices.cameras()
            open = true
        }
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            key(tick) {
                DeviceSection(t("pc.call.devices.microphone"), mics.map { it to it }, devices.microphone) {
                    devices.microphone = it; tick++
                }
                DeviceSection(t("call.speaker"), speakers.map { it to it }, devices.speaker) {
                    devices.speaker = it; tick++
                }
                MenuNote(t("pc.call.devices.nextCall"))
                if (cameras.isEmpty() && System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
                    MenuHeader(t("ai_attach_camera"))
                    MenuNote(t("pc.call.devices.cameraDefaultOnly"))
                } else {
                    DeviceSection(t("ai_attach_camera"), cameras, devices.camera) {
                        devices.camera = it; tick++; onSwitchCamera()
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceSection(title: String, options: List<Pair<String, String>>, chosen: String?, onPick: (String?) -> Unit) {
    MenuHeader(title)
    DeviceItem(t("pc.call.devices.default"), chosen == null) { onPick(null) }
    for ((id, label) in options) DeviceItem(label, chosen == id) { onPick(id) }
}

@Composable
private fun MenuHeader(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OshiTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
private fun MenuNote(text: String) {
    Text(text, fontSize = 11.sp, color = OshiTheme.colors.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 14.dp, vertical = 4.dp))
}

@Composable
private fun DeviceItem(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text((if (selected) "✓  " else "     ") + label, fontSize = 13.sp, maxLines = 1) },
        onClick = onClick,
    )
}

/**
 * __CALL_PARITY_2026_09_23__ A minimized call: one bar across the top of the window with who,
 * how long, and the way back — iOS's minimized call pill. The app stays usable under it.
 */
@Composable
fun CallMiniBar(screen: CallScreen, onRestore: () -> Unit, onHangUp: () -> Unit, nowMs: () -> Long = System::currentTimeMillis, modifier: Modifier = Modifier) = ProvideLocaleDirection {
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(screen.connectedAtMs) { while (true) { now = nowMs(); delay(1_000) } }
    // __OLED_GLASS_2026_11_16__ The bar was `success` (#34C759 / #30D158) under white
    // text: about 2:1, the least legible line in the window. `callBar` is a green that
    // holds white at AA in light and dark; OLED draws a green-tinted glass strip instead.
    val glass = OshiTheme.glass
    Row(
        modifier.fillMaxWidth()
            .then(
                if (glass == null) Modifier.background(OshiTheme.palette.callBar)
                else Modifier.background(OshiTheme.palette.callBar)
                    .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
                    .drawBehind {
                        drawLine(OshiTheme.success.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f),
                            androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
                    }
            )
            .clickable(onClick = onRestore)
            .padding(horizontal = OshiTheme.lg, vertical = 8.dp)
            .semantics { contentDescription = t("call.minimize.restore") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildString {
                append(screen.label)
                if (screen.phase == Phase.CONNECTED) append("  ·  ").append(CallSummary.duration(screen.durationSeconds(now)))
                if (screen.reconnecting) append("  ·  ").append(t("call.quality.reconnecting"))
            },
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (glass != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(OshiTheme.success))
            Spacer(Modifier.width(OshiTheme.sm))
        }
        Text(t("call.minimize.restore"), fontSize = 13.sp, color = Color.White)
        Spacer(Modifier.width(OshiTheme.md))
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(OshiTheme.danger).clickable(onClick = onHangUp)
                .semantics { contentDescription = t("accessibility.call.end") },
            contentAlignment = Alignment.Center,
        ) { CallGlyphIcon(CallGlyph.HANG_UP, Color.White, 16.dp) }
    }
}

/** The microphone delivers digital zero: OS microphone access is off (Windows privacy switch). */
@Composable
private fun MicBlockedHint(screen: CallScreen) {
    if (!screen.micBlocked) return
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val text = when {
        os.contains("win") -> t("pc.call.micBlocked.windows")
        os.contains("mac") -> t("pc.call.micBlocked.mac")
        else -> t("pc.call.micBlocked.other")
    }
    Text(
        text, fontSize = 12.sp, color = Color(0xFFFFB4AE), textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 520.dp).padding(bottom = OshiTheme.md),
    )
}

@Composable
private fun StatusLine(
    screen: CallScreen,
    nowMs: () -> Long,
    onPickVoiceEffect: ((CallVoiceEffects.Effect) -> Unit)? = null,
) {
    val text = when (screen.phase) {
        Phase.INCOMING -> if (screen.video) t("call.incoming.video") else t("call.incoming.voice")
        Phase.OUTGOING -> if (screen.ringingBack) t("call.state.ringing") else t("call.state.connecting")
        Phase.ANSWERING -> t("call.state.connecting")
        Phase.CONNECTED -> when {
            screen.reconnecting -> t("call.quality.reconnecting")
            screen.audio == Audio.CONNECTING -> t("pc.call.audio.connecting")
            screen.audio == Audio.NONE -> t("pc.call.audio.none")
            else -> t("call.state.on_call")
        }
        Phase.ENDED -> t(screen.endedKey ?: catalogKey("call.ended.hungup"))
    }
    Text(text, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)

    if (screen.phase == Phase.CONNECTED) {
        // Its own one-second clock, so the rest of the window does not recompose per tick.
        var now by remember { mutableStateOf(nowMs()) }
        LaunchedEffect(screen.connectedAtMs) {
            while (true) { now = nowMs(); delay(1_000) }
        }
        Spacer(Modifier.height(OshiTheme.xs))
        Text(
            CallSummary.duration(screen.durationSeconds(now)),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.semantics { contentDescription = t("accessibility.call.duration") },
        )
        // The transport and quality badges (iOS) moved to the top bar of the call screen;
        // the video stage keeps them under the timer, where its top bar is the picture.
        if (onPickVoiceEffect == null) {
            screen.carrier?.let { c ->
                Spacer(Modifier.height(2.dp))
                Text("🔒 ${c.label}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.semantics { contentDescription = "${t("accessibility.call.transport")}: ${c.label}" })
            }
            screen.quality?.takeIf { !screen.reconnecting }?.let { q ->
                val shown = screen.latencyMs?.let { "${t(q.key)} · $it ms" } ?: t(q.key)
                Spacer(Modifier.height(2.dp))
                Text(shown, fontSize = 11.sp, color = callQualityColor(q),
                    modifier = Modifier.semantics { contentDescription = "${t("accessibility.call.quality")}: $shown" })
            }
        }
    }
}

/**
 * __CALL_VOICE_EFFECTS_2026_10_28__ The live voice-effect picker — iOS's
 * `VoiceEffectPickerView`, desktop-shaped and clickable with a mouse.
 *
 * Eight short chips rather than a dropdown, because the set is fixed, small, and the
 * whole point is to try one and hear it: a menu that has to be opened, read and dismissed
 * for each attempt is the wrong shape for that. The current one is filled in, so the state
 * is visible without hovering.
 *
 * Shown only while CONNECTED. Before that there is no audio to colour, and offering the
 * control over a ringing call invites exactly the "I set it and it reverted" confusion the
 * lane's remembered choice exists to prevent.
 *
 * Names come from the catalog (`call.voice_effect.*`, and `common.off` for none) — all
 * eight were already translated 34/34, extracted from iOS when row 1.5 was done.
 */
@Composable
private fun VoiceEffectButton(screen: CallScreen, onPick: (CallVoiceEffects.Effect) -> Unit) {
    // __CALL_VOICE_EFFECTS_2026_10_28__ iOS's `VoiceEffectPickerView`, now one dock button
    // with a menu instead of eight chips under the timer. Names: `call.voice_effect.*`.
    var open by remember { mutableStateOf(false) }
    val on = screen.voiceEffect != CallVoiceEffects.Effect.entries.first()
    Box {
        RoundAction(
            if (on) t(screen.voiceEffect.key) else t("call.effect"), t("audio.voice_effect"),
            if (on) Color.White else Color.White.copy(alpha = 0.18f), CallGlyph.MIC, { open = true },
            iconTint = if (on) Color.Black else Color.White, toolGlyph = ToolGlyph.WAVE,
        )
        OshiDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader(t("call.effect.picker.title"))
            for (e in CallVoiceEffects.Effect.entries) DeviceItem(t(e.key), e == screen.voiceEffect) { onPick(e); open = false }
        }
    }
}

/** A badge in the call screen's top bar. */
@Composable
private fun CallChip(text: String, glyph: ToolGlyph? = null, dot: Color? = null, description: String = text) {
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        glyph?.let { ToolIcon(it, Color.White.copy(alpha = 0.85f), 13.dp) }
        dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
        Text(text, fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
    }
}

/** A round glass icon button with a tooltip, for the call screen's corners. */
@Composable
private fun CallIconButton(glyph: ToolGlyph, label: String, onClick: () -> Unit) {
    WithTooltip(label) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { ToolIcon(glyph, Color.White, 18.dp) }
    }
}

/** The avatar, with a slow ring while it rings. */
@Composable
private fun PulsingAvatar(screen: CallScreen) {
    val ringing = screen.phase == Phase.INCOMING || screen.phase == Phase.OUTGOING
    val pulse = if (ringing && !com.oshi.desktop.ui.MotionPreference.systemReducedMotion) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition()
        tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(1600))).value
    } else 0f
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        if (ringing) {
            Box(Modifier.size((140 + 60 * pulse).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f * (1f - pulse))))
            Box(Modifier.size(160.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
        }
        Monogram(screen.label, screen.peer, 140.dp)
    }
}

/**
 * __CALL_QUALITY_TIERS_2026_10_28__ iOS's own colours for the tier
 * (`ConnectionQuality.color`): green, green, yellow, orange, red. Kept as one mapping so
 * the badge cannot disagree with the tier it is naming.
 */
private fun callQualityColor(q: CallQuality): Color = when (q) {
    CallQuality.EXCELLENT, CallQuality.GOOD -> Color(0xFF7BE38B)
    CallQuality.FAIR -> Color(0xFFFFD166)
    CallQuality.POOR -> Color(0xFFFFA14A)
    CallQuality.VERY_POOR, CallQuality.DISCONNECTED -> Color(0xFFFF6B6B)
    // Never reached from the badge — the status line owns this state — but a `when` that
    // cannot be exhaustive by accident is one less way for a new tier to render invisibly.
    CallQuality.RECONNECTING -> Color(0xFFFFD166)
}

@Composable
private fun Buttons(
    screen: CallScreen,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
    onToggleCamera: () -> Unit = {},
    spacing: Dp = 56.dp,
    onPickVoiceEffect: ((CallVoiceEffects.Effect) -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
        when (screen.phase) {
            Phase.INCOMING -> {
                RoundAction(t("call.decline"), t("accessibility.call.decline"), OshiTheme.danger, CallGlyph.HANG_UP, onDecline)
                RoundAction(
                    t("call.accept"),
                    if (screen.video) t("accessibility.call.accept.video") else t("accessibility.call.accept.voice"),
                    OshiTheme.success,
                    if (screen.video) CallGlyph.CAMERA else CallGlyph.PHONE,
                    onAnswer,
                )
            }
            Phase.OUTGOING, Phase.ANSWERING ->
                RoundAction(t("accessibility.call.end"), t("accessibility.call.end"), OshiTheme.danger, CallGlyph.HANG_UP, onHangUp)
            Phase.CONNECTED -> {
                RoundAction(
                    if (screen.muted) t("call.unmute") else t("call.mute"),
                    t("accessibility.call.mute"),
                    if (screen.muted) Color.White else Color.White.copy(alpha = 0.18f),
                    if (screen.muted) CallGlyph.MIC_OFF else CallGlyph.MIC,
                    onToggleMute,
                    iconTint = if (screen.muted) Color.Black else Color.White,
                )
                // Camera: in a video call it turns ours on/off; in a voice call it asks
                // the peer to switch to video (0x0E+0x01), as the phones' button does.
                RoundAction(
                    if (screen.video) t("call.video.button") else t("call.action.video"),
                    if (screen.cameraOn) t("accessibility.call.camera_off") else t("accessibility.call.camera_on"),
                    if (screen.cameraOn) Color.White else Color.White.copy(alpha = 0.18f),
                    if (screen.cameraOn) CallGlyph.CAMERA else CallGlyph.CAMERA_OFF,
                    onToggleCamera,
                    iconTint = if (screen.cameraOn) Color.Black else Color.White,
                )
                if (onPickVoiceEffect != null) VoiceEffectButton(screen, onPickVoiceEffect)
                RoundAction(t("accessibility.call.end"), t("accessibility.call.end"), OshiTheme.danger, CallGlyph.HANG_UP, onHangUp)
            }
            Phase.ENDED ->
                RoundAction(t("common.close"), t("common.close"), Color.White.copy(alpha = 0.18f), CallGlyph.CLOSE, onDismiss)
        }
    }
}

@Composable
private fun RoundAction(
    label: String,
    description: String,
    fill: Color,
    glyph: CallGlyph,
    onClick: () -> Unit,
    size: Dp = 68.dp,
    iconTint: Color = Color.White,
    toolGlyph: ToolGlyph? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // OLED: the neutral (translucent white) controls are raised glass; the coloured ones
        // keep their fill and gain the lit rim, so all three read as the same material.
        val glass = OshiTheme.glass
        Box(
            Modifier
                .size(size)
                .then(
                    when {
                        glass == null -> Modifier.clip(CircleShape).background(fill)
                        fill.alpha < 0.5f -> Modifier.glass(CircleShape, fill, raised = true)
                        else -> Modifier.clip(CircleShape).background(fill).glassEdge(CircleShape)
                    }
                )
                .focusRing(OshiTheme.pill)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { if (toolGlyph != null) ToolIcon(toolGlyph, iconTint, 28.dp) else CallGlyphIcon(glyph, iconTint, 28.dp) }
        Spacer(Modifier.height(OshiTheme.sm))
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
    }
}

/** The four glyphs a call needs and the shared set does not draw. Stroked like [GlyphIcon]. */
enum class CallGlyph { PHONE, HANG_UP, MIC, MIC_OFF, CLOSE, CAMERA, CAMERA_OFF }

@Composable
fun CallGlyphIcon(glyph: CallGlyph, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun handset(degrees: Float) = rotate(degrees, pivot = center) {
            // A classic receiver: two ear/mouth cups joined by a curved grip.
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.22f * s, 0.30f * s)
                cubicTo(0.20f * s, 0.62f * s, 0.38f * s, 0.80f * s, 0.70f * s, 0.78f * s)
            }
            drawPath(p, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        when (glyph) {
            CallGlyph.PHONE -> handset(0f)
            CallGlyph.HANG_UP -> handset(135f)
            CallGlyph.MIC, CallGlyph.MIC_OFF -> {
                drawRoundRect(
                    tint,
                    topLeft = androidx.compose.ui.geometry.Offset(0.38f * s, 0.14f * s),
                    size = androidx.compose.ui.geometry.Size(0.24f * s, 0.42f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.12f * s),
                )
                drawArc(
                    tint, 0f, 180f, false,
                    topLeft = androidx.compose.ui.geometry.Offset(0.28f * s, 0.30f * s),
                    size = androidx.compose.ui.geometry.Size(0.44f * s, 0.40f * s),
                    style = stroke,
                )
                drawLine(tint, androidx.compose.ui.geometry.Offset(0.5f * s, 0.70f * s), androidx.compose.ui.geometry.Offset(0.5f * s, 0.84f * s), strokeWidth = w)
                if (glyph == CallGlyph.MIC_OFF) {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(0.20f * s, 0.16f * s), androidx.compose.ui.geometry.Offset(0.80f * s, 0.84f * s), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            CallGlyph.CLOSE -> {
                drawLine(tint, androidx.compose.ui.geometry.Offset(0.28f * s, 0.28f * s), androidx.compose.ui.geometry.Offset(0.72f * s, 0.72f * s), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(0.72f * s, 0.28f * s), androidx.compose.ui.geometry.Offset(0.28f * s, 0.72f * s), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            CallGlyph.CAMERA, CallGlyph.CAMERA_OFF -> {
                // A camcorder: rounded body plus a lens wedge on the right.
                drawRoundRect(
                    tint,
                    topLeft = androidx.compose.ui.geometry.Offset(0.14f * s, 0.30f * s),
                    size = androidx.compose.ui.geometry.Size(0.48f * s, 0.40f * s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.08f * s),
                    style = stroke,
                )
                val lens = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0.66f * s, 0.44f * s); lineTo(0.86f * s, 0.32f * s)
                    lineTo(0.86f * s, 0.68f * s); lineTo(0.66f * s, 0.56f * s); close()
                }
                drawPath(lens, tint, style = stroke)
                if (glyph == CallGlyph.CAMERA_OFF) {
                    drawLine(tint, androidx.compose.ui.geometry.Offset(0.14f * s, 0.18f * s), androidx.compose.ui.geometry.Offset(0.86f * s, 0.82f * s), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

/**
 * After a call, when `CallRatingClient.callDidEnd` says this one is worth asking about —
 * the same policy and the same `call.rating.*` words as iOS's `CallRatingView`. One tap on a
 * star picks, Send submits, Not now dismisses; nothing here is shown for a call the policy
 * skipped.
 */
@Composable
fun CallRatingPrompt(onSubmit: (Int) -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) = ProvideLocaleDirection {
    var stars by remember { mutableStateOf(0) }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp)
                .glassFloating(OshiTheme.radiusMd, OshiTheme.background, OshiTheme.cardOutline).padding(OshiTheme.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(t("call.rating.title"), style = OshiTheme.typography.titleMedium, color = Ink.strong, textAlign = TextAlign.Center)
            Spacer(Modifier.height(OshiTheme.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                for (n in 1..5) {
                    Text(
                        if (n <= stars) "★" else "☆",
                        fontSize = 32.sp,
                        color = if (n <= stars) OshiTheme.brand else Ink.soft,
                        modifier = Modifier.clickable { stars = n }
                            .semantics { contentDescription = t("call.rating.star.accessibility", n) },
                    )
                }
            }
            Spacer(Modifier.height(OshiTheme.md))
            Text(t("call.rating.privacy"), fontSize = 11.sp, color = Ink.soft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(OshiTheme.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xl)) {
                TextAction(t("call.rating.not_now")) { onNotNow() }
                if (stars > 0) TextAction(t("call.rating.send")) { onSubmit(stars) }
            }
        }
    }
}

// ====================================================================== video (row 2.1-v)

/** What the video stage reads each frame. Implemented over `CallVideoSession` by the window. */
interface VideoFrames {
    val remoteCount: Long
    fun remote(): com.oshi.desktop.call.video.VideoImage?
    val localCount: Long
    fun local(): com.oshi.desktop.call.video.VideoImage?
}

fun com.oshi.desktop.call.video.VideoImage.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val img = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, argb, 0, width)
    return img.toComposeImageBitmap()
}

/** Polls [read] at display rate and yields a fresh bitmap only when the count moves. */
@Composable
private fun rememberVideoBitmap(
    count: () -> Long?,
    read: () -> com.oshi.desktop.call.video.VideoImage?,
): androidx.compose.ui.graphics.ImageBitmap? {
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        var last = -1L
        while (true) {
            val c = count()
            if (c == null) { if (bitmap != null) bitmap = null }
            else if (c != last) { last = c; bitmap = read()?.toImageBitmap() }
            delay(33)
        }
    }
    return bitmap
}

/**
 * The connected video call, laid out like Meet or Telegram Desktop: the peer fills the
 * window, our own camera sits in a mirrored picture-in-picture at the top right, the name
 * and timer top left, the controls in a bar at the bottom.
 */
@Composable
private fun VideoStage(
    screen: CallScreen,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onAnswerVideoRequest: (Boolean, Boolean) -> Unit,
    nowMs: () -> Long,
    modifier: Modifier,
    video: () -> VideoFrames?,
    onMinimize: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
) {
    val remote = rememberVideoBitmap({ video()?.remoteCount?.takeIf { it > 0 } }) { video()?.remote() }
    val local = rememberVideoBitmap({ if (screen.cameraOn) video()?.localCount?.takeIf { it > 0 } else null }) { video()?.local() }
    Box(
        modifier.fillMaxSize().background(if (OshiTheme.palette === com.oshi.desktop.ui.OshiPalette.OLED) Color.Black else Color(0xFF07070C))
            .clickable(enabled = true, indication = null, interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }) {},
    ) {
        if (remote != null && !screen.remoteCameraOff) {
            androidx.compose.foundation.Image(
                remote,
                contentDescription = screen.label,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Monogram(screen.label, screen.peer, 112.dp)
                Spacer(Modifier.height(OshiTheme.md))
                Text(
                    if (screen.remoteCameraOff) t("call.video.camera_off") else t("call.video.waiting"),
                    fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f),
                )
            }
        }

        // Name, status, timer.
        Column(
            Modifier.align(Alignment.TopStart).padding(OshiTheme.lg)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(screen.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
            StatusLine(screen, nowMs)
        }

        // iOS VideoCallView: the picture stopped, and whether the voice still carries.
        if (screen.videoStalled && remote != null && !screen.remoteCameraOff) {
            Column(
                Modifier.align(Alignment.Center)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t("call.video.rx.stalled"), fontSize = 14.sp, color = Color.White)
                if (screen.audioFlowing) Text(t("call.video.rx.audio_ok"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
            }
        }

        // Devices + minimize, above the self view.
        TopActions(screen, onMinimize, onSwitchCamera, Modifier.align(Alignment.TopEnd).padding(top = OshiTheme.lg, end = OshiTheme.lg))

        // Self view.
        Box(
            Modifier.align(Alignment.TopEnd).padding(start = OshiTheme.lg, end = OshiTheme.lg, top = OshiTheme.lg + 44.dp)
                .size(width = 224.dp, height = 126.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(Color(0xFF1E1E2A)),
            contentAlignment = Alignment.Center,
        ) {
            if (local != null && screen.cameraOn) {
                androidx.compose.foundation.Image(
                    local, contentDescription = t("accessibility.call.camera_off"),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                    CallGlyphIcon(CallGlyph.CAMERA_OFF, Color.White.copy(alpha = 0.8f), 26.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        screen.cameraProblem?.let { t("error.camera_unavailable") } ?: t("call.video.camera_off"),
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = OshiTheme.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicBlockedHint(screen)
            screen.cameraProblem?.let {
                Text(
                    t("pc.call.problem", it), fontSize = 12.sp, color = Color(0xFFFFB4AE),
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp).padding(bottom = OshiTheme.md),
                )
            }
            if (screen.upgradeRequested) {
                UpgradeBanner(screen, onAnswerVideoRequest)
                Spacer(Modifier.height(OshiTheme.md))
            }
            Box(
                Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Buttons(screen, {}, {}, onHangUp, onToggleMute, {}, onToggleCamera, spacing = 36.dp)
            }
        }
    }
}

/** The peer asked for video: share my camera (`0x02`), watch only (`0x05`) or decline (`0x03`). */
@Composable
private fun UpgradeBanner(screen: CallScreen, onAnswer: (Boolean, Boolean) -> Unit) {
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.12f)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t("call.video_upgrade.message", screen.label), fontSize = 14.sp, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BannerButton(t("call.video_upgrade.accept_share"), OshiTheme.success) { onAnswer(true, true) }
            BannerButton(t("call.video_upgrade.watch_only"), Color.White.copy(alpha = 0.2f)) { onAnswer(true, false) }
            BannerButton(t("call.video_upgrade.decline"), OshiTheme.danger) { onAnswer(false, false) }
        }
    }
}

@Composable
private fun BannerButton(label: String, fill: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(fill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium) }
}

/** [VideoFrames] over the live call's `CallVideoSession`. */
class CallVideoFrames(private val v: com.oshi.desktop.call.video.CallVideoSession) : VideoFrames {
    override val remoteCount: Long get() = v.remoteFrameCount
    override fun remote() = v.remoteFrame
    override val localCount: Long get() = v.localFrameCount
    override fun local() = v.localFrame
}
