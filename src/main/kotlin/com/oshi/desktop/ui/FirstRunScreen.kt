package com.oshi.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.components.Glyph
import com.oshi.desktop.ui.components.GlyphIcon
import com.oshi.desktop.ui.components.Ink
import com.oshi.desktop.ui.components.MascotImage
import com.oshi.desktop.ui.components.SettingsIcon
import com.oshi.desktop.ui.components.SettingsIconView
import com.oshi.desktop.ui.components.focusRing
import com.oshi.desktop.ui.components.glass
import com.oshi.desktop.ui.components.rememberMascotPlaying
import com.oshi.desktop.ui.components.rememberRowInteraction

/**
 * __DESKTOP_SETTINGS_2027__ / __DESKTOP_ONBOARDING_2027_11_24__ The first screen a new person
 * sees: who OSHI is on the left (the waving O bubble in its halo, three promises in the
 * phones' own words), the one decision on the right — a new identity, or the recovery key
 * from a phone.
 *
 * Stateless so it renders without a vault (screenshots), and so the first-run window and the
 * `--restore-identity` window draw the same screen. Below 760 dp the two halves stack.
 *
 * The 24/11 polish is DESIGN ONLY: the nine callbacks and what triggers them are unchanged.
 * What it adds is the frame around them — one continuous aurora ground instead of a hard
 * split, a two-step indicator, a hero icon per step, rounded glass tiles, and the keyboard:
 *
 *  - Tab / Shift+Tab walk the tiles and buttons (each draws the focus ring);
 *  - Enter or Space on a focused tile does what a click does;
 *  - on the recovery-key step the field takes focus, Enter restores (only with a key typed,
 *    the same condition that enables the button) and Esc goes back — exactly `onCancel`.
 *
 * Copy comes ONLY from shared catalog keys (translated in all 34 locales); nothing here is
 * desktop-only prose.
 */
@Composable
fun FirstRunScreen(
    importing: Boolean,
    recoveryKey: String,
    error: String?,
    onCreate: () -> Unit,
    onStartImport: () -> Unit,
    onKeyChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(OshiTheme.paneGround)
            .drawBehind { aurora() }
            .onPreviewKeyEvent { e ->
                if (importing && e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false
            },
    ) {
        val wide = maxWidth >= 760.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
                    BrandPanel(Modifier.fillMaxWidth().padding(start = 56.dp, end = OshiTheme.xl, top = OshiTheme.xxl, bottom = OshiTheme.xxl))
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    ChoicePanel(importing, recoveryKey, error, onCreate, onStartImport, onKeyChange, onScanQr, onRestore, onCancel)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandPanel(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.xxl, vertical = OshiTheme.xl), compact = true)
                ChoicePanel(importing, recoveryKey, error, onCreate, onStartImport, onKeyChange, onScanQr, onRestore, onCancel)
            }
        }
    }
}

/** Two soft brand glows on the ground — the phones' 2027 aurora, quiet enough for OLED black. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.aurora() {
    val dark = OshiTheme.palette.isDark
    val a = if (dark) 0.22f else 0.14f
    drawCircle(
        Brush.radialGradient(
            listOf(OshiTheme.brandFill.copy(alpha = a), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.18f),
            radius = size.maxDimension * 0.55f,
        ),
        radius = size.maxDimension * 0.55f,
        center = Offset(size.width * 0.12f, size.height * 0.18f),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0.20f, 0.40f, 0.95f).copy(alpha = a * 0.8f), Color.Transparent),
            center = Offset(size.width * 0.92f, size.height * 0.95f),
            radius = size.maxDimension * 0.5f,
        ),
        radius = size.maxDimension * 0.5f,
        center = Offset(size.width * 0.92f, size.height * 0.95f),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(OshiTheme.brandSecondary.copy(alpha = a * 0.35f), Color.Transparent),
            center = Offset(size.width * 0.45f, size.height * 1.05f),
            radius = size.maxDimension * 0.3f,
        ),
        radius = size.maxDimension * 0.3f,
        center = Offset(size.width * 0.45f, size.height * 1.05f),
    )
}

@Composable
private fun BrandPanel(modifier: Modifier, compact: Boolean = false) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        MascotHalo(if (compact) 76.dp else 120.dp)
        Spacer(Modifier.height(if (compact) OshiTheme.md else OshiTheme.xl))
        Text(
            t("onboarding.welcome_title"),
            // Solid brand ink, not the fill gradient: #5616EC on OLED black is 2.7:1.
            style = OshiTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 30.sp else 40.sp,
                lineHeight = if (compact) 36.sp else 46.sp,
            ),
            color = OshiTheme.brand,
        )
        Spacer(Modifier.height(OshiTheme.sm))
        // The tagline as a small glass pill: it is a label, not a sentence.
        Row(
            Modifier
                .glass(OshiTheme.pill, OshiTheme.brand.copy(alpha = 0.10f))
                .padding(horizontal = OshiTheme.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconView(SettingsIcon.PRIVACY, OshiTheme.brand, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(t("identity.tagline"), style = OshiTheme.typography.labelLarge, color = Ink.strong)
        }
        if (!compact) {
            Spacer(Modifier.height(OshiTheme.xxl))
            Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                Promise(SettingsIcon.PRIVACY, t("onboarding.step1_title"), t("onboarding.step1_desc"))
                Promise(SettingsIcon.PROFILE, t("onboarding_feature_private"), t("onboarding_start_subtitle"))
                Promise(SettingsIcon.KEY, t("onboarding.step4_title"), t("onboarding.step4_desc"))
            }
        }
    }
}

/** The waving O bubble on a soft brand glow inside a thin ring. */
@Composable
private fun MascotHalo(size: Dp) {
    val ring = OshiTheme.brand.copy(alpha = 0.28f)
    Box(
        Modifier
            .size(size * 1.35f)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(OshiTheme.brandFill.copy(alpha = 0.38f), Color.Transparent)),
                    radius = this.size.minDimension / 2f,
                )
                drawCircle(ring, radius = this.size.minDimension / 2.25f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        OshiMascot.named("wave")?.let {
            MascotImage(it, size, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
        }
    }
}

@Composable
private fun Promise(icon: SettingsIcon, title: String, body: String) {
    Row(
        Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), OshiTheme.surface.copy(alpha = 0.55f), OshiTheme.cardOutline)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientBadge(icon, 40.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column {
            Text(title, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(body, style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
    }
}

/** A white icon on the brand gradient, in a squircle — the per-step and per-promise mark. */
@Composable
private fun GradientBadge(icon: SettingsIcon, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(OshiTheme.brandGradient),
        contentAlignment = Alignment.Center,
    ) {
        SettingsIconView(icon, Ink.onBrandStrong, size * 0.52f)
    }
}

/** "1 of 2" / "2 of 2" as two pills, the active one long; spoken as the catalog's sentence. */
@Composable
private fun StepDots(step: Int, count: Int = 2) {
    val label = t("onboarding.page.indicator", step, count)
    Row(
        Modifier.semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..count) {
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (i == step) 24.dp else 8.dp)
                    .clip(OshiTheme.pill)
                    .background(if (i == step) OshiTheme.brandGradient else SolidColor(Ink.soft.copy(alpha = 0.30f))),
            )
        }
    }
}

@Composable
private fun ChoicePanel(
    importing: Boolean,
    recoveryKey: String,
    error: String?,
    onCreate: () -> Unit,
    onStartImport: () -> Unit,
    onKeyChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .padding(OshiTheme.xxl)
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), OshiTheme.surfaceElevated, OshiTheme.cardOutline)
            .padding(28.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepDots(if (importing) 2 else 1)
            Spacer(Modifier.weight(1f))
            FirstRunLanguageButton()
        }
        Spacer(Modifier.height(OshiTheme.xl))
        AnimatedContent(
            targetState = importing,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 6 * dir }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 6 * dir })
            },
        ) { importStep ->
            if (!importStep) ChooseStep(onCreate, onStartImport)
            else ImportStep(recoveryKey, error, onKeyChange, onScanQr, onRestore, onCancel)
        }
    }
}

@Composable
private fun ChooseStep(onCreate: () -> Unit, onStartImport: () -> Unit) {
    val first = remember { FocusRequester() }
    // The primary tile owns the focus on arrival: Enter creates, Tab reaches "import".
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
        GradientBadge(SettingsIcon.PROFILE, 56.dp)
        Spacer(Modifier.height(OshiTheme.xxs))
        Text(t("onboarding.get_started"), style = OshiTheme.typography.headlineLarge, color = Ink.strong)
        Text(t("onboarding.welcome_desc"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        Spacer(Modifier.height(OshiTheme.xs))
        Choice(SettingsIcon.PROFILE, t("identity.create_new"), t("identity.create_new.hint"), primary = true, onClick = onCreate,
            modifier = Modifier.focusRequester(first))
        Choice(SettingsIcon.IMPORT, t("identity.import_existing"), t("identity.import_existing.hint"), primary = false, onClick = onStartImport)
        Spacer(Modifier.height(OshiTheme.xs))
        Row(verticalAlignment = Alignment.Top) {
            SettingsIconView(SettingsIcon.PRIVACY, Ink.soft, 13.dp, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(OshiTheme.xs))
            Text(t("onboarding_disclaimer_note"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
        }
    }
}

@Composable
private fun ImportStep(
    recoveryKey: String,
    error: String?,
    onKeyChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
) {
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(OshiTheme.md)) {
        GradientBadge(SettingsIcon.KEY, 56.dp)
        Spacer(Modifier.height(OshiTheme.xxs))
        Text(t("identity.import_existing"), style = OshiTheme.typography.headlineLarge, color = Ink.strong)
        Text(t("identity.vault.paste_description"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(OshiTheme.surface)
                .border(
                    if (focused) 2.dp else 1.dp,
                    when {
                        error != null -> OshiTheme.danger
                        focused -> OshiTheme.brand
                        else -> OshiTheme.cardOutline
                    },
                    RoundedCornerShape(18.dp),
                )
                .padding(OshiTheme.lg),
        ) {
            if (recoveryKey.isEmpty()) {
                Text(t("identity.vault.paste_key_placeholder"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
            }
            BasicTextField(
                value = recoveryKey,
                onValueChange = onKeyChange,
                textStyle = OshiTheme.typography.bodyMedium.copy(color = Ink.strong, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(OshiTheme.brand),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        // Enter restores — under the same condition that enables the button.
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                            if (recoveryKey.isNotBlank()) onRestore()
                            true
                        } else false
                    },
            )
        }
        if (error != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OshiTheme.danger.copy(alpha = 0.10f))
                    .padding(horizontal = OshiTheme.md, vertical = OshiTheme.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.ALERT, OshiTheme.danger, 16.dp)
                Spacer(Modifier.width(OshiTheme.sm))
                Text(error, style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
            }
        }
        PillButton(SettingsIcon.QR, t("identity.vault.scan_qr"), primary = false, onClick = onScanQr, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(OshiTheme.xxs))
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            PillButton(null, t("common.back"), primary = false, quiet = true, onClick = onCancel)
            PillButton(
                SettingsIcon.CHECK, t("identity.setup.restore.action"), primary = true,
                enabled = recoveryKey.isNotBlank(), onClick = onRestore, modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Enter / Space on a focused control = a click (desktop users expect both). */
private fun Modifier.activateOnKeys(enabled: Boolean, onClick: () -> Unit): Modifier = onKeyEvent { e ->
    if (enabled && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)) {
        onClick(); true
    } else false
}

/** A large (48 dp) pill: gradient when [primary], tinted otherwise, plain text when [quiet]. */
@Composable
private fun PillButton(
    icon: SettingsIcon?,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quiet: Boolean = false,
) {
    val (source, hovered) = rememberRowInteraction()
    val ink = when {
        !enabled -> Ink.soft
        primary -> Ink.onBrandStrong
        quiet -> Ink.strong
        else -> OshiTheme.brand
    }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(OshiTheme.pill)
            .then(
                when {
                    primary && enabled -> Modifier.background(OshiTheme.brandGradient)
                    primary -> Modifier.background(Ink.soft.copy(alpha = 0.14f))
                    quiet -> Modifier.background(if (hovered.value) OshiTheme.surface else Color.Transparent)
                    else -> Modifier.background(OshiTheme.brand.copy(alpha = if (hovered.value) 0.18f else 0.10f))
                },
            )
            .focusRing(OshiTheme.pill)
            .activateOnKeys(enabled, onClick)
            .let { if (enabled) it.clickable(source, null, onClick = onClick) else it }
            .padding(horizontal = OshiTheme.xl, vertical = OshiTheme.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIconView(icon, ink, 17.dp)
            Spacer(Modifier.width(OshiTheme.sm))
        }
        Text(label, style = OshiTheme.typography.labelLarge.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
private fun Choice(
    icon: SettingsIcon,
    title: String,
    detail: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (source, hovered) = rememberRowInteraction()
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (primary) Modifier.clip(shape).background(OshiTheme.brandGradient)
                else Modifier.glass(shape, OshiTheme.brand.copy(alpha = if (hovered.value) 0.16f else 0.08f), OshiTheme.cardOutline),
            )
            .focusRing(shape)
            .activateOnKeys(true, onClick)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = OshiTheme.lg, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (primary) Color.White.copy(alpha = 0.20f) else OshiTheme.brand.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            SettingsIconView(icon, if (primary) Color.White else OshiTheme.brand, 22.dp)
        }
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OshiTheme.typography.bodyLarge.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold,
                color = if (primary) Color.White else Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(detail, style = OshiTheme.typography.bodySmall, color = if (primary) Color.White.copy(alpha = 0.85f) else Ink.soft)
        }
        Spacer(Modifier.width(OshiTheme.sm))
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(if (primary) Color.White.copy(alpha = 0.22f) else OshiTheme.brand.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(Glyph.CHEVRON_RIGHT, if (primary) Color.White else OshiTheme.brand, 16.dp)
        }
    }
}

/**
 * __SYSTEM_LANGUAGE_LIST_2027__ The language, changeable before anything else. The window
 * follows the system's language list; this is for the person whose Mac says "English" with a
 * French region (dates in French, text in English — which is what they asked macOS for) and
 * who wants OSHI in French anyway. Each language is listed in its own name.
 */
@Composable
private fun FirstRunLanguageButton() {
    var open by remember { mutableStateOf(false) }
    val tags = androidx.compose.runtime.remember {
        com.oshi.desktop.i18n.Strings.availableTags.sortedBy { DisplaySettings.nativeName(it).lowercase() }
    }
    Box {
        Row(
            Modifier
                .clip(OshiTheme.pill)
                .clickable { open = true }
                .background(OshiTheme.surface)
                .padding(horizontal = OshiTheme.md, vertical = OshiTheme.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.oshi.desktop.ui.components.SettingsIconView(SettingsIcon.GLOBE, Ink.soft, 15.dp)
            Spacer(Modifier.width(OshiTheme.xs))
            Text(
                DisplaySettings.nativeName(
                    com.oshi.desktop.i18n.Strings.resolve(DisplaySettings.activeTag, com.oshi.desktop.i18n.Strings.availableTags).firstOrNull() ?: "en",
                ),
                style = OshiTheme.typography.bodySmall,
                color = Ink.strong,
            )
        }
        com.oshi.desktop.ui.components.OshiDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            com.oshi.desktop.ui.components.ChromeMenuItem(null, t("desktop.settings.language.system", DisplaySettings.nativeName(
                com.oshi.desktop.i18n.Strings.resolve(DisplaySettings.systemTag, com.oshi.desktop.i18n.Strings.availableTags).firstOrNull() ?: "en",
            ))) { open = false; DisplaySettings.setLanguage(null) }
            for (tag in tags) {
                com.oshi.desktop.ui.components.ChromeMenuItem(
                    if (tag == DisplaySettings.languageOverride) com.oshi.desktop.ui.components.Glyph.CHECK else null,
                    DisplaySettings.nativeName(tag),
                ) { open = false; DisplaySettings.setLanguage(tag) }
            }
        }
    }
}
