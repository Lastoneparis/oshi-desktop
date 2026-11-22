package com.oshi.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.mascot.OshiMascot
import com.oshi.desktop.ui.components.Ink
import com.oshi.desktop.ui.components.MascotImage
import com.oshi.desktop.ui.components.SettingsButton
import com.oshi.desktop.ui.components.SettingsIcon
import com.oshi.desktop.ui.components.SettingsIconBadge
import com.oshi.desktop.ui.components.glass
import com.oshi.desktop.ui.components.focusRing
import com.oshi.desktop.ui.components.rememberMascotPlaying

/**
 * __DESKTOP_SETTINGS_2027__ The first screen a new person sees: who OSHI is on the left (the
 * waving O bubble, three promises in the phones' own words), the one decision on the right —
 * a new identity, or the recovery key from a phone.
 *
 * Stateless so it renders without a vault (screenshots), and so the first-run window and the
 * `--restore-identity` window draw the same screen. Below 760 dp the two halves stack.
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
    BoxWithConstraints(Modifier.fillMaxSize().background(OshiTheme.paneGround)) {
        val wide = maxWidth >= 760.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                BrandPanel(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    ChoicePanel(importing, recoveryKey, error, onCreate, onStartImport, onKeyChange, onScanQr, onRestore, onCancel)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandPanel(Modifier.fillMaxWidth(), compact = true)
                ChoicePanel(importing, recoveryKey, error, onCreate, onStartImport, onKeyChange, onScanQr, onRestore, onCancel)
            }
        }
    }
}

@Composable
private fun BrandPanel(modifier: Modifier, compact: Boolean = false) {
    Column(
        modifier
            .background(
                Brush.linearGradient(
                    listOf(OshiTheme.brandFill.copy(alpha = 0.16f), Color(0.20f, 0.40f, 0.95f).copy(alpha = 0.06f)),
                ),
            )
            .padding(horizontal = 40.dp, vertical = if (compact) 24.dp else 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        OshiMascot.named("wave")?.let {
            MascotImage(it, if (compact) 72.dp else 112.dp, playing = rememberMascotPlaying(it, hovered = false, playOnAppear = true))
            Spacer(Modifier.height(OshiTheme.lg))
        }
        Text(
            t("onboarding.welcome_title"),
            // Solid brand ink, not the fill gradient: #5616EC on OLED black is 2.7:1.
            style = OshiTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = OshiTheme.brand,
        )
        Spacer(Modifier.height(OshiTheme.xs))
        Text(t("identity.tagline"), style = OshiTheme.typography.bodyLarge, color = Ink.soft)
        if (!compact) {
            Spacer(Modifier.height(OshiTheme.xxl))
            Promise(SettingsIcon.PRIVACY, t("onboarding.step1_title"), t("onboarding.step1_desc"))
            Promise(SettingsIcon.PROFILE, t("onboarding_feature_private"), t("onboarding_start_subtitle"))
            Promise(SettingsIcon.KEY, t("onboarding.step4_title"), t("onboarding.step4_desc"))
        }
    }
}

@Composable
private fun Promise(icon: SettingsIcon, title: String, body: String) {
    Row(Modifier.padding(bottom = OshiTheme.lg).widthIn(max = 420.dp), verticalAlignment = Alignment.Top) {
        SettingsIconBadge(icon, OshiTheme.brand, 34.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column {
            Text(title, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(body, style = OshiTheme.typography.bodySmall, color = Ink.soft)
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
            .padding(32.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .glass(OshiTheme.radiusXl, OshiTheme.surfaceElevated, OshiTheme.cardOutline)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        FirstRunLanguageButton()
        Text(
            if (importing) t("identity.import_existing") else t("onboarding.get_started"),
            style = OshiTheme.typography.headlineMedium,
            color = Ink.strong,
        )
        if (!importing) {
            Choice(
                SettingsIcon.PROFILE, t("identity.create_new"), t("identity.create_new.hint"), primary = true, onClick = onCreate,
            )
            Choice(
                SettingsIcon.IMPORT, t("identity.import_existing"), t("identity.import_existing.hint"), primary = false, onClick = onStartImport,
            )
        } else {
            Text(t("identity.vault.paste_description"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .clip(OshiTheme.radiusMd)
                    .background(OshiTheme.surface)
                    .padding(OshiTheme.md),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error != null) Text(error, style = OshiTheme.typography.bodySmall, color = OshiTheme.danger)
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                SettingsButton(SettingsIcon.QR, t("identity.vault.scan_qr"), onClick = onScanQr)
            }
            Spacer(Modifier.height(OshiTheme.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton(SettingsIcon.CHECK, t("identity.setup.restore.action"), primary = true, enabled = recoveryKey.isNotBlank(), onClick = onRestore)
                SettingsButton(null, t("common.cancel"), onClick = onCancel)
            }
        }
    }
}

@Composable
private fun Choice(icon: SettingsIcon, title: String, detail: String, primary: Boolean, onClick: () -> Unit) {
    val (source, hovered) = com.oshi.desktop.ui.components.rememberRowInteraction()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .then(
                if (primary) Modifier.background(OshiTheme.brandGradient)
                else Modifier.background(OshiTheme.brand.copy(alpha = if (hovered.value) 0.16f else 0.08f)),
            )
            .focusRing(OshiTheme.radiusMd)
            .clickable(source, null, onClick = onClick)
            .padding(OshiTheme.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBadge(icon, if (primary) Color.White else OshiTheme.brand, 36.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = OshiTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (primary) Color.White else Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(detail, style = OshiTheme.typography.bodySmall, color = if (primary) Color.White.copy(alpha = 0.85f) else Ink.soft)
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
