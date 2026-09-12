package com.oshi.desktop.place

import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OSHI's offline POI / street layer, as pure data processing.
 *
 * ## What this is a port OF, and what it deliberately is not
 *
 * VIEWS.md §7 recorded the offline map engine as "pure data processing and likely
 * portable (unverified — not read in this pass)". Reading it changes the picture:
 * `MapPOIDatabase.swift`, `OfflineStreetDatabase.swift`, `OfflineRouter.swift` and
 * `RoadGraphImporter.swift` are four thin `import SQLite3` query layers. The portable
 * half is real, but it is the *search and geometry* — the storage is SQLite, which is
 * why [SqliteFile] exists.
 *
 * Ported here:
 *  - the POI reader and the three POI searches (`search`, `searchByCategory`,
 *    `searchByAddress`) — `MapPOIDatabase.swift:81-251`;
 *  - the street reader, `searchStreets`, and the house-number address search with exact
 *    and linearly interpolated hits — `OfflineStreetDatabase.swift:386-650`;
 *  - the geometry: haversine on R = 6 371 km and the `radiusKm/111.32` bounding box.
 *
 * NOT ported, on purpose:
 *  - **Anything that renders.** No tiles, no `MapKit`, no map-rendering library. See
 *    VIEWS.md §7's "Rendering a map on the desktop" section for the options and costs.
 *  - **The downloader.** `downloadStreetIndex`/`importRoadGraph` fetch multi-GB artifacts;
 *    a reader that cannot open a file is the thing worth having first.
 *  - **The router.** `OfflineRouter`'s bidirectional A* is portable (the `MinHeap` is
 *    twenty lines and `road_edges.polyline` is base64 of little-endian float64 pairs),
 *    but a road graph is 78 MB per country and nothing on this platform can show a route.
 *
 * ## The GPS rule
 *
 * PARITY.md row 0.19: this platform has no GPS and none is faked. Every "near me" search
 * here takes an EXPLICIT coordinate from the caller. Nothing in this file reads a device
 * location, and there is no default centre — a search with no coordinate is an unranked
 * search, not a search from a made-up position.
 *
 * ## Where the files live
 *
 *   `<home>/Maps/regions/{regionId}/poi.db`      — mirrors iOS `MapPOIDatabase.swift:35-40`
 *   `<home>/Maps/streets/{regionId}/streets.db`  — mirrors iOS `OfflineStreetDatabase.swift:719-732`
 *
 * Android disagrees with iOS on both names (`pois.db`, and `routing.db` for the road
 * graph). iOS's names are used here because iOS's is the layout the two readers above
 * were transcribed from; [OfflinePoiDatabase] and [OfflineStreetIndex] take a `File`, so
 * the layout is the caller's problem, not the reader's.
 */
class OfflineMapData(val mapsDir: File) {

    /** Region ids that have a POI database on disk, sorted. */
    fun poiRegions(): List<String> = regionsUnder(File(mapsDir, "regions"), "poi.db")

    /** Region ids that have a street index on disk, sorted. */
    fun streetRegions(): List<String> = regionsUnder(File(mapsDir, "streets"), "streets.db")

    fun poiFile(regionId: String): File = File(File(File(mapsDir, "regions"), regionId), "poi.db")

    fun streetFile(regionId: String): File = File(File(File(mapsDir, "streets"), regionId), "streets.db")

    private fun regionsUnder(root: File, leaf: String): List<String> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, leaf).isFile }
            .map { it.name }
            .sorted()

    /**
     * Text search across every downloaded region, or one named region.
     *
     * @param near an explicit coordinate, never a device location — see the GPS rule above.
     */
    fun searchPois(
        query: String,
        near: LatLon? = null,
        radiusKm: Double = 10.0,
        limit: Int = 50,
        regionId: String? = null,
    ): List<PoiHit> {
        val regions = regionId?.let { listOf(it) } ?: poiRegions()
        val hits = ArrayList<PoiHit>()
        for (r in regions) {
            val f = poiFile(r)
            if (!f.isFile) continue
            OfflinePoiDatabase(r, f).use { db ->
                db.search(query, near, radiusKm, limit).forEach { hits.add(PoiHit(r, it, near?.let { c -> Geo.haversineMeters(c, LatLon(it.lat, it.lon)) })) }
            }
        }
        return rank(hits, near, limit)
    }

    fun searchPoisByCategory(
        category: String,
        near: LatLon? = null,
        radiusKm: Double = 10.0,
        limit: Int = 50,
        regionId: String? = null,
    ): List<PoiHit> {
        val regions = regionId?.let { listOf(it) } ?: poiRegions()
        val hits = ArrayList<PoiHit>()
        for (r in regions) {
            val f = poiFile(r)
            if (!f.isFile) continue
            OfflinePoiDatabase(r, f).use { db ->
                db.searchByCategory(category, near, radiusKm, limit)
                    .forEach { hits.add(PoiHit(r, it, near?.let { c -> Geo.haversineMeters(c, LatLon(it.lat, it.lon)) })) }
            }
        }
        return rank(hits, near, limit)
    }

    /** Street-name search across every downloaded street index, or one named region. */
    fun searchStreets(query: String, limit: Int = 20, regionId: String? = null): List<StreetRecord> {
        val regions = regionId?.let { listOf(it) } ?: streetRegions()
        val out = ArrayList<StreetRecord>()
        for (r in regions) {
            val f = streetFile(r)
            if (!f.isFile) continue
            OfflineStreetIndex(r, f).use { out.addAll(it.searchStreets(query, limit)) }
        }
        return out.take(limit)
    }

    /**
     * House-number-aware address search. Returns nothing when the query has no leading
     * house number — that is the shipped behaviour (`OfflineStreetDatabase.swift:498`),
     * not an omission: a query without a number is a street search.
     */
    fun searchAddress(
        query: String,
        near: LatLon? = null,
        limit: Int = 20,
        regionId: String? = null,
    ): List<AddressMatch> {
        val regions = regionId?.let { listOf(it) } ?: streetRegions()
        val out = ArrayList<AddressMatch>()
        for (r in regions) {
            val f = streetFile(r)
            if (!f.isFile) continue
            OfflineStreetIndex(r, f).use { out.addAll(it.searchAddress(query, near)) }
        }
        // Exact before interpolated; within a group, nearest first when a coordinate was
        // supplied. `OfflineStreetDatabase.swift:557-566`.
        val sorted = out.sortedWith(
            compareBy<AddressMatch> { if (it.confidence == AddressMatch.Confidence.EXACT) 0 else 1 }
                .thenBy { m -> near?.let { Geo.haversineMeters(it, LatLon(m.lat, m.lon)) } ?: 0.0 }
        )
        return sorted.take(limit)
    }

    private fun rank(hits: List<PoiHit>, near: LatLon?, limit: Int): List<PoiHit> {
        val sorted = if (near != null) {
            hits.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        } else {
            // iOS's FTS branch is `ORDER BY rank` — FTS5's bm25, which cannot be computed
            // without the FTS5 index this reader deliberately does not parse. iOS's own
            // non-FTS fallback orders by name, so that is what is reproduced, and the
            // difference is stated rather than hidden.
            hits.sortedWith(compareBy({ it.poi.name }, { it.regionId }, { it.poi.id }))
        }
        return sorted.take(limit)
    }
}

/** A coordinate. Two doubles — the whole of what `CLLocationCoordinate2D` was carrying. */
data class LatLon(val lat: Double, val lon: Double)

/** One POI row, exactly the ten columns the server's `pois` table has. */
data class MapPoi(
    val id: Long,
    val name: String,
    val category: String,
    val subcategory: String?,
    val lat: Double,
    val lon: Double,
    val address: String?,
    val phone: String?,
    val website: String?,
    val openingHours: String?,
)

/**
 * A POI plus which region's file it came from.
 *
 * The region matters: `pois.id` restarts at 1 in every region file, so an id alone does
 * not identify a POI across two downloaded regions.
 */
data class PoiHit(val regionId: String, val poi: MapPoi, val distanceMeters: Double?)

/** One `streets` row. */
data class StreetRecord(
    val regionId: String,
    val id: Long,
    val name: String,
    val city: String?,
    val countryCode: String?,
    val lat: Double,
    val lon: Double,
    val highwayType: String?,
)

/** One hit from [OfflineStreetIndex.searchAddress]. */
data class AddressMatch(
    val regionId: String,
    val streetId: Long,
    val houseNumber: String,
    val streetName: String,
    val city: String?,
    val lat: Double,
    val lon: Double,
    val confidence: Confidence,
) {
    enum class Confidence { EXACT, INTERPOLATED }

    /** The composite id iOS builds at `OfflineStreetDatabase.swift:523`. */
    val id: String get() = "$regionId:$streetId:$houseNumber"
}

// ---------------------------------------------------------------------------- POI

/**
 * Read-only reader for a server-built `poi.db`.
 *
 * The schema is the SERVER's and is never written to. Verified against the shipped
 * `https://oshi-messenger.com/maps/v2/ma-ifr/poi.db` (40 960 B, 118 rows, page size 4096,
 * UTF-8), whose `sqlite_master` reads:
 *
 * ```
 * CREATE TABLE pois (id INTEGER PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL,
 *                    subcategory TEXT, lat REAL NOT NULL, lon REAL NOT NULL,
 *                    address TEXT, phone TEXT, website TEXT, opening_hours TEXT);
 * CREATE INDEX idx_pois_category ON pois(category);
 * CREATE INDEX idx_pois_lat_lon  ON pois(lat, lon);
 * CREATE VIRTUAL TABLE pois_fts USING fts5(name, category, subcategory, address,
 *                    content='pois', content_rowid='id');
 * ```
 *
 * There is no `region_id` column and no `tags` column. Every SELECT on Android asked for
 * both for months and therefore failed at prepare time, silently, in both the FTS branch
 * and its fallback (`MapPOIDatabase.kt:27-34`). Columns are read BY NAME out of
 * `sqlite_master` here, so a column the file does not have reads null instead of killing
 * the query.
 *
 * The FTS5 index is not parsed — see [MapText] for what replaces `MATCH` and how far the
 * replacement has actually been measured.
 */
class OfflinePoiDatabase(private val regionId: String, file: File) : AutoCloseable {

    private val db = SqliteFile.open(file)
    private val table = db.table(TABLE)
        ?: throw SqliteFormatException("${file.name} has no '$TABLE' table — this is not an OSHI POI database")

    /**
     * The columns the file's own `pois_fts` declaration says are indexed, lower-cased.
     * Read from the file rather than hard-coded, so a server that adds a column to the
     * index is followed rather than second-guessed. Falls back to the four columns the
     * shipped file declares when there is no FTS table at all.
     */
    val indexedColumns: List<String> = run {
        val fts = db.schema.firstOrNull { it.name.equals(FTS, ignoreCase = true) }
        val declared = fts?.sql?.let { MapText.fts5ColumnNames(it) } ?: emptyList()
        if (declared.isEmpty()) listOf("name", "category", "subcategory", "address") else declared
    }

    override fun close() = db.close()

    fun count(): Int = db.scan(table).count()

    fun all(): List<MapPoi> = db.scan(table).map(::toPoi).toList()

    /**
     * `MapPOIDatabase.swift:81-135`. Text search over the FTS-indexed columns, optionally
     * clipped to a bounding box and a radius around an explicit coordinate.
     */
    fun search(query: String, near: LatLon? = null, radiusKm: Double = 10.0, limit: Int = 50): List<MapPoi> {
        val phrases = MapText.queryPhrases(query)
        if (phrases.isEmpty()) return emptyList()
        val box = near?.let { Geo.boundingBox(it, radiusKm) }
        val out = ArrayList<MapPoi>()
        for (row in db.scan(table)) {
            val poi = toPoi(row)
            if (!matchesPhrases(poi, phrases)) continue
            if (near != null && !withinBoxAndRadius(poi, near, box!!, radiusKm)) continue
            out.add(poi)
        }
        return order(out, near, limit)
    }

    /** `MapPOIDatabase.swift:202-251`. Exact, case-SENSITIVE category equality, as shipped. */
    fun searchByCategory(category: String, near: LatLon? = null, radiusKm: Double = 10.0, limit: Int = 50): List<MapPoi> {
        val box = near?.let { Geo.boundingBox(it, radiusKm) }
        val out = ArrayList<MapPoi>()
        for (row in db.scan(table)) {
            val poi = toPoi(row)
            // `WHERE category = ?` under SQLite's default BINARY collation: exact bytes.
            if (poi.category != category) continue
            if (near != null && !withinBoxAndRadius(poi, near, box!!, radiusKm)) continue
            out.add(poi)
        }
        return order(out, near, limit)
    }

    /**
     * `MapPOIDatabase.swift:146-197`. `address LIKE '%q%' COLLATE NOCASE`.
     *
     * NOCASE is SQLite's ASCII-only case folding — it does NOT fold `É` to `é`. Kotlin's
     * `contains(ignoreCase = true)` folds the whole of Unicode and would return rows the
     * phone does not, so [MapText.asciiLower] is used to reproduce NOCASE exactly.
     */
    fun searchByAddress(query: String, near: LatLon? = null, radiusKm: Double = 50.0, limit: Int = 20): List<MapPoi> {
        val needle = MapText.asciiLower(query)
        val box = near?.let { Geo.boundingBox(it, radiusKm) }
        val out = ArrayList<MapPoi>()
        for (row in db.scan(table)) {
            val poi = toPoi(row)
            val addr = poi.address ?: continue
            if (!MapText.asciiLower(addr).contains(needle)) continue
            if (near != null && !withinBoxAndRadius(poi, near, box!!, radiusKm)) continue
            out.add(poi)
        }
        return order(out, near, limit)
    }

    private fun matchesPhrases(poi: MapPoi, phrases: List<MapText.Phrase>): Boolean {
        val haystack = indexedColumns.mapNotNull { col ->
            when (col) {
                "name" -> poi.name
                "category" -> poi.category
                "subcategory" -> poi.subcategory
                "address" -> poi.address
                "phone" -> poi.phone
                "website" -> poi.website
                "opening_hours" -> poi.openingHours
                else -> null
            }
        }
        return MapText.matches(haystack, phrases)
    }

    private fun withinBoxAndRadius(poi: MapPoi, near: LatLon, box: Geo.Box, radiusKm: Double): Boolean {
        // Both filters, in iOS's order. The bounding box alone is only an index
        // accelerator on the phone, but it also CLIPS: applying the radius alone here
        // would return rows the phone never shows.
        if (poi.lat < box.minLat || poi.lat > box.maxLat) return false
        if (poi.lon < box.minLon || poi.lon > box.maxLon) return false
        return Geo.haversineMeters(near, LatLon(poi.lat, poi.lon)) / 1000.0 <= radiusKm
    }

    private fun order(list: List<MapPoi>, near: LatLon?, limit: Int): List<MapPoi> {
        val sorted = if (near != null) {
            list.sortedBy { Geo.haversineMeters(near, LatLon(it.lat, it.lon)) }
        } else {
            list.sortedWith(compareBy({ it.name }, { it.id }))
        }
        return sorted.take(limit)
    }

    private fun toPoi(row: SqliteFile.Row) = MapPoi(
        id = row.long("id") ?: row.rowid,
        name = row.text("name") ?: "",
        category = row.text("category") ?: "",
        subcategory = row.text("subcategory"),
        lat = row.double("lat") ?: 0.0,
        lon = row.double("lon") ?: 0.0,
        address = row.text("address"),
        phone = row.text("phone"),
        website = row.text("website"),
        openingHours = row.text("opening_hours"),
    )

    @Suppress("unused")
    val region: String get() = regionId

    companion object {
        const val TABLE = "pois"
        const val FTS = "pois_fts"
    }
}

// ------------------------------------------------------------------------- streets

/**
 * Read-only reader for a server-built `streets.db`, plus the house-number address search.
 *
 * Verified against the shipped `https://oshi-messenger.com/files/streets/mc-streets.db.gz`
 * (29 974 B gzipped → 81 920 B, 385 rows), whose schema is:
 *
 * ```
 * CREATE TABLE streets (id INTEGER PRIMARY KEY, name TEXT NOT NULL, city TEXT,
 *     country_code TEXT, lat REAL NOT NULL, lon REAL NOT NULL,
 *     bbox_min_lat REAL, bbox_min_lon REAL, bbox_max_lat REAL, bbox_max_lon REAL,
 *     highway_type TEXT);
 * CREATE VIRTUAL TABLE streets_fts USING fts5(name, city, content=streets, content_rowid=id);
 * CREATE INDEX idx_cc ON streets(country_code);
 * ```
 *
 * Note that the real file has NO `region` column, though `MapPOIDatabase`'s Android
 * sibling documents one (`OfflineStreetDatabase.kt:34-48`). Reading by name means the
 * difference costs nothing here.
 *
 * `street_house_numbers` is a CLIENT-side table: iOS creates it on every open
 * (`OfflineStreetDatabase.swift:774-794`) and a downloader populates it. It is absent
 * from a freshly downloaded file, so [searchAddress] returns no exact hits and no
 * interpolated hits until something has written it — which is the shipped behaviour, and
 * is reported as "no house-number index" rather than as "no such address".
 */
class OfflineStreetIndex(private val regionId: String, file: File) : AutoCloseable {

    private val db = SqliteFile.open(file)
    private val streets = db.table(STREETS)
        ?: throw SqliteFormatException("${file.name} has no '$STREETS' table — this is not an OSHI street index")
    private val houseNumbers = db.table(HOUSE_NUMBERS)

    /** True when the client-side house-number table exists in this file. */
    val hasHouseNumbers: Boolean get() = houseNumbers != null

    val indexedColumns: List<String> = run {
        val fts = db.schema.firstOrNull { it.name.equals(FTS, ignoreCase = true) }
        val declared = fts?.sql?.let { MapText.fts5ColumnNames(it) } ?: emptyList()
        if (declared.isEmpty()) listOf("name", "city") else declared
    }

    override fun close() = db.close()

    fun count(): Int = db.scan(streets).count()

    fun all(): List<StreetRecord> = db.scan(streets).map(::toStreet).toList()

    /** `OfflineStreetDatabase.swift:386-424`. */
    fun searchStreets(query: String, limit: Int = 20): List<StreetRecord> {
        val phrases = MapText.queryPhrases(query)
        if (phrases.isEmpty()) return emptyList()
        val out = ArrayList<StreetRecord>()
        for (row in db.scan(streets)) {
            val s = toStreet(row)
            val haystack = indexedColumns.mapNotNull {
                when (it) {
                    "name" -> s.name
                    "city" -> s.city
                    "country_code" -> s.countryCode
                    "highway_type" -> s.highwayType
                    else -> null
                }
            }
            if (MapText.matches(haystack, phrases)) out.add(s)
        }
        // iOS's FTS branch orders by bm25 rank, which is not reproducible here; its own
        // LIKE fallback orders by name, and so does this.
        return out.sortedWith(compareBy({ it.name }, { it.id })).take(limit)
    }

    /**
     * `OfflineStreetDatabase.swift:491-568`. Parses a leading house number, finds
     * candidate streets, then takes an exact `street_house_numbers` row or linearly
     * interpolates between the two nearest known numbers on the same street.
     */
    fun searchAddress(query: String, near: LatLon? = null): List<AddressMatch> {
        val parsed = MapText.parseHouseNumber(query) ?: return emptyList()
        val anchors = houseNumbers?.let { loadAnchors(it) } ?: emptyMap()
        val target = MapText.numericPart(parsed.number)
        val out = ArrayList<AddressMatch>()
        for (street in searchStreets(parsed.street, limit = 10)) {
            val rows = anchors[street.id] ?: emptyList()
            val exact = rows.firstOrNull { MapText.asciiLower(it.number) == MapText.asciiLower(parsed.number) }
            if (exact != null) {
                out.add(
                    AddressMatch(regionId, street.id, parsed.number, street.name, street.city,
                        exact.lat, exact.lon, AddressMatch.Confidence.EXACT)
                )
                continue
            }
            if (target != null) {
                val interp = interpolate(rows, target)
                if (interp != null) {
                    out.add(
                        AddressMatch(regionId, street.id, parsed.number, street.name, street.city,
                            interp.first, interp.second, AddressMatch.Confidence.INTERPOLATED)
                    )
                }
            }
        }
        return out.sortedWith(
            compareBy<AddressMatch> { if (it.confidence == AddressMatch.Confidence.EXACT) 0 else 1 }
                .thenBy { m -> near?.let { Geo.haversineMeters(it, LatLon(m.lat, m.lon)) } ?: 0.0 }
        )
    }

    private class Anchor(val number: String, val n: Int?, val lat: Double, val lon: Double)

    private fun loadAnchors(t: SqliteFile.SchemaEntry): Map<Long, List<Anchor>> {
        val out = HashMap<Long, MutableList<Anchor>>()
        for (row in db.scan(t)) {
            val sid = row.long("street_id") ?: continue
            val num = row.text("house_number") ?: continue
            val lat = row.double("lat") ?: continue
            val lon = row.double("lon") ?: continue
            out.getOrPut(sid) { ArrayList() }.add(Anchor(num, MapText.numericPart(num), lat, lon))
        }
        return out
    }

    /**
     * `OfflineStreetDatabase.swift:611-650`.
     *
     * Two anchors minimum — iOS refuses to extrapolate from one, "too noisy", and so does
     * this. `lo` is the greatest known number ≤ target and `hi` the smallest above it;
     * when only one side exists the two anchors closest in absolute delta are used, which
     * is what makes a number past the end of the street extrapolate rather than fail.
     */
    private fun interpolate(rows: List<Anchor>, target: Int): Pair<Double, Double>? {
        val anchors = rows.filter { it.n != null }.sortedBy { it.n }
        if (anchors.size < 2) return null
        val lo = anchors.lastOrNull { it.n!! <= target }
        val hi = anchors.firstOrNull { it.n!! > target }
        val a: Anchor
        val b: Anchor
        if (lo != null && hi != null) {
            a = lo; b = hi
        } else {
            val byDelta = anchors.sortedBy { abs(it.n!! - target) }
            a = byDelta[0]; b = byDelta[1]
        }
        if (a.n == b.n) return a.lat to a.lon
        val t = (target - a.n!!).toDouble() / (b.n!! - a.n).toDouble()
        return (a.lat + (b.lat - a.lat) * t) to (a.lon + (b.lon - a.lon) * t)
    }

    private fun toStreet(row: SqliteFile.Row) = StreetRecord(
        regionId = regionId,
        id = row.long("id") ?: row.rowid,
        name = row.text("name") ?: "",
        city = row.text("city"),
        countryCode = row.text("country_code"),
        lat = row.double("lat") ?: 0.0,
        lon = row.double("lon") ?: 0.0,
        highwayType = row.text("highway_type"),
    )

    companion object {
        const val STREETS = "streets"
        const val FTS = "streets_fts"
        const val HOUSE_NUMBERS = "street_house_numbers"
    }
}

// ---------------------------------------------------------------------- text rules

/**
 * The text rules the shipped search depends on, and an honest account of how close they
 * are to FTS5.
 *
 * ## The query shape
 *
 * Both Swift files build the same MATCH expression (`MapPOIDatabase.swift:373-381`,
 * `OfflineStreetDatabase.swift:829-836`): split the user's text on whitespace, trim
 * punctuation off each end, drop empties, wrap each survivor as `"tok"*` and join with a
 * space. FTS5's implicit operator is AND, so that is: **every query token must be a
 * PREFIX of some token in some indexed column, and the tokens of one quoted chunk must
 * be ADJACENT.** [queryPhrases] and [matches] reproduce exactly that.
 *
 * ## The tokenizer
 *
 * FTS5's default is `unicode61`: a token is a maximal run of Unicode letters and digits,
 * everything else separates, and tokens are case-folded. That is [tokenize] and [fold].
 *
 * ## Diacritics — the part that is an APPROXIMATION, and was measured
 *
 * `unicode61` defaults to `remove_diacritics 1`, whose folding table is a hard-coded C
 * array covering Latin, Greek and Cyrillic. This reimplements it as "NFD-decompose, then
 * drop a non-spacing mark **only when its base character is Latin, Greek or Cyrillic**".
 * The base-script condition is not decoration: without it, Arabic `إ` (U+0625) folds to
 * `ا` and a search for `افران` returns the nine `إفران` rows that FTS5 does NOT return —
 * measured on the shipped `ma-ifr/poi.db`, where FTS5 answers `26` and an unconditional
 * NFD strip answers `18,19,22,26,40,50,52,88,107,108`.
 *
 * What is verified: `OfflinePoiSearchParityTest` replays every probe query below against
 * the id sets the reference `sqlite3` binary produced from the real file, and they agree.
 * What is NOT verified: agreement for inputs outside that battery. This is a measured
 * approximation of FTS5, not a proof of equivalence, and it is used only for matching —
 * bm25 ranking is not reproduced at all.
 */
object MapText {

    /**
     * One `"chunk"*` term of the MATCH expression, after FTS5 has tokenized it.
     *
     * A quoted string in FTS5 is a PHRASE, not a bag of words: its tokens must appear
     * consecutively, in order, inside ONE column, and the trailing `*` applies to the
     * LAST token only. Measured on the shipped `ma-ifr/poi.db`: `"notre-dame"*` returns
     * row 28, `"dame-notre"*` returns nothing, and `"akhawayn restaurant"*` returns
     * nothing even though row 6 carries `akhawayn` in `name` and `restaurant` in
     * `category`. Treating a chunk as independent AND-ed tokens would answer all three
     * wrongly.
     */
    data class Phrase(val tokens: List<String>) {
        val isEmpty: Boolean get() = tokens.isEmpty()
    }

    /**
     * `sanitizeFTSQuery` (`MapPOIDatabase.swift:373-381`,
     * `OfflineStreetDatabase.swift:829-836`) followed by FTS5's own tokenizer.
     *
     * The Swift splits on whitespace, trims punctuation off each END, and wraps every
     * survivor as `"chunk"*`. So `notre dame` becomes two independent prefix terms while
     * `notre-dame` becomes ONE two-token phrase.
     *
     * The end-trim is not reproduced separately: FTS5's tokenizer already discards
     * leading and trailing non-alphanumerics, so trimming first cannot change the tokens.
     *
     * An empty result means "no query", which both Swift files treat as "return nothing"
     * rather than "return everything".
     */
    fun queryPhrases(query: String): List<Phrase> =
        query.split(WHITESPACE)
            .map { Phrase(tokenize(it)) }
            .filterNot { it.isEmpty }

    /** Flat token view, for callers that only ask whether a query has searchable text. */
    fun queryTokens(query: String): List<String> = queryPhrases(query).flatMap { it.tokens }

    /**
     * FTS5's implicit AND over phrases: every phrase must match, each of them inside a
     * SINGLE column, with the last token of the phrase matched as a prefix and the
     * earlier tokens matched exactly.
     */
    fun matches(values: List<String>, phrases: List<Phrase>): Boolean {
        if (phrases.isEmpty()) return false
        val perColumn = values.map { tokenize(it) }
        if (perColumn.all { it.isEmpty() }) return false
        return phrases.all { phrase -> perColumn.any { column -> phraseOccursIn(column, phrase.tokens) } }
    }

    private fun phraseOccursIn(column: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || column.size < phrase.size) return false
        val last = phrase.size - 1
        outer@ for (start in 0..(column.size - phrase.size)) {
            for (i in 0 until last) {
                if (column[start + i] != phrase[i]) continue@outer
            }
            if (column[start + last].startsWith(phrase[last])) return true
        }
        return false
    }

    /** Unicode whitespace, matching Swift's `.whitespacesAndNewlines`. */
    private val WHITESPACE = Regex("[\\p{IsWhite_Space}]+")

    /** `unicode61`: maximal runs of letters and digits, folded. */
    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val width = Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp)
            else if (sb.isNotEmpty()) { out.add(fold(sb.toString())); sb.setLength(0) }
            i += width
        }
        if (sb.isNotEmpty()) out.add(fold(sb.toString()))
        return out.filter { it.isNotEmpty() }
    }

    /** Lower-case, then strip diacritics from Latin/Greek/Cyrillic bases only. */
    fun fold(s: String): String {
        val lower = s.lowercase()
        if (lower.all { it.code < 0x80 }) return lower          // fast path, and exact
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        var baseIsFoldable = false
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            val width = Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            ) {
                if (!baseIsFoldable) sb.appendCodePoint(cp)      // e.g. Arabic: keep the mark
            } else {
                baseIsFoldable = isDiacriticFoldableScript(cp)
                sb.appendCodePoint(cp)
            }
            i += width
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }

    /** The scripts SQLite's `remove_diacritics 1` table actually covers. */
    private fun isDiacriticFoldableScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC -> true
        else -> false
    }

    /**
     * SQLite's `COLLATE NOCASE`: ASCII A–Z only. Anything else is left alone, which is
     * why `LIKE '%é%' COLLATE NOCASE` does not find `É` on the phone either.
     */
    fun asciiLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    /** Column names out of a `CREATE VIRTUAL TABLE … USING fts5(…)` declaration. */
    fun fts5ColumnNames(sql: String): List<String> {
        val open = sql.indexOf('(')
        val close = sql.lastIndexOf(')')
        if (open < 0 || close <= open) return emptyList()
        return SqliteSchemaParser.splitTopLevel(sql.substring(open + 1, close))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // fts5 options are `key = value` or `key=value`; real columns have no '='.
            .filterNot { it.contains('=') }
            .map { it.substringBefore(' ').trim('"', '\'', '`', '[', ']').lowercase() }
            .filter { it.isNotEmpty() }
    }

    data class HouseNumber(val number: String, val street: String)

    /**
     * `OfflineStreetDatabase.swift:436-467`. `12 rue de Rivoli`, `100A Main Street`,
     * `Bis 14 avenue Foch`.
     *
     * The pattern is the shipped one. `UNICODE_CHARACTER_CLASS` is set because
     * `NSRegularExpression` is ICU, where `\d` means the Nd category and `\b` is
     * Unicode-aware; Java's defaults are ASCII-only and would silently reject a query an
     * iPhone accepts. `[A-Za-z]` stays ASCII because the Swift wrote it that way.
     */
    fun parseHouseNumber(rawQuery: String): HouseNumber? {
        val q = rawQuery.trim()
        if (q.isEmpty()) return null
        val m = HOUSE_NUMBER.matcher(q)
        if (!m.lookingAt() || m.end() == 0) return null
        val sb = StringBuilder()
        m.group(1)?.let { if (it.isNotBlank()) sb.append(it.trim()).append(' ') }
        m.group(2)?.let { sb.append(it) }
        m.group(3)?.let { if (it.isNotEmpty()) sb.append(it.uppercase()) }
        val number = sb.toString()
        val street = q.substring(m.end()).trim()
        if (street.isEmpty() || number.isEmpty()) return null
        return HouseNumber(number, street)
    }

    /**
     * `OfflineStreetDatabase.swift:472-476`: the digits of a house-number token, as an
     * int. `100A` → 100, `Bis 14` → 14.
     *
     * ASCII digits only. Swift's `Int(String)` rejects non-ASCII digits, so accepting
     * them here would make the desktop interpolate an address the phone refuses.
     */
    fun numericPart(token: String): Int? {
        val digits = token.filter { it in '0'..'9' }
        return if (digits.isEmpty()) null else digits.toIntOrNull()
    }

    private val HOUSE_NUMBER: Pattern = Pattern.compile(
        "^\\s*((?:bis|ter|quater)\\s+)?(\\d+)\\s*([A-Za-z])?\\b\\s*",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNICODE_CHARACTER_CLASS,
    )
}

// ------------------------------------------------------------------------ geometry

/**
 * The two geometric primitives the shipped search uses, on the same constants.
 *
 * Both Swift files spell haversine out with R = 6 371 000 m and `2·atan2(√a, √(1−a))`;
 * `RoadGraphImporter` uses `2·R·asin(√a)`, which is the same function. R = 6371 km is a
 * mean radius, not the ellipsoid — distances here are the phone's distances, which is the
 * property that matters for ordering a result list identically.
 */
object Geo {

    data class Box(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    fun haversineMeters(a: LatLon, b: LatLon): Double = haversineMeters(a.lat, a.lon, b.lat, b.lon)

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Equivalent formulation used by `RoadGraphImporter.swift:420-429`'s Python twin. */
    fun haversineMetersAsin(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * `MapPOIDatabase.swift:385-394`. A degrees-per-km approximation, NOT a true disc:
     * 111.32 km per degree of latitude, divided by cos(lat) for longitude.
     *
     * Copied rather than corrected, because iOS applies it as a hard filter alongside the
     * radius. `max(cos, …)` is NOT in the Swift, so a search centred exactly on a pole
     * divides by zero there and produces an infinite span; that is reproduced rather than
     * papered over, since the desktop showing a row the phone hides is the failure this
     * whole pass is trying to avoid.
     */
    fun boundingBox(center: LatLon, radiusKm: Double): Box {
        val latDelta = radiusKm / 111.32
        val lonDelta = radiusKm / (111.32 * cos(Math.toRadians(center.lat)))
        return Box(
            minLat = center.lat - latDelta,
            maxLat = center.lat + latDelta,
            minLon = center.lon - lonDelta,
            maxLon = center.lon + lonDelta,
        )
    }

    @Suppress("unused")
    fun clampSpeed(v: Double): Double = max(v, 0.1)
}
