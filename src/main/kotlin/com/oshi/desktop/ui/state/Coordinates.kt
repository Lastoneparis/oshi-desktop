package com.oshi.desktop.ui.state

/**
 * __DESKTOP_1_6_PARITY__ A point typed or pasted into "Ping a place".
 *
 * A computer has no GPS, but its owner very often has the place open somewhere else — a map
 * in the browser, a message, an address book. So the picker takes, besides dragging the map:
 *
 *  * `48.8566, 2.3522` / `48.8566 2.3522` / `48.8566;2.3522`
 *  * `geo:48.8566,2.3522` (RFC 5870, what the phones' "share" produces)
 *  * an OpenStreetMap link (`?mlat=…&mlon=…` or `#map=16/48.8566/2.3522`)
 *  * a Google / Apple Maps link (`@48.8566,2.3522,15z`, `?q=48.8566,2.3522`, `ll=…`)
 *
 * Nothing is looked up: no address is geocoded and no URL is fetched. Only numbers that are
 * a point on Earth are accepted.
 */
object Coordinates {
    private const val NUM = """[-+]?\d{1,3}(?:\.\d+)?"""
    private val PATTERNS = listOf(
        Regex("""mlat=($NUM).*?mlon=($NUM)"""),
        Regex("""#map=\d{1,2}/($NUM)/($NUM)"""),
        Regex("""@($NUM),($NUM)"""),
        Regex("""[?&](?:q|ll|query|daddr|sll)=($NUM)(?:,|%2C)\s*($NUM)""", RegexOption.IGNORE_CASE),
        Regex("""^geo:($NUM),($NUM)""", RegexOption.IGNORE_CASE),
        Regex("""^\(?\s*($NUM)\s*[,; ]\s*($NUM)\s*\)?$"""),
    )

    fun parse(raw: String): Pair<Double, Double>? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 2_000) return null
        for (p in PATTERNS) {
            val m = p.find(s) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (com.oshi.desktop.place.LocationPayload.isPlausibleCoordinate(lat, lon)) return lat to lon
        }
        return null
    }

    /** `48.85660, 2.35220` — five decimals is about a metre, which is what a ping means. */
    fun format(lat: Double, lon: Double): String = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)

    /** The link "Open in Maps" opens: OpenStreetMap, the data the OSHI tiles are drawn from. */
    fun osmUrl(lat: Double, lon: Double): String =
        "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f".format(java.util.Locale.ROOT, lat, lon, lat, lon)
}
