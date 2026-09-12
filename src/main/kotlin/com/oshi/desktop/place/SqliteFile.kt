package com.oshi.desktop.place

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * A read-only reader for the SQLite 3 **file format**, written from scratch against the
 * published on-disk spec, with ZERO new dependencies.
 *
 * ## Why this exists
 *
 * PARITY.md's map rows inherited a claim from VIEWS.md: that OSHI's offline map layer
 * (`OfflineStreetDatabase.swift`, `MapPOIDatabase.swift`, `OfflineRouter.swift`,
 * `RoadGraphImporter.swift`) is "pure data processing and likely portable". Reading those
 * files shows what they actually are: **four thin SQL query layers over SQLite databases**.
 * Every one of them opens with `import SQLite3`. There is no bespoke binary format to
 * port — the format IS SQLite, and the "engine" is a C library Apple ships in the OS.
 *
 * That leaves exactly two ways to open a `poi.db` an iPhone downloaded:
 *
 *  1. Add `org.xerial:sqlite-jdbc`. That is a new dependency AND a native `.so`/`.dll`/
 *     `.dylib` per platform — the same class of dependency PARITY.md row 2.1 refused for
 *     libwebrtc, and it would be refused here for the same reason.
 *  2. Read the file format directly. It is a stable, documented, byte-exact format, and
 *     the read-only subset needed to scan a table is small. That is this file.
 *
 * Option 2 is also the only one that can be *checked*: a JDBC driver would be trusted, a
 * hand-written reader is testable against databases the reference `sqlite3` binary wrote.
 *
 * ## What is implemented
 *
 * Enough to enumerate `sqlite_master` and full-scan a rowid table:
 *
 *  - the 100-byte database header, including page size (with the 1 ⇒ 65536 encoding),
 *    reserved-per-page space, and the text encoding (UTF-8 / UTF-16LE / UTF-16BE);
 *  - table b-tree traversal — leaf (`0x0D`) and interior (`0x05`) pages, in rowid order;
 *  - cell payload spill onto overflow-page chains, with SQLite's exact local-payload
 *    arithmetic (getting this wrong reads plausible garbage rather than failing);
 *  - the record format: header varints, all serial types 0–9 and 12+, and the
 *    `INTEGER PRIMARY KEY` ⇒ rowid alias rule, without which every `pois.id` reads NULL.
 *
 * ## What is NOT implemented, and fails loudly rather than quietly
 *
 *  - **Index b-trees** (`0x02`/`0x0A`). Nothing here needs them: every query the Swift
 *    performs is answered by a full scan plus Kotlin-side filtering.
 *  - **`WITHOUT ROWID` tables**, which live in an index b-tree. Detected and refused.
 *  - **FTS5 MATCH.** The FTS5 index is a set of shadow tables with their own segment
 *    format; reimplementing it would be a second, much larger project with no way to
 *    verify it. [OfflinePoiIndex] scans and matches in Kotlin instead, and says so.
 *  - **Writing.** Nothing here ever opens the file for write.
 *  - **Encrypted / SEE databases.** No OSHI map file is encrypted.
 *
 * ## Staleness
 *
 * If a non-empty `-wal` file sits next to the database, the main file alone is an older
 * snapshot and reading it would silently return stale rows. [open] refuses in that case
 * rather than returning the wrong answer; pass `allowStaleWal = true` to override.
 * Shipped OSHI map files are downloaded and gunzipped, never written locally, so a WAL
 * beside one means something else has been writing it.
 */
class SqliteFile private constructor(
    private val raf: RandomAccessFile,
    val path: File,
    val pageSize: Int,
    val reservedPerPage: Int,
    val textEncoding: Charset,
    val pageCountFromHeader: Int,
) : AutoCloseable {

    /** Page bytes actually usable by the b-tree layer. */
    val usableSize: Int = pageSize - reservedPerPage

    private val fileLength: Long = raf.length()

    /** Every row of `sqlite_master`, in the order the schema b-tree yields them. */
    val schema: List<SchemaEntry> by lazy { readSchema() }

    /** The rowid tables in this database, by name. Virtual tables and views are excluded. */
    val tables: Map<String, SchemaEntry> by lazy {
        schema.filter { it.type == "table" && it.rootPage > 0 }.associateBy { it.name }
    }

    fun table(name: String): SchemaEntry? = tables[name]

    override fun close() = raf.close()

    // ------------------------------------------------------------------ scanning

    /**
     * Full table scan in rowid order.
     *
     * Lazy: pages are read as the sequence is consumed, so a 78 MB `fr-streets.db` does
     * not become a 78 MB heap allocation. The sequence borrows this [SqliteFile] and is
     * only valid until [close].
     */
    fun scan(table: SchemaEntry): Sequence<Row> {
        if (table.rootPage <= 0) {
            throw SqliteFormatException("'${table.name}' has no b-tree (rootpage=${table.rootPage}) — virtual table or view")
        }
        if (table.withoutRowid) {
            throw SqliteFormatException(
                "'${table.name}' is WITHOUT ROWID: its data lives in an index b-tree, which this reader does not implement"
            )
        }
        val layout = table.layout
        return sequence {
            val stack = ArrayDeque<Int>()
            val seen = HashSet<Int>()
            stack.addLast(table.rootPage)
            while (stack.isNotEmpty()) {
                val pageNo = stack.removeLast()
                if (!seen.add(pageNo)) {
                    throw SqliteFormatException("b-tree cycle: page $pageNo visited twice while scanning '${table.name}'")
                }
                val page = readPage(pageNo)
                val h = if (pageNo == 1) 100 else 0
                when (val kind = page[h].toInt() and 0xFF) {
                    LEAF_TABLE -> {
                        val cells = cellPointers(page, h, pageNo, interior = false)
                        for (off in cells) {
                            yield(readLeafCell(page, off, pageNo, layout))
                        }
                    }
                    INTERIOR_TABLE -> {
                        val cells = cellPointers(page, h, pageNo, interior = true)
                        val rightMost = readInt32(page, h + 8)
                        checkPageNo(rightMost, pageNo)
                        // LIFO: push right-most first so children pop in rowid order.
                        stack.addLast(rightMost)
                        for (i in cells.indices.reversed()) {
                            val child = readInt32(page, cells[i])
                            checkPageNo(child, pageNo)
                            stack.addLast(child)
                        }
                    }
                    INTERIOR_INDEX, LEAF_INDEX -> throw SqliteFormatException(
                        "page $pageNo of '${table.name}' is an INDEX b-tree page (type $kind); " +
                            "index b-trees are not implemented"
                    )
                    else -> throw SqliteFormatException("page $pageNo: unknown b-tree page type $kind")
                }
            }
        }
    }

    /** Convenience: scan by table name, or null when the table is absent. */
    fun scan(tableName: String): Sequence<Row>? = table(tableName)?.let { scan(it) }

    private fun checkPageNo(p: Int, from: Int) {
        if (p <= 0) throw SqliteFormatException("page $from points at invalid child page $p")
        val maxPage = ((fileLength + pageSize - 1) / pageSize).toInt()
        if (p > maxPage) {
            throw SqliteFormatException("page $from points at page $p, past the end of a ${fileLength}-byte file")
        }
    }

    private fun cellPointers(page: ByteArray, h: Int, pageNo: Int, interior: Boolean): IntArray {
        val n = readUInt16(page, h + 3)
        val arrayStart = h + if (interior) 12 else 8
        if (arrayStart + 2 * n > usableSize) {
            throw SqliteFormatException("page $pageNo: cell pointer array of $n cells does not fit in $usableSize usable bytes")
        }
        val out = IntArray(n)
        for (i in 0 until n) {
            val off = readUInt16(page, arrayStart + 2 * i)
            if (off < arrayStart || off >= usableSize) {
                throw SqliteFormatException("page $pageNo: cell $i points at offset $off, outside [$arrayStart, $usableSize)")
            }
            out[i] = off
        }
        return out
    }

    // ------------------------------------------------------- leaf cell → row

    private fun readLeafCell(page: ByteArray, off: Int, pageNo: Int, layout: TableLayout): Row {
        var p = off
        val payloadSize = readVarint(page, p); p += payloadSize.bytes
        val rowid = readVarint(page, p); p += rowid.bytes
        val total = payloadSize.value
        if (total < 0) throw SqliteFormatException("page $pageNo: negative payload size $total")

        // SQLite's local-payload arithmetic for a table LEAF page. Copying the constants
        // rather than approximating them is the whole point: an off-by-one here does not
        // throw, it decodes a different byte range into a plausible-looking record.
        val u = usableSize
        val x = u - 35
        val local: Int
        if (total <= x) {
            local = total.toInt()
        } else {
            val m = ((u - 12) * 32 / 255) - 23
            val k = m + ((total - m) % (u - 4)).toInt()
            local = if (k <= x) k else m
        }
        if (p + local > page.size) {
            throw SqliteFormatException("page $pageNo: cell payload runs past the page")
        }

        val payload: ByteArray
        if (local.toLong() == total) {
            payload = page.copyOfRange(p, p + local)
        } else {
            payload = ByteArray(total.toInt())
            System.arraycopy(page, p, payload, 0, local)
            var written = local
            var next = readInt32(page, p + local)
            val visited = HashSet<Int>()
            while (written < total) {
                if (next <= 0) throw SqliteFormatException("page $pageNo: overflow chain ended early ($written/$total bytes)")
                if (!visited.add(next)) throw SqliteFormatException("overflow chain cycle at page $next")
                checkPageNo(next, pageNo)
                val ov = readPage(next)
                val chunk = minOf(u - 4, (total - written).toInt())
                System.arraycopy(ov, 4, payload, written, chunk)
                written += chunk
                next = readInt32(ov, 0)
            }
        }
        return decodeRecord(payload, rowid.value, layout)
    }

    private fun decodeRecord(rec: ByteArray, rowid: Long, layout: TableLayout): Row {
        val headerSize = readVarint(rec, 0)
        var hp = headerSize.bytes
        val headerEnd = headerSize.value.toInt()
        if (headerEnd < hp || headerEnd > rec.size) {
            throw SqliteFormatException("record header size $headerEnd out of range for a ${rec.size}-byte record")
        }
        val types = ArrayList<Long>()
        while (hp < headerEnd) {
            val t = readVarint(rec, hp)
            hp += t.bytes
            types.add(t.value)
        }
        var bp = headerEnd
        val values = arrayOfNulls<Any>(types.size)
        for (i in types.indices) {
            val t = types[i]
            val size = serialTypeSize(t)
            if (bp + size > rec.size) {
                throw SqliteFormatException("record column $i (serial type $t, $size bytes) runs past the record")
            }
            values[i] = decodeValue(t, rec, bp, size)
            bp += size
        }
        return Row(rowid, values, layout)
    }

    private fun decodeValue(t: Long, b: ByteArray, off: Int, size: Int): Any? = when {
        t == 0L -> null
        t == 1L -> b[off].toLong()
        t in 2L..6L -> {
            var v = (b[off].toLong())          // sign-extends from the top byte
            for (i in 1 until size) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            v
        }
        t == 7L -> {
            var bits = 0L
            for (i in 0 until 8) bits = (bits shl 8) or (b[off + i].toLong() and 0xFF)
            java.lang.Double.longBitsToDouble(bits)
        }
        t == 8L -> 0L
        t == 9L -> 1L
        t == 10L || t == 11L -> throw SqliteFormatException("serial type $t is reserved for internal use")
        t % 2 == 0L -> b.copyOfRange(off, off + size)                      // BLOB
        else -> String(b, off, size, textEncoding)                          // TEXT
    }

    private fun serialTypeSize(t: Long): Int = when {
        t == 0L || t == 8L || t == 9L -> 0
        t == 1L -> 1
        t == 2L -> 2
        t == 3L -> 3
        t == 4L -> 4
        t == 5L -> 6
        t == 6L || t == 7L -> 8
        t == 10L || t == 11L -> throw SqliteFormatException("serial type $t is reserved for internal use")
        else -> ((t - 12) / 2).toInt()
    }

    // ------------------------------------------------------------------ schema

    private fun readSchema(): List<SchemaEntry> {
        // sqlite_master is the table b-tree rooted at page 1, with a fixed shape:
        //   type TEXT, name TEXT, tbl_name TEXT, rootpage INTEGER, sql TEXT
        val masterLayout = TableLayout(
            columns = listOf("type", "name", "tbl_name", "rootpage", "sql"),
            rowidAliasIndex = -1,
        )
        val master = SchemaEntry(
            type = "table", name = "sqlite_master", tblName = "sqlite_master",
            rootPage = 1, sql = null, layout = masterLayout, withoutRowid = false,
        )
        val out = ArrayList<SchemaEntry>()
        for (row in scan(master)) {
            val type = row.text("type") ?: continue
            val name = row.text("name") ?: continue
            val sql = row.text("sql")
            val root = (row.value("rootpage") as? Long)?.toInt() ?: 0
            out.add(
                SchemaEntry(
                    type = type,
                    name = name,
                    tblName = row.text("tbl_name") ?: name,
                    rootPage = root,
                    sql = sql,
                    layout = if (type == "table") SqliteSchemaParser.layoutOf(sql) else TableLayout(emptyList(), -1),
                    withoutRowid = type == "table" && SqliteSchemaParser.isWithoutRowid(sql),
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ raw I/O

    private fun readPage(pageNo: Int): ByteArray {
        val offset = (pageNo - 1).toLong() * pageSize
        if (offset + pageSize > fileLength) {
            throw SqliteFormatException("page $pageNo is past the end of ${path.name} ($fileLength bytes, page size $pageSize)")
        }
        val buf = ByteArray(pageSize)
        synchronized(raf) {
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    companion object {
        /** The 16-byte header string, NUL included. */
        val MAGIC: ByteArray = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0

        private const val INTERIOR_INDEX = 0x02
        private const val INTERIOR_TABLE = 0x05
        private const val LEAF_INDEX = 0x0A
        private const val LEAF_TABLE = 0x0D

        fun open(file: File, allowStaleWal: Boolean = false): SqliteFile {
            if (!file.isFile) throw SqliteFormatException("not a file: ${file.absolutePath}")
            if (file.length() < 100) throw SqliteFormatException("${file.name} is ${file.length()} bytes — too short to be a database")

            if (!allowStaleWal) {
                val wal = File(file.parentFile, file.name + "-wal")
                if (wal.isFile && wal.length() > 0) {
                    throw SqliteFormatException(
                        "${wal.name} exists and is ${wal.length()} bytes: the main file is an OLDER snapshot and " +
                            "reading it would return stale rows. Checkpoint the WAL, or pass allowStaleWal = true."
                    )
                }
            }

            val raf = RandomAccessFile(file, "r")
            try {
                val head = ByteArray(100)
                raf.readFully(head)
                for (i in MAGIC.indices) {
                    if (head[i] != MAGIC[i]) {
                        throw SqliteFormatException(
                            "${file.name} does not start with the SQLite 3 magic — first 16 bytes are " +
                                head.copyOfRange(0, 16).joinToString(" ") { "%02x".format(it) }
                        )
                    }
                }
                val rawPageSize = readUInt16(head, 16)
                val pageSize = if (rawPageSize == 1) 65536 else rawPageSize
                if (pageSize < 512 || (pageSize and (pageSize - 1)) != 0) {
                    throw SqliteFormatException("page size $pageSize is not a power of two ≥ 512")
                }
                val readVersion = head[19].toInt() and 0xFF
                if (readVersion > 2) {
                    throw SqliteFormatException("read version $readVersion is newer than this reader understands (1 = rollback, 2 = WAL)")
                }
                val reserved = head[20].toInt() and 0xFF
                val usable = pageSize - reserved
                if (usable < 480) {
                    throw SqliteFormatException("usable page size $usable (page $pageSize − $reserved reserved) is below SQLite's 480-byte floor")
                }
                if ((head[21].toInt() and 0xFF) != 64 || (head[22].toInt() and 0xFF) != 32 || (head[23].toInt() and 0xFF) != 32) {
                    throw SqliteFormatException("payload-fraction bytes are not the required 64/32/32 — this is not a standard SQLite file")
                }
                val encoding = when (val e = readInt32(head, 56)) {
                    0, 1 -> Charsets.UTF_8      // 0 only occurs on a never-written database
                    2 -> Charsets.UTF_16LE
                    3 -> Charsets.UTF_16BE
                    else -> throw SqliteFormatException("unknown text encoding $e in the header")
                }
                val pages = readInt32(head, 28)
                return SqliteFile(raf, file, pageSize, reserved, encoding, pages)
            } catch (e: Throwable) {
                raf.close()
                throw e
            }
        }

        internal fun readUInt16(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        internal fun readInt32(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

        /**
         * SQLite's big-endian variable-length integer: up to nine bytes, the first eight
         * contributing seven bits each with the high bit as a continuation flag, and the
         * ninth contributing all eight of its bits.
         */
        internal fun readVarint(b: ByteArray, off: Int): Varint {
            var value = 0L
            var i = 0
            while (i < 8) {
                if (off + i >= b.size) throw SqliteFormatException("varint at $off runs past the end of the buffer")
                val byte = b[off + i].toInt() and 0xFF
                value = (value shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) return Varint(value, i + 1)
                i++
            }
            if (off + 8 >= b.size) throw SqliteFormatException("9-byte varint at $off runs past the end of the buffer")
            value = (value shl 8) or (b[off + 8].toLong() and 0xFF)
            return Varint(value, 9)
        }
    }

    // ------------------------------------------------------------------ types

    internal data class Varint(val value: Long, val bytes: Int)

    /** One row of `sqlite_master`. */
    data class SchemaEntry(
        val type: String,
        val name: String,
        val tblName: String,
        val rootPage: Int,
        val sql: String?,
        val layout: TableLayout,
        val withoutRowid: Boolean,
    )

    /** Column names, and which of them (if any) is the `INTEGER PRIMARY KEY` rowid alias. */
    data class TableLayout(val columns: List<String>, val rowidAliasIndex: Int)

    /**
     * One decoded row. Values are `Long`, `Double`, `String`, `ByteArray` or null, exactly
     * as the record's serial types say — no type affinity is applied, because affinity is a
     * property of the SQL layer and not of the file.
     */
    class Row internal constructor(
        val rowid: Long,
        private val values: Array<Any?>,
        private val layout: TableLayout,
    ) {
        val columnCount: Int get() = values.size

        fun value(index: Int): Any? {
            val v = values.getOrNull(index)
            // A column declared INTEGER PRIMARY KEY stores NULL and IS the rowid. Without
            // this rule every `pois.id` and `streets.id` reads back as null, and the
            // Swift's `p.id = f.rowid` join has nothing to join on.
            if (v == null && index == layout.rowidAliasIndex) return rowid
            return v
        }

        fun value(name: String): Any? {
            val i = layout.columns.indexOfFirst { it.equals(name, ignoreCase = true) }
            return if (i < 0) null else value(i)
        }

        fun text(name: String): String? = when (val v = value(name)) {
            null -> null
            is String -> v
            is Long -> v.toString()
            is Double -> v.toString()
            is ByteArray -> null
            else -> null
        }

        fun long(name: String): Long? = when (val v = value(name)) {
            is Long -> v
            is Double -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

        fun double(name: String): Double? = when (val v = value(name)) {
            is Double -> v
            is Long -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

        fun blob(name: String): ByteArray? = value(name) as? ByteArray

        override fun toString(): String =
            "Row(rowid=$rowid, " + layout.columns.indices.joinToString(", ") { "${layout.columns[it]}=${value(it)}" } + ")"
    }
}

class SqliteFormatException(message: String) : java.io.IOException(message)

/**
 * The smallest `CREATE TABLE` parser that answers the two questions the reader has to ask:
 * what are the column names, and which column (if any) is the `INTEGER PRIMARY KEY` alias
 * for the rowid.
 *
 * It is deliberately not a SQL parser. It splits the top-level column-definition list on
 * commas that are not inside parentheses, quotes or brackets, takes the first identifier of
 * each definition as the name, and recognises the alias in the two forms SQLite accepts:
 * the column constraint (`id INTEGER PRIMARY KEY`) and the single-column table constraint
 * (`..., PRIMARY KEY(id)`) over a column whose declared type is exactly INTEGER.
 *
 * `INTEGER PRIMARY KEY DESC` is NOT an alias in SQLite, and is not treated as one here.
 */
internal object SqliteSchemaParser {

    fun isWithoutRowid(sql: String?): Boolean {
        if (sql == null) return false
        val tail = sql.substringAfterLast(')', "")
        return tail.replace(Regex("\\s+"), " ").trim().uppercase().startsWith("WITHOUT ROWID")
    }

    fun layoutOf(sql: String?): SqliteFile.TableLayout {
        if (sql == null) return SqliteFile.TableLayout(emptyList(), -1)
        val open = sql.indexOf('(')
        val close = sql.lastIndexOf(')')
        if (open < 0 || close <= open) return SqliteFile.TableLayout(emptyList(), -1)
        val body = sql.substring(open + 1, close)

        val defs = splitTopLevel(body)
        val names = ArrayList<String>()
        val decls = ArrayList<String>()
        var alias = -1
        for (def in defs) {
            val trimmed = def.trim()
            if (trimmed.isEmpty()) continue
            val first = firstIdentifier(trimmed) ?: continue
            if (isTableConstraintKeyword(first)) {
                decls.add(trimmed)   // keep for the PRIMARY KEY(col) pass below
                continue
            }
            val index = names.size
            names.add(first)
            decls.add("")
            val rest = trimmed.substring(identifierLength(trimmed)).trim()
            if (isIntegerPrimaryKey(rest)) alias = index
        }

        if (alias < 0) {
            // Table-constraint form: PRIMARY KEY(col) where col's declared type is INTEGER.
            for (def in defs) {
                val t = def.trim()
                val up = t.uppercase()
                if (!up.startsWith("PRIMARY KEY") && !up.startsWith("PRIMARY\tKEY")) continue
                val inner = t.substringAfter('(', "").substringBeforeLast(')', "")
                val cols = splitTopLevel(inner).map { it.trim() }.filter { it.isNotEmpty() }
                if (cols.size != 1) continue
                if (cols[0].uppercase().endsWith(" DESC")) continue
                val colName = firstIdentifier(cols[0]) ?: continue
                val idx = names.indexOfFirst { it.equals(colName, ignoreCase = true) }
                if (idx < 0) continue
                val colDef = defs.map { it.trim() }.firstOrNull {
                    firstIdentifier(it)?.equals(colName, ignoreCase = true) == true
                } ?: continue
                val declared = colDef.substring(identifierLength(colDef)).trim()
                if (declared.uppercase().startsWith("INTEGER")) alias = idx
            }
        }
        return SqliteFile.TableLayout(names, alias)
    }

    private fun isIntegerPrimaryKey(rest: String): Boolean {
        val up = rest.uppercase().replace(Regex("\\s+"), " ")
        if (!up.startsWith("INTEGER")) return false
        val after = up.removePrefix("INTEGER").trim()
        // A parenthesised size ("INTEGER(11)") is a different declared type in SQLite's
        // rules for the alias, so it does not qualify.
        if (after.startsWith("(")) return false
        val pk = after.indexOf("PRIMARY KEY")
        if (pk < 0) return false
        // Only constraints may sit between the type and PRIMARY KEY, never another word
        // that would make this a different declared type.
        val between = after.substring(0, pk).trim()
        if (between.isNotEmpty() && between != "NOT NULL") return false
        val afterPk = after.substring(pk + "PRIMARY KEY".length).trim()
        if (afterPk.startsWith("DESC")) return false
        return true
    }

    private fun isTableConstraintKeyword(word: String): Boolean =
        word.uppercase() in setOf("PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT")

    private fun firstIdentifier(def: String): String? {
        val len = identifierLength(def)
        if (len == 0) return null
        val raw = def.substring(0, len)
        return when {
            raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                raw.substring(1, raw.length - 1).replace("\"\"", "\"")
            raw.startsWith("`") && raw.endsWith("`") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
            raw.startsWith("[") && raw.endsWith("]") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
            else -> raw
        }
    }

    private fun identifierLength(def: String): Int {
        if (def.isEmpty()) return 0
        return when (def[0]) {
            '"' -> quotedLength(def, '"')
            '`' -> quotedLength(def, '`')
            '[' -> def.indexOf(']').let { if (it < 0) def.length else it + 1 }
            else -> {
                var i = 0
                while (i < def.length && (def[i].isLetterOrDigit() || def[i] == '_' || def[i] == '$')) i++
                i
            }
        }
    }

    private fun quotedLength(def: String, q: Char): Int {
        var i = 1
        while (i < def.length) {
            if (def[i] == q) {
                if (i + 1 < def.length && def[i + 1] == q) { i += 2; continue }
                return i + 1
            }
            i++
        }
        return def.length
    }

    /** Split on commas that are not nested inside (), "", '' , `` or []. */
    fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c == '\'' || c == '"' || c == '`' -> {
                    val end = quotedLength(s.substring(i), c)
                    sb.append(s, i, minOf(s.length, i + end))
                    i += end
                    continue
                }
                c == '[' -> {
                    val end = s.indexOf(']', i).let { if (it < 0) s.length - 1 else it }
                    sb.append(s, i, end + 1)
                    i = end + 1
                    continue
                }
                c == ',' && depth == 0 -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
