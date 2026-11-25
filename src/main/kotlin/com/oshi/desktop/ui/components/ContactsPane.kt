package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.pairing.ContactQr
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.desktop.ui.state.ShellState

/**
 * __CONTACTS_2027__ Contacts, as the phones list them: a search field, one row per person —
 * avatar, name, verified shield, what they call themselves — and icons for the things you do
 * to a person (message, verify, show their code, "⋯" for rename / copy / block). Blocked
 * people are a second section, never hidden: this list is where a block is undone.
 *
 * It used to be a card of text links ("Rename", "Copy", "Show code", "Block contact",
 * "Verify safety number") under every name, with the 60-digit number printed in full on each
 * row. The number now lives in the Safety Number sheet, where it can be compared.
 */
@Composable
fun ContactsCard(
    state: ShellState,
    onSelect: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRename: (String, String?) -> Unit,
    onSetBlocked: (String, Boolean) -> Unit,
    onSetVerified: (String, Boolean) -> Unit,
    onVerifySafetyNumber: (String, String) -> Boolean,
    onNewChat: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ContactRow?>(null) }
    var verifying by remember { mutableStateOf<ContactRow?>(null) }
    var showingCode by remember { mutableStateOf<ContactRow?>(null) }
    var confirmBlock by remember { mutableStateOf<ContactRow?>(null) }
    val q = query.trim().lowercase()
    val match: (ContactRow) -> Boolean = { c ->
        q.isEmpty() || c.label.lowercase().contains(q) || c.sharedNickname.orEmpty().lowercase().contains(q) || c.address.lowercase().startsWith(q)
    }
    val known = state.contacts.filterNot { it.blocked }.sortedBy { it.label.lowercase() }
    val blocked = state.contacts.filter { it.blocked }.sortedBy { it.label.lowercase() }

    PaneCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            Text(t("conv.contacts.title"), style = OshiTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong, modifier = Modifier.weight(1f))
            if (known.isNotEmpty()) Text("${known.size}", style = OshiTheme.typography.labelMedium, color = Ink.soft)
        }
        Spacer(Modifier.height(OshiTheme.md))
        if (known.isEmpty() && blocked.isEmpty()) {
            MascotEmptyState(
                mascot = "wave",
                title = t("empty.no_contacts"),
                body = t("conv.contacts.empty_body"),
                size = 88.dp,
                action = onNewChat?.let { t("new.add_contact") to it },
            )
            return@PaneCard
        }
        // Search.
        Row(
            Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.surface)
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            GlyphIcon(Glyph.SEARCH, Ink.soft, 15.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(t("contact_selector.search"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong),
                    cursorBrush = SolidColor(OshiTheme.brand), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) GlyphButton(Glyph.CLOSE, t("a11y.clear_search"), size = 22.dp, glyphSize = 11.dp) { query = "" }
        }
        Spacer(Modifier.height(OshiTheme.sm))
        val shown = known.filter(match)
        if (shown.isEmpty() && known.isNotEmpty()) {
            Text(t("empty.no_search_results"), style = OshiTheme.typography.bodySmall, color = Ink.soft, modifier = Modifier.padding(OshiTheme.md))
        }
        for (c in shown) {
            ContactLine(
                c,
                onOpen = { onSelect(c.address) },
                onVerify = { verifying = c },
                onShowCode = { showingCode = c },
                onRename = { renaming = c },
                onCopy = { onCopy(c.address) },
                onBlock = { confirmBlock = c },
            )
        }

        val shownBlocked = blocked.filter(match)
        if (shownBlocked.isNotEmpty()) {
            Spacer(Modifier.height(OshiTheme.lg))
            Text(t("contact.blocked"), style = OshiTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Ink.soft)
            Text(t("conv.contacts.blocked_note"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xs))
            for (c in shownBlocked) {
                ContactLine(
                    c,
                    onOpen = null,
                    onVerify = null,
                    onShowCode = null,
                    onRename = { renaming = c },
                    onCopy = { onCopy(c.address) },
                    onBlock = null,
                    onUnblock = { onSetBlocked(c.address, false) },
                )
            }
        }
    }

    renaming?.let { c ->
        TextEditDialog(
            title = t("conv.contacts.rename"),
            initial = c.alias.orEmpty(),
            maxLength = 48,
            singleLine = true,
            onSave = { onRename(c.address, it.ifBlank { null }) },
            onDismiss = { renaming = null },
        )
    }
    verifying?.let { c ->
        SafetyNumberDialog(
            name = c.label,
            safetyNumber = c.safetyNumber,
            qrPayload = com.oshi.desktop.crypto.SafetyNumber.qrPayload(state.selfAddress, c.address),
            verified = c.verified,
            onMark = { onSetVerified(c.address, it) },
            onVerifyPayload = { onVerifySafetyNumber(c.address, it) },
            onCopy = onCopy,
            onDismiss = { verifying = null },
        )
    }
    showingCode?.let { c ->
        OshiAlertDialog(
            onDismissRequest = { showingCode = null },
            title = { Text(c.label, style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    Box(Modifier.clip(OshiTheme.radiusMd).background(Color.White).padding(OshiTheme.sm)) {
                        QrCode(ContactQr.deepLinkFor(c.address), 200.dp)
                    }
                    Text(t("address.scan_to_add"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onCopy(ContactQr.deepLinkFor(c.address)) }) {
                    Text(t("url_action_copy"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showingCode = null }) { Text(t("common.close"), color = OshiTheme.textPrimary) }
            },
        )
    }
    confirmBlock?.let { c ->
        ConfirmDestructiveDialog(
            title = t("block_contact_confirm_title"),
            text = t("block_contact_confirm_message"),
            confirmLabel = t("chat.block"),
            onConfirm = { onSetBlocked(c.address, true) },
            onDismiss = { confirmBlock = null },
        )
    }
}

@Composable
private fun ContactLine(
    c: ContactRow,
    onOpen: (() -> Unit)?,
    onVerify: (() -> Unit)?,
    onShowCode: (() -> Unit)?,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onBlock: (() -> Unit)?,
    onUnblock: (() -> Unit)? = null,
) {
    val (source, hovered) = rememberRowInteraction()
    var menu by remember(c.address) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(if (hovered.value) OshiTheme.textPrimary.copy(alpha = 0.05f) else Color.Transparent)
            .then(if (onOpen != null) Modifier.focusRing(OshiTheme.radiusMd).clickable(source, null, onClickLabel = t("contact.start_chat"), role = Role.Button, onClick = onOpen) else Modifier.hoverable(source))
            .onSecondaryClick { menu = true }
            .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        Monogram(c.label, c.address, 40.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Text(c.label, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.verified) ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, OshiTheme.success, 15.dp)
            }
            val shared = c.sharedNickname
            Text(
                // A key fragment is always left-to-right, isolated so an Arabic row does not move its "…".
                if (shared != null && shared != c.label) t("contact.shared_nickname", shared) else "\u2066${c.addressShort}\u2069",
                style = OshiTheme.typography.bodySmall, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        onOpen?.let { GlyphButton(Glyph.COMPOSE, t("contact.start_chat"), size = 32.dp, glyphSize = 16.dp, onClick = it) }
        onVerify?.let {
            IconActionButton(if (c.verified) t("safety.verified") else t("profile.verify_identity"), size = 32.dp, active = c.verified, icon = { tint -> ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, if (c.verified) OshiTheme.success else tint, 17.dp) }, onClick = it)
        }
        onShowCode?.let { GlyphButton(Glyph.QR, t("address.scan_to_add"), size = 32.dp, glyphSize = 16.dp, onClick = it) }
        if (onUnblock != null) {
            androidx.compose.material3.TextButton(onClick = onUnblock) {
                Text(t("chat.unblock"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
        Box {
            GlyphButton(Glyph.MORE, t("a11y.more_options"), size = 32.dp, glyphSize = 16.dp, active = menu) { menu = true }
            OshiDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, 4.dp)) {
                onOpen?.let { ChromeMenuItem(Glyph.COMPOSE, t("contact.start_chat")) { menu = false; it() } }
                ChromeMenuItem(Glyph.PENCIL, t("conv.contacts.rename")) { menu = false; onRename() }
                ChromeMenuItem(Glyph.COPY, t("conv.contacts.copy_key")) { menu = false; onCopy() }
                onVerify?.let { ChromeMenuItemIcon({ tint -> ConvGlyphIcon(ConvGlyph.SHIELD_CHECK, tint, 18.dp) }, t("safety.title")) { menu = false; it() } }
                if (onBlock != null) {
                    ChromeMenuSeparator()
                    ChromeMenuItem(Glyph.BLOCK, t("contact.block_contact"), danger = true) { menu = false; onBlock() }
                }
                if (onUnblock != null) {
                    ChromeMenuSeparator()
                    ChromeMenuItem(Glyph.BLOCK, t("contact.unblock_contact")) { menu = false; onUnblock() }
                }
            }
        }
    }
}
