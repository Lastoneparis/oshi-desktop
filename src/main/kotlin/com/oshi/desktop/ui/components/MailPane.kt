package com.oshi.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mail.MailClient
import com.oshi.desktop.mail.MailMime
import com.oshi.desktop.ui.state.MailModel
import kotlinx.coroutines.delay
import java.io.File
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI

/** Mail uses the same sealed mailbox and MIME reader as the terminal client. */
@Composable
fun MailPane(model: MailModel, pickFile: () -> File?) {
    var state by remember { mutableStateOf(model.state) }
    var section by remember { mutableStateOf("mail") }
    var localpart by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf<Boolean?>(null) }
    var checkingAvailability by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var pairingBrowser by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        val stopObserving = model.observe { state = it }
        onDispose(stopObserving)
    }
    val availabilityCandidate = localpart.trim().lowercase()
    // The availability endpoint is rate-limited; mirror iOS's 400 ms debounce and ignore
    // a response for a local-part the user has already changed.
    LaunchedEffect(availabilityCandidate) {
        availability = null
        checkingAvailability = false
        if (availabilityCandidate.length < 3) return@LaunchedEffect
        delay(400)
        checkingAvailability = true
        model.isAvailable(availabilityCandidate) { free ->
            if (localpart.trim().lowercase() == availabilityCandidate) {
                availability = free
                checkingAvailability = false
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("mail.title"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = model::refresh) { Text(t("common.refresh")) }
        }
        (state.error ?: feedback)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        when {
            state.loading -> CircularProgressIndicator()
            !state.hasMailbox -> {
                Text(t("mail.mailbox.title"), style = MaterialTheme.typography.titleLarge)
                Text(t("mail.mailbox.explain"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(localpart, { localpart = it }, label = { Text(t("mail.section.address")) }, suffix = { Text("@${MailClient.MAIL_DOMAIN}") })
                when {
                    checkingAvailability -> Text(t("mail.avail.checking"), style = MaterialTheme.typography.bodySmall)
                    availability == true -> Text(t("mail.avail.free"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    availability == false -> Text(t("mail.avail.taken"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    localpart.isNotBlank() -> Text(t("mail.avail.rules"), style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = availability == true && !working, onClick = {
                    working = true
                    model.claim(localpart.trim()) { feedback = it; working = false }
                }) { Text(t("mail.create.button")) }
            }
            else -> {
                Text(state.account?.address.orEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { section = "mail" }) { Text(t("mail.account.title")) }
                    TextButton(onClick = { section = "drive"; model.loadDrive() }) { Text(t("mail.drive.title")) }
                    TextButton(onClick = { section = "settings" }) { Text("Aliases & storage") }
                    Button(onClick = { section = "mail"; model.newDraft() }, enabled = state.editingDraft == null) { Text(t("mail.compose.title")) }
                }
                if (state.editingDraft != null) {
                    MailComposer(model, state, state.editingDraft!!, pickFile, Modifier.weight(1f))
                } else when (section) {
                    "settings" -> {
                        val account = state.account!!
                        Text("${account.sendRemaining} of ${account.sendQuota} sends remaining")
                        Text("Mail: ${account.storageUsed} / ${account.storageQuota} bytes")
                        Text("Drive: ${account.driveUsed} / ${account.driveQuota} bytes")
                        account.aliases.forEach { alias -> Row {
                            Text(alias, Modifier.weight(1f))
                            TextButton(onClick = { confirmation = "Remove $alias?" to { model.removeAlias(alias) } }) { Text(t("common.remove")) }
                        } }
                        OutlinedTextField(localpart, { localpart = it }, label = { Text("New alias") })
                        Button(enabled = !working && localpart.isNotBlank() && account.aliases.size < account.aliasLimit, onClick = {
                            working = true
                            model.addAlias(localpart.trim()) { feedback = it; working = false; if (it == null) localpart = "" }
                        }) { Text("Add alias") }
                        val keyAlias = model.keyBasedAliasLocalpart()
                        if (keyAlias !in account.aliases) TextButton(enabled = !working && account.aliases.size < account.aliasLimit, onClick = {
                            localpart = keyAlias
                        }) { Text(t("mail.alias.usekey").replace("%@", keyAlias)) }
                        TextButton(onClick = { pairingBrowser = true; model.loadBrowserSessions() }) { Text(t("mail.browser.connect")) }
                    }
                    "drive" -> {
                        Button(onClick = { pickFile()?.let { model.uploadFile(it) { feedback = it } } }) { Text("Upload file") }
                        LazyColumn { items(state.drive, key = { it.id }) { file ->
                            var name by remember(file.id) { mutableStateOf(file.name.orEmpty()) }
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                OutlinedTextField(name, { name = it }, label = { Text("Filename") })
                                Text("${file.bytes} bytes")
                                Row {
                                    TextButton(onClick = { model.renameDriveFile(file.id, name) { feedback = it } }) { Text(t("mail.drive.rename")) }
                                    TextButton(onClick = { saveMailFile(file.name ?: "file")?.let { model.downloadFile(file.id, it) { feedback = it } } }) { Text(t("mail.drive.download")) }
                                    TextButton(onClick = { confirmation = "Permanently delete this file?" to { model.deleteDriveFile(file.id) } }) { Text(t("common.delete")) }
                                }
                            }
                        } }
                    }
                    else -> {
                        Row {
                            MailModel.FOLDERS.forEach { folder -> TextButton(onClick = { model.setFolder(folder); model.closeOpen() }) {
                                Text("${folderLabel(folder)} (${state.badgeCount(folder)})", fontWeight = if (folder == state.folder) FontWeight.Bold else FontWeight.Normal)
                            } }
                        }
                        OutlinedTextField(state.searchQuery, model::setSearchQuery, label = { Text("Search mail") }, modifier = Modifier.fillMaxWidth())
                        Row {
                            Checkbox(state.deepSearch, { model.toggleDeepSearch() })
                            Text("Include bodies opened this session", Modifier.padding(top = 12.dp))
                        }
                        val open = state.open
                        if (open == null) {
                            if (state.visibleMessages.isEmpty()) Text("No messages in this folder")
                            LazyColumn { items(state.visibleMessages, key = { it.id }) { message ->
                                Column(Modifier.fillMaxWidth().clickable { model.open(message.id) }.padding(vertical = 12.dp)) {
                                    Text(message.envelope?.subject?.ifBlank { "(no subject)" } ?: "Encrypted message", fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold)
                                    Text(message.envelope?.from.orEmpty())
                                    Text(message.receivedAt, style = MaterialTheme.typography.bodySmall)
                                }
                                Hairline()
                            } }
                        } else Column(Modifier.verticalScroll(rememberScrollState())) {
                            TextButton(onClick = model::closeOpen) { Text("Back to folder") }
                            Text(open.subject, style = MaterialTheme.typography.titleLarge)
                            Text("From: ${open.from}\nTo: ${open.to}\n${open.date}")
                            Row {
                                TextButton(onClick = { model.reply(open) }) { Text(t("message.action.reply")) }
                                TextButton(onClick = { model.forward(open) }) { Text(t("message.action.forward")) }
                                TextButton(onClick = { model.markUnread(open.id) }) { Text(t("mail.action.markUnread")) }
                                TextButton(onClick = { model.archive(open.id) }) { Text(t("mail.action.archive")) }
                                if (open.folder != MailModel.FOLDER_INBOX) TextButton(onClick = { model.restore(open.id) }) { Text(t("mail.action.restore")) }
                                TextButton(onClick = {
                                    if (open.folder == MailModel.FOLDER_TRASH) confirmation = "Permanently delete this message?" to { model.delete(open.id) }
                                    else model.delete(open.id)
                                }) { Text(t("common.delete")) }
                            }
                            val linkColor = MaterialTheme.colorScheme.primary
                            Text(linkifyMailBody(open.body, linkColor), Modifier.padding(vertical = 12.dp))
                            open.attachments.forEach { attachment -> Row {
                                Text("${attachment.filename} (${attachment.bytes.size} bytes)", Modifier.padding(top = 12.dp))
                                TextButton(onClick = {
                                    saveMailFile(attachment.filename)?.let { target -> model.saveAttachment(attachment, target) { feedback = it } }
                                }) { Text(t("common.save")) }
                                TextButton(onClick = {
                                    model.saveAttachmentToDrive(attachment) { feedback = it ?: "Saved “${attachment.filename}” to your drive." }
                                }) { Text(t("mail.action.saveToDrive")) }
                            } }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (question, action) -> AlertDialog(
        onDismissRequest = { confirmation = null }, title = { Text(question) },
        confirmButton = { TextButton(onClick = { confirmation = null; action() }) { Text(t("common.delete")) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(t("common.cancel")) } },
    ) }
    if (pairingBrowser) BrowserPairingDialog(model, state, state.account?.address.orEmpty()) { pairingBrowser = false }
}

/**
 * A browser pairing request carries only its ephemeral public key until the user reviews
 * it and taps approve. The private mail key is then sealed in [MailClient], never here.
 */
@Composable
internal fun BrowserPairingDialog(model: MailModel, state: MailModel.MailUiState, address: String, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf<MailClient.PairingRequest?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var approvedAddress by remember { mutableStateOf<String?>(null) }
    val cleanCode = code.uppercase().filterNot(Char::isWhitespace)
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(t("mail.pair.title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    approvedAddress != null -> {
                        Text(t("mail.pair.connected"), color = MaterialTheme.colorScheme.primary)
                        Text(t("mail.pair.canRead").replace("%@", approvedAddress!!))
                    }
                    pairing != null -> {
                        Text(t("mail.pair.asking"))
                        Text("Code: ${pairing!!.code}")
                        Text("Mailbox: $address")
                        Text(t("mail.pair.approveHint"), style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text(t("mail.pair.openHint"), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(code, { code = it }, enabled = !working, label = { Text(t("mail.pair.codePlaceholder")) }, modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !working, onClick = {
                            pickMailPairingQrImage()?.let { image ->
                                working = true; error = null
                                model.inspectBrowserPairingQr(image) { found, problem ->
                                    working = false; pairing = found; error = problem
                                }
                            }
                        }) { Text(t("identity.vault.scan_qr")) }
                        Text(t("mail.pair.noCamera"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.webSessions.isNotEmpty()) {
                    Text(t("mail.pair.connectedList"), style = MaterialTheme.typography.titleSmall)
                    state.webSessions.forEach { session -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(session.label)
                            Text(t("mail.pair.lastUsed").replace("%@", session.lastSeenAt.take(16)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = !working, onClick = {
                            working = true
                            model.revokeBrowserSession(session.id) { problem -> working = false; error = problem }
                        }) { Text(t("common.remove")) }
                    } }
                    TextButton(enabled = !working, onClick = {
                        working = true
                        model.revokeBrowserSession("all") { problem -> working = false; error = problem }
                    }) { Text(t("mail.pair.signoutAll")) }
                    Text(t("mail.pair.signoutHint"), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                approvedAddress != null -> TextButton(onClick = onDismiss) { Text(t("common.done")) }
                pairing != null -> Button(enabled = !working, onClick = {
                    working = true; error = null
                    model.approveBrowserPairing(pairing!!, "Approved from OSHI Desktop") { approved, problem ->
                        working = false; approvedAddress = approved; error = problem
                    }
                }) { Text(t("mail.pair.approve")) }
                else -> Button(enabled = cleanCode.length >= 8 && !working, onClick = {
                    working = true; error = null
                    model.inspectBrowserPairing(cleanCode) { found, problem ->
                        working = false; pairing = found; error = problem
                    }
                }) { Text(t("common.continue")) }
            }
        },
        dismissButton = { if (approvedAddress == null) TextButton(enabled = !working, onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MailComposer(
    model: MailModel,
    state: MailModel.MailUiState,
    draft: MailModel.DraftContent,
    pickFile: () -> File?,
    modifier: Modifier,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingDrive by remember(draft.composerId) { mutableStateOf(false) }
    var choosingFrom by remember(draft.composerId) { mutableStateOf(false) }
    var from by remember(draft.composerId) { mutableStateOf<String?>(null) }
    val sendingAddresses = state.account?.let { listOf(it.address) + it.aliases }.orEmpty()
    val latestDraft by rememberUpdatedState(draft)
    // iOS saves text drafts after a 1.5 s pause. Attachments are intentionally excluded:
    // the sealed draft format stores text only on every shipped client.
    LaunchedEffect(draft.composerId, draft.id, draft.to, draft.cc, draft.subject, draft.body) {
        if (listOf(draft.to, draft.cc, draft.subject, draft.body).all(String::isBlank)) return@LaunchedEffect
        delay(1_500)
        model.autosaveDraft(draft.id, draft.to, draft.cc, draft.subject, draft.body) { }
    }
    // Pane changes dispose the composer before its debounce may fire. Match iOS's
    // onDisappear save, but only while this exact composer is still active: sending or
    // discarding clears it first and must never recreate a draft on the server.
    DisposableEffect(draft.composerId) {
        onDispose {
            val active = model.state.editingDraft
            if (active?.composerId == draft.composerId &&
                listOf(latestDraft.to, latestDraft.cc, latestDraft.subject, latestDraft.body).any(String::isNotBlank)) {
                model.autosaveDraft(active.id, latestDraft.to, latestDraft.cc, latestDraft.subject, latestDraft.body) { }
            }
        }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sendingAddresses.size > 1) ExposedDropdownMenuBox(expanded = choosingFrom, onExpandedChange = { if (!busy) choosingFrom = it }) {
            OutlinedTextField(
                value = from ?: sendingAddresses.first(), onValueChange = {}, readOnly = true, enabled = !busy,
                label = { Text("From") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(choosingFrom) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = choosingFrom, onDismissRequest = { choosingFrom = false }) {
                sendingAddresses.forEach { address -> DropdownMenuItem(
                    text = { Text(address) }, onClick = { from = address.takeIf { it != sendingAddresses.first() }; choosingFrom = false },
                ) }
            }
        }
        OutlinedTextField(draft.to, { model.editDraft(draft.copy(to = it)) }, enabled = !busy, label = { Text(t("mail.compose.to")) }, modifier = Modifier.fillMaxWidth())
        state.recipientSuggestions(draft.to).forEach { (name, address) ->
            TextButton(enabled = !busy, onClick = {
                model.editDraft(draft.copy(to = state.applyRecipientSuggestion(draft.to, address)))
            }) { Column {
                if (name.isNotBlank()) Text(name)
                Text(address, style = MaterialTheme.typography.bodySmall)
            } }
        }
        OutlinedTextField(draft.cc, { model.editDraft(draft.copy(cc = it)) }, enabled = !busy, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.subject, { model.editDraft(draft.copy(subject = it)) }, enabled = !busy, label = { Text(t("mail.compose.subject")) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.body, { model.editDraft(draft.copy(body = it)) }, enabled = !busy, label = { Text("Message") }, minLines = 8, modifier = Modifier.fillMaxWidth())
        draft.attachments.forEachIndexed { index, attachment -> Row {
            Text(attachment.filename, Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { model.editDraft(draft.copy(attachments = draft.attachments.filterIndexed { i, _ -> i != index })) }) { Text(t("common.remove")) }
        } }
        TextButton(enabled = !busy, onClick = { pickFile()?.let(model::attachFile) }) { Text(t("mail.compose.attachFile")) }
        TextButton(enabled = !busy, onClick = { pickingDrive = true; model.loadDrive() }) { Text(t("mail.compose.attachDrive")) }
        if (draft.attachments.isNotEmpty()) Text("Draft storage supports text only. Send or remove attachments before saving.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row {
            Button(enabled = !busy && draft.to.isNotBlank(), onClick = {
                busy = true
                model.send(draft.to.split(',').map(String::trim).filter(String::isNotEmpty), draft.cc.split(',').map(String::trim).filter(String::isNotEmpty), draft.subject, draft.body, draft.inReplyTo, draft.attachments, from, draft.id) {
                    error = it; busy = false
                }
            }) { Text(t("common.send")) }
            TextButton(enabled = !busy && draft.attachments.isEmpty(), onClick = {
                busy = true
                model.autosaveDraft(draft.id, draft.to, draft.cc, draft.subject, draft.body) { id ->
                    busy = false
                    if (model.state.editingDraft?.composerId == draft.composerId && (id != null || listOf(draft.to, draft.cc, draft.subject, draft.body).all(String::isBlank))) model.clearEditingDraft()
                }
            }) { Text("Save & close") }
            TextButton(enabled = !busy, onClick = { if (draft.id == null) model.clearEditingDraft() else model.deleteDraft(draft.id) }) { Text("Discard") }
        }
    }
    if (pickingDrive) AlertDialog(
        onDismissRequest = { if (!busy) pickingDrive = false },
        title = { Text(t("mail.compose.attachDrive")) },
        text = {
            when {
                state.busy != null && state.drive.isEmpty() -> CircularProgressIndicator()
                state.drive.isEmpty() -> Text(t("mail.drive.emptyBody"))
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.drive.forEach { file ->
                        TextButton(
                            enabled = !busy && file.name != null,
                            onClick = {
                                busy = true
                                model.attachDriveFile(file) { problem ->
                                    busy = false
                                    error = problem
                                    if (problem == null) pickingDrive = false
                                }
                            },
                        ) { Text("${file.name ?: "file"} (${file.bytes} bytes)") }
                    }
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { pickingDrive = false }) { Text(t("common.cancel")) } },
        confirmButton = {},
    )
}

/** Folder wire values are fixed by [MailModel]; keep their catalog keys explicit for the audit. */
private fun folderLabel(folder: String): String = when (folder) {
    MailModel.FOLDER_INBOX -> t("mail.folder.inbox")
    MailModel.FOLDER_SENT -> t("mail.folder.sent")
    MailModel.FOLDER_DRAFT -> t("mail.folder.drafts")
    MailModel.FOLDER_ARCHIVE -> t("mail.folder.archive")
    MailModel.FOLDER_TRASH -> t("mail.folder.trash")
    else -> folder
}

private fun saveMailFile(name: String): File? {
    val dialog = FileDialog(null as Frame?, "Save attachment", FileDialog.SAVE)
    return try {
        dialog.file = name.replace('\\', '/').substringAfterLast('/').ifBlank { "attachment" }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    } finally { dialog.dispose() }
}

/** Browser pairing has no webcam dependency: a screenshot or phone photo works everywhere. */
private fun pickMailPairingQrImage(): File? {
    val dialog = FileDialog(null as Frame?, "Select QR image", FileDialog.LOAD)
    return try {
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    } finally { dialog.dispose() }
}

/**
 * URLs in a mail body, made clickable — the desktop counterpart of iOS
 * `MailView.linkified` (NSDataDetector). A bare `www.` host is promoted to `https://`
 * before it opens, and each link opens in the system browser via [openInBrowser]. Only
 * `http`/`https` are ever handed to the browser, so a hostile body cannot smuggle a
 * `file:`/`jar:` scheme past the parser.
 */
private val URL_REGEX =
    Regex("""\b(?:https?://|www\.)[^\s<>"'`)\]}]+""", RegexOption.IGNORE_CASE)

private fun linkifyMailBody(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (m in URL_REGEX.findAll(text)) {
            append(text.substring(last, m.range.first))
            // Trim trailing sentence punctuation that a detector should not treat as URL.
            val shown = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            val href = if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown
            val link = LinkAnnotation.Url(
                href,
                TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            ) { openInBrowser(href) }
            withLink(link) { append(shown) }
            last = m.range.first + shown.length
        }
        append(text.substring(last))
    }

private fun openInBrowser(url: String) {
    runCatching {
        val uri = URI(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
        }
    }
}
