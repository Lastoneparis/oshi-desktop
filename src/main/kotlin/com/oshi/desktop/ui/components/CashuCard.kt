package com.oshi.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oshi.desktop.i18n.t
import androidx.compose.ui.draw.clip
import com.oshi.desktop.ui.OshiTheme
import com.oshi.messenger.util.CashuTokens
import java.awt.Desktop
import java.net.URI

/**
 * 🪙 The eCash card under a message that carries a Cashu token.
 *
 * Third platform, ONE reader: `CashuTokens` is compiled straight out of the
 * Android tree (build.gradle.kts, `sharedCashuSources`), not copied. A token
 * pasted into a conversation has to show the same amount, the same mint and
 * the same bearer warning on a phone and on a laptop — two implementations of
 * a money format drift, and the half that drifts is always the warning.
 *
 * Scope is the same here as everywhere: recognise, preview, hand off. The
 * desktop contacts no mint, holds no balance and signs nothing. "Redeem" opens
 * the `cashu:` URI in whatever wallet this computer has registered for it.
 */
@Composable
fun CashuTokenCardsForText(text: String, modifier: Modifier = Modifier) {
    // Cheap bail before any parsing, exactly like TickerChipsForText.
    if (!text.contains("cashu", ignoreCase = true)) return
    val tokens = remember(text) {
        CashuTokens.find(text).map { it.token }.distinctBy { it.raw }
    }
    if (tokens.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tokens.forEach { CashuCard(it) }
    }
}

@Composable
private fun CashuCard(token: CashuTokens.Token) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OshiTheme.radiusMd)
            .background(Ink.strong.copy(alpha = 0.06f))
            .border(1.dp, Ink.strong.copy(alpha = 0.12f), OshiTheme.radiusMd)
            .padding(OshiTheme.sm),
    ) {
        Text(
            "⚡ ${token.totalAmount} ${token.unit ?: "sat"}",
            style = OshiTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Ink.strong,
        )
        // The mint is WHO owes the money: a token is only worth what its mint
        // will honour, so it is named rather than hidden behind "eCash".
        Text(
            token.mints.joinToString(", ") {
                it.removePrefix("https://").removePrefix("http://")
            },
            style = OshiTheme.typography.bodySmall,
            color = Ink.soft,
        )
        token.memo?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = OshiTheme.typography.bodyMedium, color = Ink.strong,
                 modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            t("cashu_bearer_note"),
            style = OshiTheme.typography.labelSmall,
            color = Ink.soft,
            modifier = Modifier.padding(top = 6.dp),
        )
        // OSHI validates NOTHING — no DLEQ, no call to the mint. The figure above
        // is the amount the token CLAIMS, and a card that showed it without saying
        // so would present an already-spent token as a payment received.
        Text(
            t("cashu_not_verified"),
            style = OshiTheme.typography.labelSmall,
            color = Ink.soft,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(t("cashu_redeem"), style = OshiTheme.typography.labelMedium,
                 color = OshiTheme.brand, modifier = Modifier.clickable { redeemInWallet(token) })
            Text(t("cashu_copy_token"), style = OshiTheme.typography.labelMedium,
                 color = OshiTheme.brand, modifier = Modifier.clickable { copyToken(token) })
            // Separate, and never automatic: a bearer token in a URL on someone
            // else's server is the user's decision to take.
            Text(t("cashu_redeem_web"), style = OshiTheme.typography.labelMedium,
                 color = OshiTheme.brand, modifier = Modifier.clickable { redeemOnWeb(token) })
        }
    }
}

/** A wallet this computer registered for `cashu:`, and nothing else. */
private fun redeemInWallet(token: CashuTokens.Token) = open(token.redeemUri)

/** The token on the clipboard, so it can be pasted into any wallet by hand. */
private fun copyToken(token: CashuTokens.Token) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(token.raw), null)
    }
}

/**
 * The public redemption page, opened only because the user asked for it. The
 * token travels in the URL, so it reaches that site; OSHI itself still contacts
 * no mint and no server.
 */
private fun redeemOnWeb(token: CashuTokens.Token) =
    open("https://redeem.cashu.me/?token=" + java.net.URLEncoder.encode(token.raw, "UTF-8"))

/** Best-effort: a session with no browser at all must not take the window down. */
private fun open(uri: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(uri))
        }
    }
}
