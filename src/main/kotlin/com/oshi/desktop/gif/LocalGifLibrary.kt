package com.oshi.desktop.gif

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * The GIF and sticker library shipped WITH the app — the desktop port of iOS
 * `LocalGifLibrary.swift` (`__LOCAL_GIF_LIBRARY_2026_09_13__`) and Android's
 * `service/media/LocalGifLibrary.kt`.
 *
 * Same files, same manifest, same rules, so the three pickers show the same library in the
 * same order:
 *  - the two packs (`GifPack`, `StickerPack`) are read from the classpath under `gifpacks/`
 *    (see `build.gradle.kts`, `__GIF_PACK_2026_09_23__`);
 *  - a manifest entry whose file is missing is dropped rather than drawn as an empty cell;
 *  - one malformed entry never takes the whole library down;
 *  - the user's language first, then drawn-only (`any`) before it, then English, then the rest,
 *    stable inside each group — iOS `localeFirst`;
 *  - categories in iOS's hand-picked order, with any unlisted one appended sorted;
 *  - search over id + tags, case- and accent-insensitive.
 *
 * No network, no API key: this is what works during the outage OSHI exists for.
 */
class LocalGifLibrary internal constructor(
    private val resourceRoot: String = ROOT,
    private val language: String = Locale.getDefault().language.ifBlank { "en" },
    private val loader: (String) -> ByteArray? = ::classpathBytes,
    private val exists: (String) -> Boolean = ::classpathExists,
) {

    enum class Kind(val folder: String) { GIF("GifPack"), STICKER("StickerPack") }

    data class Item(
        val id: String,
        val fileName: String,
        val category: String,
        val tags: List<String>,
        val kind: Kind,
        /** ISO code of the text DRAWN in the picture (`any` = no text). */
        val lang: String,
    )

    private val items: Map<Kind, List<Item>> = Kind.entries.associateWith { load(it) }

    fun all(kind: Kind): List<Item> = items[kind].orEmpty()

    /** True when the pack was found on the classpath at all. */
    val isAvailable: Boolean get() = items.values.any { it.isNotEmpty() }

    fun categories(kind: Kind): List<String> {
        val present = all(kind).map { it.category }.toSet()
        return PREFERRED_CATEGORIES.filter { it in present } + (present - PREFERRED_CATEGORIES.toSet()).sorted()
    }

    fun search(query: String, kind: Kind, category: String? = null): List<Item> {
        var pool = all(kind)
        if (category != null) pool = pool.filter { it.category == category }
        val q = fold(query.trim())
        if (q.isEmpty()) return pool
        return pool.filter { item -> fold((listOf(item.id) + item.tags).joinToString(" ")).contains(q) }
    }

    /** The GIF bytes of one item, or null if the file is not in this build. */
    fun bytes(item: Item): ByteArray? = loader(pathOf(item))

    fun pathOf(item: Item): String = "$resourceRoot/${item.kind.folder}/${item.fileName}"

    private fun load(kind: Kind): List<Item> {
        val raw = loader("$resourceRoot/${kind.folder}/manifest.json") ?: return emptyList()
        val arr = runCatching { JSONObject(String(raw, Charsets.UTF_8)).getJSONArray("gifs") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            // Tolerant per entry, as on iOS: a bad row is skipped, never fatal.
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            val file = o.optString("file").ifBlank { continue }
            val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()
            out += Item(
                id = id,
                fileName = file,
                category = o.optString("category", ""),
                tags = tags,
                kind = kind,
                // Absent on the 286 original entries, which are all English.
                lang = o.optString("lang").ifBlank { "en" },
            )
        }
        val present = out.filter { exists(pathOf(it)) }
        return localeFirst(present)
    }

    private fun localeFirst(list: List<Item>): List<Item> {
        fun rank(lang: String) = when (lang) {
            "any" -> 0
            language -> 1
            "en" -> 2
            else -> 3
        }
        return list.withIndex().sortedWith(compareBy({ rank(it.value.lang) }, { it.index })).map { it.value }
    }

    companion object {
        const val ROOT = "gifpacks"

        /** iOS `LocalGifLibrary.categories` order, verbatim. */
        val PREFERRED_CATEGORIES = listOf(
            "Reactions", "Yes / No", "Greetings", "Thanks", "Sorry",
            "Love", "Celebrate", "Funny", "Mood", "Urgent",
            "Work", "Question", "Food", "Night", "OSHI",
        )

        val shared: LocalGifLibrary by lazy { LocalGifLibrary() }

        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

        private fun classpathExists(path: String): Boolean =
            LocalGifLibrary::class.java.classLoader.getResource(path) != null

        private fun classpathBytes(path: String): ByteArray? =
            LocalGifLibrary::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
    }
}
