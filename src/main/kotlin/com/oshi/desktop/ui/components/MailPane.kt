package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.Strings
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mail.MailClient
import com.oshi.desktop.mail.MailMime
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.MailModel
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * __DESKTOP_TOOLS_2027__ Mail as a desktop mail reader: folders on the left, the message
 * list in the middle, the open message (or the composer) on the right — the three columns
 * every desktop mail client uses, because a window has the width a phone does not.
 *
 * Same sealed mailbox and MIME reader as before; only the presentation changed. Below
 * 1100 dp the folder column folds to an icon rail so the reader keeps its room.
 */
@Composable
fun MailPane(model: MailModel, pickFile: () -> File?) {
    var state by remember { mutableStateOf(model.state) }
    DisposableEffect(model) {
        val stopObserving = model.observe { state = it }
        onDispose(stopObserving)
    }
    MailScreen(model, state, pickFile)
}

/** The section of the mail window — folders are "mail", plus Drive and the account page. */
enum class MailSection { MAIL, DRIVE, ACCOUNT }

/** Lets a render pin a section and dialog without clicking. */
data class MailPreview(val section: MailSection? = null, val browsersOpen: Boolean = false)
val LocalMailPreview = staticCompositionLocalOf { MailPreview() }

@Composable
internal fun MailScreen(model: MailModel, state: MailModel.MailUiState, pickFile: () -> File?) = ProvideLocaleDirection {
    val preview = LocalMailPreview.current
    var section by remember { mutableStateOf(preview.section ?: MailSection.MAIL) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var pairingBrowser by remember { mutableStateOf(preview.browsersOpen) }

    BoxWithConstraints(Modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        val wide = maxWidth >= 1100.dp
        Column(Modifier.fillMaxSize()) {
            ToolHeader(
                title = t("mail.title"),
                subtitle = state.account?.address,
                icon = ToolGlyph.MAIL,
            ) {
                if (state.hasMailbox) {
                    state.busy?.let { busy ->
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OshiTheme.brand)
                        Text(busy, style = ToolType.caption, color = Ink.soft, maxLines = 1)
                        Spacer(Modifier.width(OshiTheme.sm))
                    }
                    ToolIconButton(ToolGlyph.REFRESH, t("common.refresh")) { model.refresh() }
                    ToolIconButton(ToolGlyph.BROWSER, t("mail.browser.connect")) { pairingBrowser = true; model.loadBrowserSessions() }
                    Spacer(Modifier.width(OshiTheme.xs))
                    ToolButton(t("mail.compose.title"), icon = ToolGlyph.COMPOSE, style = ToolButtonStyle.PRIMARY, enabled = state.editingDraft == null) {
                        section = MailSection.MAIL; model.newDraft()
                    }
                }
            }
            Hairline()
            val banner = state.error ?: feedback
            if (banner != null || state.newWebSessions.isNotEmpty()) {
                Column(Modifier.padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    if (state.newWebSessions.isNotEmpty()) ToolNotice(
                        t("pc.mail.newBrowser", state.newWebSessions.joinToString(", ")), ToolTone.WARNING,
                    ) {
                        ToolButton(t("pc.mail.review"), style = ToolButtonStyle.QUIET) { pairingBrowser = true; model.loadBrowserSessions(); model.acknowledgeNewWebSessions() }
                    }
                    if (banner != null) ToolNotice(banner, ToolTone.ERROR) {
                        ToolIconButton(ToolGlyph.CLOSE, t("common.dismiss"), size = 28.dp) { feedback = null; model.dismissNotice() }
                    }
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OshiTheme.brand)
                }
                !state.hasMailbox -> MailOnboarding(model) { feedback = it }
                else -> Row(Modifier.fillMaxSize()) {
                    MailSidebar(state, section, wide, onSection = { s ->
                        section = s
                        if (s == MailSection.DRIVE) model.loadDrive()
                    }, onFolder = { f -> section = MailSection.MAIL; model.setFolder(f); model.closeOpen() })
                    VerticalRule()
                    when {
                        state.editingDraft != null -> MailComposer(model, state, state.editingDraft!!, pickFile, Modifier.weight(1f))
                        section == MailSection.DRIVE -> DriveSection(model, state, pickFile, Modifier.weight(1f), onFeedback = { feedback = it }) { q, a -> confirmation = q to a }
                        section == MailSection.ACCOUNT -> AccountSection(model, state, Modifier.weight(1f), onFeedback = { feedback = it }, onBrowsers = { pairingBrowser = true; model.loadBrowserSessions() }) { q, a -> confirmation = q to a }
                        else -> {
                            MailList(model, state, Modifier.width(if (wide) 360.dp else 300.dp).fillMaxHeight())
                            VerticalRule()
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                val open = state.open
                                if (open == null) ToolEmptyState(
                                    t("pc.mail.reader.emptyTitle"), t("pc.mail.reader.emptyBody"),
                                    Modifier.align(Alignment.Center), mascot = "peek",
                                ) else MailReader(model, open, onFeedback = { feedback = it }) { q, a -> confirmation = q to a }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (question, action) ->
        OshiAlertDialog(
            onDismissRequest = { confirmation = null }, title = { Text(question, style = ToolType.section) },
            confirmButton = { TextButton(onClick = { confirmation = null; action() }) { Text(t("common.delete"), color = OshiTheme.danger) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(t("common.cancel")) } },
        )
    }
    if (pairingBrowser) BrowserPairingDialog(model, state, state.account?.address.orEmpty()) { pairingBrowser = false }
}

// ---------------------------------------------------------------------------- onboarding

@Composable
private fun MailOnboarding(model: MailModel, onFeedback: (String?) -> Unit) {
    var localpart by remember { mutableStateOf("") }
    var availability by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val candidate = localpart.trim().lowercase()
    // The availability endpoint is rate-limited; mirror iOS's 400 ms debounce and ignore
    // a response for a local-part the user has already changed.
    LaunchedEffect(candidate) {
        availability = null
        checking = false
        if (candidate.length < 3) return@LaunchedEffect
        delay(400)
        checking = true
        model.isAvailable(candidate) { free ->
            if (localpart.trim().lowercase() == candidate) { availability = free; checking = false }
        }
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).padding(OshiTheme.xl), verticalArrangement = Arrangement.spacedBy(OshiTheme.lg)) {
            ToolEmptyState(t("mail.mailbox.title"), t("mail.mailbox.explain"), mascot = "wave")
            ToolCard {
                for ((glyph, key) in listOf(
                    ToolGlyph.INBOX to t("mail.feature.mailbox"), ToolGlyph.SEND to t("mail.feature.messages"),
                    ToolGlyph.DRIVE to t("mail.feature.drive"), ToolGlyph.PEOPLE to t("mail.feature.aliases"),
                )) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                        ToolIcon(glyph, OshiTheme.brand, 18.dp)
                        Text(key, style = ToolType.body, color = Ink.strong)
                    }
                }
            }
            ToolCard(title = t("mail.section.address"), icon = ToolGlyph.MAIL) {
                ToolField(localpart, { localpart = it }, t("mail.alias.namePlaceholder"), Modifier.fillMaxWidth()) {
                    Text("@${MailClient.MAIL_DOMAIN}", style = ToolType.body, color = Ink.soft)
                }
                val (hint, color) = when {
                    checking -> t("mail.avail.checking") to Ink.soft
                    availability == true -> t("mail.avail.free") to OshiTheme.success
                    availability == false -> t("mail.avail.taken") to OshiTheme.danger
                    else -> t("mail.avail.rules") to Ink.soft
                }
                ToolHint(hint, color = color)
                ToolButton(t("mail.create.button"), style = ToolButtonStyle.PRIMARY, enabled = availability == true && !working) {
                    working = true
                    model.claim(localpart.trim()) { onFeedback(it); working = false }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- sidebar

@Composable
private fun MailSidebar(
    state: MailModel.MailUiState,
    section: MailSection,
    wide: Boolean,
    onSection: (MailSection) -> Unit,
    onFolder: (String) -> Unit,
) {
    Column(
        Modifier.width(if (wide) 220.dp else 64.dp).fillMaxHeight().padding(vertical = OshiTheme.md, horizontal = OshiTheme.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (folder in MailModel.FOLDERS) {
            SidebarItem(
                folderGlyph(folder), folderLabel(folder), state.badgeCount(folder).takeIf { it > 0 },
                selected = section == MailSection.MAIL && folder == state.folder, wide = wide,
                emphasise = folder == MailModel.FOLDER_INBOX,
            ) { onFolder(folder) }
        }
        Spacer(Modifier.height(OshiTheme.md))
        Hairline()
        Spacer(Modifier.height(OshiTheme.md))
        SidebarItem(ToolGlyph.DRIVE, t("mail.drive.title"), null, section == MailSection.DRIVE, wide) { onSection(MailSection.DRIVE) }
        SidebarItem(ToolGlyph.SETTINGS, t("mail.account.title"), null, section == MailSection.ACCOUNT, wide) { onSection(MailSection.ACCOUNT) }
        Spacer(Modifier.weight(1f))
        val account = state.account
        if (wide && account != null) {
            QuotaMeter(t("mail.quota.mailbox"), account.storageUsed, account.storageQuota)
            Spacer(Modifier.height(OshiTheme.sm))
            QuotaMeter(t("mail.quota.drive"), account.driveUsed, account.driveQuota)
        }
    }
}

@Composable
private fun SidebarItem(glyph: ToolGlyph, label: String, count: Int?, selected: Boolean, wide: Boolean, emphasise: Boolean = false, onClick: () -> Unit) {
    val (source, hovered) = rememberRowInteraction()
    WithTooltip(label) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(OshiTheme.radiusSm)
                .background(
                    when {
                        selected -> OshiTheme.brand.copy(alpha = 0.14f)
                        hovered.value -> OshiTheme.textPrimary.copy(alpha = 0.05f)
                        else -> Color.Transparent
                    },
                )
                .focusRing(OshiTheme.radiusSm)
                .clickable(interactionSource = source, indication = null, onClickLabel = label, onClick = onClick)
                .hoverable(source)
                .padding(horizontal = OshiTheme.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm, if (wide) Alignment.Start else Alignment.CenterHorizontally),
        ) {
            Box {
                ToolIcon(glyph, if (selected) OshiTheme.brand else Ink.soft, 18.dp)
                if (!wide && count != null && emphasise) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-3).dp).size(8.dp).clip(CircleShape).background(OshiTheme.brand))
            }
            if (wide) {
                Text(label, style = if (selected) ToolType.bodyStrong else ToolType.body, color = Ink.strong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count != null) Text(
                    count.toString(), style = ToolType.caption.copy(fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (emphasise) OshiTheme.brand else Ink.soft,
                )
            }
        }
    }
}

@Composable
private fun QuotaMeter(label: String, used: Long, quota: Long) {
    val frac = if (quota > 0) (used.toFloat() / quota).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(horizontal = OshiTheme.sm), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(label, style = ToolType.caption, color = Ink.soft, modifier = Modifier.weight(1f))
            Text("${humanBytes(used)} / ${humanBytes(quota)}", style = ToolType.caption, color = Ink.soft)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(OshiTheme.pill).background(OshiTheme.separator.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(OshiTheme.pill).background(if (frac > 0.9f) OshiTheme.danger else OshiTheme.brand))
        }
    }
}

// ---------------------------------------------------------------------------- list

@Composable
private fun MailList(model: MailModel, state: MailModel.MailUiState, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(OshiTheme.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            ToolField(state.searchQuery, model::setSearchQuery, t("pc.mail.search"), Modifier.weight(1f), icon = ToolGlyph.SEARCH, container = OshiTheme.surfaceElevated)
            ToolIconButton(ToolGlyph.TEXT, t("mail.deepsearch.help"), active = state.deepSearch) { model.toggleDeepSearch() }
        }
        Text(
            folderLabel(state.folder), style = ToolType.overline, color = Ink.soft,
            modifier = Modifier.padding(horizontal = OshiTheme.lg, vertical = OshiTheme.xs),
        )
        if (state.visibleMessages.isEmpty()) {
            ToolEmptyState(
                if (state.searchQuery.isBlank()) t("empty.no_messages") else t("empty.no_search_results"),
                null, mascot = if (state.searchQuery.isBlank()) "sleep" else "thinking", size = 88.dp,
            )
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(state.visibleMessages, key = { it.id }) { message ->
                MailListRow(message, state.bodyCache[message.id], selected = state.open?.id == message.id) { model.open(message.id) }
                Hairline(inset = OshiTheme.lg)
            }
        }
    }
}

/**
 * __MAIL_LIST_PREVIEW_2026_11_16__ A list row as on the phones: sender (bold while unread),
 * subject, the sealed snippet on up to two lines in the secondary colour, and the image /
 * paperclip markers before the date.
 */
@Composable
private fun MailListRow(message: MailClient.MailSummary, openedBody: String?, selected: Boolean, onClick: () -> Unit) {
    val env = message.envelope
    val unread = !message.seen
    val (source, hovered) = rememberRowInteraction()
    Row(
        Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> OshiTheme.brand.copy(alpha = 0.12f)
                    hovered.value -> OshiTheme.textPrimary.copy(alpha = 0.04f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .hoverable(source)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (unread) OshiTheme.brand else Color.Transparent))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
                Text(
                    env?.let { displayName(it.from) } ?: t("mail.msg.sealedOther"),
                    style = if (unread) ToolType.bodyStrong else ToolType.body,
                    color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (env?.hasImages == true) GlyphIcon(Glyph.IMAGE, Ink.soft, 13.dp)
                if (env?.hasAttachments == true) ToolIcon(ToolGlyph.ATTACH, Ink.soft, 13.dp)
                Text(listDate(message.receivedAt), style = ToolType.caption, color = if (unread) OshiTheme.brand else Ink.soft, maxLines = 1)
            }
            Text(
                env?.subject?.ifBlank { t("pc.mail.noSubject") } ?: "",
                style = if (unread) ToolType.bodyStrong else ToolType.body, color = Ink.strong,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val snippet = com.oshi.desktop.mail.MailHtml.listSnippet(env?.snippet, openedBody?.substringAfter('\n')?.substringAfter('\n'))
            if (snippet != null) Text(snippet, style = ToolType.caption, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------- reader

@Composable
private fun MailReader(model: MailModel, open: MailModel.OpenMail, onFeedback: (String?) -> Unit, confirm: (String, () -> Unit) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Action bar: icons only, each with its name as a tooltip.
        Row(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            ToolIconButton(ToolGlyph.REPLY, t("message.action.reply")) { model.reply(open) }
            ToolIconButton(ToolGlyph.FORWARD, t("message.action.forward")) { model.forward(open) }
            Spacer(Modifier.width(OshiTheme.sm))
            ToolIconButton(ToolGlyph.UNREAD, t("mail.action.markUnread")) { model.markUnread(open.id) }
            if (open.folder != MailModel.FOLDER_ARCHIVE) ToolIconButton(ToolGlyph.ARCHIVE, t("mail.action.archive")) { model.archive(open.id) }
            if (open.folder != MailModel.FOLDER_INBOX) ToolIconButton(ToolGlyph.RESTORE, t("mail.action.restoreInbox")) { model.restore(open.id) }
            ToolIconButton(ToolGlyph.TRASH, if (open.folder == MailModel.FOLDER_TRASH) t("mail.action.deleteForever") else t("common.delete"), tint = OshiTheme.danger) {
                if (open.folder == MailModel.FOLDER_TRASH) confirm(t("mail.confirm.deleteForever")) { model.delete(open.id) }
                else model.delete(open.id)
            }
            Spacer(Modifier.weight(1f))
            ToolIconButton(ToolGlyph.CLOSE, t("common.close")) { model.closeOpen() }
        }
        Hairline()
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = OshiTheme.xl, vertical = OshiTheme.lg).widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
            ) {
                Text(open.subject, style = ToolType.title, color = Ink.strong)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                    Monogram(displayName(open.from), addressOf(open.from), 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(displayName(open.from), style = ToolType.bodyStrong, color = Ink.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(addressOf(open.from), style = ToolType.caption, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t("mail.msg.to", open.to), style = ToolType.caption, color = Ink.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(readerDate(open.date), style = ToolType.caption, color = Ink.soft)
                }
                Hairline()
                val linkColor = OshiTheme.brand
                // __MAIL_READER_2026_11_16__ HTML when the mail has it (paragraphs,
                // bold, links, its own cid: images; remote images blocked until asked).
                val rendered = remember(open.id) { open.html?.let { com.oshi.desktop.mail.MailHtml.render(it) }?.takeIf { it.blocks.isNotEmpty() } }
                if (rendered != null) MailHtmlBody(open, rendered, linkColor)
                else Text(linkifyMailBody(open.body, linkColor), style = ToolType.natural.copy(fontSize = 14.sp), color = Ink.strong)
                // Attached images are SHOWN, as on the phones, before the download rows.
                open.downloadable.filter { it.isImage && it.bytes.size <= MAX_SHOWN_IMAGE_BYTES }.forEach { MailImage(it.bytes, it.filename) }
                if (open.downloadable.isNotEmpty()) {
                    Text(t("new.composer.attachments").uppercase(), style = ToolType.overline, color = Ink.soft)
                    for (attachment in open.downloadable) {
                        ToolRow(
                            attachment.filename, subtitle = humanBytes(attachment.bytes.size.toLong()),
                            icon = if (attachment.isImage) ToolGlyph.LAYERS else ToolGlyph.DOC,
                            modifier = Modifier.clip(OshiTheme.radiusMd).background(OshiTheme.surface),
                        ) {
                            ToolIconButton(ToolGlyph.DOWNLOAD, t("common.save")) {
                                saveMailFile(attachment.filename)?.let { target -> model.saveAttachment(attachment, target) { onFeedback(it) } }
                            }
                            ToolIconButton(ToolGlyph.DRIVE, t("mail.action.saveToDrive")) {
                                model.saveAttachmentToDrive(attachment) { onFeedback(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- drive

@Composable
private fun DriveSection(
    model: MailModel, state: MailModel.MailUiState, pickFile: () -> File?, modifier: Modifier,
    onFeedback: (String?) -> Unit, confirm: (String, () -> Unit) -> Unit,
) {
    var renaming by remember { mutableStateOf<MailClient.DriveFile?>(null) }
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("mail.drive.title"), style = ToolType.section, color = Ink.strong)
                state.account?.let { Text("${humanBytes(it.driveUsed)} / ${humanBytes(it.driveQuota)}", style = ToolType.caption, color = Ink.soft) }
            }
            ToolButton(t("pc.mail.drive.upload"), icon = ToolGlyph.UPLOAD, style = ToolButtonStyle.PRIMARY) {
                pickFile()?.let { model.uploadFile(it) { e -> onFeedback(e) } }
            }
        }
        Hairline()
        if (state.drive.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.busy != null) CircularProgressIndicator(color = OshiTheme.brand)
                else ToolEmptyState(t("mail.drive.emptyTitle"), t("mail.drive.emptyBody"), mascot = "sleep")
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm)) {
            items(state.drive, key = { it.id }) { file ->
                ToolRow(
                    file.name ?: t("pc.mail.drive.unnamed"),
                    subtitle = humanBytes(file.bytes) + (shortDate(file.createdAt)?.let { " · $it" } ?: ""),
                    icon = ToolGlyph.DOC,
                ) {
                    ToolIconButton(ToolGlyph.EDIT, t("mail.drive.rename")) { renaming = file }
                    ToolIconButton(ToolGlyph.DOWNLOAD, t("mail.drive.download")) {
                        saveMailFile(file.name ?: "file")?.let { model.downloadFile(file.id, it) { e -> onFeedback(e) } }
                    }
                    ToolIconButton(ToolGlyph.TRASH, t("common.delete"), tint = OshiTheme.danger) {
                        confirm(t("pc.mail.drive.confirmDelete")) { model.deleteDriveFile(file.id) }
                    }
                }
            }
        }
    }
    renaming?.let { file ->
        var name by remember(file.id) { mutableStateOf(file.name.orEmpty()) }
        OshiAlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t("mail.drive.renameTitle"), style = ToolType.section) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    Text(t("mail.drive.renameHint"), style = ToolType.caption, color = Ink.soft)
                    ToolField(name, { name = it }, t("mail.drive.namePlaceholder"), Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.renameDriveFile(file.id, name) { onFeedback(it) }; renaming = null }) { Text(t("mail.drive.rename")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t("common.cancel")) } },
        )
    }
}

// ---------------------------------------------------------------------------- account

@Composable
private fun AccountSection(
    model: MailModel, state: MailModel.MailUiState, modifier: Modifier,
    onFeedback: (String?) -> Unit, onBrowsers: () -> Unit, confirm: (String, () -> Unit) -> Unit,
) {
    val account = state.account ?: return
    var alias by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(OshiTheme.xl),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.lg),
    ) {
        Text(t("mail.section.usage"), style = ToolType.section, color = Ink.strong)
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
            val sentFrac = if (account.sendQuota > 0) (account.sendQuota - account.sendRemaining).toFloat() / account.sendQuota else 0f
            ToolStat(ToolGlyph.SEND, t("mail.quota.sentThisMonth"), "${account.sendQuota - account.sendRemaining} / ${account.sendQuota}", Modifier.weight(1f), level = sentFrac)
            ToolStat(ToolGlyph.INBOX, t("mail.quota.mailbox"), humanBytes(account.storageUsed), Modifier.weight(1f),
                detail = "/ ${humanBytes(account.storageQuota)}", level = if (account.storageQuota > 0) account.storageUsed.toFloat() / account.storageQuota else 0f)
            ToolStat(ToolGlyph.DRIVE, t("mail.quota.drive"), humanBytes(account.driveUsed), Modifier.weight(1f),
                detail = "/ ${humanBytes(account.driveQuota)}", level = if (account.driveQuota > 0) account.driveUsed.toFloat() / account.driveQuota else 0f)
        }
        ToolCard(title = t("mail.alias.count", account.aliases.size, account.aliasLimit), icon = ToolGlyph.PEOPLE) {
            ToolHint(t("mail.alias.explain"))
            ToolRow(account.address, subtitle = t("mail.account.title"), icon = ToolGlyph.MAIL)
            for (a in account.aliases) {
                ToolRow(a, icon = ToolGlyph.LINK) {
                    ToolIconButton(ToolGlyph.TRASH, t("common.remove"), tint = OshiTheme.danger) {
                        confirm(t("pc.mail.alias.confirmRemove", a)) { model.removeAlias(a) }
                    }
                }
            }
            val canAdd = account.aliases.size < account.aliasLimit
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                ToolField(alias, { alias = it }, t("mail.alias.newPlaceholder"), Modifier.weight(1f)) {
                    Text("@${MailClient.MAIL_DOMAIN}", style = ToolType.body, color = Ink.soft)
                }
                ToolButton(t("common.add"), icon = ToolGlyph.PLUS, enabled = !working && alias.isNotBlank() && canAdd) {
                    working = true
                    model.addAlias(alias.trim()) { onFeedback(it); working = false; if (it == null) alias = "" }
                }
            }
            val keyAlias = model.keyBasedAliasLocalpart()
            if (keyAlias !in account.aliases && canAdd) {
                ToolButton(t("mail.alias.usekey", keyAlias.take(8)), icon = ToolGlyph.KEY, style = ToolButtonStyle.QUIET) { alias = keyAlias }
            }
        }
        ToolCard(title = t("mail.pair.title"), icon = ToolGlyph.BROWSER) {
            ToolHint(t("mail.browser.hint"))
            ToolButton(t("mail.browser.connect"), icon = ToolGlyph.BROWSER) { onBrowsers() }
        }
    }
}

// ---------------------------------------------------------------------------- browser pairing

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
    OshiAlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { ProvideLocaleDirection { Text(t("mail.pair.title"), style = ToolType.section) } },
        text = {
            ProvideLocaleDirection {
                Column(Modifier.widthIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    when {
                        approvedAddress != null -> {
                            ToolNotice(t("mail.pair.connected"), ToolTone.SUCCESS)
                            Text(t("mail.pair.canRead", approvedAddress!!), style = ToolType.body, color = Ink.strong)
                        }
                        pairing != null -> {
                            Text(t("mail.pair.asking"), style = ToolType.bodyStrong, color = Ink.strong)
                            ToolValue(t("pc.mail.pair.code"), pairing!!.code, mono = true)
                            ToolValue(t("mail.account.title"), address)
                            ToolHint(t("mail.pair.approveHint"))
                        }
                        else -> {
                            ToolHint(t("mail.pair.openHint"))
                            ToolField(code, { code = it }, t("mail.pair.codePlaceholder"), Modifier.fillMaxWidth(), mono = true, enabled = !working)
                            ToolButton(t("identity.vault.scan_qr"), icon = ToolGlyph.UPLOAD, style = ToolButtonStyle.QUIET, enabled = !working) {
                                pickMailPairingQrImage()?.let { image ->
                                    working = true; error = null
                                    model.inspectBrowserPairingQr(image) { found, problem -> working = false; pairing = found; error = problem }
                                }
                            }
                            ToolHint(t("mail.pair.noCamera"))
                        }
                    }
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = OshiTheme.brand)
                    error?.let { ToolNotice(it, ToolTone.ERROR) }
                    if (state.webSessions.isNotEmpty()) {
                        Spacer(Modifier.height(OshiTheme.xs))
                        Text(t("mail.pair.connectedList").uppercase(), style = ToolType.overline, color = Ink.soft)
                        state.webSessions.forEach { session ->
                            ToolRow(session.label, subtitle = t("mail.pair.lastUsed", shortDate(session.lastSeenAt) ?: session.lastSeenAt.take(16)), icon = ToolGlyph.BROWSER) {
                                ToolIconButton(ToolGlyph.CLOSE, t("common.remove"), enabled = !working) {
                                    working = true
                                    model.revokeBrowserSession(session.id) { problem -> working = false; error = problem }
                                }
                            }
                        }
                        ToolButton(t("mail.pair.signoutAll"), style = ToolButtonStyle.DANGER, enabled = !working) {
                            working = true
                            model.revokeBrowserSession("all") { problem -> working = false; error = problem }
                        }
                        ToolHint(t("mail.pair.signoutHint"))
                    }
                }
            }
        },
        confirmButton = {
            when {
                approvedAddress != null -> TextButton(onClick = onDismiss) { Text(t("common.done")) }
                pairing != null -> Button(enabled = !working, onClick = {
                    working = true; error = null
                    model.approveBrowserPairing(pairing!!, "OSHI Desktop") { approved, problem ->
                        working = false; approvedAddress = approved; error = problem
                    }
                }) { Text(t("mail.pair.approve")) }
                else -> Button(enabled = cleanCode.length >= 8 && !working, onClick = {
                    working = true; error = null
                    model.inspectBrowserPairing(cleanCode) { found, problem -> working = false; pairing = found; error = problem }
                }) { Text(t("common.continue")) }
            }
        },
        dismissButton = { if (approvedAddress == null) TextButton(enabled = !working, onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

// ---------------------------------------------------------------------------- composer

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
    var showCc by remember(draft.composerId) { mutableStateOf(draft.cc.isNotBlank()) }
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
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs)) {
            Text(if (draft.inReplyTo != null) draft.subject.ifBlank { t("mail.compose.title") } else t("mail.compose.title"), style = ToolType.section, color = Ink.strong, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolIconButton(ToolGlyph.ATTACH, t("mail.compose.attachFile"), enabled = !busy) { pickFile()?.let(model::attachFile) }
            ToolIconButton(ToolGlyph.DRIVE, t("mail.compose.attachDrive"), enabled = !busy) { pickingDrive = true; model.loadDrive() }
            ToolIconButton(ToolGlyph.TRASH, t("pc.mail.discard"), enabled = !busy, tint = OshiTheme.danger) {
                if (draft.id == null) model.clearEditingDraft() else model.deleteDraft(draft.id)
            }
            Spacer(Modifier.width(OshiTheme.xs))
            ToolButton(t("pc.mail.saveClose"), style = ToolButtonStyle.QUIET, enabled = !busy && draft.attachments.isEmpty()) {
                busy = true
                model.autosaveDraft(draft.id, draft.to, draft.cc, draft.subject, draft.body) { id ->
                    busy = false
                    if (model.state.editingDraft?.composerId == draft.composerId && (id != null || listOf(draft.to, draft.cc, draft.subject, draft.body).all(String::isBlank))) model.clearEditingDraft()
                }
            }
            ToolButton(t("common.send"), icon = ToolGlyph.SEND, style = ToolButtonStyle.PRIMARY, enabled = !busy && draft.to.isNotBlank()) {
                busy = true
                model.send(draft.to.split(',').map(String::trim).filter(String::isNotEmpty), draft.cc.split(',').map(String::trim).filter(String::isNotEmpty), draft.subject, draft.body, draft.inReplyTo, draft.attachments, from, draft.id) {
                    error = it; busy = false
                }
            }
        }
        Hairline()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md),
            verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
        ) {
            if (sendingAddresses.size > 1) {
                Box {
                    ComposerLine(t("pc.mail.field.from")) {
                        Row(Modifier.clip(OshiTheme.radiusSm).clickable(enabled = !busy) { choosingFrom = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(from ?: sendingAddresses.first(), style = ToolType.body, color = Ink.strong)
                            ToolIcon(ToolGlyph.CHEVRON, Ink.soft, 14.dp, Modifier.padding(start = 4.dp))
                        }
                    }
                    OshiDropdownMenu(expanded = choosingFrom, onDismissRequest = { choosingFrom = false }) {
                        sendingAddresses.forEach { address ->
                            DropdownMenuItem(text = { Text(address) }, onClick = { from = address.takeIf { it != sendingAddresses.first() }; choosingFrom = false })
                        }
                    }
                }
            }
            ComposerLine(t("mail.compose.to")) {
                ComposerInput(draft.to, { model.editDraft(draft.copy(to = it)) }, enabled = !busy, modifier = Modifier.weight(1f))
                if (!showCc) Text(t("pc.mail.field.cc"), style = ToolType.caption, color = OshiTheme.brand, modifier = Modifier.clip(OshiTheme.radiusSm).clickable { showCc = true }.padding(4.dp))
            }
            val lastToken = draft.to.substringAfterLast(',').trim()
            val suggestions = state.recipientSuggestions(draft.to).filterNot { it.second.equals(lastToken, ignoreCase = true) }
            if (suggestions.isNotEmpty()) Column(Modifier.clip(OshiTheme.radiusMd).background(OshiTheme.surface)) {
                suggestions.forEach { (name, address) ->
                    ToolRow(name.ifBlank { address }, subtitle = address.takeIf { name.isNotBlank() }, leading = { Monogram(name.ifBlank { address }, address, 28.dp) }, onClick = {
                        model.editDraft(draft.copy(to = state.applyRecipientSuggestion(draft.to, address)))
                    })
                }
            }
            if (showCc) ComposerLine(t("pc.mail.field.cc")) { ComposerInput(draft.cc, { model.editDraft(draft.copy(cc = it)) }, enabled = !busy, modifier = Modifier.weight(1f)) }
            ComposerLine(t("mail.compose.subject")) { ComposerInput(draft.subject, { model.editDraft(draft.copy(subject = it)) }, enabled = !busy, modifier = Modifier.weight(1f)) }
            if (draft.attachments.isNotEmpty()) {
                FlowRowCompat {
                    draft.attachments.forEachIndexed { index, attachment ->
                        Row(
                            Modifier.clip(OshiTheme.pill).background(OshiTheme.surface).padding(start = OshiTheme.md, end = OshiTheme.xs, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.xs),
                        ) {
                            ToolIcon(ToolGlyph.ATTACH, Ink.soft, 14.dp)
                            Text(attachment.filename, style = ToolType.caption, color = Ink.strong, maxLines = 1)
                            ToolIconButton(ToolGlyph.CLOSE, t("common.remove"), size = 26.dp, enabled = !busy) {
                                model.editDraft(draft.copy(attachments = draft.attachments.filterIndexed { i, _ -> i != index }))
                            }
                        }
                    }
                }
                ToolHint(t("pc.mail.draftTextOnly"))
            }
            error?.let { ToolNotice(it, ToolTone.ERROR) }
            Hairline()
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                if (draft.body.isEmpty()) Text(t("new.composer.message"), style = ToolType.body, color = Ink.soft)
                androidx.compose.foundation.text.BasicTextField(
                    value = draft.body, onValueChange = { model.editDraft(draft.copy(body = it)) }, enabled = !busy,
                    textStyle = ToolType.body.copy(color = Ink.strong, fontSize = ToolType.body.fontSize * 1.08f),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                )
            }
        }
    }
    if (pickingDrive) OshiAlertDialog(
        onDismissRequest = { if (!busy) pickingDrive = false },
        title = { Text(t("mail.compose.attachDrive"), style = ToolType.section) },
        text = {
            ProvideLocaleDirection {
                when {
                    state.busy != null && state.drive.isEmpty() -> CircularProgressIndicator(color = OshiTheme.brand)
                    state.drive.isEmpty() -> ToolEmptyState(t("mail.drive.emptyTitle"), t("mail.drive.emptyBody"), mascot = "sleep", size = 72.dp)
                    else -> Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        state.drive.forEach { file ->
                            ToolRow(file.name ?: t("pc.mail.drive.unnamed"), subtitle = humanBytes(file.bytes), icon = ToolGlyph.DOC, onClick = if (!busy && file.name != null) ({
                                busy = true
                                model.attachDriveFile(file) { problem ->
                                    busy = false; error = problem
                                    if (problem == null) pickingDrive = false
                                }
                            }) else null)
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { pickingDrive = false }) { Text(t("common.cancel")) } },
        confirmButton = {},
    )
}

@Composable
private fun ComposerLine(label: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
            Text(label, style = ToolType.caption, color = Ink.soft, modifier = Modifier.width(64.dp))
            content()
        }
        Hairline()
    }
}

@Composable
private fun ComposerInput(value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValue, enabled = enabled, singleLine = true,
        textStyle = ToolType.body.copy(color = Ink.strong),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(OshiTheme.brand),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) { content() }
}

// ---------------------------------------------------------------------------- helpers

/** Folder wire values are fixed by [MailModel]; keep their catalog keys explicit for the audit. */
private fun folderLabel(folder: String): String = when (folder) {
    MailModel.FOLDER_INBOX -> t("mail.folder.inbox")
    MailModel.FOLDER_SENT -> t("mail.folder.sent")
    MailModel.FOLDER_DRAFT -> t("mail.folder.drafts")
    MailModel.FOLDER_ARCHIVE -> t("mail.folder.archive")
    MailModel.FOLDER_TRASH -> t("mail.folder.trash")
    else -> folder
}

private fun folderGlyph(folder: String): ToolGlyph = when (folder) {
    MailModel.FOLDER_INBOX -> ToolGlyph.INBOX
    MailModel.FOLDER_SENT -> ToolGlyph.SEND
    MailModel.FOLDER_DRAFT -> ToolGlyph.DRAFT
    MailModel.FOLDER_ARCHIVE -> ToolGlyph.ARCHIVE
    MailModel.FOLDER_TRASH -> ToolGlyph.TRASH
    else -> ToolGlyph.FOLDER
}

/** `"Camille" <c@x.org>` → Camille; a bare address stays an address. */
internal fun displayName(from: String): String =
    from.substringBefore('<').trim().trim('"').ifBlank { from.substringAfter('<').substringBefore('>').ifBlank { from } }

internal fun addressOf(from: String): String =
    if ('<' in from) from.substringAfter('<').substringBefore('>') else from.trim()

private fun locale(): Locale = Locale.forLanguageTag(Strings.activeTag)

private fun parseInstant(raw: String): java.time.ZonedDateTime? =
    runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
        ?: runCatching { java.time.ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
        ?: runCatching { java.time.Instant.parse(raw).atZone(ZoneId.systemDefault()) }.getOrNull()

/** Today → the time; this year → day and month; older → the short date. */
internal fun listDate(raw: String, today: LocalDate = LocalDate.now()): String {
    val z = parseInstant(raw) ?: return raw.take(10)
    val l = locale()
    return when {
        z.toLocalDate() == today -> z.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(l))
        z.year == today.year -> z.format(DateTimeFormatter.ofPattern("d MMM", l))
        else -> z.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(l))
    }
}

private fun readerDate(raw: String): String {
    val z = parseInstant(raw) ?: return raw
    return z.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale()))
}

private fun shortDate(raw: String): String? =
    parseInstant(raw)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()))

private fun saveMailFile(name: String): File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("common.save"), FileDialog.SAVE)
    return try {
        dialog.file = name.replace('\\', '/').substringAfterLast('/').ifBlank { "attachment" }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    } finally { dialog.dispose() }
}

/** Browser pairing has no webcam dependency: a screenshot or phone photo works everywhere. */
private fun pickMailPairingQrImage(): File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("identity.vault.scan_qr"), FileDialog.LOAD)
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

private fun linkifyMailBody(text: String, linkColor: Color): AnnotatedString =
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
        com.oshi.desktop.ui.openInBrowser(uri.toString())
    }
}

private const val MAX_SHOWN_IMAGE_BYTES = 12 * 1024 * 1024
private const val MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024

/** The rendered HTML body: paragraphs with links, inline cid: images, remote images behind a button. */
@Composable
private fun MailHtmlBody(open: MailModel.OpenMail, rendered: com.oshi.desktop.mail.MailHtml.Rendered, linkColor: Color) {
    var loadRemote by remember(open.id) { mutableStateOf(false) }
    val byCid = remember(open.id) { open.attachments.filter { it.contentId != null }.associateBy { it.contentId!!.lowercase() } }
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
        if (rendered.remoteImageCount > 0 && !loadRemote) {
            Row(
                Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.surface).padding(OshiTheme.md),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm),
            ) {
                ToolIcon(ToolGlyph.EYE_OFF, Ink.soft, 16.dp)
                Text(t("mail.images.blocked"), style = ToolType.caption, color = Ink.strong, modifier = Modifier.weight(1f))
                ToolButton(t("mail.images.loadOnce"), style = ToolButtonStyle.QUIET) { loadRemote = true }
            }
        }
        for (block in rendered.blocks) when (block) {
            is com.oshi.desktop.mail.MailHtml.Block.Paragraph -> Text(
                buildAnnotatedString {
                    for (span in block.spans) {
                        val style = SpanStyle(fontWeight = if (span.bold || block.heading) FontWeight.Bold else null)
                        if (span.href != null) {
                            withLink(LinkAnnotation.Url(span.href, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { openInBrowser(span.href) }) {
                                pushStyle(style); append(span.text); pop()
                            }
                        } else { pushStyle(style); append(span.text); pop() }
                    }
                },
                style = if (block.heading) ToolType.section else ToolType.natural.copy(fontSize = 14.sp),
                color = Ink.strong,
            )
            is com.oshi.desktop.mail.MailHtml.Block.InlineImage -> byCid[block.contentId.lowercase()]?.let { MailImage(it.bytes, block.alt) }
            is com.oshi.desktop.mail.MailHtml.Block.RemoteImage -> if (loadRemote) RemoteMailImage(block.url, block.alt)
        }
    }
}

@Composable
private fun MailImage(bytes: ByteArray, alt: String) {
    val bitmap = remember(bytes) {
        // __IMAGE_PIXEL_BOMB_2026_11_20__ header-checked before decoding.
        runCatching { com.oshi.desktop.media.SafeImage.decode(bytes)!!.toComposeImageBitmap() }.getOrNull()
    } ?: return
    // Tracking pixels and spacer GIFs: not worth a slot.
    if (bitmap.width < 4 || bitmap.height < 4) return
    androidx.compose.foundation.Image(
        bitmap, contentDescription = alt.ifBlank { null },
        modifier = Modifier.widthIn(max = minOf(bitmap.width, 640).dp).fillMaxWidth().clip(OshiTheme.radiusMd),
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
    )
}

/** Fetched only after "Load images for this message": https/http, 5 MB cap, 10 s timeout, no redirects to other schemes. */
@Composable
private fun RemoteMailImage(url: String, alt: String) {
    val bytes by produceState<ByteArray?>(null, url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val client = java.net.http.HttpClient.newBuilder()
                    // __MAIL_IMAGE_REDIRECT_2026_11_20__ NEVER: a sender's image must not bounce the
                    // fetch to a LAN/intranet address or another host.
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build()
                val req = java.net.http.HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofSeconds(10)).GET().build()
                val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
                if (resp.statusCode() !in 200..299) null
                else resp.body().use { it.readNBytes(MAX_REMOTE_IMAGE_BYTES + 1) }.takeIf { it.size <= MAX_REMOTE_IMAGE_BYTES }
            }.getOrNull()
        }
    }
    bytes?.let { MailImage(it, alt) }
}
