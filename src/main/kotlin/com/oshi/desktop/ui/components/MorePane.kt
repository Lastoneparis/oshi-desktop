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
import com.oshi.desktop.pairing.ContactQr
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
    /** __PROFILE_PHOTOS_DESKTOP_2027__ choose / remove this account's picture. */
    onSetPhoto: (() -> Unit)? = null,
    onRemovePhoto: (() -> Unit)? = null,
    /** __CALL_LOG_AT_REST_2026_09_23__ Pick a target, then [ChatShellModel.exportCallDiagnostics]. */
    onExportCallDiagnostics: () -> Unit = {},
    /** __DESKTOP_SETTINGS_2027__ receipts and device sync, formerly only on the Account pane. */
    onDeliveryReceipts: (Boolean) -> Unit = {},
    onReadReceipts: (Boolean) -> Unit = {},
    onTypingIndicators: (Boolean) -> Unit = {},
    onSyncPush: () -> Unit = {},
    onSyncPull: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    /** __CONTACTS_2027__ "Mark as Verified" from the Safety Number sheet. */
    onSetVerified: (String, Boolean) -> Unit = { _, _ -> },
    /** __CONTACTS_2027__ the empty contacts card's "Add Contact". */
    onNewChat: (() -> Unit)? = null,
    /** The pages that need the live client (devices, disguise, deletion, recovery key). */
    slots: SettingsSlots = SettingsSlots(),
) {
    // __DESKTOP_SETTINGS_2027__ More IS Settings now: sections on the left, the page on the
    // right. Contacts keep their own page (and their own code, below) inside it.
    SettingsPane(
        state = state,
        slots = SettingsSlots(
            contacts = {
                ContactsSettingsContent(state, onSelect, onSetBlocked, onRename, onVerifySafetyNumber, onCopy, onSetVerified, onNewChat)
            },
            linkedDevices = slots.linkedDevices,
            disguise = slots.disguise,
            deleteAccount = slots.deleteAccount,
            recoveryKey = slots.recoveryKey,
            version = slots.version,
            updates = slots.updates,
            bots = slots.bots,
        ),
        actions = SettingsActions(
            onShow = onShow,
            onCopy = onCopy,
            onOpenUrl = onOpenUrl,
            onExportMessages = onExportMessages,
            onImportMessages = onImportMessages,
            onSetNickname = onSetNickname,
            onPullNickname = onPullNickname,
            onPushNickname = onPushNickname,
            onSetPhoto = onSetPhoto,
            onRemovePhoto = onRemovePhoto,
            onExportCallDiagnostics = onExportCallDiagnostics,
            onDeliveryReceipts = onDeliveryReceipts,
            onReadReceipts = onReadReceipts,
            onTypingIndicators = onTypingIndicators,
            onSyncPush = onSyncPush,
            onSyncPull = onSyncPull,
        ),
        modifier = modifier,
    )
}

/**
 * The Contacts page of Settings. __MERGE_2027__ the settings branch kept the old PersonRow
 * list here while the messages branch redesigned contacts in ContactsPane.kt (search, avatar
 * rows, icon actions, Safety Number sheet, blocked section); the page now shows that one
 * redesigned card, so there is a single contacts UI in the app.
 */
@Composable
fun ContactsSettingsContent(
    state: ShellState,
    onSelect: (String) -> Unit,
    onSetBlocked: (String, Boolean) -> Unit,
    onRename: (String, String?) -> Unit,
    onVerifySafetyNumber: (String, String) -> Boolean,
    onCopy: (String) -> Unit,
    onSetVerified: (String, Boolean) -> Unit = { _, _ -> },
    onNewChat: (() -> Unit)? = null,
) {
    ContactsCard(
        state = state,
        onSelect = onSelect,
        onCopy = onCopy,
        onRename = onRename,
        onSetBlocked = onSetBlocked,
        onSetVerified = onSetVerified,
        onVerifySafetyNumber = onVerifySafetyNumber,
        onNewChat = onNewChat,
    )
}

/**
 * __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ The native save / open dialogs for `.oshiexport`.
 * Cancel returns null. A save target always gets the extension, so the file a user hands to
 * another device is recognisable as what it is.
 */
fun pickMessageExportTarget(): java.io.File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("export.select_location"), java.awt.FileDialog.SAVE)
    dialog.file = com.oshi.desktop.store.EncryptedMessageExport.defaultFileName()
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return com.oshi.desktop.store.EncryptedMessageExport.withExtension(java.io.File(dir, name))
}

/** Save target for the redacted call diagnostics (`.txt`). Cancel returns null. */
fun pickCallDiagnosticsTarget(): java.io.File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("export.select_location"), java.awt.FileDialog.SAVE)
    dialog.file = com.oshi.messenger.service.diag.CallFileLogger.EXPORT_FILE_NAME
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return java.io.File(dir, name)
}

fun pickMessageExportSource(): java.io.File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("import.select_file"), java.awt.FileDialog.LOAD)
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
            // __DESKTOP_SETTINGS_2027__ empty states show the OSHI bubble, not a warning sign:
            // an empty list is not an error.
            com.oshi.desktop.mascot.OshiMascot.named("thinking")?.let {
                MascotImage(it, 80.dp, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
            } ?: BrandDisc(Glyph.INFO)
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

/** Kept for the screen renderers: the Appearance page on its own. */
@Composable
internal fun AppearanceCard() = AppearanceSettingsContent()

/** __O_BUBBLE_MASCOT_2026_11_16__ "Animated OSHI reactions" — default on. */
@Composable
internal fun ReactionsCard() {
    val animated by com.oshi.desktop.ui.MotionPreference.animatedReactions
    SettingsCard(title = t("desktop.reactions.card.title")) {
        SettingsToggleRow(
            SettingsIcon.SMILE,
            t("desktop.reactions.animated"),
            t("desktop.reactions.animated.body"),
            checked = animated,
        ) { com.oshi.desktop.ui.MotionPreference.setAnimatedReactions(it) }
    }
}
