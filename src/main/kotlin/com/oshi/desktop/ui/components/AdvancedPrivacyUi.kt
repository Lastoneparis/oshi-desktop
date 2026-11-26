package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.msg.AdvancedPrivacyPolicy
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.AdvancedPrivacyNoticeRow

/**
 * 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Everything "advanced chat privacy" draws on desktop, for the
 * 1:1 and the group screens alike (Android `AdvancedChatPrivacyUi.kt`, iOS
 * `AdvancedChatPrivacyViews.swift`).
 */

/** Whether the conversation on screen has advanced chat privacy on (read by the media gallery). */
val LocalAdvancedChatPrivacy = compositionLocalOf { false }

/**
 * The media opener a bubble of this conversation gets: while the protection is on, OTHER
 * people's media opens in the viewer with its save / open-elsewhere / show-in-folder actions
 * withheld ([MediaViewerTarget.saveBlocked]). Your own media stays yours.
 */
fun guardedMediaOpener(outer: MediaOpener, privacyOn: Boolean, fromMe: Boolean): MediaOpener =
    if (AdvancedPrivacyPolicy.mediaSaveAllowed(privacyOn, fromMe)) outer
    else MediaOpener { target -> outer.open(target.copy(saveBlocked = true)) }

private val switchColors
    @Composable get() = SwitchDefaults.colors(
        checkedTrackColor = OshiTheme.brand,
        checkedThumbColor = if (OshiTheme.palette.isDark) Color(0xFF14082E) else Color.White,
    )

/** The overflow-menu entry: title, the subtitle saying what it does, and a switch. */
@Composable
fun AdvancedPrivacyMenuItem(on: Boolean, onToggle: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(t("desktop.adv_privacy.title"), fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    t("desktop.adv_privacy.menu.subtitle"),
                    fontSize = 12.sp, lineHeight = 15.sp, color = Ink.soft, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = { onToggle(!on) },
        leadingIcon = { ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, if (on) OshiTheme.brand else Ink.soft, 18.dp) },
        trailingIcon = { Switch(checked = on, onCheckedChange = { onToggle(it) }, colors = switchColors) },
        colors = MenuDefaults.itemColors(textColor = OshiTheme.textPrimary),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 280.dp, max = 360.dp),
    )
}

/**
 * The info-panel block (1:1 contact info and group info): the switch with its subtitle, "Learn
 * more", and for a group how many members will not apply it until they update.
 */
@Composable
fun AdvancedPrivacyInfoBlock(on: Boolean, olderMembers: Int, isGroup: Boolean, onToggle: (Boolean) -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    InfoSection(t("desktop.adv_privacy.title")) {
        Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, if (on) OshiTheme.brand else Ink.soft, 18.dp)
                Text(
                    t("desktop.adv_privacy.menu.subtitle"),
                    fontSize = 13.sp, lineHeight = 16.sp, color = Ink.strong, modifier = Modifier.weight(1f),
                )
                Switch(checked = on, onCheckedChange = { onToggle(it) }, colors = switchColors)
            }
            Text(
                t("desktop.adv_privacy.learn_more"),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OshiTheme.brand,
                modifier = Modifier.clip(OshiTheme.radiusSm).clickable(role = Role.Button) { showInfo = true }.padding(vertical = 2.dp),
            )
            if (isGroup && olderMembers > 0) {
                Text(t("desktop.adv_privacy.sheet.not_applied", olderMembers), fontSize = 12.sp, lineHeight = 15.sp, color = Ink.soft)
            }
        }
    }
    if (showInfo) AdvancedPrivacyInfoDialog { showInfo = false }
}

/** The centred line in the thread. [AdvancedPrivacyNoticeRow.actor] null = we switched it. */
@Composable
fun AdvancedPrivacyNotice(row: AdvancedPrivacyNoticeRow) {
    var showInfo by remember { mutableStateOf(false) }
    val text = when {
        row.actor == null && row.on -> t("desktop.adv_privacy.notice.on.you")
        row.actor == null -> t("desktop.adv_privacy.notice.off.you")
        row.on -> t("desktop.adv_privacy.notice.on.other", row.actor)
        else -> t("desktop.adv_privacy.notice.off.other", row.actor)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(OshiTheme.radiusMd)
                .background(OshiTheme.textPrimary.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, Ink.soft, 14.dp)
            Text(text, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, color = Ink.soft, modifier = Modifier.padding(top = 4.dp))
            Text(
                t("desktop.adv_privacy.learn_more"),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OshiTheme.brand,
                modifier = Modifier.padding(top = 4.dp).clip(OshiTheme.radiusSm).clickable(role = Role.Button) { showInfo = true },
            )
        }
    }
    if (showInfo) AdvancedPrivacyInfoDialog { showInfo = false }
}

/**
 * The explanation behind every "Learn more". The capture line is shown only where this build
 * can keep it (Windows); on macOS and Linux it would be a claim the window does not honour.
 */
@Composable
fun AdvancedPrivacyInfoDialog(onDismiss: () -> Unit) {
    val points = buildList {
        add(t("desktop.adv_privacy.sheet.media"))
        add(t("desktop.adv_privacy.sheet.export"))
        add(t("desktop.adv_privacy.sheet.ai"))
        if (com.oshi.desktop.store.ScreenCapturePreference.supported) add(t("desktop.adv_privacy.sheet.capture"))
        add(t("desktop.adv_privacy.sheet.forward"))
        add(t("desktop.adv_privacy.sheet.anyone"))
    }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("desktop.adv_privacy.title"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Text(t("desktop.adv_privacy.sheet.intro"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                for (p in points) {
                    Row {
                        Text("•  ", style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                        Text(p, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                    }
                }
                Text(t("desktop.adv_privacy.sheet.limits"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("common.close"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold) }
        },
    )
}

/** Shown in the media viewer in place of the save / open-elsewhere actions. */
@Composable
fun AdvancedPrivacySaveBlockedLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
        ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, Ink.soft, 14.dp)
        Text(t("desktop.adv_privacy.capture.title"), fontSize = 12.sp, color = Ink.soft)
    }
}
