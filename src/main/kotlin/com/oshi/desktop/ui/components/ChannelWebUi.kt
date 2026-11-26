package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.group.ChannelWeb
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ui.OshiTheme
import com.oshi.desktop.ui.state.WebLinkState

/**
 * __CHANNEL_WEB_2026_11_26__ A channel post's "Web link" (iOS `ChannelWebPostSheet`): the link —
 * copy it, open it in the browser — and the embed code (PUBLIC_CHANNEL_WEB_SPEC §8, byte for
 * byte) with a dark-theme switch and a copy button. While the post's number is being looked up:
 * a spinner; when it is not on the web: why.
 */
@Composable
fun ChannelWebPostDialog(
    state: WebLinkState,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Renders only: the dark switch's initial value. */
    initialDark: Boolean = false,
) {
    var dark by remember(state.messageId) { mutableStateOf(initialDark) }
    var copiedLink by remember(state.messageId) { mutableStateOf(false) }
    var copiedCode by remember(state.messageId) { mutableStateOf(false) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                GlyphIcon(Glyph.GLOBE, OshiTheme.brand, 20.dp)
                Text(t("desktop.cw.post_menu"), style = OshiTheme.typography.titleMedium, color = OshiTheme.textPrimary)
            }
        },
        text = {
            Column(Modifier.widthIn(min = 360.dp, max = 500.dp), verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                val n = state.n
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = OshiTheme.brand, strokeWidth = 2.5.dp, modifier = Modifier.width(28.dp))
                    }
                    n == null -> Column(Modifier.fillMaxWidth().padding(vertical = OshiTheme.md), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm)) {
                        GlyphIcon(Glyph.GLOBE, Ink.soft, 36.dp)
                        Text(t("desktop.cw.not_on_web"), style = OshiTheme.typography.bodyMedium, color = Ink.soft, textAlign = TextAlign.Center)
                    }
                    else -> {
                        val link = ChannelWeb.postUrl(state.handle, n)
                        val code = ChannelWeb.embedSnippet(state.handle, n, if (dark) true else null)
                        Card {
                            SelectionContainer {
                                Text(link.removePrefix("https://"), fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = OshiTheme.brand,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            SettingsButtonRow {
                                SettingsButton(if (copiedLink) SettingsIcon.CHECK else SettingsIcon.LINK,
                                    if (copiedLink) t("copied") else t("desktop.cw.copy_link"), primary = true) { onCopy(link); copiedLink = true }
                                SettingsButton(SettingsIcon.GLOBE, t("desktop.cw.open_browser")) { onOpenUrl(link) }
                            }
                        }
                        Text(t("desktop.cw.embed_title"), style = OshiTheme.typography.labelLarge, color = Ink.strong, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = OshiTheme.xs))
                        Card {
                            SelectionContainer {
                                Text(code, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = OshiTheme.textPrimary)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(t("desktop.cw.embed_dark"), fontSize = 14.sp, color = Ink.strong, modifier = Modifier.weight(1f))
                                Switch(checked = dark, onCheckedChange = { dark = it; copiedCode = false },
                                    colors = SwitchDefaults.colors(checkedTrackColor = OshiTheme.brandFill))
                            }
                            SettingsButtonRow {
                                SettingsButton(if (copiedCode) SettingsIcon.CHECK else SettingsIcon.COPY,
                                    if (copiedCode) t("copied") else t("desktop.cw.copy_code")) { onCopy(code); copiedCode = true }
                            }
                        }
                        Text(t("desktop.cw.embed_footer"), fontSize = 12.sp, color = Ink.soft)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("common.done"), color = OshiTheme.brand, fontWeight = FontWeight.SemiBold) } },
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(OshiTheme.radiusMd).background(OshiTheme.textPrimary.copy(alpha = 0.05f)).padding(OshiTheme.md),
        verticalArrangement = Arrangement.spacedBy(OshiTheme.sm),
    ) { content() }
}
