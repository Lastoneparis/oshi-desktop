package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
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
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.ShellState

/**
 * "New" — the pairing screen, and the window's answer to the phone's QR tab.
 *
 * ============================================================ HALF OF PAIRING, HONESTLY
 *
 * Pairing on the phones is symmetric: each side shows a code and each side scans one. This
 * window can only ever do one of those halves. `DesktopLimits.MISSING` records why and it is
 * not a schedule — no desktop machine is guaranteed a webcam, and a "Scan" button that
 * worked on the developer's laptop and not on a user's desktop would be worse than no
 * button. So: **this screen SHOWS a code and ACCEPTS a pasted one.** The phone scans what is
 * on screen here; what the phone shows is pasted into the box below.
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
    modifier: Modifier = Modifier,
) {
    var pasted by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(OshiTheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(OshiTheme.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 620.dp)) {

            Text("New conversation", style = OshiTheme.typography.headlineLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xs))
            Text(
                "OSHI has no phone numbers and no accounts. Two people exchange keys, and that is " +
                    "the whole of it.",
                style = OshiTheme.typography.bodyMedium,
                color = Ink.soft,
            )
            Spacer(Modifier.height(OshiTheme.xl))

            // ---------------------------------------------------------------- your code
            PaneCard {
                Text("Your code", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    "Have the other person scan this with the OSHI app on their phone.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.lg))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    QrCode(state.shareText, size = 200.dp)
                    Spacer(Modifier.size(OshiTheme.xl))
                    Column(Modifier.weight(1f)) {
                        Text("Address", style = OshiTheme.typography.labelMedium, color = Ink.soft)
                        Spacer(Modifier.height(OshiTheme.xxs))
                        SelectableMono(state.selfAddress)
                        Spacer(Modifier.height(OshiTheme.md))
                        TextAction("Copy address") { onCopy(state.selfAddress) }
                        Spacer(Modifier.height(OshiTheme.xs))
                        TextAction("Copy share text") { onCopy(state.shareText) }
                    }
                }
            }

            Spacer(Modifier.height(OshiTheme.lg))

            // ---------------------------------------------------------------- their code
            PaneCard {
                Text("Their code", style = OshiTheme.typography.titleMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xs))
                Text(
                    "Paste what their app gave them — a bare key, an oshi:// link, or the whole " +
                        "share message. There is no camera here: no desktop machine is guaranteed " +
                        "one, so a Scan button would work on some computers and not on others.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
                Spacer(Modifier.height(OshiTheme.lg))

                PasteField(
                    value = pasted,
                    onValue = { pasted = it },
                    placeholder = "oshi://add?key=…",
                )
                Spacer(Modifier.height(OshiTheme.md))
                Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                    PrimaryButton("Open conversation", enabled = pasted.isNotBlank()) {
                        onStartConversation(pasted)
                        pasted = ""
                    }
                }
                Spacer(Modifier.height(OshiTheme.md))
                Text(
                    "Nothing is sent and the relay is not told. Adding someone here writes one row " +
                        "on this machine; the first message is what travels.",
                    style = OshiTheme.typography.bodySmall,
                    color = Ink.soft,
                )
            }

            Spacer(Modifier.height(OshiTheme.xl))
        }
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
                "This address is too long to encode here — copy it instead.",
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
            .clip(OshiTheme.radiusXl)
            .background(OshiTheme.surfaceElevated)
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
            .clip(OshiTheme.pill)
            .background(if (enabled) OshiTheme.brandGradient else SolidColor(OshiTheme.separator))
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
