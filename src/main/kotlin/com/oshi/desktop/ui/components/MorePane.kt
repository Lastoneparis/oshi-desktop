package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.Pane
import com.oshi.desktop.ui.state.ShellState

/**
 * "More" — people, and the account this machine holds.
 *
 * ============================================================ WHY CONTACTS LIVE HERE
 *
 * The shipped app's More tab is a settings list; the phone's contact list hangs off it. This
 * window keeps that shape for one reason that is not deference to the phone: the conversation
 * sidebar shows people you have TALKED to, and this shows people you KNOW — which on a
 * messenger with no accounts and no directory are different sets, and the second one is the
 * only place a blocked peer is visible at all.
 *
 * ============================================================ BLOCKED PEOPLE ARE SHOWN HERE
 *
 * PARITY.md row 0.21: blocking WITHHOLDS a conversation from the list and never deletes it.
 * That is the right behaviour and it has an obvious trap — if the only screen that lists
 * people also hid the blocked ones, blocking would be a one-way door with no handle on the
 * inside. So this list reads `contacts.all()`, marks the blocked rows, and puts Unblock on
 * them. The conversation list still reads `conversations()` and still hides them.
 *
 * ============================================================ THE SAFETY NUMBER IS NOT A BADGE
 *
 * PARITY.md row 0.20. A safety number is shown as what it is — sixty digits to compare out
 * loud with the other person — and "Verified" is only ever set by a human who did that
 * comparison. Nothing here computes trust: there is no automatic verification, no "verified
 * because the key has not changed", and no green tick that means anything except that
 * somebody pressed the button.
 */
@Composable
fun MorePane(
    state: ShellState,
    onShow: (Pane) -> Unit,
    onSelect: (String) -> Unit,
    onSetBlocked: (String, Boolean) -> Unit,
    onRename: (String, String?) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = state.contacts.filterNot { it.blocked }
    val blocked = state.contacts.filter { it.blocked }

    Column(
        modifier
            .fillMaxSize()
            .background(OshiTheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {

            PaneHeading(
                "More",
                "Everyone this machine knows about, and the account it knows them as.",
            )

            // ---------------------------------------------------------------- this account
            PaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Monogram(state.displayName, state.selfAddress, Metrics.avatarLarge)
                    Spacer(Modifier.widthIn(min = OshiTheme.lg))
                    Column(Modifier.weight(1f)) {
                        Text(state.displayName, style = OshiTheme.typography.titleLarge, color = Ink.strong)
                        Spacer(Modifier.height(OshiTheme.xxs))
                        Text(state.selfAddressShort, style = OshiTheme.typography.bodySmall, color = Ink.soft)
                    }
                    TextAction("Copy address") { onCopy(state.selfAddress) }
                }
                Spacer(Modifier.height(OshiTheme.lg))
                SettingsRow("Account and relay", "Keys, gate, delivery model") { onShow(Pane.ACCOUNT) }
                Hairline()
                SettingsRow("What this client will not do", "Every gap, with the ledger row behind it") { onShow(Pane.LIMITS) }
                Hairline()
                SettingsRow("Covert text", "Hide a message inside ordinary prose, and carry it yourself") { onShow(Pane.COVERT) }
            }

            Spacer(Modifier.height(OshiTheme.lg))

            // ---------------------------------------------------------------- people
            PaneCard {
                Text("Contacts", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    if (known.isEmpty())
                        "Nobody yet. A contact appears the first time a message is exchanged, or " +
                            "when a code is pasted under New."
                    else "${known.size} known. Compare a safety number out loud before you trust one.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                if (known.isNotEmpty()) {
                    Spacer(Modifier.height(OshiTheme.md))
                    for (c in known) {
                        PersonRow(
                            c,
                            onSelect = { onSelect(c.address) },
                            onCopy = onCopy,
                            onRename = { onRename(c.address, it) },
                        ) { onSetBlocked(c.address, true) }
                        Hairline(inset = Metrics.separatorInset)
                    }
                }
            }

            if (blocked.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text("Blocked", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                    Spacer(Modifier.height(OshiTheme.xs))
                    Text(
                        "Their conversations are hidden from the list and were NOT deleted. " +
                            "Unblocking brings the history back exactly as it was.",
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    Spacer(Modifier.height(OshiTheme.md))
                    for (c in blocked) {
                        PersonRow(
                            c,
                            onSelect = null,
                            onCopy = onCopy,
                            onRename = { onRename(c.address, it) },
                        ) { onSetBlocked(c.address, false) }
                        Hairline(inset = Metrics.separatorInset)
                    }
                }
            }

            Spacer(Modifier.height(OshiTheme.xl))
        }
    }
}

// ---------------------------------------------------------------------------- pieces

/**
 * One person: their name, their safety number, and the two things you can do about them.
 *
 * **The name is EDITABLE and it is local.** Without this, a contact whose profile update
 * never arrived is a truncated base64 key forever — and PARITY.md row 0.18 says an Android
 * peer's profile rides the legacy lane and never reaches this client at all, so "forever"
 * is the common case rather than the edge one. Editing writes `ContactStore.setDisplayName`
 * and nothing leaves the machine: the peer is not told what you called them.
 */
@Composable
private fun PersonRow(
    c: ContactRow,
    onSelect: (() -> Unit)?,
    onCopy: (String) -> Unit,
    onRename: (String?) -> Unit,
    onToggleBlock: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    // Keyed by address: switching rows must not carry one person's half-typed name onto
    // another, which a bare `remember` in a reused row slot would do.
    var editing by remember(c.address) { mutableStateOf(false) }
    var typed by remember(c.address) { mutableStateOf(c.label) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(if (hovered.value) OshiTheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .let { if (onSelect != null) it.focusRing(OshiTheme.radiusMd).clickable(source, null, onClick = onSelect) else it }
            .padding(vertical = OshiTheme.md, horizontal = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(c.label, c.address, Metrics.avatarList)
        Spacer(Modifier.widthIn(min = OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editing) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        textStyle = OshiTheme.typography.titleSmall.copy(color = Ink.strong),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                        modifier = Modifier.weight(1f),
                    )
                    TextAction("Save") { onRename(typed); editing = false }
                    TextAction("Cancel") { typed = c.label; editing = false }
                } else {
                    Text(c.label, style = OshiTheme.typography.titleSmall, color = Ink.strong, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.widthIn(min = OshiTheme.xs))
                    TextAction("Rename") { editing = true }
                    if (c.verified) {
                        Spacer(Modifier.widthIn(min = OshiTheme.xs))
                        VerifiedChip()
                    }
                }
            }
            Spacer(Modifier.height(OshiTheme.xxs))
            // Sixty digits is the point: a truncated safety number is not comparable, and a
            // control that showed six of them would invite exactly the comparison that does
            // not prove anything.
            Text(
                c.safetyNumber,
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(OshiTheme.xxs)) {
            TextAction("Copy number") { onCopy(c.safetyNumber) }
            TextAction(if (c.blocked) "Unblock" else "Block", onToggleBlock)
        }
    }
}

@Composable
private fun VerifiedChip() {
    Row(
        Modifier
            .clip(OshiTheme.pill)
            .background(OshiTheme.success.copy(alpha = 0.14f))
            .padding(horizontal = OshiTheme.sm, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.CHECK, OshiTheme.success, 11.dp)
        Spacer(Modifier.widthIn(min = OshiTheme.xxs))
        Text("Verified", style = OshiTheme.typography.labelSmall, color = OshiTheme.success)
    }
}

@Composable
private fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(if (hovered.value) OshiTheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .focusRing(OshiTheme.radiusMd)
            .clickable(source, null, onClick = onClick)
            .padding(vertical = OshiTheme.md, horizontal = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OshiTheme.typography.bodyLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(detail, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
        GlyphIcon(Glyph.CHEVRON_RIGHT, Ink.soft, 16.dp)
    }
}

/** A full-width box for a pane that has nothing to show yet. */
@Composable
fun EmptyPaneNote(title: String, body: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = OshiTheme.xxl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp)) {
            BrandDisc(Glyph.ALERT)
            Spacer(Modifier.height(OshiTheme.md))
            Text(title, style = OshiTheme.typography.titleMedium, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                body,
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
