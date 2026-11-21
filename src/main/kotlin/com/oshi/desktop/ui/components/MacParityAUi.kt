package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.call.media.CallVoiceEffects
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ForwardSelection
import com.oshi.desktop.ui.state.GroupCandidateRow
import com.oshi.desktop.ui.state.MessageRow
import com.oshi.desktop.ui.state.ReactorGroup
import com.oshi.desktop.ui.state.Reactors

/*
 * __MAC_PARITY_A_2027__ The surfaces of six Mac-app features: the reaction pill and its
 * "Reactions" sheet, the multi-recipient forward sheet, the view-once badges, the profile
 * picture card, and the voice-message preview with its effects. Rules live in
 * `ui/state/MacParityA.kt`; this file only draws them.
 */

// ---------------------------------------------------------------------------- who reacted

/** The reaction capsule under a bubble: each glyph with its count (iOS `ReactionBadgeLabel`). Opens the sheet. */
@Composable
fun ReactionPill(row: MessageRow, onOpen: (() -> Unit)?) {
    val counts = row.reactors.associate { it.emoji to it.names.size }
    Row(
        Modifier
            .glass(OshiTheme.pill, OshiTheme.surfaceElevated, OshiTheme.separator, raised = true)
            .then(if (onOpen != null) Modifier.clip(OshiTheme.pill).clickable(role = Role.Button, onClickLabel = t("reaction.see_who.hint"), onClick = onOpen) else Modifier)
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xxs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val keys = row.reactionKeys.ifEmpty { row.reactors.map { it.emoji } }
        for (key in keys) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ReactionGlyph(key, fontSize = 13.sp)
                val n = counts[key] ?: 0
                if (n > 1) Text("$n", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink.soft)
            }
        }
    }
}

/** iOS `ReactorsListView`: one section per reaction, the people under it. */
@Composable
fun ReactorsDialog(groups: List<ReactorGroup>, onDismiss: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                GlyphIcon(Glyph.SMILE, OshiTheme.brand, 20.dp)
                Text(t("reaction.who_reacted"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary)
                Text("${Reactors.total(groups)}", style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
        },
        text = {
            Column(
                Modifier.widthIn(min = 300.dp, max = 420.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
            ) {
                for (g in groups) {
                    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                            ReactionGlyph(g.emoji, fontSize = 20.sp)
                            Text("${g.names.size}", style = OshiTheme.typography.labelMedium, color = Ink.soft)
                        }
                        Column(
                            Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.textPrimary.copy(alpha = 0.05f))
                                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
                        ) {
                            g.names.forEachIndexed { i, name ->
                                if (i > 0) Hairline(inset = 36.dp)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
                                ) {
                                    Monogram(name, name, 28.dp)
                                    Text(name, style = OshiTheme.typography.bodyMedium, color = OshiTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.done"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold) } },
    )
}

// ---------------------------------------------------------------------------- forward

/**
 * __FORWARD_MANY_DESKTOP_2027__ "Forward to" with search and ticks: pick several people, one
 * button. iOS's picker sends to one person per open; this one keeps iOS's search (name, or a
 * pasted key, case kept) and its row (picture, name, short key) and lets the Mac do several.
 */
@Composable
fun ForwardSheet(targets: List<GroupCandidateRow>, onSend: (List<String>) -> Unit, onDismiss: () -> Unit, initiallySelected: List<String> = emptyList()) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initiallySelected) }
    val shown = remember(targets, query) { ForwardSelection.filter(targets, query) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                GlyphIcon(Glyph.FORWARD, OshiTheme.brand, 20.dp)
                Text(t("message.forward_to"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary)
            }
        },
        text = {
            Column(Modifier.widthIn(min = 340.dp, max = 440.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                ToolField(query, { query = it }, t("conv.fa.forward.search"), icon = ToolGlyph.SEARCH)
                if (selected.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                        for (a in selected) {
                            val label = targets.firstOrNull { it.address == a }?.label ?: a.take(8)
                            Row(
                                Modifier.clip(OshiTheme.pill).background(OshiTheme.brand.copy(alpha = 0.12f))
                                    .clickable { selected = ForwardSelection.toggle(selected, a) }
                                    .padding(horizontal = OshiTheme.sm, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(label, fontSize = 12.sp, color = OshiTheme.brand, maxLines = 1)
                                GlyphIcon(Glyph.CLOSE, OshiTheme.brand, 10.dp)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty()) {
                        Text(
                            if (query.isBlank()) t("contacts.empty") else t("conv.fa.forward.no_match"),
                            style = OshiTheme.typography.bodyMedium, color = Ink.soft,
                            modifier = Modifier.fillMaxWidth().padding(OshiTheme.lg),
                        )
                    }
                    for (row in shown) {
                        val on = row.address in selected
                        Row(
                            Modifier.fillMaxWidth().clip(OshiTheme.radiusMd)
                                .background(if (on) OshiTheme.selectedFill else Color.Transparent)
                                .clickable(role = Role.Checkbox) { selected = ForwardSelection.toggle(selected, row.address) }
                                .padding(horizontal = OshiTheme.sm, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
                        ) {
                            GroupAvatar(row.pictureBase64, row.label, row.address, 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(row.label, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = OshiTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    ltrIsolate(if (row.address.length > 16) row.address.take(8) + "…" + row.address.takeLast(6) else row.address),
                                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Ink.soft, maxLines = 1,
                                )
                            }
                            CheckDot(on)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSend(selected) }, enabled = selected.isNotEmpty()) {
                Text(
                    if (selected.isEmpty()) t("forward.send_to") else t("conv.fa.forward.send", selected.size),
                    color = if (selected.isEmpty()) Ink.soft else OshiTheme.brand, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel"), color = OshiTheme.textPrimary, maxLines = 1, softWrap = false) } },
    )
}

/** A round tick, filled violet when on. */
@Composable
fun CheckDot(on: Boolean, size: Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (on) Modifier.background(OshiTheme.brandGradient) else Modifier.border(1.5.dp, Ink.soft.copy(alpha = 0.6f), CircleShape)),
        contentAlignment = Alignment.Center,
    ) { if (on) GlyphIcon(Glyph.CHECK, Color.White, size * 0.62f) }
}

// ---------------------------------------------------------------------------- view once

/** The orange "1" disc the phones put on everything view-once. */
@Composable
fun ViewOnceDisc(size: Dp = 18.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(ViewOnceOrange),
        contentAlignment = Alignment.Center,
    ) { Text("1", color = Color.White, fontSize = (size.value * 0.58f).sp, fontWeight = FontWeight.Bold) }
}

/** iOS orange, used only for view-once — a colour that means one thing. Dark ink on it is not needed: the "1" is bold white at ≥ 10 sp. */
val ViewOnceOrange = Color(0xFFF2770D)

/** Under OUR view-once attachment: "View Once" (iOS `viewOnceSenderView`). */
@Composable
fun ViewOnceBadge(onBrand: Boolean) {
    Row(
        Modifier.clip(OshiTheme.pill).background(if (onBrand) Color.Black.copy(alpha = 0.22f) else ViewOnceOrange.copy(alpha = 0.14f))
            .padding(horizontal = OshiTheme.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ViewOnceDisc(14.dp)
        Text(t("media_picker.view_once"), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (onBrand) Color.White else ViewOnceOrange)
    }
}

/** A view-once attachment we already opened: the bytes are gone (iOS `viewOnceViewedPlaceholder`). */
@Composable
fun ViewOnceViewedPill() {
    Row(
        Modifier.clip(OshiTheme.radiusBubble).background(Ink.soft.copy(alpha = 0.16f))
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        GlyphIcon(Glyph.EYE_SLASH, Ink.soft, 18.dp)
        Text(t("media.view_once.viewed"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
    }
}

/** The switch above the "+" panel and in the voice preview: send the next photo, video or voice note view-once. */
@Composable
fun ViewOnceToggle(on: Boolean, description: String, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(OshiTheme.radiusMd)
            .background(if (on) ViewOnceOrange.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Switch) { onToggle(!on) }
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        ViewOnceDisc(22.dp)
        Column(Modifier.weight(1f)) {
            Text(t("media_picker.view_once"), style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Text(description, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        androidx.compose.material3.Switch(
            checked = on, onCheckedChange = onToggle,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = ViewOnceOrange),
        )
    }
}

// ---------------------------------------------------------------------------- profile picture

/** __PROFILE_PHOTOS_DESKTOP_2027__ Our picture with Change / Remove — the phones' profile header. */
@Composable
fun OwnPhotoAvatar(pictureBase64: String?, name: String, address: String, size: Dp, onChange: (() -> Unit)?, onRemove: (() -> Unit)?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        Box(
            Modifier.clip(CircleShape).then(if (onChange != null) Modifier.clickable(onClickLabel = t("profile.photo.change"), onClick = onChange) else Modifier),
        ) {
            GroupAvatar(pictureBase64, name, address, size)
            if (onChange != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(size * 0.34f).clip(CircleShape).background(OshiTheme.background).padding(2.dp)
                        .clip(CircleShape).background(OshiTheme.brandGradient),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.CAMERA, Color.White, size * 0.2f) }
            }
        }
        if (onRemove != null && pictureBase64 != null) {
            Text(
                t("profile.photo.remove"), fontSize = 11.sp, color = OshiTheme.danger,
                modifier = Modifier.clip(OshiTheme.pill).clickable(onClick = onRemove).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- voice preview

/** The order and accent of the voice effects in the preview (iOS `VoiceEffect` + `accent`). */
internal val VoiceEffectAccents: List<Pair<CallVoiceEffects.Effect, Color>> = listOf(
    CallVoiceEffects.Effect.NONE to Color(0xFF7C4DFF),
    CallVoiceEffects.Effect.ROBOT to Color(0xFF00A7C4),
    CallVoiceEffects.Effect.ALIEN to Color(0xFF2FA84F),
    CallVoiceEffects.Effect.DEEP to Color(0xFF5856D6),
    CallVoiceEffects.Effect.CHIPMUNK to Color(0xFFF2770D),
    CallVoiceEffects.Effect.ECHO to Color(0xFFE0457B),
    CallVoiceEffects.Effect.REVERB to Color(0xFFAF52DE),
    CallVoiceEffects.Effect.ANONYMOUS to Color(0xFF6E6E73),
)

/** The displayed name: "Normal" for none (iOS `audio.effect.normal`), the call names otherwise. */
fun voiceEffectName(e: CallVoiceEffects.Effect): String =
    if (e == CallVoiceEffects.Effect.NONE) t("audio.effect.normal") else t(e.key)

/**
 * __VOICE_EFFECTS_DESKTOP_2027__ The voice-message preview: listen, pick a voice, optionally
 * view-once, then send or bin it — iOS's voice preview sheet, desktop-shaped (a panel over the
 * composer). The effect is rendered into the recording BEFORE it is encoded, so what a phone
 * plays is exactly what was previewed; nothing about the effect goes on the wire.
 */
@Composable
fun VoicePreviewPanel(
    durationLabel: String,
    effect: CallVoiceEffects.Effect,
    onEffect: (CallVoiceEffects.Effect) -> Unit,
    playing: Boolean,
    processing: Boolean,
    onPlay: () -> Unit,
    viewOnce: Boolean,
    onViewOnce: ((Boolean) -> Unit)?,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
) {
    val accent = VoiceEffectAccents.firstOrNull { it.first == effect }?.second ?: OshiTheme.brand
    Column(
        Modifier.fillMaxWidth().padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)
            .glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, OshiTheme.separator, raised = true)
            .padding(OshiTheme.md),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(accent).clickable(enabled = !processing, role = Role.Button,
                    onClickLabel = if (playing) t("audio.stop") else t("audio.play"), onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(Color.White))
                else androidx.compose.foundation.Canvas(Modifier.size(18.dp).padding(start = 2.dp)) {
                    // A play triangle: no glyph in either icon set draws one.
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, 0f); lineTo(size.width, size.height / 2f); lineTo(0f, size.height); close()
                    }
                    drawPath(p, Color.White)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(t("audio.voice_message"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
                Text(
                    if (processing) t("audio.processing_effect", voiceEffectName(effect))
                    else listOf(durationLabel, if (effect == CallVoiceEffects.Effect.NONE) t("audio.effect.normal.desc") else voiceEffectName(effect)).bidiJoin(),
                    style = OshiTheme.typography.bodySmall, color = if (processing) accent else Ink.soft,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            Text(t("audio.voice_effect"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                for ((e, tint) in VoiceEffectAccents) {
                    val on = e == effect
                    Text(
                        voiceEffectName(e),
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else Ink.strong,
                        maxLines = 1,
                        modifier = Modifier.clip(OshiTheme.pill)
                            .background(if (on) tint else OshiTheme.textPrimary.copy(alpha = 0.06f))
                            .clickable(enabled = !processing, role = Role.RadioButton) { onEffect(e) }
                            .padding(horizontal = OshiTheme.md, vertical = 7.dp),
                    )
                }
            }
        }
        if (onViewOnce != null) ViewOnceToggle(viewOnce, t("audio.view_once.hint"), onViewOnce)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            ToolButton(t("common.delete"), icon = ToolGlyph.TRASH, style = ToolButtonStyle.DANGER, onClick = onDiscard)
            Spacer(Modifier.weight(1f))
            ToolButton(t("common.send"), icon = ToolGlyph.SEND, style = ToolButtonStyle.PRIMARY, enabled = !processing, onClick = onSend)
        }
    }
}
