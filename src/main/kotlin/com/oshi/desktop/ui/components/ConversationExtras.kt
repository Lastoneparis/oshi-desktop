package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ConversationRow

/**
 * __CONVERSATION_PIN_MUTE_2027__ Pieces the conversation surfaces share: the list row's menu,
 * the extra icons the chrome needs (verify, export, pin-to-top) and the mascot empty state.
 *
 * Kept in their own file so the shared [Glyph] enum and `Foundation.kt` stay untouched by
 * this pass: other screens are being reworked at the same time, and an enum both sides add
 * to is a merge conflict for nothing.
 */

// ---------------------------------------------------------------------------- input

/** Right-click (secondary press) anywhere on the element. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onSecondaryClick(action: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { e -> if (e.buttons.isSecondaryPressed) action() }

// ---------------------------------------------------------------------------- list row menu

/** What a conversation row's menu can do. Null = not offered for this row. */
class ConversationRowMenuActions(
    val onSetPinned: (Boolean) -> Unit,
    val onSetMuted: ((Boolean) -> Unit)?,
    val onMarkRead: (() -> Unit)?,
    val onOpen: () -> Unit,
    val onDelete: (() -> Unit)? = null,
)

/** Pin to top · Mute · Mark as read — the phones' long-press menu on a conversation row. */
@Composable
fun ConversationRowMenuItems(row: ConversationRow, a: ConversationRowMenuActions, close: () -> Unit) {
    ChromeMenuItemIcon(
        { ConvGlyphIcon(if (row.pinned) ConvGlyph.UNPIN else ConvGlyph.PIN_TOP, it, 18.dp) },
        if (row.pinned) t("conv.unpin_chat") else t("conv.pin_chat"),
    ) { close(); a.onSetPinned(!row.pinned) }
    a.onSetMuted?.let { set ->
        ChromeMenuItem(
            if (row.muted) Glyph.BELL else Glyph.BELL_SLASH,
            if (row.muted) t("chat.unmute") else t("chat.mute"),
        ) { close(); set(!row.muted) }
    }
    a.onMarkRead?.let { f -> ChromeMenuItem(Glyph.DOUBLE_CHECK, t("conv.mark_read")) { close(); f() } }
    a.onDelete?.let { f -> ChromeMenuItem(Glyph.TRASH, t("alert.delete_conversation").trim('?', '？', '؟', '¿', ' '), danger = true) { close(); f() } }
}

/** [ChromeMenuItem] with any icon, for the glyphs that live in [ConvGlyph]. */
@Composable
fun ChromeMenuItemIcon(
    icon: @Composable (Color) -> Unit,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (danger) OshiTheme.danger else OshiTheme.textPrimary
    DropdownMenuItem(
        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 17.sp) },
        onClick = onClick,
        leadingIcon = { icon(if (danger) OshiTheme.danger else Ink.soft) },
        colors = MenuDefaults.itemColors(textColor = ink),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        modifier = Modifier.heightIn(min = 38.dp).widthIn(min = 220.dp),
    )
}

// ---------------------------------------------------------------------------- icons

/** Icons this pass needs that the shared [Glyph] set does not have. Same 1:1 line grid. */
enum class ConvGlyph { SHIELD_CHECK, EXPORT, PIN_TOP, UNPIN }

@Composable
fun ConvGlyphIcon(glyph: ConvGlyph, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.085f
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
            if (close) close()
        }
        when (glyph) {
            ConvGlyph.SHIELD_CHECK -> {
                drawPath(
                    Path().apply {
                        moveTo(0.5f * s, 0.10f * s)
                        lineTo(0.84f * s, 0.24f * s)
                        cubicTo(0.84f * s, 0.58f * s, 0.70f * s, 0.80f * s, 0.5f * s, 0.92f * s)
                        cubicTo(0.30f * s, 0.80f * s, 0.16f * s, 0.58f * s, 0.16f * s, 0.24f * s)
                        close()
                    },
                    tint, style = stroke,
                )
                drawPath(path(0.34f to 0.50f, 0.46f to 0.62f, 0.67f to 0.40f), tint, style = stroke)
            }
            ConvGlyph.EXPORT -> {
                drawPath(path(0.30f to 0.42f, 0.18f to 0.42f, 0.18f to 0.88f, 0.82f to 0.88f, 0.82f to 0.42f, 0.70f to 0.42f), tint, style = stroke)
                drawLine(tint, p(0.5f, 0.64f), p(0.5f, 0.10f), strokeWidth = w, cap = StrokeCap.Round)
                drawPath(path(0.33f to 0.26f, 0.5f to 0.09f, 0.67f to 0.26f), tint, style = stroke)
            }
            ConvGlyph.PIN_TOP, ConvGlyph.UNPIN -> {
                // A push-pin: head, body, needle — and a slash across it for "unpin".
                drawPath(path(0.36f to 0.14f, 0.64f to 0.14f, 0.60f to 0.42f, 0.74f to 0.58f, 0.26f to 0.58f, 0.40f to 0.42f, close = true), tint, style = stroke)
                drawLine(tint, p(0.5f, 0.58f), p(0.5f, 0.90f), strokeWidth = w, cap = StrokeCap.Round)
                if (glyph == ConvGlyph.UNPIN) drawLine(tint, p(0.16f, 0.16f), p(0.84f, 0.84f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------------------- empty states

/**
 * __MASCOT_EMPTY_2027__ An empty screen the way the phones draw one: the OSHI mascot, a
 * title, one line, and at most one action. [mascot] names a pose in the shipped pack
 * (`wave`, `peek`, `thinking`, `sleep`…); a build without the pack falls back to the emoji.
 */
@Composable
fun MascotEmptyState(
    mascot: String,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val m = OshiMascot.named(mascot) ?: OshiMascot.named("wave")
        if (m != null) {
            val playing = rememberMascotPlaying(m, hovered = false, playOnAppear = true)
            MascotImage(m, size, playing = playing)
        }
        Spacer(Modifier.height(OshiTheme.md))
        Text(
            title,
            style = OshiTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Ink.strong,
            textAlign = TextAlign.Center,
        )
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                body,
                style = OshiTheme.typography.bodyMedium,
                color = Ink.soft,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(OshiTheme.lg))
            Box { PrimaryButton(action.first, onClick = action.second) }
        }
    }
}

// ---------------------------------------------------------------------------- 1:1 block

/** __CONVERSATION_BLOCK_2027__ The banner over a blocked contact's closed composer. */
@Composable
fun BlockedContactBanner(onUnblock: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .background(OshiTheme.danger.copy(alpha = 0.12f))
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        GlyphIcon(Glyph.BLOCK, OshiTheme.danger, 18.dp)
        Column(Modifier.weight(1f)) {
            Text(t("chat.contact_blocked_title"), style = OshiTheme.typography.labelMedium, color = OshiTheme.danger)
            Text(t("chat.contact_blocked_message"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        androidx.compose.material3.TextButton(onClick = onUnblock) {
            Text(t("chat.unblock"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}


// ---------------------------------------------------------------------------- safety number

/**
 * __CONVERSATION_VERIFY_2027__ The phones' Safety Number screen, as a dialog: the 60 digits
 * in twelve groups, the QR a phone can scan, "Mark as Verified" after comparing out loud, and
 * the stronger route — paste the code their app shows — which checks the bytes and records a
 * mismatch as a changed number instead of a green tick.
 *
 * What a computer cannot do here, and why there is no button for it: scan THEIR code with a
 * camera from this dialog. The New screen's camera scanner reads contact codes; reusing it for
 * this would put a second camera flow in a dialog for one rare action, and the phone scanning
 * OUR code verifies the same pair of keys.
 */
@Composable
fun SafetyNumberDialog(
    name: String,
    safetyNumber: String,
    qrPayload: String,
    verified: Boolean,
    onMark: (Boolean) -> Unit,
    onVerifyPayload: ((String) -> Boolean)?,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    var result by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, if (verified) OshiTheme.success else OshiTheme.brand, 22.dp)
                Text(t("safety.title"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary)
            }
        },
        text = {
            Column(Modifier.widthIn(min = 360.dp, max = 460.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                Text(t("safety.verify_with", name), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OshiTheme.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (qrPayload.isNotBlank()) {
                        Box(Modifier.clip(OshiTheme.radiusMd).background(Color.White).padding(OshiTheme.sm)) {
                            QrCode(qrPayload, size = 132.dp)
                        }
                    }
                    // Digits are numbers: always left-to-right, even in an Arabic window.
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            safetyNumber.split(' ').filter { it.isNotBlank() }.chunked(3).forEach { line ->
                                Text(
                                    line.joinToString("   "),
                                    fontSize = 15.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = Ink.strong,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
                Text(t("safety.instruction_2"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    if (verified) ChromePill(t("safety.verified"), OshiTheme.success) else ChromePill(t("safety.not_verified"), Ink.soft)
                    Spacer(Modifier.weight(1f))
                    GlyphButton(Glyph.COPY, t("common.copy"), size = 30.dp, glyphSize = 16.dp) { onCopy(safetyNumber) }
                }
                if (onVerifyPayload != null) {
                    Text(t("conv.verify.paste_hint"), style = OshiTheme.typography.labelMedium, color = Ink.soft)
                    PasteField(pasted, { pasted = it; result = null }, t("conv.verify.paste_placeholder"))
                    result?.let { ok ->
                        Text(
                            if (ok) t("safety.verified") else t("safety.verification_failed"),
                            style = OshiTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (ok) OshiTheme.success else OshiTheme.danger,
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                if (onVerifyPayload != null && pasted.isNotBlank()) {
                    androidx.compose.material3.TextButton(onClick = { result = onVerifyPayload(pasted) }) {
                        Text(t("conv.verify.check"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
                androidx.compose.material3.TextButton(onClick = { onMark(!verified); onDismiss() }) {
                    Text(
                        if (verified) t("conv.verify.unmark") else t("safety.mark_verified"),
                        color = if (verified) OshiTheme.danger else OshiTheme.brand,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    )
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("common.close"), color = OshiTheme.textPrimary, maxLines = 1, softWrap = false) }
        },
    )
}


// ---------------------------------------------------------------------------- export

/**
 * __CONVERSATION_EXPORT_2027__ Says what the file will be BEFORE the save dialog: readable
 * text, not encrypted — unlike the account backup, which only an OSHI holding this key can
 * open. The person decides with that in front of them.
 */
@Composable
fun ExportConversationDialog(title: String, count: Int, onExport: () -> Unit, onDismiss: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                ConvGlyphIcon(ConvGlyph.EXPORT, OshiTheme.brand, 20.dp)
                Text(t("conv.export.title"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary)
            }
        },
        text = {
            Column(Modifier.widthIn(min = 320.dp, max = 440.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Text(t("conv.export.body", title, count), style = OshiTheme.typography.bodyMedium, color = OshiTheme.textPrimary)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.Top) {
                    GlyphIcon(Glyph.ALERT, OshiTheme.warning, 16.dp)
                    Text(t("conv.export.warning"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onExport() }) {
                Text(t("conv.export.menu") + "…", color = OshiTheme.brand, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("common.cancel"), color = OshiTheme.textPrimary, maxLines = 1, softWrap = false) }
        },
    )
}

/** __CONVERSATION_EXPORT_2027__ Save target for one conversation as text. Cancel returns null. */
fun pickConversationExportTarget(title: String): java.io.File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("export.select_location"), java.awt.FileDialog.SAVE)
    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifBlank { "conversation" }
    dialog.file = "OSHI - $safe - ${java.time.LocalDate.now()}.txt"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    val f = java.io.File(dir, name)
    return if (f.name.contains('.')) f else java.io.File(dir, "$name.txt")
}
