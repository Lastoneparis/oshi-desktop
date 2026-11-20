package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.dt
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme

/**
 * __ATTACH_PANEL_2027__ The "+" panel — the desktop twin of Android's `AttachmentPanel`
 * (5bf961f4 / ebddb827) and iOS's (c52f1a8f), in the shape iOS gives it on the Mac: there
 * is no photo library to read on a desktop, so the "Recent" grid becomes a card that opens
 * the system file chooser (iOS Mac Catalyst does exactly this, "Photos and videos from your
 * library"), and the actions are one row of colour tiles in the phones' order and colours.
 *
 * It replaces the loose text buttons (GIF · Poll · Contact · Disguise · Video note) the
 * composer row used to carry. Every tile runs the SAME callback that button ran; a tile is
 * only drawn when its callback exists (a contact card is not offered in a group, a disguise
 * not in a group either — the gates stay where `ThreadPane` decides them).
 *
 * Pure layout over [Tile]s, so `AttachmentPanelTest` pins the order and the gating without
 * a window.
 */
object AttachmentPanelSpec {

    enum class Tile(val glyph: Glyph?, val text: String?, val tint: Color) {
        // The phones' tile colours (iOS `AttachmentPanel.Style`).
        GALLERY(Glyph.IMAGE, null, Color(0xFF2980FA)),
        /** __DESKTOP_CAMERA_2027__ this computer's camera: a photo (marked up before sending) or a clip. */
        CAMERA(Glyph.CAMERA, null, Color(0xFF5E5CE6)),
        FILE(Glyph.FILE, null, Color(0xFFFF9E1A)),
        GIF(null, "GIF", Color(0xFFB854ED)),
        CONTACT(Glyph.PERSON, null, Color(0xFF8C735C)),
        POLL(Glyph.POLL, null, Color(0xFF1AA8D9)),
        VIDEO_NOTE(Glyph.CAMERA, null, Color(0xFF1AB3AD)),
        DISGUISE(Glyph.MASK, null, Color(0xFFED4D4D)),
    }

    /**
     * The tiles shown, in order, for the callbacks that exist. [attachEnabled] false (a
     * group whose members cannot all decrypt media, a bot/radio lane) leaves only what does
     * not travel as an attachment: the disguise, which never touches the relay.
     */
    fun tiles(
        attachEnabled: Boolean,
        gif: Boolean,
        contact: Boolean,
        poll: Boolean,
        videoNote: Boolean,
        disguise: Boolean,
        camera: Boolean = false,
    ): List<Tile> = buildList {
        if (attachEnabled) {
            add(Tile.GALLERY)
            if (camera) add(Tile.CAMERA)
            add(Tile.FILE)
            if (gif) add(Tile.GIF)
            if (contact) add(Tile.CONTACT)
            if (poll) add(Tile.POLL)
            if (videoNote) add(Tile.VIDEO_NOTE)
        }
        if (disguise) add(Tile.DISGUISE)
    }

    @Composable
    fun label(tile: Tile): String = when (tile) {
        Tile.GALLERY -> t("chat.gallery")
        Tile.CAMERA -> t("chat.camera")
        Tile.FILE -> t("attach.option.file")
        Tile.GIF -> t("gif.picker.title")
        Tile.CONTACT -> t("attach.option.contact")
        Tile.POLL -> t("attach.option.poll")
        Tile.VIDEO_NOTE -> t("attach.option.video_note")
        Tile.DISGUISE -> t("disguise.photo.short")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentPanel(
    tiles: List<AttachmentPanelSpec.Tile>,
    attachEnabled: Boolean,
    disabledReason: String?,
    videoUnavailable: String?,
    onChooseFiles: () -> Unit,
    onTile: (AttachmentPanelSpec.Tile) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm)
            .glass(
                OshiTheme.radiusXl,
                if (OshiTheme.palette.isDark) OshiTheme.surface else OshiTheme.surfaceElevated,
                OshiTheme.separator,
                raised = true,
            )
            .padding(OshiTheme.md),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("attach.button"),
                style = OshiTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Ink.strong,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(OshiTheme.textPrimary.copy(alpha = 0.06f))
                    .clickable(onClickLabel = t("attach.collapse"), onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.CLOSE, Ink.soft, 12.dp) }
        }

        if (attachEnabled) {
            // The "Recent" slot: what iOS shows on the Mac, a card that opens the chooser.
            val (source, hovered) = rememberRowInteraction()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OshiTheme.brand.copy(alpha = if (hovered.value) 0.12f else 0.07f))
                    .border(1.dp, OshiTheme.brand.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                    .focusRing(RoundedCornerShape(14.dp))
                    .clickable(interactionSource = source, indication = null, onClick = onChooseFiles)
                    .padding(horizontal = OshiTheme.md, vertical = OshiTheme.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
            ) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(OshiTheme.brand),
                    contentAlignment = Alignment.Center,
                ) { GlyphIcon(Glyph.IMAGE, Color.White, 22.dp) }
                Column(Modifier.weight(1f)) {
                    Text(t("attach.access.choose"), style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                    Text(t("attach.mac.photos_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
                GlyphIcon(Glyph.CHEVRON_RIGHT, Ink.soft, 14.dp)
            }
        } else if (disabledReason != null) {
            Text(disabledReason, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            for (tile in tiles) {
                val disabled = tile == AttachmentPanelSpec.Tile.VIDEO_NOTE && videoUnavailable != null
                AttachTile(tile, AttachmentPanelSpec.label(tile), enabled = !disabled) { onTile(tile) }
            }
        }
    }
}

@Composable
private fun AttachTile(tile: AttachmentPanelSpec.Tile, label: String, enabled: Boolean, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    val dark = OshiTheme.palette.isDark
    // Lifted toward white on dark grounds, as iOS's `accent(_:dark:)` does, so a saturated
    // tile does not sink into #1C1C1E / #000.
    val tint = if (dark) lerpToWhite(tile.tint, 0.12f) else tile.tint
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered.value && enabled) OshiTheme.textPrimary.copy(alpha = 0.06f) else Color.Transparent)
            .focusRing(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label }
            .padding(vertical = OshiTheme.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(TILE).clip(CircleShape).background(if (enabled) tint else tint.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (tile.glyph != null) GlyphIcon(tile.glyph, Color.White, 22.dp)
            else Text(tile.text.orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (enabled) Ink.strong else Ink.soft,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.widthIn(max = 76.dp),
        )
    }
}

private val TILE: Dp = 48.dp

private fun lerpToWhite(c: Color, k: Float) =
    Color(c.red + (1 - c.red) * k, c.green + (1 - c.green) * k, c.blue + (1 - c.blue) * k, c.alpha)
