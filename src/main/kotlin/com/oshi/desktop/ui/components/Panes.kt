package com.oshi.desktop.ui.components

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.DesktopLimits
import com.oshi.desktop.ui.state.ShellState

/** The reading width for prose. Beyond ~78 characters a paragraph stops being scannable. */
private val PROSE = 720.dp

/**
 * The "no conversation open" state — the whole right pane, not a sentence in the middle of it.
 *
 * The shipped app never shows this: on a phone the list IS the screen and a chat is pushed on
 * top of it. A two-column desktop has an empty right half from the moment it opens, so this is
 * a screen the port has to invent, and the honest thing to put in an invented screen is what
 * the window can actually do next.
 */
@Composable
fun NoConversationPane(state: ShellState, onNewChat: (() -> Unit)? = null) {
    // __MASCOT_EMPTY_2027__ Nothing open: the mascot, what to do, and the one action.
    Box(Modifier.fillMaxSize().background(OshiTheme.paneGround), contentAlignment = Alignment.Center) {
        MascotEmptyState(
            mascot = if (state.conversations.isEmpty()) "ok" else "peek",
            // Empty profile: the list already says "No conversations yet" with its own action,
            // so this side says the other true thing — what OSHI promises — not the same line twice.
            title = if (state.conversations.isEmpty()) t("chat.empty.title") else t("conv.no_selection.title"),
            body = if (state.conversations.isEmpty()) t("chat.empty.subtitle") else t("conv.no_selection.body"),
            size = 128.dp,
            action = onNewChat?.let { t("chats.merged.new_chat") to it },
        )
    }
}

/**
 * Account and relay — the facts about this install, including the uncomfortable ones.
 *
 * Two of the paragraphs at the bottom exist because they are what a user would otherwise
 * discover the hard way: delivery stops when the window closes (there is no push on desktop,
 * PARITY.md row 2.3), and the message journal is encrypted at rest. Neither belongs only in a
 * code comment.
 */
@Composable
fun AccountPane(
    state: ShellState,
    onCopyAddress: (String) -> Unit,
    onDeliveryReceipts: (Boolean) -> Unit,
    onReadReceipts: (Boolean) -> Unit,
    onTypingIndicators: (Boolean) -> Unit = {},
    onSyncPush: () -> Unit,
    onSyncPull: () -> Unit,
) {
    // __DESKTOP_SETTINGS_2027__ The same page as Settings › Account, so the menu-bar route
    // and the Settings section can never disagree. Receipts and the privacy relay live under
    // Settings › Privacy now.
    SettingsPageFrame {
        SettingsPageHeader(SettingsIcon.KEY, t("desktop.settings.section.account"), t("desktop.settings.account.subtitle"))
        AccountSettingsContent(
            state,
            SettingsActions(
                onCopy = onCopyAddress,
                onDeliveryReceipts = onDeliveryReceipts,
                onReadReceipts = onReadReceipts,
                onTypingIndicators = onTypingIndicators,
                onSyncPush = onSyncPush,
                onSyncPull = onSyncPull,
            ),
        )
    }
}

/**
 * What this client will not do — [DesktopLimits], rendered.
 *
 * The two buckets are visually different on purpose, because the distinction is the whole
 * point of the screen: red for what the CLIENT cannot do, neutral for what this WINDOW has
 * not been taught. Collapsing them would either overstate the client or understate it.
 */
@Composable
fun LimitsPane() {
    var ledgerOpen by remember { mutableStateOf(false) }
    // __DESKTOP_TOOLS_2027__ First what a COMPUTER cannot do and why, in the person's language;
    // the engineering ledger below, row by row, translated too (__DESKTOP_LEDGER_TRANSLATED_2027__).
    ToolScaffold(t("pc.limits.title"), subtitle = t("pc.limits.subtitle"), icon = ToolGlyph.INFO) {
        ToolColumns(
            first = {
                LimitCard(ToolGlyph.ALERT, t("pc.limits.push.title"), t("pc.limits.push.why"))
                LimitCard(ToolGlyph.LOCATE, t("pc.limits.gps.title"), t("pc.limits.gps.why"))
                LimitCard(ToolGlyph.RADIO, t("pc.limits.bluetooth.title"), t("pc.limits.bluetooth.why"))
                LimitCard(ToolGlyph.WIFI, t("pc.limits.nearby.title"), t("pc.limits.nearby.why"))
            },
            second = {
                LimitCard(ToolGlyph.EYE_OFF, t("pc.limits.screenshot.title"), t("pc.limits.screenshot.why"))
                LimitCard(ToolGlyph.CLOCK, t("pc.limits.watch.title"), t("pc.limits.watch.why"))
                LimitCard(ToolGlyph.VIDEO, t("pc.limits.camera.title"), t("pc.limits.camera.why"))
                LimitCard(ToolGlyph.SPARKLE, t("pc.limits.systemAi.title"), t("pc.limits.systemAi.why"))
            },
        )
        ToolCard(title = t("pc.limits.ledger"), icon = ToolGlyph.DOC, trailing = {
            ToolIconButton(ToolGlyph.CHEVRON, if (ledgerOpen) t("common.collapse") else t("common.expand"), active = ledgerOpen) { ledgerOpen = !ledgerOpen }
        }) {
            ToolHint(t("pc.limits.ledger.note"))
            if (ledgerOpen) {
                Text(t("desktop.limits.intro"), style = ToolType.caption, color = Ink.soft)
                SectionHeading(t("desktop.limits.missing.heading"), OshiTheme.danger)
                Text(t("desktop.limits.missing.note"), style = ToolType.caption, color = Ink.soft)
                DesktopLimits.MISSING.forEachIndexed { i, limit ->
                    if (i > 0) Hairline()
                    LimitRow(limit, OshiTheme.danger)
                }
                SectionHeading(t("desktop.limits.notYet.heading"), OshiTheme.brand)
                Text(t("desktop.limits.notYet.note"), style = ToolType.caption, color = Ink.soft)
                DesktopLimits.NOT_IN_THIS_WINDOW.forEachIndexed { i, limit ->
                    if (i > 0) Hairline()
                    LimitRow(limit, OshiTheme.brand)
                }
            }
        }
    }
}

@Composable
private fun LimitCard(glyph: ToolGlyph, title: String, why: String) {
    ToolCard {
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(OshiTheme.brand.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                ToolIcon(glyph, OshiTheme.brand, 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Text(title, style = ToolType.bodyStrong, color = Ink.strong)
                Text(why, style = ToolType.caption, color = Ink.soft)
            }
        }
    }
}

// ---------------------------------------------------------------------------- pieces

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, OshiTheme.separator),
    ) { content() }
}

@Composable
private fun SectionHeading(text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.strong)
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    mono: Boolean = false,
    action: Pair<Glyph, () -> Unit>? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(OshiTheme.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                value.ifBlank { "—" },
                style = OshiTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = Ink.strong,
            )
        }
        action?.let { (glyph, onClick) ->
            val (source, hovered) = rememberRowInteraction()
            Box(
                Modifier
                    .size(Metrics.actionButton)
                    .clip(CircleShape)
                    .background(if (hovered.value) OshiTheme.surface else Color.Transparent)
                    .focusRing(CircleShape)
                    .clickable(interactionSource = source, indication = null, onClickLabel = t("common.copy"), onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(glyph, Ink.soft, 16.dp) }
        }
    }
}

@Composable
private fun FactCard(glyph: Glyph, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, OshiTheme.separator)
            .padding(OshiTheme.lg),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(glyph, Ink.soft, 18.dp)
        Column {
            Text(title, style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(body, fontSize = 11.sp, color = Ink.soft)
        }
    }
}

@Composable
private fun LimitRow(limit: DesktopLimits.Limit, tint: Color) {
    Row(
        Modifier.fillMaxWidth().padding(OshiTheme.lg),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = OshiTheme.xs).size(6.dp).clip(CircleShape).background(tint))
        Column {
            Text(t(limit.titleKey), style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(t(limit.whyKey), fontSize = 11.sp, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            // A reference, not prose: the ledger row a developer can check this against.
            Text("PARITY.md · " + limit.row, fontSize = 10.sp, color = Ink.soft.copy(alpha = 0.45f))
        }
    }
}
