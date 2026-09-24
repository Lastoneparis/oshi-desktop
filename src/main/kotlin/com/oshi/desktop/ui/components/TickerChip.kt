package com.oshi.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.desktop.i18n.t
import com.oshi.desktop.ticker.TickerLinks
import com.oshi.desktop.ticker.TickerQuote
import com.oshi.desktop.ticker.TickerQuoteCache
import com.oshi.desktop.ticker.TickerQuoteSnapshots
import com.oshi.desktop.ui.OshiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `@AAPL`, `@NASDAQ:TSLA`, `@gold`, `@btc` → a price chip — `__TICKER_QUOTES_2026_09_23__`.
 *
 * Port of iOS `TickerChipView.swift` / `TickerChipsForText` and Android
 * `ui/components/TickerChip.kt`, drawn under the text of a 1:1 OR group message exactly
 * where both phones draw it: `$SYMBOL`, a sparkline above 8 points, the price in the
 * quote's own currency and `▲/▼ %` in green/red, on a 20 % brand capsule.
 *
 *  - The first price a message shows is FROZEN for that message ([TickerQuoteSnapshots]) —
 *    an old message never refetches.
 *  - Tap when loaded → the TradingView chart ([TickerLinks.Match.url]) in the browser; tap
 *    while failed → retry. iOS `tap()`.
 *  - Nothing is sent: the message on the wire stays plain text.
 */
@Composable
fun TickerChip(match: TickerLinks.Match, messageId: String?, tint: Color = OshiTheme.brand, ink: Color = Ink.strong) {
    val frozen = remember(messageId, match.symbol) {
        messageId?.let { TickerQuoteSnapshots.shared.snapshot(it, match.symbol)?.quote }
    }
    var quote by remember(match.symbol, frozen) { mutableStateOf(frozen ?: TickerQuoteCache.cached(match.symbol)) }
    var failed by remember(match.symbol) { mutableStateOf(false) }
    var attempt by remember(match.symbol) { mutableIntStateOf(0) }

    LaunchedEffect(match.symbol, frozen, attempt) {
        if (frozen != null) return@LaunchedEffect
        val q = withContext(Dispatchers.IO) { TickerQuoteCache.get(match.symbol) }
        if (q != null) {
            quote = q
            failed = false
            if (messageId != null) TickerQuoteSnapshots.shared.capture(messageId, match.symbol, q)
        } else if (quote == null) failed = true
    }

    val loaded = quote
    Row(
        Modifier
            .background(tint.copy(alpha = 0.20f), CircleShape)
            .clickable(onClickLabel = if (loaded != null) t("ticker.open_chart") else t("ticker.tap_for_price")) {
                if (loaded != null) openInBrowser(match.url) else { failed = false; attempt++ }
            }
            .semantics { contentDescription = loaded?.let { "${match.display} ${it.formattedPrice()} ${it.formattedChange()}" } ?: match.display }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(match.display, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            loaded != null -> {
                if (loaded.series.size > TickerQuote.MIN_SPARKLINE_POINTS) {
                    Sparkline(loaded.series, loaded.isUp, Modifier.width(46.dp).height(16.dp))
                }
                Text(loaded.formattedPrice(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
                Text(loaded.formattedChange(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (loaded.isUp) UP else DOWN, maxLines = 1)
            }
            failed -> Text(t("quote.unavailable"), fontSize = 12.sp, color = Ink.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> Text("…", fontSize = 12.sp, color = Ink.soft)
        }
    }
}

/** Every distinct ticker in [text], as a wrapping row of chips. Nothing when there is none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TickerChipsForText(
    text: String,
    messageId: String?,
    modifier: Modifier = Modifier,
    tint: Color = OshiTheme.brand,
    /** The bubble's own text colour: white on the brand-filled outgoing bubble, as iOS inherits it. */
    ink: Color = Ink.strong,
    /** __MENTIONS_2026_09_23__ ranges owned by a person mention; a ticker overlapping one is dropped. */
    exclude: List<IntRange> = emptyList(),
) {
    if (!text.contains('@')) return
    val matches = remember(text, exclude) {
        TickerLinks.matches(text)
            .filter { m -> exclude.none { r -> m.start <= r.last && r.first < m.end } }
            .distinctBy { it.symbol }
    }
    if (matches.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        matches.forEach { TickerChip(it, messageId, tint, ink) }
    }
}

private val UP = Color(0xFF1B9E4B)
private val DOWN = Color(0xFFD13438)

@Composable
private fun Sparkline(values: List<Double>, up: Boolean, modifier: Modifier) {
    val stroke = if (up) UP else DOWN
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val span = (values.max() - lo).takeIf { it > 0.0 } ?: 1.0
        val dx = size.width / (values.size - 1)
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val p = Offset(i * dx, size.height - ((v - lo) / span).toFloat() * size.height)
            prev?.let { drawLine(stroke, it, p, strokeWidth = 1.5f) }
            prev = p
        }
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } else {
            val cmd = when {
                com.oshi.desktop.store.DesktopPaths.isMac -> listOf("open", url)
                com.oshi.desktop.store.DesktopPaths.isWindows -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> listOf("xdg-open", url)
            }
            ProcessBuilder(cmd).start()
        }
    }
}
