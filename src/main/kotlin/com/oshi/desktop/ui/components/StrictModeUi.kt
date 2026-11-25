package com.oshi.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.security.RemoteControlWatch
import com.oshi.desktop.store.AppLock
import com.oshi.desktop.store.DesktopPaths
import com.oshi.desktop.store.StrictMode
import com.oshi.desktop.ui.OshiTheme

/**
 * __STRICT_MODE_2026_11_22__ Settings › Security › "Strict security mode": the switch, its three
 * sub-switches, the passcode step to turn it off, and the explanation page (STRICT_MODE_SPEC).
 * The rules are in [StrictMode]; the guards in `StrictModeGuards`, `RemoteControlDetector`.
 */
object StrictModeNavigation {
    /** True while Settings › Security shows the explanation page instead of its cards. */
    var explaining: Boolean by mutableStateOf(false)
}

/** The card at the top of Settings › Security. */
@Composable
fun StrictModeCard() {
    val v = StrictMode.values
    val hasPasscode = AppLock.config.hasPasscode
    var askingPasscode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun turnOff() {
        when (StrictMode.disable(code.takeIf { it.isNotEmpty() })) {
            StrictMode.DisableResult.Done -> { askingPasscode = false; code = ""; wrong = false }
            StrictMode.DisableResult.PasscodeRequired -> askingPasscode = true
            StrictMode.DisableResult.WrongPasscode -> { wrong = true; code = "" }
        }
    }

    SettingsCard(
        title = t("desktop.strict.title"),
        footer = if (hasPasscode) t("desktop.strict.footer") else t("desktop.strict.footer.nopasscode"),
    ) {
        SettingsToggleRow(
            SettingsIcon.PRIVACY,
            t("desktop.strict.title"),
            t("desktop.strict.toggle.detail"),
            checked = v.enabled,
        ) { want ->
            if (want) StrictMode.enable() else if (hasPasscode) { askingPasscode = true; wrong = false } else turnOff()
        }
        if (askingPasscode && v.enabled) {
            Hairline()
            SettingsCardBody {
                Text(t("desktop.strict.off.prompt"), style = OshiTheme.typography.bodyMedium, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.sm))
                SecretField(code, { code = it.take(AppLock.MAX_LENGTH); wrong = false }, t("pc.lock.current"), onEnter = ::turnOff)
                if (wrong) SettingsNotice(t("lock.incorrect_passcode"), error = true)
                Spacer(Modifier.height(OshiTheme.sm))
                SettingsButtonRow {
                    SettingsButton(SettingsIcon.KEY, t("desktop.strict.off.button"), danger = true) { turnOff() }
                    SettingsButton(null, t("common.cancel")) { askingPasscode = false; code = ""; wrong = false }
                }
            }
        }
        if (v.enabled) {
            SettingsDivider()
            SettingsToggleRow(SettingsIcon.COPY, t("desktop.strict.paste"), t("desktop.strict.paste.detail"), checked = v.blockPaste) {
                StrictMode.setBlockPaste(it)
            }
            SettingsDivider()
            SettingsToggleRow(SettingsIcon.CHECK, t("desktop.strict.receipts"), t("desktop.strict.receipts.detail"), checked = v.receiptsOff) {
                StrictMode.setReceiptsOff(it)
            }
            SettingsDivider()
            SettingsToggleRow(SettingsIcon.EYE, t("desktop.strict.a11y"), t("desktop.strict.a11y.detail"), checked = v.keepScreenReader) {
                StrictMode.setKeepScreenReader(it)
            }
            Hairline()
            SettingsCardBody {
                val hits = RemoteControlWatch.hits
                if (hits.isEmpty()) {
                    Text(t("desktop.strict.watch.none"), style = OshiTheme.typography.bodySmall, color = Ink.soft)
                } else {
                    SettingsNotice(t("desktop.strict.watch.found", hits.joinToString(", ") { it.label }), error = true)
                }
            }
        }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.INFO, t("desktop.strict.learn"), t("desktop.strict.learn.detail")) {
            StrictModeNavigation.explaining = true
        }
    }
}

/** Settings › Privacy: where people look first — points at the switch in Security. */
@Composable
fun StrictModeLinkCard() {
    SettingsCard {
        SettingsLinkRow(
            SettingsIcon.PRIVACY,
            t("desktop.strict.title"),
            t("desktop.strict.toggle.detail"),
        ) {
            StrictModeNavigation.explaining = false
            SettingsNavigation.section = SettingsSection.SECURITY
        }
    }
}

/** The explanation page: exactly what it does, per platform, and what it cannot do (spec §5). */
@Composable
fun StrictModeExplanation() {
    SettingsButtonRow {
        SettingsButton(null, t("common.back")) { StrictModeNavigation.explaining = false }
    }
    Spacer(Modifier.height(OshiTheme.lg))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsIconBadge(SettingsIcon.PRIVACY, OshiTheme.brand, 44.dp)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            Text(t("desktop.strict.learn"), style = OshiTheme.typography.headlineMedium, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(t("desktop.strict.explain.intro"), style = OshiTheme.typography.bodyMedium, color = Ink.soft)
        }
    }
    Spacer(Modifier.height(OshiTheme.xl))

    SettingsCard(title = t("desktop.strict.explain.section.does")) {
        // Literal keys (not "explain.$id"): the catalog audit reads keys from the source.
        val items = listOf(
            ExplainItem(SettingsIcon.COPY, t("desktop.strict.explain.copy.title"), t("desktop.strict.explain.copy.body"), OshiTheme.brand),
            ExplainItem(SettingsIcon.TEXT, t("desktop.strict.explain.paste.title"), t("desktop.strict.explain.paste.body"), OshiTheme.brand),
            ExplainItem(SettingsIcon.IMAGE, t("desktop.strict.explain.screen.title"), t("desktop.strict.explain.screen.body"), OshiTheme.brand),
            ExplainItem(SettingsIcon.ALERT, t("desktop.strict.explain.agents.title"), t("desktop.strict.explain.agents.body"), OshiTheme.warning),
            ExplainItem(SettingsIcon.EYE, t("desktop.strict.explain.a11y.title"), t("desktop.strict.explain.a11y.body"), OshiTheme.brand),
            ExplainItem(SettingsIcon.KEY, t("desktop.strict.explain.lock.title"), t("desktop.strict.explain.lock.body"), OshiTheme.brand),
            ExplainItem(SettingsIcon.NOTIFICATIONS, t("desktop.strict.explain.notif.title"), t("desktop.strict.explain.notif.body"), OshiTheme.brand),
            ExplainItem(SettingsIcon.GLOBE, t("desktop.strict.explain.network.title"), t("desktop.strict.explain.network.body"), OshiTheme.brand),
        )
        items.forEachIndexed { i, it ->
            if (i > 0) SettingsDivider()
            ExplainRow(it.icon, it.tint, it.title, it.body)
        }
    }

    SettingsCard(title = t("desktop.strict.explain.section.platform")) {
        // The platform this copy runs on first: that is the line that matters to the reader.
        val rows = listOf(
            Triple(SettingsIcon.DEVICES, t("desktop.strict.explain.windows"), DesktopPaths.isWindows),
            Triple(SettingsIcon.LAPTOP_OFF, t("desktop.strict.explain.linux"), DesktopPaths.isLinux),
            Triple(SettingsIcon.LAPTOP_OFF, t("desktop.strict.explain.mac"), DesktopPaths.isMac),
        ).sortedByDescending { it.third }
        rows.forEachIndexed { i, (icon, text, here) ->
            if (i > 0) SettingsDivider()
            ExplainRow(icon, if (here) OshiTheme.brand else Ink.soft, null, text)
        }
    }

    SettingsCard(title = t("desktop.strict.explain.section.cannot")) {
        ExplainRow(SettingsIcon.ALERT, OshiTheme.danger, null, t("desktop.strict.explain.cannot.body"))
    }
}

private class ExplainItem(val icon: SettingsIcon, val title: String, val body: String, val tint: Color)

@Composable
private fun ExplainRow(icon: SettingsIcon, tint: Color, title: String?, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md), verticalAlignment = Alignment.Top) {
        SettingsIconBadge(icon, tint)
        Spacer(Modifier.width(OshiTheme.md))
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(title, style = OshiTheme.typography.bodyLarge, color = Ink.strong)
                Spacer(Modifier.height(OshiTheme.xxs))
            }
            Text(body, style = if (title == null) OshiTheme.typography.bodyMedium else OshiTheme.typography.bodySmall,
                color = if (title == null) Ink.strong else Ink.soft)
        }
    }
}

/**
 * The warning over the window when strict mode is on and remote-control / screen-sharing /
 * automation / AI-agent software is running. Dismissing hides it until the set changes.
 */
@Composable
fun StrictModeBanner(modifier: Modifier = Modifier) {
    val hits = RemoteControlWatch.visible
    if (hits.isEmpty()) return
    Row(
        modifier
            .widthIn(max = 640.dp)
            .glass(RoundedCornerShape(14.dp), OshiTheme.surfaceElevated, OshiTheme.warning.copy(alpha = 0.55f))
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OshiTheme.md),
    ) {
        SettingsIconBadge(SettingsIcon.ALERT, OshiTheme.warning)
        Column(Modifier.weight(1f)) {
            Text(t("desktop.strict.banner.title"), style = OshiTheme.typography.bodyLarge, color = Ink.strong)
            Spacer(Modifier.height(OshiTheme.xxs))
            Text(
                t("desktop.strict.banner.body", hits.joinToString(", ") { it.label }),
                style = OshiTheme.typography.bodySmall,
                color = Ink.soft,
            )
        }
        Box { SettingsButton(null, t("common.dismiss")) { RemoteControlWatch.dismiss() } }
    }
}
