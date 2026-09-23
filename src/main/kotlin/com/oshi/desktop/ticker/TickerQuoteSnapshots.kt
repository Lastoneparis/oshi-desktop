package com.oshi.desktop.ticker

import com.oshi.desktop.store.DesktopPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The FIRST price a message showed, kept — `__TICKER_QUOTES_2026_09_23__`.
 *
 * Port of iOS `TickerQuoteSnapshots.swift` and Android `util/TickerQuoteSnapshots.kt`: a chip
 * in an old message keeps the price it had when it was first drawn ("Apple at $231" stays
 * $231), is never refetched, and survives a restart. Keyed `messageId|SYMBOL`, capped at
 * 3,000 entries with the oldest dropped first. Nothing is sent to anyone: the message on the
 * wire is plain text `@AAPL`.
 *
 * Stored beside the other small non-content state (receipt preferences, router state) — a
 * symbol and a price are not message content.
 */
class TickerQuoteSnapshots internal constructor(
    private val storeFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Snapshot(
        val symbol: String,
        val price: Double,
        val previousClose: Double,
        val currency: String,
        val series: List<Double>,
        val capturedAt: Long,
    ) {
        val quote: TickerQuote get() = TickerQuote(symbol, price, previousClose, currency, series)
    }

    private val entries = HashMap<String, Snapshot>()

    init { load() }

    private fun key(messageId: String, symbol: String) = "$messageId|${symbol.uppercase()}"

    fun snapshot(messageId: String, symbol: String): Snapshot? = synchronized(entries) { entries[key(messageId, symbol)] }

    /** First capture wins; a later price for the same message never replaces it. */
    fun capture(messageId: String, symbol: String, quote: TickerQuote) {
        synchronized(entries) {
            val k = key(messageId, symbol)
            if (entries.containsKey(k)) return
            entries[k] = Snapshot(quote.symbol, quote.price, quote.previousClose, quote.currency, quote.series, now())
            if (entries.size > MAX_ENTRIES) {
                entries.entries.sortedBy { it.value.capturedAt }.take(entries.size - MAX_ENTRIES).map { it.key }
                    .forEach { entries.remove(it) }
            }
        }
        save()
    }

    val count: Int get() = synchronized(entries) { entries.size }

    internal fun save() {
        val json = JSONObject()
        synchronized(entries) {
            for ((k, v) in entries) json.put(k, JSONObject().apply {
                put("symbol", v.symbol); put("price", v.price); put("previousClose", v.previousClose)
                put("currency", v.currency); put("series", JSONArray(v.series)); put("capturedAt", v.capturedAt)
            })
        }
        runCatching {
            storeFile.parentFile?.mkdirs()
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(storeFile)) { storeFile.writeText(json.toString()); tmp.delete() }
        }
    }

    private fun load() {
        val decoded = runCatching {
            if (!storeFile.exists()) return
            val root = JSONObject(storeFile.readText())
            val out = HashMap<String, Snapshot>()
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                val arr = o.optJSONArray("series") ?: JSONArray()
                out[k] = Snapshot(
                    o.getString("symbol"), o.getDouble("price"), o.getDouble("previousClose"),
                    o.optString("currency", ""), List(arr.length()) { arr.getDouble(it) }, o.optLong("capturedAt", 0L),
                )
            }
            out
        }.getOrNull() ?: return
        synchronized(entries) { entries.putAll(decoded) }
    }

    companion object {
        /** iOS `TickerQuoteSnapshots.maxEntries`. */
        const val MAX_ENTRIES = 3000
        val shared: TickerQuoteSnapshots by lazy { TickerQuoteSnapshots(DesktopPaths.file("ticker-quote-snapshots.json")) }
    }
}
