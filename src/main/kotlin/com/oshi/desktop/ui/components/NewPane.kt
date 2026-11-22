package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.pairing.QrMatrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.ContentScale
import java.awt.EventQueue
import com.oshi.desktop.pairing.QrCameraScanner
import com.oshi.desktop.pairing.QrImageDecoder
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ShellState
import java.io.File

/**
 * "New" — the pairing screen, and the window's answer to the phone's QR tab.
 *
 * ============================================================ HALF OF PAIRING, HONESTLY
 *
 * Pairing on the phones is symmetric: each side shows a code and each side scans one, and
 * this window does all three now: it shows its code, accepts pasted text, decodes a QR from
 * a selected image, and — since 2026-10-28 — SCANS ONE WITH THE CAMERA.
 *
 * This note used to end "a live webcam scanner remains a separate native capability", and
 * `ContactQr` said in bold that *"this client has no live camera"*. The capability had been
 * in the tree since video calls: `CameraCapture` opens avfoundation, dshow or video4linux2,
 * honours the camera chosen in settings, and renders a BGRA preview of every frame. It had
 * one caller. [com.oshi.desktop.pairing.QrCameraScanner] is the second.
 *
 * The code drawn is the real thing, not a picture of one. [QrMatrix] is this project's own
 * encoder — the same one `/qr png` writes a file with — and what it encodes is
 * [com.oshi.desktop.pairing.ContactQr.deepLinkFor], byte for byte the payload the shipped
 * apps put in theirs. A QR that a phone cannot read is indistinguishable from a QR that is
 * simply not being scanned, so there is no room here for a lookalike.
 *
 * ============================================================ ADDING SOMEONE SENDS NOTHING
 *
 * Pasting a code writes a row in the local contact store and opens a conversation. It does
 * not message the peer, does not publish anything to the relay, and the other side is not
 * told. The screen says so, because "Add" on every other messenger means a request that
 * travels, and a user who assumes that here would sit waiting for an acceptance that is not
 * coming.
 */
@Composable
fun NewPane(
    state: ShellState,
    onStartConversation: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPickQrImage: () -> File?,
    modifier: Modifier = Modifier,
) {
    var pasted by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val scanner = remember { QrCameraScanner { com.oshi.desktop.call.video.CameraCapture() } }
    // Leaving this screen releases the camera. A scanner left running behind another pane is
    // a camera light that stays on for no reason anybody can see.
    DisposableEffect(scanner) { onDispose { scanner.stop() } }

    val startScan: () -> Unit = {
        scanError = null
        cameraFrame = null
        scanning = true
        scanner.start(object : QrCameraScanner.Listener {
            override fun onPreview(image: com.oshi.desktop.call.video.VideoImage) {
                val bmp = image.toImageBitmap()
                EventQueue.invokeLater { cameraFrame = bmp }
            }
            override fun onFinished(payload: String?, error: String?, stopped: Boolean) {
                EventQueue.invokeLater {
                    scanning = false
                    cameraFrame = null
                    // The scanner decides nothing: a scanned string lands in the SAME field a
                    // pasted one does, so the person sees what was read before anything is
                    // added, and `ContactQr` judges both by one code path.
                    if (payload != null) pasted = payload
                    scanError = error
                }
            }
        })
    }

    // __NEW_CHAT_2027__ Two cards — "Add someone" and "My QR code" — side by side when the
    // window is wide, stacked when it is not; every action an icon or a button, no text links.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxSize().background(OshiTheme.paneGround),
    ) {
        val wide = maxWidth >= 920.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OshiTheme.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = if (wide) 1040.dp else 620.dp)) {
                Text(t("chats.merged.new_chat"), style = OshiTheme.typography.headlineLarge, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(t("new.hero.subtitle"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
                Spacer(Modifier.height(OshiTheme.xl))

                val addCard: @Composable (Modifier) -> Unit = { m ->
                    Box(m) {
                        PaneCard {
                            CardTitle(Glyph.PERSON, t("new.add_contact"))
                            Spacer(Modifier.height(OshiTheme.xs))
                            Text(t("conv.new.paste_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            Spacer(Modifier.height(OshiTheme.lg))
                            PasteField(value = pasted, onValue = { pasted = it }, placeholder = t("new.paste.placeholder"))
                            Spacer(Modifier.height(OshiTheme.md))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                                PrimaryButton(t("new.start_conversation"), enabled = pasted.isNotBlank()) {
                                    onStartConversation(pasted)
                                    pasted = ""
                                }
                                Spacer(Modifier.weight(1f))
                                GlyphButton(Glyph.IMAGE, t("conv.new.scan_image")) {
                                    onPickQrImage()?.let { image ->
                                        QrImageDecoder.decode(image).fold(
                                            onSuccess = { pasted = it; scanError = null },
                                            onFailure = { scanError = t("conv.new.scan_image_failed") },
                                        )
                                    }
                                }
                                GlyphButton(Glyph.CAMERA, if (scanning) t("conv.new.stop_camera") else t("a11y.scan_qr"), active = scanning) {
                                    if (scanning) scanner.stop() else startScan()
                                }
                            }
                            if (scanning) {
                                Spacer(Modifier.height(OshiTheme.md))
                                Box(
                                    Modifier.fillMaxWidth().height(220.dp).clip(OshiTheme.radiusMd).background(OshiTheme.surface),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val shot = cameraFrame
                                    if (shot != null) {
                                        Image(bitmap = shot, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Text(t("conv.new.opening_camera"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                                    }
                                }
                                Spacer(Modifier.height(OshiTheme.xs))
                                Text(t("conv.new.camera_hint"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            }
                            scanError?.let {
                                Spacer(Modifier.height(OshiTheme.xs))
                                Text(it, style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
                            }
                            Spacer(Modifier.height(OshiTheme.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.Top) {
                                GlyphIcon(Glyph.LOCK, Ink.soft, 14.dp)
                                Text(t("conv.new.privacy_note"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            }
                        }
                    }
                }
                val codeCard: @Composable (Modifier) -> Unit = { m ->
                    Box(m) {
                        PaneCard {
                            CardTitle(Glyph.QR, t("my_qr_code"))
                            Spacer(Modifier.height(OshiTheme.xs))
                            Text(t("qr.instructions_2"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                            Spacer(Modifier.height(OshiTheme.lg))
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                QrCode(state.shareText, size = 200.dp)
                                Spacer(Modifier.height(OshiTheme.md))
                                // An address is a key: always left-to-right, even in an Arabic window.
                                androidx.compose.runtime.CompositionLocalProvider(
                                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
                                ) {
                                    Text(
                                        state.selfAddress,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Ink.soft,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                                Spacer(Modifier.height(OshiTheme.md))
                                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                                    PrimaryButton(t("settings.qr.copyAddress")) { onCopy(state.selfAddress) }
                                    GlyphButton(Glyph.LINK, t("conv.new.copy_share_text")) { onCopy(state.shareText) }
                                }
                            }
                        }
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.lg), verticalAlignment = Alignment.Top) {
                        addCard(Modifier.weight(1.2f))
                        codeCard(Modifier.weight(1f))
                    }
                } else {
                    addCard(Modifier)
                    Spacer(Modifier.height(OshiTheme.lg))
                    codeCard(Modifier)
                }
                Spacer(Modifier.height(OshiTheme.xl))
            }
        }
    }
}

@Composable
private fun CardTitle(glyph: Glyph, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
        Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(OshiTheme.brand.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            GlyphIcon(glyph, OshiTheme.brand, 16.dp)
        }
        Text(title, style = OshiTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Ink.strong)
    }
}

// ---------------------------------------------------------------------------- the code itself

/**
 * A real QR symbol, drawn module by module.
 *
 * No image decoding and no library: [QrMatrix] hands back a boolean grid and this paints it.
 * The quiet zone is FOUR modules because the specification says four and scanners enforce
 * it — a symbol drawn flush to its container is the classic unreadable QR, and it looks
 * perfectly fine to the person who drew it.
 *
 * Black on white, deliberately, even though everything else in this window is violet: a
 * tinted QR narrows the contrast a camera has to work with for no gain the user can see.
 */
@Composable
fun QrCode(payload: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { runCatching { QrMatrix.encode(payload) }.getOrNull() }
    if (matrix == null) {
        // Encoding can only fail if the payload outgrew the largest version this encoder
        // builds. Saying so beats drawing an empty white square that reads as a blank code.
        Box(
            modifier.size(size).clip(OshiTheme.radiusMd).background(OshiTheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                t("conv.qr.too_long"),
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
                modifier = Modifier.padding(OshiTheme.md),
            )
        }
        return
    }
    val quiet = 4
    val span = matrix.size + 2 * quiet
    Canvas(modifier.size(size).clip(OshiTheme.radiusMd).background(Color.White)) {
        val module = this.size.minDimension / span
        for (r in 0 until matrix.size) {
            for (c in 0 until matrix.size) {
                if (!matrix.dark(r, c)) continue
                drawRect(
                    color = Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset((c + quiet) * module, (r + quiet) * module),
                    size = androidx.compose.ui.geometry.Size(module, module),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- pieces

@Composable
fun PaneCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // OLED: crystal glass on the black pane ground (__OLED_GLASS_2026_11_16__).
            .glass(OshiTheme.radiusXl, OshiTheme.surfaceElevated, OshiTheme.cardOutline)
            .padding(OshiTheme.xl),
        content = content,
    )
}

/** A monospace value the user is expected to read character by character. */
@Composable
fun SelectableMono(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Ink.strong,
    )
}

@Composable
fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = OshiTheme.typography.labelLarge,
        color = OshiTheme.brand,
        modifier = Modifier
            .clip(OshiTheme.radiusSm)
            .focusRing(OshiTheme.radiusSm)
            .clickable(onClick = onClick)
            .padding(horizontal = OshiTheme.xs, vertical = OshiTheme.xxs),
    )
}

@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            // OLED: a soft violet glow under the enabled button; a disabled one is glass.
            .brandGlow(OshiTheme.pill, active = enabled)
            .then(
                if (enabled || OshiTheme.glass == null) Modifier.clip(OshiTheme.pill)
                    .background(if (enabled) OshiTheme.brandGradient else SolidColor(OshiTheme.separator))
                else Modifier.glass(OshiTheme.pill, OshiTheme.separator, raised = true)
            )
            .focusRing(OshiTheme.pill)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = OshiTheme.typography.labelLarge,
            color = if (enabled) Ink.onBrandStrong else Ink.soft,
        )
    }
}

@Composable
fun PasteField(value: String, onValue: (String) -> Unit, placeholder: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surface)
            .padding(OshiTheme.md),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Ink.strong,
            ),
            cursorBrush = SolidColor(OshiTheme.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A label above a value, the shape every settings row in this window uses. */
@Composable
fun LabelledValue(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = OshiTheme.xs)) {
        Text(label, style = OshiTheme.typography.labelMedium, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.xxs))
        if (mono) SelectableMono(value)
        else Text(value, style = OshiTheme.typography.bodyMedium, color = Ink.strong)
    }
}

/** A heading inside a scrolling settings column. */
@Composable
fun PaneHeading(text: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = OshiTheme.typography.headlineLarge, color = Ink.strong)
        if (sub != null) {
            Spacer(Modifier.height(OshiTheme.xs))
            Text(sub, style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        }
        Spacer(Modifier.height(OshiTheme.xl))
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = Ink.strong,
        modifier = modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(OshiTheme.surface)
            .padding(OshiTheme.md),
    )
}
