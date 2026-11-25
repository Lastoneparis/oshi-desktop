package com.oshi.desktop.ticker

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.NumberFormat
import java.time.Duration
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * One price, as the `@AAPL` chip draws it — `__TICKER_QUOTES_2026_09_23__`.
 *
 * Port of iOS `TickerQuoteFetcher.swift` + `TickerChipView.price(_:)` and Android
 * `util/TickerQuote.kt`: the same Yahoo chart endpoint (`range=1d&interval=5m`, a browser
 * User-Agent), the same fields (`regularMarketPrice`, then `chartPreviousClose`), the same
 * pence→pounds fix for `GBp`, and iOS's decimals rule (the CURRENCY's own decimals, more
 * below 1 / 0.01 / 0.000001), so a chip reads the same on all three platforms.
 */
data class TickerQuote(
    val symbol: String,
    val price: Double,
    val previousClose: Double,
    val currency: String,
    val series: List<Double>,
) {
    val change: Double get() = price - previousClose
    val changePercent: Double get() = if (previousClose == 0.0) 0.0 else change / previousClose * 100.0
    val isUp: Boolean get() = change >= 0

    /** iOS `TickerChipView.price(_:)`. */
    fun formattedPrice(locale: Locale = Locale.getDefault()): String {
        var value = price
        var code = currency.uppercase(Locale.US)
        if (currency == "GBp" || code == "GBX") { value /= 100.0; code = "GBP" }
        val f = NumberFormat.getCurrencyInstance(locale)
        val base = runCatching { Currency.getInstance(code) }.getOrNull()?.also { f.currency = it }
            ?.defaultFractionDigits?.takeIf { it >= 0 } ?: 2
        val a = abs(value)
        val digits = when {
            a < 0.000_001 && a != 0.0 -> maxOf(base, 8)
            a < 0.01 && a != 0.0 -> maxOf(base, 6)
            a < 1 && a != 0.0 -> maxOf(base, 4)
            else -> base
        }
        f.minimumFractionDigits = digits
        f.maximumFractionDigits = digits
        return runCatching { f.format(value) }.getOrElse { "%.${digits}f %s".format(locale, value, code) }
    }

    /** `▲1.23%` / `▼0.40%`, as both phones draw it. */
    fun formattedChange(locale: Locale = Locale.getDefault()): String =
        "%s%.2f%%".format(locale, if (isUp) "▲" else "▼", abs(changePercent))

    companion object {
        /** iOS draws the sparkline only above 8 points. */
        const val MIN_SPARKLINE_POINTS = 8

        fun url(symbol: String): String {
            val resolved = TickerLinks.resolvedSymbol(symbol)
            val sb = StringBuilder()
            for (b in resolved.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.') sb.append(c)
                else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
            }
            return "https://query1.finance.yahoo.com/v8/finance/chart/$sb?range=1d&interval=5m"
        }

        fun parse(body: String, symbol: String): TickerQuote? = runCatching {
            val first = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = first.getJSONObject("meta")
            fun d(k: String) = if (meta.has(k) && !meta.isNull(k)) meta.optDouble(k).takeIf { !it.isNaN() } else null
            val price = d("regularMarketPrice") ?: d("previousClose") ?: return null
            val prev = d("chartPreviousClose") ?: d("previousClose") ?: price
            val series = ArrayList<Double>()
            first.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")?.let { c ->
                for (i in 0 until c.length()) if (!c.isNull(i)) series += c.optDouble(i)
            }
            TickerQuote(meta.optString("symbol", symbol), price, prev, meta.optString("currency", "USD"), series)
        }.getOrNull()
    }
}

/** 60 s memory cache in front of Yahoo — iOS `TickerQuoteFetcher.ttl`, Android `TickerQuoteCache`. */
object TickerQuoteCache {
    private const val TTL_MS = 60_000L
    private data class Entry(val quote: TickerQuote, val at: Long)
    private val entries = HashMap<String, Entry>()
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build() }

    /** Swappable for tests; the default goes to Yahoo. */
    @Volatile var fetcher: (String) -> String? = { symbol ->
        runCatching {
            val req = HttpRequest.newBuilder(URI(TickerQuote.url(symbol)))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Mozilla/5.0")
                .header("Cache-Control", "no-store")
                .GET().build()
            http.send(req, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() in 200..299 }?.body()
        }.getOrNull()
    }

    fun cached(symbol: String): TickerQuote? = synchronized(entries) { entries[symbol.uppercase()] }?.quote

    /** Blocking — call off the UI thread. A failed fetch keeps the last price if there is one. */
    fun get(symbol: String): TickerQuote? {
        val key = symbol.uppercase()
        synchronized(entries) { entries[key] }?.let { if (System.currentTimeMillis() - it.at <= TTL_MS) return it.quote }
        val fetched = fetcher(symbol)?.let { TickerQuote.parse(it, symbol) }
        if (fetched != null) synchronized(entries) {
            entries[key] = Entry(fetched, System.currentTimeMillis())
            if (entries.size > 128) entries.entries.sortedBy { it.value.at }.take(64).forEach { entries.remove(it.key) }
        }
        return fetched ?: cached(symbol)
    }
}
