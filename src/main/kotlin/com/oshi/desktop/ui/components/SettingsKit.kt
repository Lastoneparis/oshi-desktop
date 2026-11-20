package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.i18n.t

/**
 * __DESKTOP_SETTINGS_2027__ The handful of shapes every Settings page is built from, so the
 * pages share one spacing and type scale:
 *
 *   page title     headlineMedium (22)      card title   titleSmall
 *   row title      bodyLarge (15)           row detail   bodySmall, Ink.soft
 *   card padding   lg (16) / rows md (12)   between cards lg (16)
 *
 * Rows lead with an icon badge rather than a text link, and every action is an icon + label
 * button, never a bare word.
 */

/** Page frame: scrolls, centres a column of at most [maxWidth], same gutters on every page. */
@Composable
fun SettingsPageFrame(
    modifier: Modifier = Modifier,
    maxWidth: androidx.compose.ui.unit.Dp = 720.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(OshiTheme.paneGround)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OshiTheme.xxl, vertical = OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

/** The page header: icon badge, title, one line of what the page is for. */
@Composable
fun SettingsPageHeader(icon: SettingsIcon, title: String, subtitle: String?, tint: Color = OshiTheme.brand) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsIconBadge(icon, tint, 44.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OshiTheme.typography.headlineMedium, color = Ink.strong)
            if (subtitle != null) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(subtitle, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
        }
    }
    Spacer(Modifier.height(OshiTheme.xl))
}

/** A glass card with an optional small title above its rows and an optional footer below. */
@Composable
fun SettingsCard(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) {
        Text(
            title,
            style = OshiTheme.typography.labelLarge,
            color = Ink.soft,
            modifier = Modifier.padding(start = OshiTheme.sm, bottom = OshiTheme.xs),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, OshiTheme.cardOutline)
            .padding(vertical = OshiTheme.xs),
        content = content,
    )
    if (footer != null) {
        Text(
            footer,
            style = OshiTheme.typography.bodySmall,
            color = Ink.soft,
            modifier = Modifier.padding(start = OshiTheme.sm, end = OshiTheme.sm, top = OshiTheme.xs),
        )
    }
    Spacer(Modifier.height(OshiTheme.lg))
}

/** Free content inside a card, with the card's horizontal gutters. */
@Composable
fun SettingsCardBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), content = content)
}

@Composable
fun SettingsDivider() = Hairline(Modifier.padding(start = OshiTheme.lg + 30.dp + OshiTheme.md))

/** Icon · title/detail · switch. The whole row toggles. */
@Composable
fun SettingsToggleRow(
    icon: SettingsIcon?,
    title: String,
    detail: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingsRowShell(icon, title, detail, enabled = enabled, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OshiTheme.brandFill),
        )
    }
}

/** Icon · title/detail · chevron. Opens something. */
@Composable
fun SettingsLinkRow(
    icon: SettingsIcon?,
    title: String,
    detail: String?,
    tint: Color = OshiTheme.brand,
    onClick: () -> Unit,
) {
    SettingsRowShell(icon, title, detail, tint = tint, onClick = onClick) {
        SettingsIconView(SettingsIcon.CHEVRON, Ink.soft, 14.dp)
    }
}

/** Label above a value; the value can be copied with the icon at the end. */
@Composable
fun SettingsValueRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = OshiTheme.typography.labelMedium, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(
                value.ifBlank { "—" },
                style = OshiTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = Ink.strong,
            )
        }
        if (onCopy != null) {
            Spacer(Modifier.width(OshiTheme.md))
            SettingsIconButton(SettingsIcon.COPY, t("common.copy"), onCopy)
        }
    }
}

@Composable
private fun SettingsRowShell(
    icon: SettingsIcon?,
    title: String,
    detail: String?,
    tint: Color = OshiTheme.brand,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (hovered.value && onClick != null) OshiTheme.surface.copy(alpha = 0.6f) else Color.Transparent)
            .let { if (onClick != null) it.focusRing(OshiTheme.radiusSm).clickable(source, null, onClick = onClick) else it }
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIconBadge(icon, if (enabled) tint else Ink.soft)
            Spacer(Modifier.width(OshiTheme.md))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = OshiTheme.typography.bodyLarge, color = if (enabled) Ink.strong else Ink.soft)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(OshiTheme.xxs))
                Text(detail, style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }
        }
        Spacer(Modifier.width(OshiTheme.md))
        trailing()
    }
}

/** A round icon-only button with a tooltip-style accessible label. */
@Composable
fun SettingsIconButton(icon: SettingsIcon, label: String, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Box(
        Modifier
            .clip(OshiTheme.pill)
            .background(if (hovered.value) OshiTheme.surface else Color.Transparent)
            .focusRing(OshiTheme.pill)
            .clickable(source, null, onClickLabel = label, onClick = onClick)
            .padding(OshiTheme.sm),
        contentAlignment = Alignment.Center,
    ) { SettingsIconView(icon, Ink.soft, 16.dp) }
}

/**
 * Icon + label, pill shaped. [primary] fills it with the brand gradient; otherwise it is a
 * quiet tinted pill. [danger] paints it red. Replaces the piles of bare text links.
 */
@Composable
fun SettingsButton(
    icon: SettingsIcon?,
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Ink.soft
        danger -> OshiTheme.danger
        primary -> Ink.onBrandStrong
        else -> OshiTheme.brand
    }
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .clip(OshiTheme.pill)
            .then(
                when {
                    primary && enabled && !danger -> Modifier.background(OshiTheme.brandGradient)
                    else -> Modifier.background(
                        SolidColor((if (danger) OshiTheme.danger else OshiTheme.brand).copy(alpha = if (hovered.value && enabled) 0.18f else 0.10f)),
                    )
                },
            )
            .focusRing(OshiTheme.pill)
            .let { if (enabled) it.clickable(source, null, onClick = onClick) else it }
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIconView(icon, tint, 15.dp)
            Spacer(Modifier.width(OshiTheme.sm))
        }
        Text(label, style = OshiTheme.typography.labelLarge, color = tint)
    }
}

/** A row of [SettingsButton]s that wraps instead of squeezing a label to nothing. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsButtonRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) { content() }
}

/** A coloured note (outcome of an action). */
@Composable
fun SettingsNotice(text: String, error: Boolean) {
    Text(
        text,
        style = OshiTheme.typography.bodySmall,
        color = if (error) OshiTheme.danger else OshiTheme.success,
        modifier = Modifier.padding(top = OshiTheme.sm),
    )
}

/** The OSHI bubble and two lines: every empty Settings list says why it is empty this way. */
@Composable
fun MascotEmptyState(mascot: String, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = OshiTheme.xl, horizontal = OshiTheme.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OshiMascot.named(mascot)?.let {
            MascotImage(it, 88.dp, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
            Spacer(Modifier.height(OshiTheme.md))
        }
        Text(title, style = OshiTheme.typography.titleMedium, color = Ink.strong, textAlign = TextAlign.Center)
        Spacer(Modifier.height(OshiTheme.xs))
        Text(
            body,
            style = OshiTheme.typography.bodySmall,
            color = Ink.soft,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}
