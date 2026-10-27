package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.net.V2ReportClient

import com.oshi.desktop.ui.OshiTheme

/**
 * __DESKTOP_REPORT_2026_09_23__ The phones' report sheet, inline under the thread header:
 * reason (fixed list), optional free text, "also block", the privacy note, send/cancel.
 * Every string is the phones' (`report.*`, `block_reason_*`), already in 34 languages.
 * Nothing from the conversation is attached — see [V2ReportClient].
 */
@Composable
fun ReportPanel(
    isGroup: Boolean,
    alreadyReported: Boolean,
    onSubmit: (V2ReportClient.Reason, String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var reason by remember { mutableStateOf<V2ReportClient.Reason?>(null) }
    var details by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().background(OshiTheme.background)
            .padding(horizontal = OshiTheme.lg, vertical = OshiTheme.sm),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.xs),
    ) {
        Text(if (isGroup) t("group.report_group") else t("report.contact_title"),
            style = OshiTheme.typography.titleSmall, color = Ink.strong)
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            for (r in V2ReportClient.Reason.entries) {
                // Literal keys: the catalog audit only sees keys spelled out in source.
                val label = when (r) {
                    V2ReportClient.Reason.SPAM -> t("block_reason_spam")
                    V2ReportClient.Reason.HARASSMENT -> t("block_reason_harassment")
                    V2ReportClient.Reason.INAPPROPRIATE -> t("block_reason_inappropriate")
                    V2ReportClient.Reason.OTHER -> t("block_reason_other")
                }
                TextAction(if (reason == r) "● $label" else "○ $label") { reason = r }
            }
        }
        Text(t("report.details_header"), fontSize = 11.sp, color = Ink.soft)
        BasicTextField(
            value = details,
            onValueChange = { details = it.take(V2ReportClient.MAX_DETAILS) },
            textStyle = OshiTheme.typography.bodySmall.copy(color = Ink.strong),
            cursorBrush = SolidColor(OshiTheme.brand),
            modifier = Modifier.fillMaxWidth().padding(vertical = OshiTheme.xxs),
        )
        Text(t("report.privacy_note"), fontSize = 11.sp, color = Ink.soft)
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            TextAction((if (alsoBlock) "☑ " else "☐ ") + t("report.also_block")) { alsoBlock = !alsoBlock }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm), verticalAlignment = Alignment.CenterVertically) {
            reason?.let { r -> TextAction(t("report.submit")) { onSubmit(r, details, alsoBlock); onClose() } }
            TextAction(t("common.cancel")) { onClose() }
            if (alreadyReported) Text(t("report.status_sent"), fontSize = 11.sp, color = Ink.soft)
        }
    }
}
