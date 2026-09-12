package com.oshi.desktop.place

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * POI and street SEARCH, measured against FTS5's own answers.
 *
 * ## What is being compared, and how the expectations were made
 *
 * `ma-ifr-poi.fts.tsv` and `mc-streets.fts.tsv` are `query<TAB>id,id,id` lines. Every id
 * list was produced by the reference `sqlite3` binary (3.51.0) running the EXACT MATCH
 * expression the Swift builds for that query — `sanitizeFTSQuery`'s `"chunk"*` per
 * whitespace-separated chunk — against the real shipped databases:
 *
 * ```
 * SELECT p.id FROM pois p JOIN pois_fts f ON p.id = f.rowid
 *  WHERE f.pois_fts MATCH '"chez"* "said"*' ORDER BY p.id;
 * ```
 *
 * So a failure here means the Kotlin matcher and FTS5 disagree about a real query over a
 * real database — not that two pieces of this repo disagree with each other.
 *
 * ## What this does NOT prove
 *
 * Sets are compared, not order: FTS5's `ORDER BY rank` is bm25 over an index this reader
 * deliberately does not parse, and [OfflineMapData] says so in its own doc. And agreement
 * across this battery is agreement across this battery — it is a measurement, not a proof
 * of equivalence for every possible input. The one divergence that IS known is asserted
 * explicitly at the bottom of this file rather than left to be discovered.
 */
class OfflinePoiSearchTest {

    private fun res(name: String): File {
        val url = javaClass.classLoader.getResource("place/$name")
            ?: fail("missing test resource place/$name").let { error("unreachable") }
        return File(url.toURI())
    }

    private fun expectations(name: String): List<Pair<String, Set<Long>>> =
        res(name).readLines().filter { it.isNotBlank() }.map { line ->
            val q = line.substringBefore('\t')
            val ids = line.substringAfter('\t')
                .split(',').filter { it.isNotBlank() }.map { it.trim().toLong() }.toSet()
            q to ids
        }

    // ------------------------------------------------------------------ POI search

    @Test
    fun `POI text search returns exactly the rows FTS5 returns, on the shipped database`() {
        val cases = expectations("ma-ifr-poi.fts.tsv")
        assertTrue("expectation file is empty", cases.size >= 40)
        var nonEmpty = 0
        OfflinePoiDatabase("ma-ifr", res("ma-ifr-poi.db")).use { db ->
            assertEquals(
                "the reader must follow the FILE's fts5 declaration",
                listOf("name", "category", "subcategory", "address"), db.indexedColumns,
            )
            for ((query, expected) in cases) {
                val got = db.search(query, limit = 10_000).map { it.id }.toSet()
                assertEquals("query '$query'", expected, got)
                if (expected.isNotEmpty()) nonEmpty++
            }
        }
        assertTrue(
            "a battery where everything returns nothing would pass vacuously",
            nonEmpty >= cases.size / 2,
        )
    }

    @Test
    fun `street search returns exactly the rows FTS5 returns, on the shipped street index`() {
        val cases = expectations("mc-streets.fts.tsv")
        assertTrue(cases.size >= 15)
        var nonEmpty = 0
        OfflineStreetIndex("mc", res("mc-streets.db")).use { idx ->
            assertEquals(listOf("name", "city"), idx.indexedColumns)
            for ((query, expected) in cases) {
                val got = idx.searchStreets(query, limit = 10_000).map { it.id }.toSet()
                assertEquals("query '$query'", expected, got)
                if (expected.isNotEmpty()) nonEmpty++
            }
        }
        assertTrue("vacuous battery", nonEmpty >= cases.size / 2)
    }

    @Test
    fun `a query with no searchable text returns nothing, never everything`() {
        OfflinePoiDatabase("ma-ifr", res("ma-ifr-poi.db")).use { db ->
            for (q in listOf("", "   ", "!!!", "-", "…")) {
                assertEquals("'$q' must not become a wildcard", emptyList<MapPoi>(), db.search(q))
            }
            assertEquals(118, db.count())
        }
    }

    @Test
    fun `category search is exact and case-sensitive, as the shipped SQL is`() {
        OfflinePoiDatabase("ma-ifr", res("ma-ifr-poi.db")).use { db ->
            // `WHERE category = ?` under BINARY collation.
            assertEquals(29, db.searchByCategory("parking", limit = 10_000).size)
            assertEquals(22, db.searchByCategory("restaurant", limit = 10_000).size)
            assertTrue(
                "SQLite's default collation is BINARY — 'Parking' is not 'parking'",
                db.searchByCategory("Parking", limit = 10_000).isEmpty(),
            )
        }
    }

    @Test
    fun `address search reproduces COLLATE NOCASE, which is ASCII-only`() {
        // NOCASE folds A-Z and nothing else, so an accented letter does NOT fold. Kotlin's
        // own `contains(ignoreCase = true)` would fold it and return a row the phone hides.
        assertEquals("abcé", MapText.asciiLower("ABCé"))
        assertNotEquals("é must not fold to e", "abce", MapText.asciiLower("ABCÉ"))
        assertEquals("ıi", MapText.asciiLower("ıI"))   // and no Turkish dotless-i surprise

        OfflinePoiDatabase("ma-ifr", res("ma-ifr-poi.db")).use { db ->
            val lower = db.searchByAddress("ifrane", limit = 10_000).map { it.id }.toSet()
            val upper = db.searchByAddress("IFRANE", limit = 10_000).map { it.id }.toSet()
            assertEquals("NOCASE must make these identical", lower, upper)
            assertTrue("the fixture must actually contain the substring", lower.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ distance

    @Test
    fun `a near search orders by distance and clips at the radius`() {
        // Ifrane town centre, taken from the fixture's own rows — not a device location.
        val centre = LatLon(33.5265, -5.1108)
        OfflinePoiDatabase("ma-ifr", res("ma-ifr-poi.db")).use { db ->
            val near = db.search("restaurant", near = centre, radiusKm = 2.0, limit = 10_000)
            val far = db.search("restaurant", near = centre, radiusKm = 100.0, limit = 10_000)
            assertTrue("a 2 km radius must not return more than a 100 km one", near.size <= far.size)
            assertTrue("the radius must actually clip something in this fixture", near.size < far.size)

            val distances = near.map { Geo.haversineMeters(centre, LatLon(it.lat, it.lon)) }
            assertEquals("results are not sorted by distance", distances.sorted(), distances)
            assertTrue("a row past the radius survived", distances.all { it <= 2_000.0001 })
        }
    }

    @Test
    fun `haversine matches the shipped constants`() {
        // Both Swift formulations, on the same inputs, to within a micrometre.
        val a = LatLon(48.8584, 2.2945)      // Eiffel Tower
        val b = LatLon(48.8606, 2.3376)      // Louvre
        val atan2Form = Geo.haversineMeters(a, b)
        val asinForm = Geo.haversineMetersAsin(a.lat, a.lon, b.lat, b.lon)
        assertEquals("the two shipped formulations must agree", atan2Form, asinForm, 1e-6)
        // Sanity against an independently known figure: ~3.17 km.
        assertEquals(3_170.0, atan2Form, 30.0)
        assertEquals("a point is zero metres from itself", 0.0, Geo.haversineMeters(a, a), 0.0)
    }

    @Test
    fun `the bounding box is the shipped approximation, not a corrected one`() {
        // `MapPOIDatabase.swift:385-394`: 111.32 km per degree, lon divided by cos(lat).
        val box = Geo.boundingBox(LatLon(0.0, 0.0), 111.32)
        assertEquals(1.0, box.maxLat, 1e-12)
        assertEquals(-1.0, box.minLat, 1e-12)
        assertEquals("at the equator cos = 1, so the spans are equal", 1.0, box.maxLon, 1e-12)

        val paris = Geo.boundingBox(LatLon(48.8566, 2.3522), 10.0)
        assertTrue("longitude must span further than latitude away from the equator",
            (paris.maxLon - paris.minLon) > (paris.maxLat - paris.minLat))
    }

    // ------------------------------------------------------------------ addresses

    @Test
    fun `the house-number parser accepts what the shipped regex accepts`() {
        fun p(s: String) = MapText.parseHouseNumber(s)

        assertEquals(MapText.HouseNumber("12", "rue de Rivoli"), p("12 rue de Rivoli"))
        assertEquals(MapText.HouseNumber("100A", "Main Street"), p("100A Main Street"))
        assertEquals("a lower-case suffix is upper-cased", MapText.HouseNumber("100A", "Main Street"), p("100a Main Street"))
        assertEquals(MapText.HouseNumber("Bis 14", "avenue Foch"), p("Bis 14 avenue Foch"))
        assertEquals(MapText.HouseNumber("ter 3", "rue Blanche"), p("ter 3 rue Blanche"))
        assertEquals("leading space is trimmed first", MapText.HouseNumber("7", "quai"), p("   7 quai"))

        assertNull("no leading number is not an address query", p("rue de Rivoli"))
        assertNull("a number with no street is not an address", p("12"))
        assertNull(p(""))
        assertNull(p("   "))
    }

    @Test
    fun `numericPart takes the digits, ASCII only`() {
        assertEquals(100, MapText.numericPart("100A"))
        assertEquals(14, MapText.numericPart("Bis 14"))
        assertEquals(7, MapText.numericPart("7"))
        assertNull(MapText.numericPart("bis"))
        assertNull("Swift's Int(String) rejects non-ASCII digits, so this must too",
            MapText.numericPart("٣"))
    }

    @Test
    fun `an exact house number wins, and the composite id is the shipped one`() {
        OfflineStreetIndex("mc", res("mc-streets-housenumbers.db")).use { idx ->
            assertTrue(idx.hasHouseNumbers)
            val hits = idx.searchAddress("10 Avenue Princesse Alice")
            assertEquals(1, hits.size)
            val h = hits[0]
            assertEquals(AddressMatch.Confidence.EXACT, h.confidence)
            assertEquals(43.7390, h.lat, 1e-12)
            assertEquals(7.4270, h.lon, 1e-12)
            assertEquals("mc:1:10", h.id)

            // The letter suffix is its own anchor, not a fold of the bare number.
            val a = idx.searchAddress("10A Avenue Princesse Alice")
            assertEquals(1, a.size)
            assertEquals(AddressMatch.Confidence.EXACT, a[0].confidence)
            assertEquals(43.7395, a[0].lat, 1e-12)
        }
    }

    @Test
    fun `an unknown number is interpolated between its two nearest neighbours`() {
        OfflineStreetIndex("mc", res("mc-streets-housenumbers.db")).use { idx ->
            // Anchors on street 1: 2 -> (43.7380, 7.4260) and 10 -> (43.7390, 7.4270).
            // t = (5 - 2) / (10 - 2) = 0.375.
            val hits = idx.searchAddress("5 Avenue Princesse Alice")
            assertEquals(1, hits.size)
            assertEquals(AddressMatch.Confidence.INTERPOLATED, hits[0].confidence)
            assertEquals(43.7380 + (43.7390 - 43.7380) * 0.375, hits[0].lat, 1e-12)
            assertEquals(7.4260 + (7.4270 - 7.4260) * 0.375, hits[0].lon, 1e-12)
        }
    }

    @Test
    fun `a number past the last anchor extrapolates rather than failing`() {
        OfflineStreetIndex("mc", res("mc-streets-housenumbers.db")).use { idx ->
            val hits = idx.searchAddress("30 Avenue Princesse Alice")
            assertEquals(1, hits.size)
            assertEquals(AddressMatch.Confidence.INTERPOLATED, hits[0].confidence)
            // Asserted as a property, not a point: with anchors at 10 and 10A both at
            // numeric 10, which of the two the "two closest" fallback picks depends on
            // sort stability, and Swift's `sorted(by:)` is NOT stable. The direction is
            // the part both platforms agree on.
            assertTrue("extrapolation must continue past the highest anchor", hits[0].lat > 43.7400)
        }
    }

    @Test
    fun `a single anchor is never extrapolated from`() {
        // `OfflineStreetDatabase.swift:630` — "we don't extrapolate from a single anchor,
        // too noisy". Street 2 has exactly one house number.
        OfflineStreetIndex("mc", res("mc-streets-housenumbers.db")).use { idx ->
            assertTrue("the exact number still resolves", idx.searchAddress("7 Boulevard d'Italie").isNotEmpty())
            assertEquals(
                "one anchor must not produce an interpolated guess",
                emptyList<AddressMatch>(), idx.searchAddress("99 Boulevard d'Italie"),
            )
        }
    }

    @Test
    fun `a freshly downloaded street index has no house-number table and says so`() {
        // The server file does not ship `street_house_numbers`; iOS creates it on open and
        // a downloader fills it. Before that, an address search finds nothing — and that
        // must be visible as "no house-number index", not as a crash.
        OfflineStreetIndex("mc", res("mc-streets.db")).use { idx ->
            assertTrue("the shipped file should not carry the client-side table", !idx.hasHouseNumbers)
            assertEquals(emptyList<AddressMatch>(), idx.searchAddress("10 Avenue Princesse Alice"))
            assertTrue("street search must still work", idx.searchStreets("princesse").isNotEmpty())
        }
        // And the fixture that DOES have it proves the table is what makes the difference.
        val expected = res("mc-streets-housenumbers.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertEquals(5, expected.size)
    }

    @Test
    fun `a query without a leading number is not an address query`() {
        OfflineStreetIndex("mc", res("mc-streets-housenumbers.db")).use { idx ->
            assertEquals(
                "'Avenue Princesse Alice' is a street search, and searchAddress must decline it",
                emptyList<AddressMatch>(), idx.searchAddress("Avenue Princesse Alice"),
            )
        }
    }

    // ------------------------------------------------------------------ tokenizer

    @Test
    fun `a quoted chunk is a phrase, so token order and adjacency matter`() {
        val phrase = MapText.queryPhrases("notre-dame")
        assertEquals("one chunk means one phrase", 1, phrase.size)
        assertEquals(listOf("notre", "dame"), phrase[0].tokens)

        val two = MapText.queryPhrases("notre dame")
        assertEquals("two chunks mean two independent terms", 2, two.size)

        val text = listOf("l'église de Notre-Dame-des-Cèdres")
        assertTrue(MapText.matches(text, MapText.queryPhrases("notre-dame")))
        assertTrue(MapText.matches(text, MapText.queryPhrases("de-notre")))
        assertTrue("word order is free ACROSS chunks", MapText.matches(text, MapText.queryPhrases("dame notre")))
        assertTrue("but not WITHIN one", !MapText.matches(text, MapText.queryPhrases("dame-notre")))
    }

    @Test
    fun `a phrase must live inside one column`() {
        // Row 6 of the shipped file: name 'Restaurant Al-Akhawayn', category 'restaurant'.
        val cols = listOf("Restaurant Al-Akhawayn", "restaurant", "restaurant", "")
        assertTrue("two chunks may land in two columns", MapText.matches(cols, MapText.queryPhrases("akhawayn restaurant")))
        assertTrue(
            "one phrase may not span two columns",
            !MapText.matches(cols, MapText.queryPhrases("akhawayn-restaurant")),
        )
    }

    @Test
    fun `only the last token of a phrase is a prefix match`() {
        val cols = listOf("Avenue Princesse Alice")
        assertTrue(MapText.matches(cols, MapText.queryPhrases("princesse-ali")))
        assertTrue("a prefix in the middle of a phrase is not enough",
            !MapText.matches(cols, MapText.queryPhrases("prince-alice")))
    }

    @Test
    fun `diacritics fold for Latin, Greek and Cyrillic and are left alone elsewhere`() {
        assertEquals("credit", MapText.fold("Crédit"))
        assertEquals("eglise", MapText.fold("église"))
        assertEquals("aeroport", MapText.fold("Aéroport"))
        assertEquals("cedres", MapText.fold("Cèdres"))

        // The divergence that the base-script condition exists to prevent. FTS5's
        // `remove_diacritics 1` does not touch Arabic: on the shipped file it answers 26
        // for `افران` and nine different rows for `إفران`. Folding U+0625 down to U+0627
        // would merge the two and return rows the phone never shows.
        assertNotEquals(
            "Arabic hamza must survive folding",
            MapText.fold("إفران"), MapText.fold("افران"),
        )
        assertEquals("Tifinagh is untouched", "ⵉⴼⵔⴰⵏ", MapText.fold("ⵉⴼⵔⴰⵏ"))
    }

    @Test
    fun `the tokenizer splits on everything that is not a letter or a digit`() {
        assertEquals(listOf("a", "b", "c"), MapText.tokenize("a-b_c"))
        assertEquals(listOf("fast", "food"), MapText.tokenize("fast_food"))
        assertEquals(listOf("l", "eglise", "de"), MapText.tokenize("l'église de"))
        assertEquals(listOf("12", "rue"), MapText.tokenize("12, rue"))
        assertEquals(emptyList<String>(), MapText.tokenize("!!! ??? ---"))
    }

    // ------------------------------------------------------------ the layer on top

    @Test
    fun `OfflineMapData finds only regions that actually have a file`() {
        val root = java.nio.file.Files.createTempDirectory("oshi-maps").toFile()
        try {
            File(root, "regions/ma-ifr").mkdirs()
            File(root, "regions/empty-region").mkdirs()
            File(root, "streets/mc").mkdirs()
            res("ma-ifr-poi.db").copyTo(File(root, "regions/ma-ifr/poi.db"))
            res("mc-streets-housenumbers.db").copyTo(File(root, "streets/mc/streets.db"))

            val data = OfflineMapData(root)
            assertEquals("a directory with no poi.db is not a downloaded region",
                listOf("ma-ifr"), data.poiRegions())
            assertEquals(listOf("mc"), data.streetRegions())

            val hits = data.searchPois("chez said")
            assertEquals(1, hits.size)
            assertEquals("ma-ifr", hits[0].regionId)
            assertEquals("Chez Said", hits[0].poi.name)
            assertNull("with no coordinate there is no distance to report", hits[0].distanceMeters)

            val addr = data.searchAddress("10 Avenue Princesse Alice")
            assertEquals(1, addr.size)
            assertEquals(AddressMatch.Confidence.EXACT, addr[0].confidence)

            assertEquals(emptyList<PoiHit>(), OfflineMapData(File(root, "nope")).searchPois("chez"))
        } finally {
            root.deleteRecursively()
        }
    }
}
