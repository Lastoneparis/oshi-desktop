package com.oshi.desktop.place

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [SqliteFile] against REAL BYTES.
 *
 * ## Where the fixtures come from, and why that matters
 *
 * Both `.db` files under `src/test/resources/place/` were downloaded from OSHI's own
 * server on 2026-08-27 — they are the exact artifacts an iPhone downloads:
 *
 * | file | source | bytes | sha256 |
 * |---|---|---|---|
 * | `ma-ifr-poi.db` | `https://oshi-messenger.com/maps/v2/ma-ifr/poi.db` | 40 960 | `6a260435df79fa9058aa0085493a16a204afec07d78155db738135a073feeed0` |
 * | `mc-streets.db` | `https://oshi-messenger.com/files/streets/mc-streets.db.gz`, gunzipped | 81 920 | `7f5a0dd701f767f385f93605c56011979315b39702c40df680ac4da90fe1b06a` |
 *
 * The `.jsonl` expectation files were produced by the REFERENCE implementation — the
 * system `sqlite3` binary, 3.51.0 — not by any Kotlin in this repo:
 *
 * ```
 * sqlite3 ma-ifr-poi.db "SELECT json_object('id',id,'name',name,…,
 *     'lat',printf('%!.17g',lat), …) FROM pois ORDER BY id;"
 * ```
 *
 * That is the whole point. A fixture this reader generated would prove that the reader
 * agrees with itself; a fixture SQLite generated proves the reader agrees with SQLite,
 * which is the only claim worth making about a file-format reader.
 *
 * Reals are compared on their raw bit pattern, not on a printed form, so a rounding
 * difference in the comparison cannot mask a decoding difference.
 */
class SqliteFileTest {

    private fun res(name: String): File {
        val url = javaClass.classLoader.getResource("place/$name")
            ?: fail("missing test resource place/$name").let { error("unreachable") }
        return File(url.toURI())
    }

    // ------------------------------------------------------------------- header

    @Test
    fun `the shipped POI database's header decodes to what sqlite3 reports for it`() {
        SqliteFile.open(res("ma-ifr-poi.db")).use { db ->
            // `sqlite3 ma-ifr-poi.db "pragma page_size; pragma encoding; pragma page_count;"`
            // answers 4096 / UTF-8 / 10.
            assertEquals(4096, db.pageSize)
            assertEquals(Charsets.UTF_8, db.textEncoding)
            assertEquals(10, db.pageCountFromHeader)
            assertEquals("no reserved-per-page space in the shipped file", 0, db.reservedPerPage)
            assertEquals(4096, db.usableSize)
        }
    }

    @Test
    fun `the shipped street index's header decodes too`() {
        SqliteFile.open(res("mc-streets.db")).use { db ->
            assertEquals(4096, db.pageSize)
            assertEquals(Charsets.UTF_8, db.textEncoding)
            assertEquals(20, db.pageCountFromHeader)
        }
    }

    // ------------------------------------------------------------------- schema

    @Test
    fun `sqlite_master enumerates the server's tables, indexes and FTS5 shadow tables`() {
        SqliteFile.open(res("ma-ifr-poi.db")).use { db ->
            val byName = db.schema.associateBy { it.name }
            assertTrue("no 'pois' table", byName.containsKey("pois"))
            assertEquals("table", byName["pois"]!!.type)
            assertTrue("'pois' should have a b-tree", byName["pois"]!!.rootPage > 0)

            // The virtual table itself has rootpage 0 — there is no b-tree behind the name.
            assertEquals("table", byName["pois_fts"]!!.type)
            assertEquals(0, byName["pois_fts"]!!.rootPage)
            assertNull("a virtual table must not be offered as a scannable table", db.table("pois_fts"))

            // FTS5's four shadow tables and the server's two indexes are all visible.
            for (n in listOf("pois_fts_data", "pois_fts_idx", "pois_fts_docsize", "pois_fts_config")) {
                assertTrue("shadow table $n missing from sqlite_master", byName.containsKey(n))
            }
            assertEquals("index", byName["idx_pois_category"]!!.type)
            assertEquals("index", byName["idx_pois_lat_lon"]!!.type)
        }
    }

    @Test
    fun `the INTEGER PRIMARY KEY column is recognised as the rowid alias`() {
        SqliteFile.open(res("ma-ifr-poi.db")).use { db ->
            val pois = db.table("pois")!!
            assertEquals(
                listOf("id", "name", "category", "subcategory", "lat", "lon",
                    "address", "phone", "website", "opening_hours"),
                pois.layout.columns,
            )
            assertEquals("'id INTEGER PRIMARY KEY' is column 0's alias", 0, pois.layout.rowidAliasIndex)
        }
    }

    // ------------------------------------------------------------------- content

    @Test
    fun `every row of the shipped pois table decodes exactly as sqlite3 dumped it`() {
        assertRowsMatch(
            "ma-ifr-poi.db", "pois", "ma-ifr-poi.rows.jsonl",
            textCols = listOf("name", "category", "subcategory", "address", "phone", "website", "opening_hours"),
            realCols = listOf("lat", "lon"),
        )
    }

    @Test
    fun `every row of the shipped streets table decodes exactly as sqlite3 dumped it`() {
        assertRowsMatch(
            "mc-streets.db", "streets", "mc-streets.rows.jsonl",
            textCols = listOf("name", "city", "country_code", "highway_type"),
            realCols = listOf("lat", "lon"),
        )
    }

    private fun assertRowsMatch(
        dbName: String,
        tableName: String,
        expectedName: String,
        textCols: List<String>,
        realCols: List<String>,
    ) {
        val expected = res(expectedName).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertTrue("expectation file is empty", expected.isNotEmpty())
        SqliteFile.open(res(dbName)).use { db ->
            val rows = db.scan(db.table(tableName)!!).toList()
            assertEquals("row count differs from sqlite3's", expected.size, rows.size)
            for (i in expected.indices) {
                val e = expected[i]
                val r = rows[i]
                val id = e.getLong("id")
                assertEquals("row $i: rowid", id, r.rowid)
                assertEquals("row $i: the INTEGER PRIMARY KEY alias must read back as the rowid", id, r.long("id"))
                for (c in textCols) {
                    val want = if (e.isNull(c)) null else e.getString(c)
                    assertEquals("row $id column $c", want, r.text(c))
                }
                for (c in realCols) {
                    // sqlite3 printed these with %!.17g, which round-trips; compare bits.
                    val want = e.getString(c).toDouble()
                    val got = r.double(c)!!
                    assertEquals(
                        "row $id column $c: $got is a different double from $want",
                        java.lang.Double.doubleToLongBits(want),
                        java.lang.Double.doubleToLongBits(got),
                    )
                }
            }
        }
    }

    @Test
    fun `NULL and the empty string are kept apart`() {
        // The shipped POI file stores '' in `address`, while the shipped street file
        // stores NULL in `city`. A reader that conflated them would pass one of these
        // tests and fail the other, which is why both files are here.
        SqliteFile.open(res("ma-ifr-poi.db")).use { db ->
            val first = db.scan(db.table("pois")!!).first()
            assertEquals("the shipped POI rows carry '' , not NULL, in address", "", first.text("address"))
        }
        SqliteFile.open(res("mc-streets.db")).use { db ->
            val first = db.scan(db.table("streets")!!).first()
            assertNull("the shipped street rows carry NULL in city", first.value("city"))
        }
    }

    @Test
    fun `a payload that spills onto an overflow page is reassembled`() {
        // 28 000 characters at a 512-byte page size: far past `usableSize - 35`, so the
        // record is written across an overflow chain. If the local-payload arithmetic in
        // the reader were off by one, this would not throw — it would return a shorter or
        // differently-broken string, which is why the length AND the content are checked.
        val expected = res("edge-cases.big.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        SqliteFile.open(res("edge-cases.db")).use { db ->
            assertEquals(512, db.pageSize)
            val rows = db.scan(db.table("big")!!).toList()
            assertEquals(expected.size, rows.size)
            for (i in expected.indices) {
                assertEquals("row $i length", expected[i].getInt("len"), rows[i].text("v")!!.length)
                assertEquals("row $i content", expected[i].getString("v"), rows[i].text("v"))
            }
            assertTrue("the fixture must actually spill", expected[1].getInt("len") > db.usableSize)
        }
    }

    @Test
    fun `a three-level b-tree is walked in rowid order and every serial type decodes`() {
        // `deep` in the fixture is a 600-row table at a 512-byte page size: its b-tree is
        // root interior (0x05) -> 2 interior -> 86 leaves. A reader that only handled leaf
        // pages would throw here, and one that mishandled the right-most pointer would
        // silently drop the last child's rows — hence the row-count assertion.
        val expected = res("edge-cases.deep.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        assertEquals(600, expected.size)
        SqliteFile.open(res("edge-cases.db")).use { db ->
            val rows = db.scan(db.table("deep")!!).toList()
            assertEquals("rows were lost walking the interior pages", expected.size, rows.size)
            for (i in expected.indices) {
                val e = expected[i]
                val r = rows[i]
                assertEquals("row $i is out of rowid order", e.getLong("id"), r.rowid)
                // serial types 1..6: one-, two-, three-, four-, six- and eight-byte ints
                assertEquals("i1", -1L, r.long("i1"))
                assertEquals("i2", 300L, r.long("i2"))
                assertEquals("i3", 70_000L, r.long("i3"))
                assertEquals("i4", 20_000_000L, r.long("i4"))
                assertEquals("i6", 200_000_000_000L, r.long("i6"))
                assertEquals("i8", 9_007_199_254_740_993L, r.long("i8"))
                // serial types 8 and 9 carry their value in the TYPE, with a zero-byte body
                assertEquals("serial type 8 (integer 0)", 0L, r.value("z"))
                assertEquals("serial type 9 (integer 1)", 1L, r.value("o"))
                assertNull("serial type 0 (NULL)", r.value("nul"))
                // serial type 7: IEEE-754 big-endian
                assertEquals(
                    "real",
                    java.lang.Double.doubleToLongBits(e.getString("r").toDouble()),
                    java.lang.Double.doubleToLongBits(r.double("r")!!),
                )
                // odd >= 13: TEXT in the database's encoding
                assertEquals("text", e.getString("t"), r.text("t"))
                // even >= 12: BLOB, raw bytes, never decoded as text
                val blob = r.blob("b")!!
                assertEquals("blob", e.getString("b").lowercase(), blob.joinToString("") { "%02x".format(it) })
            }
        }
    }

    @Test
    fun `a composite PRIMARY KEY leaves the rowid separate from the columns`() {
        // `road_edges` is the road-graph shape. Reading `from_id` as the rowid would
        // corrupt every edge; here rowid 1 sits on a row whose from_id is 10.
        val expected = res("edge-cases.edges.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        SqliteFile.open(res("edge-cases.db")).use { db ->
            val t = db.table("road_edges")!!
            assertEquals(-1, t.layout.rowidAliasIndex)
            val rows = db.scan(t).toList()
            assertEquals(expected.size, rows.size)
            for (i in expected.indices) {
                assertEquals(expected[i].getLong("rowid"), rows[i].rowid)
                assertEquals(expected[i].getLong("from_id"), rows[i].long("from_id"))
                assertEquals(expected[i].getLong("to_id"), rows[i].long("to_id"))
                assertEquals(expected[i].getString("highway"), rows[i].text("highway"))
            }
            assertTrue("the fixture must not have from_id == rowid, or it proves nothing",
                rows.any { it.rowid != it.long("from_id") })
        }
    }

    @Test
    fun `a UTF-16LE database decodes its text through the header's encoding, not a guess`() {
        val expected = res("utf16.rows.jsonl").readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        SqliteFile.open(res("utf16.db")).use { db ->
            assertEquals(Charsets.UTF_16LE, db.textEncoding)
            val rows = db.scan(db.table("u")!!).toList()
            assertEquals(expected.size, rows.size)
            for (i in expected.indices) {
                assertEquals("row $i", expected[i].getString("t"), rows[i].text("t"))
            }
        }
    }

    // ------------------------------------------------------------------- refusals

    @Test
    fun `a WITHOUT ROWID table is refused by name, not read as garbage`() {
        SqliteFile.open(res("ma-ifr-poi.db")).use { db ->
            val idx = db.schema.first { it.name == "pois_fts_idx" }
            assertTrue("pois_fts_idx should be detected as WITHOUT ROWID", idx.withoutRowid)
            try {
                db.scan(idx).toList()
                fail("scanning a WITHOUT ROWID table must fail, not return rows")
            } catch (e: SqliteFormatException) {
                assertTrue("the refusal must name the reason: ${e.message}", e.message!!.contains("WITHOUT ROWID"))
            }
        }
    }

    @Test
    fun `a file that is not a SQLite database is refused before any page is read`() {
        val tmp = Files.createTempFile("not-sqlite", ".db").toFile()
        try {
            tmp.writeBytes(ByteArray(4096) { 0x41 })
            try {
                SqliteFile.open(tmp).close()
                fail("a file of 'A's was accepted as a database")
            } catch (e: SqliteFormatException) {
                assertTrue(e.message!!.contains("magic"))
            }
        } finally { tmp.delete() }
    }

    @Test
    fun `a truncated database is refused rather than read short`() {
        val tmp = Files.createTempFile("truncated", ".db").toFile()
        try {
            tmp.writeBytes(res("ma-ifr-poi.db").readBytes().copyOfRange(0, 60))
            try {
                SqliteFile.open(tmp).close()
                fail("a 60-byte file was accepted as a database")
            } catch (e: SqliteFormatException) {
                assertTrue("${e.message}", e.message!!.contains("too short"))
            }
        } finally { tmp.delete() }
    }

    @Test
    fun `a non-empty WAL beside the database refuses the read instead of returning a stale snapshot`() {
        val tmp = Files.createTempDirectory("sqlite-wal").toFile()
        try {
            val db = File(tmp, "poi.db")
            db.writeBytes(res("ma-ifr-poi.db").readBytes())
            File(tmp, "poi.db-wal").writeBytes(ByteArray(32) { 1 })
            try {
                SqliteFile.open(db).close()
                fail("the main file was read even though a WAL sits beside it")
            } catch (e: SqliteFormatException) {
                assertTrue("${e.message}", e.message!!.contains("OLDER snapshot"))
            }
            // …and the override says so explicitly rather than being the default.
            SqliteFile.open(db, allowStaleWal = true).use { assertEquals(118, it.scan(it.table("pois")!!).count()) }
        } finally { tmp.deleteRecursively() }
    }

    // ------------------------------------------------------------------- varints

    @Test
    fun `varints decode to the values the file format spec defines`() {
        // From the format's own definition: 7 bits per byte, big-endian, with the ninth
        // byte contributing all eight of its bits.
        fun v(vararg b: Int) = SqliteFile.readVarint(b.map { it.toByte() }.toByteArray(), 0)
        assertEquals(0L, v(0x00).value); assertEquals(1, v(0x00).bytes)
        assertEquals(127L, v(0x7F).value); assertEquals(1, v(0x7F).bytes)
        assertEquals(128L, v(0x81, 0x00).value); assertEquals(2, v(0x81, 0x00).bytes)
        assertEquals(16383L, v(0xFF, 0x7F).value)
        assertEquals(16384L, v(0x81, 0x80, 0x00).value)
        assertEquals(2097151L, v(0xFF, 0xFF, 0x7F).value)
        // Nine bytes: the last one is NOT 7-bit.
        val nine = v(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(9, nine.bytes)
        assertEquals(-1L, nine.value)
        assertNotNull(nine)
    }

    @Test
    fun `a varint that runs off the end of its buffer throws instead of reading zeros`() {
        try {
            SqliteFile.readVarint(byteArrayOf(0x81.toByte()), 0)
            fail("a truncated varint was decoded")
        } catch (e: SqliteFormatException) {
            assertTrue("${e.message}", e.message!!.contains("past the end"))
        }
    }

    // ------------------------------------------------------------------- schema SQL

    @Test
    fun `the CREATE TABLE parser answers the two questions it exists to answer`() {
        fun layout(sql: String) = SqliteSchemaParser.layoutOf(sql)

        // The server's real declaration, whitespace and all.
        val pois = layout(
            """CREATE TABLE pois (
                    id            INTEGER PRIMARY KEY,
                    name          TEXT NOT NULL,
                    lat           REAL NOT NULL
                )"""
        )
        assertEquals(listOf("id", "name", "lat"), pois.columns)
        assertEquals(0, pois.rowidAliasIndex)

        // A composite PRIMARY KEY is NOT a rowid alias — `road_edges` is exactly this
        // shape, and treating from_id as the rowid would corrupt every edge read.
        val edges = layout(
            """CREATE TABLE road_edges (from_id INTEGER NOT NULL, to_id INTEGER NOT NULL,
                 weight_car REAL, polyline TEXT, PRIMARY KEY(from_id, to_id))"""
        )
        assertEquals(listOf("from_id", "to_id", "weight_car", "polyline"), edges.columns)
        assertEquals("a two-column PRIMARY KEY is not an alias", -1, edges.rowidAliasIndex)

        // Table-constraint form over a single INTEGER column IS an alias.
        val single = layout("CREATE TABLE t (a INTEGER, b TEXT, PRIMARY KEY(a))")
        assertEquals(0, single.rowidAliasIndex)

        // DESC defeats the alias, per SQLite's own rules.
        assertEquals(-1, layout("CREATE TABLE t (a INTEGER PRIMARY KEY DESC, b TEXT)").rowidAliasIndex)

        // A different declared type is not an alias either.
        assertEquals(-1, layout("CREATE TABLE t (a INT PRIMARY KEY, b TEXT)").rowidAliasIndex)

        // Quoted identifiers and a type with a parenthesised size, plus a comma inside it.
        val quoted = layout("""CREATE TABLE t ("first, odd" VARCHAR(10, 2), [b] INTEGER PRIMARY KEY)""")
        assertEquals(listOf("first, odd", "b"), quoted.columns)
        assertEquals(1, quoted.rowidAliasIndex)

        assertTrue(SqliteSchemaParser.isWithoutRowid("CREATE TABLE t(a, b, PRIMARY KEY(a)) WITHOUT ROWID"))
        assertTrue(!SqliteSchemaParser.isWithoutRowid("CREATE TABLE t(a INTEGER PRIMARY KEY)"))
    }

    @Test
    fun `fts5 column names are read out of the declaration, options excluded`() {
        // The shipped declaration, verbatim.
        val cols = MapText.fts5ColumnNames(
            """CREATE VIRTUAL TABLE pois_fts USING fts5(
                    name, category, subcategory, address,
                    content='pois', content_rowid='id'
                )"""
        )
        assertEquals(listOf("name", "category", "subcategory", "address"), cols)
        assertEquals(
            listOf("name", "city"),
            MapText.fts5ColumnNames("CREATE VIRTUAL TABLE streets_fts USING fts5(\n name, city, content=streets, content_rowid=id\n)"),
        )
    }
}
