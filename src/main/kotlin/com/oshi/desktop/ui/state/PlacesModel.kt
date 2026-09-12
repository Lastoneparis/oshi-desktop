package com.oshi.desktop.ui.state

import com.oshi.desktop.place.OfflineMapData
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The Places destination — the phone's Map tab, minus everything a desktop cannot honestly do.
 *
 * ============================================================ WHY IT IS NOT CALLED "MAP"
 *
 * PARITY.md row 0.19 is blunt: this client has no GPS and none is faked, so live location and
 * check-ins are RECEIVE-ONLY by construction, and there is no navigation on this platform at
 * all. A destination labelled Map would promise three things (where I am, how to get there,
 * where you are) of which this window can do none.
 *
 * What it CAN do is real and shipped: the offline POI and street reader, which opens the same
 * SQLite region files the phones download and answers questions about them with no network
 * whatsoever. That is a genuine offline capability, it is the honest half of that tab, and it
 * is the whole of this screen.
 *
 * ============================================================ AN EMPTY REGION LIST IS THE NORM
 *
 * Nothing downloads regions on this client — there is no region manager here and this model
 * does not invent one. So the overwhelmingly common state is "no regions on this machine",
 * and that has to read as a fact about the DATA rather than as a broken search box. The empty
 * state names the directory it looked in, because the one thing a user can do about it is put
 * a region there.
 *
 * ============================================================ SEARCH IS OFF THE UI THREAD
 *
 * Each query opens region databases and scans them. That is disk work, it grows with the
 * number of regions, and running it inline would freeze the window on exactly the machines
 * with the most data on them.
 */
class PlacesModel(
    private val mapsDir: File,
    private val worker: Executor = defaultWorker(),
    private val dataFactory: (File) -> OfflineMapData = ::OfflineMapData,
) {

    enum class Kind { POI, STREET }

    data class Hit(val title: String, val detail: String, val region: String, val coords: String)

    data class PlacesState(
        val mapsPath: String,
        val poiRegions: List<String>,
        val streetRegions: List<String>,
        val kind: Kind,
        val query: String,
        val hits: List<Hit>,
        val searched: Boolean,
        val busy: Boolean,
        val notice: Notice?,
    ) {
        val hasData: Boolean get() = poiRegions.isNotEmpty() || streetRegions.isNotEmpty()
        val regionsFor: List<String> get() = if (kind == Kind.POI) poiRegions else streetRegions
    }

    private val lock = Any()
    private var kind = Kind.POI
    private var query = ""
    private var hits: List<Hit> = emptyList()
    private var searched = false
    private var busy = false
    private var notice: Notice? = null

    var onChange: (PlacesState) -> Unit = {}

    @Volatile
    var state: PlacesState = snapshot()
        private set

    fun setKind(k: Kind) {
        synchronized(lock) {
            if (kind == k) return
            kind = k
            hits = emptyList()
            searched = false
            notice = null
        }
        publish()
    }

    fun setQuery(q: String) {
        synchronized(lock) { query = q }
        publish()
    }

    fun search() {
        val (q, k) = synchronized(lock) {
            when {
                busy -> return
                query.isBlank() -> { notice = Notice("Type something to look for.", Severity.INFO); return publish() }
                else -> { busy = true; notice = null; query.trim() to kind }
            }
        }
        publish()

        worker.execute {
            val found = try {
                val data = dataFactory(mapsDir)
                when (k) {
                    Kind.POI -> data.searchPois(q, limit = 40).map { h ->
                        Hit(
                            title = h.poi.name,
                            detail = listOfNotNull(
                                h.poi.subcategory ?: h.poi.category,
                                h.poi.address,
                                h.poi.openingHours,
                            ).joinToString(" · "),
                            region = h.regionId,
                            coords = coords(h.poi.lat, h.poi.lon),
                        )
                    }
                    Kind.STREET -> data.searchStreets(q, limit = 40).map { s ->
                        Hit(
                            title = s.name,
                            detail = listOfNotNull(s.city, s.countryCode, s.highwayType).joinToString(" · "),
                            region = s.regionId,
                            coords = coords(s.lat, s.lon),
                        )
                    }
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    busy = false
                    searched = true
                    hits = emptyList()
                    notice = Notice("That region file could not be read: ${e.javaClass.simpleName}: ${e.message}", Severity.ERROR)
                }
                publish()
                return@execute
            }
            synchronized(lock) {
                busy = false
                searched = true
                hits = found
            }
            publish()
        }
    }

    /**
     * Six decimals — about 11 cm, and the precision the region files carry.
     *
     * Rounding further would make two neighbouring shopfronts share a coordinate; keeping
     * every digit a `Double` prints would claim a precision the source data does not have.
     */
    private fun coords(lat: Double, lon: Double): String = "%.6f, %.6f".format(lat, lon)

    private fun snapshot(): PlacesState = synchronized(lock) {
        val data = runCatching { dataFactory(mapsDir) }.getOrNull()
        PlacesState(
            mapsPath = mapsDir.absolutePath,
            poiRegions = data?.let { runCatching { it.poiRegions() }.getOrDefault(emptyList()) } ?: emptyList(),
            streetRegions = data?.let { runCatching { it.streetRegions() }.getOrDefault(emptyList()) } ?: emptyList(),
            kind = kind,
            query = query,
            hits = hits,
            searched = searched,
            busy = busy,
            notice = notice,
        )
    }

    private fun publish() {
        val next = snapshot()
        state = next
        onChange(next)
    }

    companion object {
        /** Where region files are looked for, beside everything else this account owns. */
        fun defaultDir(home: File): File = File(home, "maps")

        private fun defaultWorker(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "oshi-ui-places").apply { isDaemon = true }
        }
    }
}
