package com.oshi.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme

/**
 * __DESKTOP_HELP_2027__ Settings › Help: iOS `HelpView` + `FAQView` (SettingsView.swift:1251),
 * answered in the app because help that needs the website is no help offline.
 *
 * The phones' answers are reused where they are true on a computer (`faq.q1/q3/q4`); the ones
 * that describe a phone — Bluetooth mesh, "tap the + button" — are replaced by desktop answers
 * (`pc.help.*`), so nothing on this page describes a gesture this window does not have.
 */
data class FaqEntry(val question: () -> String, val answer: () -> String)

// Literal keys so the catalog audit sees each one.
val DESKTOP_FAQ: List<FaqEntry> = listOf(
    FaqEntry({ t("faq.q1.question") }, { t("faq.q1.answer") }),
    FaqEntry({ t("faq.q3.question") }, { t("faq.q3.answer") }),
    FaqEntry({ t("pc.help.add.q") }, { t("pc.help.add.a") }),
    FaqEntry({ t("pc.help.same_account.q") }, { t("pc.help.same_account.a") }),
    FaqEntry({ t("pc.help.offline.q") }, { t("pc.help.offline.a") }),
    FaqEntry({ t("pc.help.groups.q") }, { t("pc.help.groups.a") }),
    FaqEntry({ t("pc.help.passcode.q") }, { t("pc.help.passcode.a") }),
    FaqEntry({ t("faq.q4.question") }, { t("faq.q4.answer") }),
)

/** Which questions open expanded — the renderer opens some; the app opens none. */
object HelpNavigation { var open: Set<Int> = emptySet() }

@Composable
fun HelpPage(onOpenUrl: (String) -> Unit, initiallyOpen: Set<Int> = emptySet()) {
    SettingsCard(title = t("settings.help.faq")) {
        DESKTOP_FAQ.forEachIndexed { i, entry ->
            if (i > 0) SettingsDivider()
            FaqRow(entry.question(), entry.answer(), i in initiallyOpen)
        }
    }
    SettingsCard(title = t("pc.help.more")) {
        SettingsLinkRow(SettingsIcon.TEXT, t("settings.help.contact"), "oshi-messenger.com/support") { onOpenUrl(SUPPORT_URL) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.PRIVACY, t("settings.help.privacy"), null) { onOpenUrl(PRIVACY_URL) }
        SettingsDivider()
        SettingsLinkRow(SettingsIcon.ARCHIVE, t("settings.help.terms"), null) { onOpenUrl(TERMS_URL) }
    }
}

@Composable
private fun FaqRow(question: String, answer: String, startOpen: Boolean) {
    var open by remember(question) { mutableStateOf(startOpen) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (open) t("common.collapse") else t("common.expand")) { open = !open }
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBadge(SettingsIcon.HELP, OshiTheme.brand)
            Spacer(Modifier.width(OshiTheme.md))
            Text(question, style = OshiTheme.typography.bodyLarge, color = Ink.strong, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(OshiTheme.sm))
            SettingsIconView(SettingsIcon.CHEVRON, Ink.soft, 14.dp, Modifier.rotate(if (open) 90f else 0f))
        }
        AnimatedVisibility(open) {
            Text(
                answer,
                style = OshiTheme.typography.bodyMedium,
                color = Ink.soft,
                modifier = Modifier.padding(start = 30.dp + OshiTheme.md, top = OshiTheme.sm),
            )
        }
    }
}

const val SUPPORT_URL = "https://oshi-messenger.com/support"
const val PRIVACY_URL = "https://oshi-messenger.com/privacy"
const val TERMS_URL = "https://oshi-messenger.com/terms"
