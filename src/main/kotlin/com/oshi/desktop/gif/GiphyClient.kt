package com.oshi.desktop.gif

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * GIPHY search/trending — the desktop port of iOS `GiphyClient.swift` and Android
 * `network/giphy/GiphyClient.kt`: same endpoints, same parameters (`rating=g`, page size 50,
 * at most 10 pages, the same ten-term default feed), same rendition choice, so the three
 * pickers show the same remote results in the same order.
 *
 * ============================================================ THE KEY IS NOT IN THIS FILE
 *
 * Both phones compile the key into their binary. This client does NOT, on purpose: the
 * desktop source is published as open source, and GIPHY's beta key is capped at ~100 calls
 * per HOUR for the whole application — a key in a public repository is a key anyone can
 * exhaust, and exhausting it empties the remote results on every iPhone and Android too.
 *
 * The key is looked up at run time, in order: the `oshi.giphy.apiKey` system property, the
 * `OSHI_GIPHY_API_KEY` environment variable, then an `oshi-giphy.properties` resource that
 * a RELEASE build can generate from a CI secret. With none of them, [isConfigured] is false
 * and the picker is the offline pack alone — exactly what iOS shows when GIPHY is
 * unreachable or over quota. Nothing fails, nothing is hidden.
 */
class GiphyClient internal constructor(
    private val apiKey: String? = resolveKey(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build(),
) {
    enum class Kind(val path: String) { GIF("gifs"), STICKER("stickers") }

    data class Item(val id: String, val title: String, val previewUrl: String, val sendUrl: String)

    class RateLimited : Exception("GIPHY hourly limit reached — the offline pack still works")

    val isConfigured: Boolean get() = !apiKey.isNullOrBlank()

    private val cache = ConcurrentHashMap<String, List<Item>>()

    /** Page [page] of the default (no search term) feed — trending, then broad terms. */
    fun feed(kind: Kind, page: Int): List<Item> {
        if (page >= FEED_TERMS.size) return emptyList()
        val term = FEED_TERMS[page]
        return if (term.isEmpty()) trending(kind, offset = page * PAGE_SIZE) else search(term, kind, offset = 0)
    }

    fun trending(kind: Kind, offset: Int = 0): List<Item> =
        fetch("${kind.path}/trending", emptyMap(), "trend:${kind.path}:$offset", offset)

    fun search(term: String, kind: Kind, offset: Int = 0): List<Item> {
        val t = term.trim()
        if (t.isEmpty()) return trending(kind, offset)
        return fetch("${kind.path}/search", mapOf("q" to t), "q:${kind.path}:${t.lowercase(Locale.ROOT)}:$offset", offset)
    }

    /** CDN bytes (thumbnails and the file to send). Not an API call: no quota. */
    fun download(url: String, maxBytes: Int = MAX_SEND_BYTES): ByteArray? = runCatching {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        resp.body().takeIf { resp.statusCode() in 200..299 && it.size <= maxBytes }
    }.getOrNull()

    private fun fetch(path: String, query: Map<String, String>, cacheKey: String, offset: Int): List<Item> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        cache[cacheKey]?.let { return it }
        val params = linkedMapOf(
            "api_key" to key,
            "limit" to PAGE_SIZE.toString(),
            "offset" to offset.toString(),
            "rating" to "g",
            "lang" to Locale.getDefault().language.take(2).ifBlank { "en" },
        ) + query
        val qs = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val req = HttpRequest.newBuilder(URI("https://api.giphy.com/v1/$path?$qs"))
            .timeout(Duration.ofSeconds(6)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 429) throw RateLimited()
        if (resp.statusCode() !in 200..299) return emptyList()
        val items = parse(resp.body())
        cache[cacheKey] = items
        return items
    }

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 10
        /** iOS `GiphyClient.maxSendBytes`. */
        const val MAX_SEND_BYTES = 5 * 1024 * 1024
        /** iOS `GiphyClient.feedTerms`, verbatim. */
        val FEED_TERMS = listOf("", "reaction", "funny", "love", "dance", "happy", "wow", "excited", "hello", "thank you")

        val shared: GiphyClient by lazy { GiphyClient() }

        /**
         * iOS `GiphyClient.parse`: preview = `fixed_width_small` ?: `preview_gif` ?: `fixed_width`;
         * send = the first of `downsized_small`, `fixed_width`, `downsized`, `original` that is
         * within [MAX_SEND_BYTES], else `fixed_width`.
         */
        fun parse(body: String): List<Item> {
            val arr = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id").ifBlank { return@mapNotNull null }
                val images = e.optJSONObject("images") ?: return@mapNotNull null
                fun url(n: String) = images.optJSONObject(n)?.optString("url")?.ifBlank { null }
                fun bytes(n: String) = images.optJSONObject(n)?.optString("size")?.toLongOrNull() ?: Long.MAX_VALUE
                val preview = url("fixed_width_small") ?: url("preview_gif") ?: url("fixed_width") ?: return@mapNotNull null
                val send = listOf("downsized_small", "fixed_width", "downsized", "original")
                    .firstOrNull { bytes(it) <= MAX_SEND_BYTES && url(it) != null }?.let(::url)
                    ?: url("fixed_width") ?: return@mapNotNull null
                Item(id, e.optString("title", ""), preview, send)
            }
        }

        internal fun resolveKey(): String? =
            System.getProperty("oshi.giphy.apiKey")?.takeIf { it.isNotBlank() }
                ?: System.getenv("OSHI_GIPHY_API_KEY")?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    GiphyClient::class.java.classLoader.getResourceAsStream("oshi-giphy.properties")?.use {
                        java.util.Properties().apply { load(it) }.getProperty("apiKey")
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
