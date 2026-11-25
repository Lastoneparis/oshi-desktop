package com.oshi.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.stego.DesktopPhotoDisguise
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ContactRow
import com.oshi.messenger.service.stego.StegoV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * __PRIVACY_DISGUISE_2026_11_18__ "Privacy disguise" on the desktop: the settings pane, the
 * "Disguise as photo" sheet, the reveal sheet and the drop target — the phones' feature in
 * the window's own idiom (glass cards, the brand pill, the mascot), with desktop verbs:
 * save / copy-as-file instead of a share sheet, a file dialog or a drag instead of a photo
 * picker, keyboard shortcuts on the menu.
 *
 * Copy comes from the phones' catalog keys (`disguise.*`, 34 locales) wherever the phone
 * sentence is TRUE here; where it is not — the phones say "In a 1:1 chat: + → …" and
 * "share" — the desktop says its own sentence (`desktop.disguise.*`, English overlay).
 *
 * The crypto is not in this file. [DesktopPhotoDisguise] wraps Android's own STEGO-v1 code.
 */
class PhotoDisguiseHost(
    private val selfPriv: ByteArray,
    private val selfPub: ByteArray,
    private val contacts: () -> List<ContactRow>,
) {
    sealed interface RevealUi {
        val fileName: String
        data class Checking(override val fileName: String) : RevealUi
        data class Found(override val fileName: String, val from: String, val payload: StegoV1.Payload) : RevealUi
        data class Damaged(override val fileName: String, val from: String) : RevealUi
        data class NotForYou(override val fileName: String) : RevealUi
        data class Unreadable(override val fileName: String) : RevealUi
    }

    /** The contact the disguise sheet is open for, or null. */
    var sheetFor by mutableStateOf<ContactRow?>(null)
    var reveal by mutableStateOf<RevealUi?>(null)
    /** A file is being dragged over the window. */
    var dragging by mutableStateOf(false)
    /** What a newly opened sheet's message box starts with (the screenshot renderer sets it). */
    var draftPrefill: String = ""

    fun open(contact: ContactRow) { reveal = null; sheetFor = contact }
    fun close() { sheetFor = null; reveal = null }

    fun labelFor(address: String): String {
        val key = DesktopPhotoDisguise.keyBytes(address)
        return contacts().firstOrNull { c -> DesktopPhotoDisguise.keyBytes(c.address)?.contentEquals(key) == true }?.label
            ?: (address.take(10) + "…")
    }

    /** Off the UI thread; the sheet shows "Looking…" meanwhile. */
    fun revealFile(file: File) {
        sheetFor = null
        reveal = RevealUi.Checking(file.name)
        Thread({
            val result = runCatching {
                val bytes = file.readBytes()
                DesktopPhotoDisguise.reveal(selfPriv, selfPub, bytes, contacts().filterNot { it.blocked }.map { it.address })
            }.getOrDefault(DesktopPhotoDisguise.Outcome.Unreadable)
            reveal = when (result) {
                is DesktopPhotoDisguise.Outcome.Found -> RevealUi.Found(file.name, labelFor(result.senderAddress), result.payload)
                is DesktopPhotoDisguise.Outcome.Damaged -> RevealUi.Damaged(file.name, labelFor(result.senderAddress))
                DesktopPhotoDisguise.Outcome.NotForYou -> RevealUi.NotForYou(file.name)
                DesktopPhotoDisguise.Outcome.Unreadable -> RevealUi.Unreadable(file.name)
            }
        }, "oshi-stego-reveal").apply { isDaemon = true }.start()
    }

    fun hide(recipient: ContactRow, cover: StegoV1.RgbImage, payload: StegoV1.Payload): ByteArray =
        DesktopPhotoDisguise.hide(selfPriv, selfPub, recipient.address, cover, payload)

    /** The file dialog — images only. Null when cancelled. */
    fun pickImage(title: String): File? {
        val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(title, java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { dir, name -> DesktopPhotoDisguise.looksLikeImageFile(File(dir, name)) }
        // __FILEDIALOG_FILTER_IS_A_NOOP_ON_WINDOWS_2026_11_19__ (see AiPane.pickGgufFile)
        if (com.oshi.desktop.store.DesktopPaths.isWindows) dialog.file = "*.png;*.jpg;*.jpeg;*.heic;*.webp;*.gif;*.bmp"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name).takeIf { DesktopPhotoDisguise.looksLikeImageFile(it) }
    }

    fun chooseAndReveal() { pickImage(t("disguise.reveal.button"))?.let(::revealFile) }
}

// ============================================================================ the pane

/** Settings → Privacy disguise. Two independent features, one card each, honest captions. */
@Composable
fun PrivacyDisguisePane(
    host: PhotoDisguiseHost,
    contacts: List<ContactRow>,
    coverOn: Boolean,
    onCoverChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** __RESTRICTED_NETWORK_HINT_2026_11_18__ "Let contacts know I'm on a restricted network". */
    hintOn: Boolean = true,
    onHintChange: (Boolean) -> Unit = {},
) {
    val known = remember(contacts) { contacts.filterNot { it.blocked } }
    var peer by remember(known) { mutableStateOf(known.firstOrNull()?.address.orEmpty()) }

    Column(
        modifier.fillMaxSize().background(OshiTheme.paneGround)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OshiTheme.xxl, vertical = OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            // __DESKTOP_SETTINGS_2027__ the same header every Settings page has.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    SettingsPageHeader(SettingsIcon.DISGUISE, t("disguise.title"), t("desktop.disguise.subtitle"))
                }
                OshiMascot.named("peek")?.let {
                    MascotImage(it, 56.dp, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
                }
            }

            // ----------------------------------------------------------- traffic
            PaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandDisc(Glyph.LOCK, 36.dp)
                    Spacer(Modifier.width(OshiTheme.md))
                    Text(t("disguise.cover.title"), style = OshiTheme.typography.titleMedium, color = Ink.strong, modifier = Modifier.weight(1f))
                    Switch(checked = coverOn, onCheckedChange = onCoverChange)
                }
                Spacer(Modifier.height(OshiTheme.md))
                Text(t("disguise.cover.caption"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                Spacer(Modifier.height(OshiTheme.md))
                Hairline()
                Spacer(Modifier.height(OshiTheme.md))
                // The desktop's own sentence, not the phones' `disguise.cover.not_hidden`: here
                // groups ARE disguised (they are V2 fan-out, no legacy relay), and saying they
                // are not would be the wrong kind of honest.
                Caveat(Glyph.EYE_SLASH, OshiTheme.warning, t("desktop.disguise.cover.not_hidden"))
                Spacer(Modifier.height(OshiTheme.sm))
                Caveat(Glyph.ALERT, Ink.soft, t("desktop.disguise.cover.desktop_note"))
                Spacer(Modifier.height(OshiTheme.md))
                Hairline()
                Spacer(Modifier.height(OshiTheme.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("rnhint.setting.title"), style = OshiTheme.typography.bodyMedium, color = Ink.strong, modifier = Modifier.weight(1f))
                    Switch(checked = hintOn, onCheckedChange = onHintChange)
                }
                Spacer(Modifier.height(OshiTheme.xs))
                Text(t("rnhint.setting.caption"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            }

            Spacer(Modifier.height(OshiTheme.xl))

            // ------------------------------------------------------------- photo
            Text(t("disguise.photo.title"), style = OshiTheme.typography.titleLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.sm))
            PaneCard {
                Text(t("desktop.disguise.explainer"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.lg))
                if (known.isEmpty()) {
                    Text(t("desktop.disguise.no_contacts"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                } else {
                    Text(t("desktop.disguise.for"), style = OshiTheme.typography.labelLarge, color = Ink.soft)
                    Spacer(Modifier.height(OshiTheme.xs))
                    for (c in known.take(8)) {
                        DisguisePeer(c, c.address == peer) { peer = c.address }
                    }
                    if (known.size > 8) {
                        Text(t("desktop.disguise.more_contacts", known.size - 8), fontSize = 11.sp, color = Ink.soft)
                    }
                    Spacer(Modifier.height(OshiTheme.lg))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                        PrimaryButton(t("disguise.photo.title") + "…", enabled = peer.isNotEmpty()) {
                            known.firstOrNull { it.address == peer }?.let(host::open)
                        }
                        ShortcutHint(shift = true, letter = "D")
                    }
                }
                Spacer(Modifier.height(OshiTheme.lg))
                Hairline()
                Spacer(Modifier.height(OshiTheme.md))
                Caveat(Glyph.ALERT, OshiTheme.warning, t("disguise.warning.recompress"))
                Spacer(Modifier.height(OshiTheme.sm))
                Caveat(Glyph.EYE_SLASH, Ink.soft, t("disguise.warning.forensic"))
            }

            Spacer(Modifier.height(OshiTheme.xl))

            // ------------------------------------------------------------ reveal
            Text(t("disguise.reveal.button"), style = OshiTheme.typography.titleLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.sm))
            DropZone(active = host.dragging, onClick = host::chooseAndReveal)
            Spacer(Modifier.height(OshiTheme.sm))
            Text(t("disguise.reveal.hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.xxl))
        }
    }
}

@Composable
private fun Caveat(glyph: Glyph, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp)) { GlyphIcon(glyph, tint, 14.dp) }
        Spacer(Modifier.width(OshiTheme.sm))
        Text(text, style = OshiTheme.typography.bodySmall, color = Ink.soft)
    }
}

@Composable
private fun ShortcutHint(shift: Boolean, letter: String) {
    val mac = com.oshi.desktop.ui.MacDesktopIntegration.isMac
    val label = (if (mac) "⌘" else "Ctrl+") + (if (shift) (if (mac) "⇧" else "Shift+") else "") + letter
    Text(
        label, fontSize = 11.sp, color = Ink.soft,
        modifier = Modifier.clip(OshiTheme.radiusSm).background(OshiTheme.surface).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DisguisePeer(c: ContactRow, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (selected) Modifier.glass(OshiTheme.radiusMd, OshiTheme.surfaceElevated, raised = true)
                else Modifier.clip(OshiTheme.radiusMd)
            )
            .clickable(onClick = onPick)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(c.label, c.address, 28.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Text(c.label, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
            Text(c.addressShort, fontSize = 11.sp, color = Ink.soft)
        }
        if (selected) GlyphIcon(Glyph.CHECK, OshiTheme.brand, 14.dp)
    }
}

/** The dashed drop target; also a button that opens the file dialog. */
@Composable
fun DropZone(active: Boolean, onClick: () -> Unit) {
    val ring = if (active) OshiTheme.brand else OshiTheme.separator
    Column(
        Modifier.fillMaxWidth()
            .clip(OshiTheme.radiusXl)
            .background(if (active) OshiTheme.brand.copy(alpha = 0.10f) else OshiTheme.surface)
            .border(if (active) 2.dp else 1.dp, ring, OshiTheme.radiusXl)
            .clickable(onClick = onClick)
            .padding(vertical = OshiTheme.xl, horizontal = OshiTheme.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OshiMascot.named(if (active) "wow" else "thinking")?.let { MascotImage(it, 48.dp, playing = active) }
        Spacer(Modifier.height(OshiTheme.sm))
        Text(t("desktop.disguise.drop"), style = OshiTheme.typography.titleMedium, color = Ink.strong)
        Spacer(Modifier.height(OshiTheme.xxs))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
            Text(t("disguise.reveal.from_files"), style = OshiTheme.typography.labelLarge, color = OshiTheme.brand)
            ShortcutHint(shift = true, letter = "R")
        }
    }
}

// ============================================================================ the overlays

/**
 * The sheets and the drag hint, drawn over the whole window. Each sheet is a modal card on a
 * scrim, sized for a desktop (≤ 560 dp wide, scrolling inside), with an explicit Close and
 * Esc — the window has no swipe to dismiss.
 */
@Composable
fun PhotoDisguiseOverlays(host: PhotoDisguiseHost) {
    host.sheetFor?.let { c -> Scrim(host::close) { DisguiseSheet(host, c) } }
    host.reveal?.let { r -> Scrim(host::close) { RevealSheet(host, r) } }
    if (host.dragging && host.sheetFor == null && host.reveal == null) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).padding(OshiTheme.xxl),
            contentAlignment = Alignment.Center,
        ) {
            // Opaque under the zone's own tint, so the pane behind does not read through it.
            Box(Modifier.widthIn(max = 460.dp).clip(OshiTheme.radiusXl).background(OshiTheme.surfaceElevated)) {
                DropZone(active = true, onClick = {})
            }
        }
    }
}

@Composable
private fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // Focusable so Esc reaches it even when nothing inside the sheet holds focus (the reveal
    // sheet has no field); a sheet that requests focus for a field takes it after this.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false
            }
            .focusRequester(focus)
            .focusable()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(OshiTheme.xl),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SheetCard(
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.widthIn(min = 380.dp, max = 600.dp)
            .glassFloating(OshiTheme.radiusXl, OshiTheme.surfaceElevated, OshiTheme.cardOutline)
            // Swallow clicks so a click inside the card does not reach the scrim.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(OshiTheme.xl),
    ) {
        // The body scrolls; the actions stay pinned, so a short window never hides the button.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        if (footer != null) {
            Spacer(Modifier.height(OshiTheme.lg))
            footer()
        }
    }
}

@Composable
private fun SheetTitle(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandDisc(Glyph.EYE_SLASH, 32.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Text(title, style = OshiTheme.typography.headlineSmall, color = Ink.strong, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(OshiTheme.pill).clickable(onClick = onClose).padding(OshiTheme.xs),
        ) { GlyphIcon(Glyph.CLOSE, Ink.soft, 16.dp) }
    }
}

// ---------------------------------------------------------------------------- disguise sheet

private sealed interface CoverChoice {
    data object Generated : CoverChoice
    data class Photo(val file: File) : CoverChoice
}

@Composable
private fun DisguiseSheet(host: PhotoDisguiseHost, contact: ContactRow) {
    val scope = rememberCoroutineScope()
    var text by remember(contact.address) { mutableStateOf(host.draftPrefill) }
    var attachment by remember(contact.address) { mutableStateOf<StegoV1.Attachment?>(null) }
    var fileProblem by remember(contact.address) { mutableStateOf<String?>(null) }
    var choice by remember(contact.address) { mutableStateOf<CoverChoice>(CoverChoice.Generated) }
    var seed by remember(contact.address) { mutableStateOf(System.nanoTime()) }
    var cover by remember(contact.address) { mutableStateOf<StegoV1.RgbImage?>(null) }
    var preview by remember(contact.address) { mutableStateOf<ImageBitmap?>(null) }
    var working by remember(contact.address) { mutableStateOf(false) }
    var problem by remember(contact.address) { mutableStateOf<String?>(null) }
    var saved by remember(contact.address) { mutableStateOf<File?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(choice, seed) {
        cover = null; preview = null
        val c = withContext(Dispatchers.Default) {
            when (val ch = choice) {
                CoverChoice.Generated -> DesktopPhotoDisguise.generatedCover(seed)
                is CoverChoice.Photo -> DesktopPhotoDisguise.coverFromFile(ch.file)
            }
        }
        if (c == null) { problem = t("disguise.failed"); choice = CoverChoice.Generated; return@LaunchedEffect }
        cover = c
        preview = withContext(Dispatchers.Default) { DesktopPhotoDisguise.toBufferedImage(c).toComposeImageBitmap() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val payload = StegoV1.Payload(text, System.currentTimeMillis(), attachment)
    val needed = remember(text, attachment) { DesktopPhotoDisguise.needed(payload) }
    val available = cover?.let { DesktopPhotoDisguise.capacity(it) }
    val fits = available != null && needed <= available
    val canCreate = (text.isNotBlank() || attachment != null) && fits && !working

    val create: () -> Unit = create@{
        val c = cover ?: return@create
        if (!canCreate) return@create
        working = true; problem = null
        scope.launch {
            val png = withContext(Dispatchers.Default) {
                runCatching { host.hide(contact, c, StegoV1.Payload(text, System.currentTimeMillis(), attachment)) }.getOrNull()
            }
            working = false
            if (png == null) { problem = t("disguise.failed"); return@launch }
            val target = pickSaveTarget(DesktopPhotoDisguise.suggestedFileName()) ?: return@launch
            problem = runCatching { target.writeBytes(png); saved = target; null }.getOrElse { t("disguise.failed") }
        }
    }

    SheetCard(footer = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction(t("common.cancel"), host::close)
            Spacer(Modifier.weight(1f))
            if (working) {
                CircularProgressIndicator(color = OshiTheme.brand, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(OshiTheme.sm))
                Text(t("disguise.working"), fontSize = 12.sp, color = Ink.soft)
                Spacer(Modifier.width(OshiTheme.md))
            }
            PrimaryButton(t("desktop.disguise.create_save"), enabled = canCreate, onClick = create)
        }
    }) {
        Column(Modifier.onPreviewKeyEvent { e ->
            val cmd = e.isMetaPressed || e.isCtrlPressed
            if (e.type == KeyEventType.KeyDown && cmd && (e.key == Key.S || e.key == Key.Enter)) { create(); true } else false
        }) {
            SheetTitle(t("disguise.photo.title"), host::close)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(t("disguise.sheet.for", contact.label), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            Spacer(Modifier.height(OshiTheme.lg))

            // message
            Box(
                Modifier.fillMaxWidth().heightIn(min = 96.dp)
                    .clip(OshiTheme.radiusMd).background(OshiTheme.surface)
                    .border(1.dp, if (text.isEmpty()) OshiTheme.separator else OshiTheme.brand.copy(alpha = 0.6f), OshiTheme.radiusMd)
                    .padding(OshiTheme.md),
            ) {
                if (text.isEmpty()) Text(t("disguise.message_hint"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; saved = null },
                    textStyle = TextStyle(fontSize = 14.sp, color = Ink.strong),
                    cursorBrush = SolidColor(OshiTheme.brand),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.height(OshiTheme.sm))
            val att = attachment
            if (att == null) {
                TextAction("📎  " + t("disguise.add_file")) {
                    host.pickAny(t("disguise.add_file"))?.let { f ->
                        if (f.length() > StegoV1.MAX_FILE) { fileProblem = t("disguise.file_too_large") } else {
                            fileProblem = null; saved = null
                            attachment = StegoV1.Attachment(
                                f.name,
                                runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream",
                                f.readBytes(),
                            )
                        }
                    }
                }
            } else {
                TextAction("✕  " + t("disguise.remove_file", att.name)) { attachment = null; saved = null }
            }
            fileProblem?.let { Text(it, fontSize = 11.sp, color = OshiTheme.danger) }

            Spacer(Modifier.height(OshiTheme.lg))
            // cover preview
            Box(
                Modifier.fillMaxWidth().height(190.dp).clip(OshiTheme.radiusMd).background(OshiTheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                val p = preview
                if (p != null) Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(color = OshiTheme.brand, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(OshiTheme.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                CoverChip("⛰  " + t("disguise.cover.generated"), choice is CoverChoice.Generated) {
                    if (choice is CoverChoice.Generated) seed = System.nanoTime() else choice = CoverChoice.Generated
                    saved = null
                }
                CoverChip("🖼  " + t("disguise.cover.photo"), choice is CoverChoice.Photo) {
                    host.pickImage(t("disguise.cover.photo"))?.let { choice = CoverChoice.Photo(it); saved = null }
                }
            }
            Spacer(Modifier.height(OshiTheme.md))
            if (available != null) {
                CapacityBar(needed, available)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    if (fits) t("disguise.capacity", humanBytes(needed), humanBytes(available)) else t("disguise.too_big"),
                    fontSize = 11.sp, color = if (fits) Ink.soft else OshiTheme.danger,
                )
            }
            Spacer(Modifier.height(OshiTheme.md))
            Caveat(Glyph.ALERT, OshiTheme.warning, t("disguise.warning.recompress"))
            Spacer(Modifier.height(OshiTheme.xs))
            Caveat(Glyph.EYE_SLASH, Ink.soft, t("disguise.warning.forensic"))

            problem?.let { Spacer(Modifier.height(OshiTheme.sm)); Text(it, fontSize = 12.sp, color = OshiTheme.danger) }
            saved?.let { f ->
                Spacer(Modifier.height(OshiTheme.md))
                Row(
                    Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.success.copy(alpha = 0.12f)).padding(OshiTheme.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.CHECK, OshiTheme.success, 14.dp)
                    Spacer(Modifier.width(OshiTheme.sm))
                    Text(t("desktop.disguise.saved", f.name), fontSize = 12.sp, color = Ink.strong, modifier = Modifier.weight(1f))
                    TextAction(t("desktop.disguise.copy_file")) { copyFileToClipboard(f) }
                    TextAction(t("desktop.disguise.show_in_folder")) { showInFolder(f) }
                }
            }

        }
    }
}

@Composable
private fun CoverChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = OshiTheme.typography.labelLarge,
        color = if (selected) Ink.strong else Ink.soft,
        modifier = Modifier
            .clip(OshiTheme.pill)
            .background(if (selected) OshiTheme.brand.copy(alpha = 0.14f) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) OshiTheme.brand else OshiTheme.separator, OshiTheme.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
    )
}

@Composable
private fun CapacityBar(needed: Int, available: Int) {
    val frac = if (available <= 0) 1f else (needed.toFloat() / available).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(OshiTheme.pill).background(OshiTheme.separator)) {
        Box(
            Modifier.fillMaxWidth(maxOf(frac, 0.01f)).height(6.dp).clip(OshiTheme.pill)
                .background(if (needed <= available) OshiTheme.brandGradient else SolidColor(OshiTheme.danger)),
        )
    }
}

// ---------------------------------------------------------------------------- reveal sheet

@Composable
private fun RevealSheet(host: PhotoDisguiseHost, r: PhotoDisguiseHost.RevealUi) {
    SheetCard {
        SheetTitle(t("disguise.reveal.title"), host::close)
        Spacer(Modifier.height(OshiTheme.xs))
        Text(r.fileName, fontSize = 11.sp, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.lg))
        when (r) {
            is PhotoDisguiseHost.RevealUi.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = OshiTheme.brand, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(OshiTheme.md))
                Text(t("disguise.reveal.checking"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
            is PhotoDisguiseHost.RevealUi.Found -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OshiMascot.named("wow")?.let { MascotImage(it, 44.dp, playing = rememberMascotPlaying(it, false, true)) }
                    Spacer(Modifier.width(OshiTheme.md))
                    Column {
                        Text(t("disguise.reveal.from", r.from), style = OshiTheme.typography.titleMedium, color = Ink.strong)
                        Text(
                            t("disguise.reveal.hidden_on", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.payload.createdAtMs))),
                            fontSize = 11.sp, color = Ink.soft,
                        )
                    }
                }
                Spacer(Modifier.height(OshiTheme.sm))
                // __RESTRICTED_NETWORK_HINT_2026_11_18__ a text that came hidden in a photo was,
                // by construction, sent around a restricted network (local flag, RN-HINT §4.5).
                RestrictedNetworkChip()
                Spacer(Modifier.height(OshiTheme.md))
                if (r.payload.text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            r.payload.text, style = OshiTheme.typography.bodyLarge, color = Ink.strong,
                            modifier = Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.surface).padding(OshiTheme.md),
                        )
                    }
                    Spacer(Modifier.height(OshiTheme.sm))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
                    if (r.payload.text.isNotEmpty()) TextAction(t("disguise.reveal.copy")) {
                        com.oshi.desktop.ui.SensitiveClipboard.copy(r.payload.text)
                    }
                    r.payload.file?.let { f ->
                        TextAction("📎  " + t("desktop.disguise.save_attachment", f.name)) {
                            pickSaveTarget(DesktopPhotoDisguise.safeAttachmentName(f.name))?.let { runCatching { it.writeBytes(f.data) } }
                        }
                    }
                }
            }
            is PhotoDisguiseHost.RevealUi.Damaged -> Outcome("cry", t("disguise.reveal.damaged", r.from), OshiTheme.warning)
            is PhotoDisguiseHost.RevealUi.NotForYou -> Outcome("thinking", t("disguise.reveal.not_found"), Ink.soft)
            is PhotoDisguiseHost.RevealUi.Unreadable -> Outcome("thinking", t("desktop.disguise.unreadable"), Ink.soft)
        }
        Spacer(Modifier.height(OshiTheme.xl))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction(t("disguise.reveal.from_files")) { host.chooseAndReveal() }
            Spacer(Modifier.weight(1f))
            PrimaryButton(t("desktop.disguise.done"), onClick = host::close)
        }
    }
}

@Composable
private fun Outcome(mascot: String, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OshiMascot.named(mascot)?.let { MascotImage(it, 44.dp, playing = rememberMascotPlaying(it, false, true)) }
        Spacer(Modifier.width(OshiTheme.md))
        Text(text, style = OshiTheme.typography.bodyMedium, color = if (tint == Ink.soft) Ink.strong else tint)
    }
}

// ---------------------------------------------------------------------------- desktop verbs

/** Any file, for the small attachment. */
fun PhotoDisguiseHost.pickAny(title: String): File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(title, java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

internal fun pickSaveTarget(suggested: String): File? {
    val dialog = com.oshi.desktop.ui.DialogOwner.fileDialog(t("export.select_location"), java.awt.FileDialog.SAVE)
    dialog.file = suggested
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** Puts the FILE on the clipboard, so it pastes into Mail, Finder or another messenger as an attachment. */
internal fun copyFileToClipboard(f: File) {
    val sel = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any = listOf(f)
    }
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null) }
}

internal fun showInFolder(f: File) {
    runCatching {
        val d = java.awt.Desktop.getDesktop()
        if (d.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) d.browseFileDirectory(f) else d.open(f.parentFile)
    }
}

internal fun humanBytes(n: Int): String = when {
    n < 1000 -> "$n B"
    n < 1_000_000 -> "${(n + 500) / 1000} kB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1_000_000.0)
}
