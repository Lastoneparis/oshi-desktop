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
import com.oshi.desktop.i18n.t
import com.oshi.desktop.i18n.dt
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
    onVerifySafetyNumber: (String, String) -> Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Pick a target, then [ChatShellModel.exportMessages]. */
    onExportMessages: () -> Unit = {},
    /** Pick an `.oshiexport`, then [ChatShellModel.importMessages]. */
    onImportMessages: () -> Unit = {},
    /** __SHARED_NICKNAME_2026_09_22__ [ChatShellModel.setOwnNickname]; null/blank removes it. */
    onSetNickname: (String?) -> Unit = {},
    /** [ChatShellModel.pullNicknameFromPhone]. */
    onPullNickname: () -> Unit = {},
    /** [ChatShellModel.pushNicknameToPhone]. */
    onPushNickname: () -> Unit = {},
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
                t("tab_more"),
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
                NicknameEditor(state, onSetNickname, onPullNickname, onPushNickname)
                Spacer(Modifier.height(OshiTheme.lg))
                SettingsRow(t("mail.title"), "Mailbox, drafts, aliases and encrypted drive") { onShow(Pane.MAIL) }
                Hairline()
                SettingsRow("Account and relay", "Keys, gate, delivery model") { onShow(Pane.ACCOUNT) }
                Hairline()
                SettingsRow("What this client will not do", "Every gap, with the ledger row behind it") { onShow(Pane.LIMITS) }
                Hairline()
                SettingsRow("Covert text", "Hide a message inside ordinary prose, and carry it yourself") { onShow(Pane.COVERT) }
                Hairline()
                SettingsRow("Scheduled messages", "Local delivery queue; OSHI must be running at the due time") { onShow(Pane.SCHEDULED) }
                Hairline()
                // __DEVSYNC_DIRECT_2026_09_22__ Direct own-device sync.
                SettingsRow("Linked devices", "Sync your history directly with your phone and other devices") { onShow(Pane.DEVICES) }
            }

            Spacer(Modifier.height(OshiTheme.lg))

            // ---------------------------------------------------------------- background
            // __DESKTOP_BACKGROUND_2026_09_23__ PARITY.md row 2.3: no push, so the process
            // has to be running — this is where the person decides it starts with the session.
            BackgroundCard()

            Spacer(Modifier.height(OshiTheme.lg))

            // ---------------------------------------------------------------- history backup
            // __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Same container as iOS: sealed with the
            // ACCOUNT key, so only an OSHI session holding this account can open it. There is
            // no plaintext export on this client, on purpose.
            PaneCard {
                Text(dt("desktop.export.card.title"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(dt("desktop.export.card.body"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Spacer(Modifier.height(OshiTheme.md))
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                    PrimaryButton(t("export.messages"), enabled = !state.busy, onClick = onExportMessages)
                    PrimaryButton(dt("desktop.import.action"), enabled = !state.busy, onClick = onImportMessages)
                }
                state.backupNotice?.let { outcome ->
                    Spacer(Modifier.height(OshiTheme.md))
                    Text(
                        outcome.text,
                        style = OshiTheme.typography.bodySmall,
                        color = if (outcome.severity == com.oshi.desktop.ui.state.Severity.ERROR) OshiTheme.danger else Ink.strong,
                    )
                }
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
                            onVerifySafetyNumber = { onVerifySafetyNumber(c.address, it) },
                        ) { onSetBlocked(c.address, true) }
                        Hairline(inset = Metrics.separatorInset)
                    }
                }
            }

            if (blocked.isNotEmpty()) {
                Spacer(Modifier.height(OshiTheme.lg))
                PaneCard {
                    Text(t("contact.blocked"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
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
                            onVerifySafetyNumber = { onVerifySafetyNumber(c.address, it) },
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
 * never arrived is a truncated base64 key forever — and until 2026-09-22 an Android peer's
 * profile rode the legacy lane only and never reached this client (PARITY.md row 0.18; older
 * Android builds still do). Editing writes `ContactStore.setDisplayName`
 * and nothing leaves the machine: the peer is not told what you called them.
 */
@Composable
private fun PersonRow(
    c: ContactRow,
    onSelect: (() -> Unit)?,
    onCopy: (String) -> Unit,
    onRename: (String?) -> Unit,
    onVerifySafetyNumber: (String) -> Boolean,
    onToggleBlock: () -> Unit,
) {
    val (source, hovered) = rememberRowInteraction()
    // Keyed by address: switching rows must not carry one person's half-typed name onto
    // another, which a bare `remember` in a reused row slot would do.
    var editing by remember(c.address) { mutableStateOf(false) }
    // __SHARED_NICKNAME_2026_09_22__ The field edits OUR alias, so it starts from the alias
    // and not from `label`: `label` can now be the peer's shared nickname (or the short
    // key), and pre-filling it would turn "Save" into silently freezing their name as ours.
    var typed by remember(c.address) { mutableStateOf(c.alias.orEmpty()) }
    var verifying by remember(c.address) { mutableStateOf(false) }
    var safetyPayload by remember(c.address) { mutableStateOf("") }
    var safetyResult by remember(c.address) { mutableStateOf<Boolean?>(null) }
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
                    TextAction(t("contact.save_contact")) { onRename(typed); editing = false }
                    TextAction(t("common.cancel")) { typed = c.alias.orEmpty(); editing = false }
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
            // __SHARED_NICKNAME_2026_09_22__ What they call themselves, whenever the line above
            // is not already showing it (i.e. we gave them an alias, or we are editing one).
            val shared = c.sharedNickname
            if (shared != null && (editing || shared != c.label)) {
                Text(
                    t("contact.shared_nickname", shared),
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
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
            if (!c.verified) {
                Spacer(Modifier.height(OshiTheme.xxs))
                if (verifying) {
                    Text(
                        dt("desktop.safety.verify.hint"),
                        style = OshiTheme.typography.bodySmall,
                        color = Ink.soft,
                    )
                    androidx.compose.foundation.text.BasicTextField(
                        value = safetyPayload,
                        onValueChange = { safetyPayload = it; safetyResult = null },
                        singleLine = true,
                        textStyle = OshiTheme.typography.bodySmall.copy(color = Ink.strong),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                        modifier = Modifier.fillMaxWidth().padding(vertical = OshiTheme.xxs),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        TextAction(dt("desktop.safety.verify.action")) { safetyResult = onVerifySafetyNumber(safetyPayload) }
                        TextAction(t("common.cancel")) { verifying = false; safetyPayload = ""; safetyResult = null }
                    }
                    if (safetyResult == false) Text(
                        dt("desktop.safety.verify.mismatch"),
                        style = OshiTheme.typography.bodySmall,
                        color = OshiTheme.danger,
                    )
                } else {
                    TextAction(dt("desktop.safety.verify.action")) { verifying = true }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(OshiTheme.xxs)) {
            TextAction(t("common.copy")) { onCopy(c.safetyNumber) }
            TextAction(if (c.blocked) t("contact.unblock_contact") else t("contact.block_contact"), onToggleBlock)
        }
    }
}

/**
 * __SHARED_NICKNAME_2026_09_22__ THIS account's nickname — the desktop counterpart of the
 * phones' Settings → nickname. Saving sends it to every contact we have written to, inside
 * the silent `📸PROFILE_UPDATE📸`; an empty field removes it. The two archive actions reach
 * the user's phones only when this machine holds the same account.
 */
@Composable
private fun NicknameEditor(
    state: ShellState,
    onSetNickname: (String?) -> Unit,
    onPullNickname: () -> Unit,
    onPushNickname: () -> Unit,
) {
    // Keyed on the stored value: a pull (or a save) that changes it re-seeds the field.
    var typed by remember(state.ownNickname) { mutableStateOf(state.ownNickname.orEmpty()) }
    Text(dt("desktop.nickname.title"), style = OshiTheme.typography.titleSmall, color = Ink.strong)
    Spacer(Modifier.height(OshiTheme.xxs))
    Text(
        if (state.nicknameBroadcasts) dt("desktop.nickname.body") else dt("desktop.nickname.withheld"),
        style = OshiTheme.typography.bodySmall,
        color = Ink.soft,
    )
    Spacer(Modifier.height(OshiTheme.sm))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .clip(OshiTheme.radiusMd)
                .background(OshiTheme.surface)
                .padding(horizontal = OshiTheme.sm, vertical = OshiTheme.xs),
        ) {
            if (typed.isEmpty()) {
                Text(dt("desktop.nickname.placeholder"), style = OshiTheme.typography.bodyLarge, color = Ink.soft)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = typed,
                // Same cap as both phones' Settings field; the wire sanitizer caps again.
                onValueChange = { v -> typed = if (v.codePointCount(0, v.length) > 48) typed else v },
                singleLine = true,
                textStyle = OshiTheme.typography.bodyLarge.copy(color = Ink.strong),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.widthIn(min = OshiTheme.md))
        PrimaryButton(
            t("common.save"),
            enabled = !state.busy && typed.trim() != state.ownNickname.orEmpty(),
        ) { onSetNickname(typed.trim().ifEmpty { null }) }
    }
    Spacer(Modifier.height(OshiTheme.xs))
    Text(dt("desktop.nickname.sync.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
        TextAction(dt("desktop.nickname.pull")) { if (!state.busy) onPullNickname() }
        TextAction(dt("desktop.nickname.push")) { if (!state.busy) onPushNickname() }
    }
    state.profileNotice?.let { outcome ->
        Spacer(Modifier.height(OshiTheme.xs))
        Text(
            outcome.text,
            style = OshiTheme.typography.bodySmall,
            color = if (outcome.severity == com.oshi.desktop.ui.state.Severity.ERROR) OshiTheme.danger else Ink.strong,
        )
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
        Text(t("safety.verified"), style = OshiTheme.typography.labelSmall, color = OshiTheme.success)
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

/**
 * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ The native save / open dialogs for `.oshiexport`.
 * Cancel returns null. A save target always gets the extension, so the file a user hands to
 * another device is recognisable as what it is.
 */
fun pickMessageExportTarget(): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, t("export.select_location"), java.awt.FileDialog.SAVE)
    dialog.file = com.oshi.desktop.store.EncryptedMessageExport.defaultFileName()
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return com.oshi.desktop.store.EncryptedMessageExport.withExtension(java.io.File(dir, name))
}

fun pickMessageExportSource(): java.io.File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, t("import.select_file"), java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = false
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return java.io.File(dir, name).takeIf { it.isFile }
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

@Composable
private fun BackgroundCard() {
    var atLogin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.oshi.desktop.ui.LoginItem.isEnabled()) }
    var problem by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    PaneCard {
        Text(dt("desktop.background.title"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Spacer(Modifier.height(OshiTheme.xs))
        Text(dt("desktop.background.body"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dt("desktop.background.login"), style = OshiTheme.typography.bodyMedium, color = Ink.strong, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = atLogin,
                onCheckedChange = { want ->
                    problem = com.oshi.desktop.ui.LoginItem.setEnabled(want)
                    atLogin = com.oshi.desktop.ui.LoginItem.isEnabled()
                },
            )
        }
        problem?.let {
            Spacer(Modifier.height(OshiTheme.xs))
            Text(dt("desktop.background.login.failed", it), style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
        }
    }
}
