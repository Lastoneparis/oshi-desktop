package com.oshi.desktop.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.dt
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
fun NoConversationPane(state: ShellState) {
    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface).padding(OshiTheme.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandDisc(Glyph.LOCK, Metrics.avatarLarge)
        Spacer(Modifier.height(OshiTheme.lg))
        Text(dt("desktop.noConversation.title"), style = OshiTheme.typography.headlineMedium, color = Ink.strong)
        Spacer(Modifier.height(OshiTheme.sm))
        Text(
            // The count used to be spelled inline as `conversation` + an English `s`. That is
            // an English plural rule hardcoded into a window that renders in 34 languages, and
            // it cannot be fixed by translating the sentence: Polish needs three forms, Arabic
            // six, Japanese none. iOS ships ZERO .stringsdict (CatalogAuditTest asserts it), so
            // this project has no plural machinery to port and must not invent one — the string
            // labels the number instead of agreeing with it, which is correct for every n.
            if (state.conversations.isEmpty()) dt("desktop.noConversation.empty")
            else dt("desktop.noConversation.count", state.conversations.size),
            style = OshiTheme.typography.bodyLarge,
            color = Ink.soft,
            modifier = Modifier.widthIn(max = Metrics.bubbleMax),
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
    onSyncPush: () -> Unit,
    onSyncPull: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface).verticalScroll(rememberScrollState()).padding(OshiTheme.xxl),
    ) {
        Column(Modifier.widthIn(max = PROSE)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.lg)) {
                Monogram(state.displayName, state.selfAddress, Metrics.avatarLarge)
                Column {
                    Text(state.displayName, style = OshiTheme.typography.headlineMedium, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(state.selfAddressShort, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.soft)
                }
            }

            Spacer(Modifier.height(OshiTheme.xl))

            Card {
                FieldRow(t("settings.profile.name"), state.displayName)
                Hairline()
                FieldRow(
                    dt("desktop.account.address.label"),
                    state.selfAddress,
                    mono = true,
                    action = Glyph.COPY to { onCopyAddress(state.selfAddress) },
                )
                Hairline()
                FieldRow(t("portal.app.relay"), state.relayUrl, mono = true)
                Hairline()
                FieldRow(
                    dt("desktop.account.gate.label"),
                    if (state.gateOpen) dt("desktop.account.gate.open.value")
                    else dt("desktop.account.gate.closed.value"),
                )
            }

            Spacer(Modifier.height(OshiTheme.xl))

            Card {
                FieldRow("Delivery receipts", if (state.deliveryReceiptsEnabled) "On" else "Off", action = Glyph.CHECK to { onDeliveryReceipts(!state.deliveryReceiptsEnabled) })
                Hairline()
                FieldRow("Read receipts", if (state.readReceiptsEnabled) "On" else "Off", action = Glyph.CHECK to { onReadReceipts(!state.readReceiptsEnabled) })
            }

            Spacer(Modifier.height(OshiTheme.xl))
            Card {
                Column(Modifier.padding(OshiTheme.lg), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    Text("Device sync", style = OshiTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
                    Text(
                        "Push or pull this identity's encrypted contact archive. Pull never deletes server records; checkpointing remains REPL-only.",
                        fontSize = 11.sp,
                        color = Ink.soft,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                        PrimaryButton("Push contacts", enabled = !state.busy, onClick = onSyncPush)
                        PrimaryButton("Pull archive", enabled = !state.busy, onClick = onSyncPull)
                    }
                }
            }
            state.syncNotice?.let { outcome ->
                Spacer(Modifier.height(OshiTheme.md))
                FactCard(if (outcome.severity == com.oshi.desktop.ui.state.Severity.ERROR) Glyph.ALERT else Glyph.CHECK, "Device sync", outcome.text)
            }

            Spacer(Modifier.height(OshiTheme.xl))
            // These are desktop facts — no push and the at-rest history posture — and neither
            // has a shared upstream localisation key.
            FactCard(
                Glyph.ALERT,
                dt("desktop.account.fact.noPush.title"),
                dt("desktop.account.fact.noPush.body"),
            )
            Spacer(Modifier.height(OshiTheme.md))
            FactCard(
                Glyph.LOCK,
                dt("desktop.account.fact.plaintext.title"),
                dt("desktop.account.fact.plaintext.body"),
            )
            Spacer(Modifier.height(OshiTheme.xl))
        }
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
    Column(
        Modifier.fillMaxSize().background(OshiTheme.surface).verticalScroll(rememberScrollState()).padding(OshiTheme.xxl),
    ) {
        Column(Modifier.widthIn(max = PROSE)) {
            // The same key the menu item and the sidebar row use: three call sites, one
            // sentence, so a retranslation can never leave the screen and its entry points
            // disagreeing about what the screen is called.
            Text(dt("desktop.nav.limits"), style = OshiTheme.typography.headlineLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.sm))
            Text(
                dt("desktop.limits.intro"),
                style = OshiTheme.typography.bodyLarge,
                color = Ink.soft,
            )

            Spacer(Modifier.height(OshiTheme.xl))
            SectionHeading(dt("desktop.limits.missing.heading"), OshiTheme.danger)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                dt("desktop.limits.missing.note"),
                fontSize = 11.sp,
                color = Ink.soft,
            )
            Spacer(Modifier.height(OshiTheme.md))
            Card {
                DesktopLimits.MISSING.forEachIndexed { i, limit ->
                    if (i > 0) Hairline()
                    LimitRow(limit.what, limit.why, OshiTheme.danger)
                }
            }

            Spacer(Modifier.height(OshiTheme.xl))
            SectionHeading(dt("desktop.limits.notYet.heading"), OshiTheme.brand)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                dt("desktop.limits.notYet.note"),
                fontSize = 11.sp,
                color = Ink.soft,
            )
            Spacer(Modifier.height(OshiTheme.md))
            Card {
                DesktopLimits.NOT_IN_THIS_WINDOW.forEachIndexed { i, limit ->
                    if (i > 0) Hairline()
                    LimitRow(limit.what, limit.why, OshiTheme.brand)
                }
            }
            Spacer(Modifier.height(OshiTheme.xl))
        }
    }
}

// ---------------------------------------------------------------------------- pieces

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surfaceElevated)
            .border(Metrics.hairline, OshiTheme.separator, OshiTheme.radiusMd),
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
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surfaceElevated)
            .border(Metrics.hairline, OshiTheme.separator, OshiTheme.radiusMd)
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
private fun LimitRow(what: String, why: String, tint: Color) {
    Row(
        Modifier.fillMaxWidth().padding(OshiTheme.lg),
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = OshiTheme.xs).size(6.dp).clip(CircleShape).background(tint))
        Column {
            Text(what, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(why, fontSize = 11.sp, color = Ink.soft)
        }
    }
}
