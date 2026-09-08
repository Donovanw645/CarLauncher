package com.donovan.carlauncher.nav

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Place(
    val name: String,
    val address: String,
    val point: LatLon,
    /** Straight-line distance from the car when the search knew where that was. */
    val meters: Double? = null,
    /** OSM value for the feature - "cafe", "fuel", "aerodrome"... Blank if unknown. */
    val category: String = "",
) {
    val miles: Double? get() = meters?.div(1609.344)
}

/**
 * Place search backed by Photon, with Nominatim kept only as a fallback.
 *
 * Nominatim ranks by "importance", which is derived from things like Wikipedia
 * prominence, and its viewbox is a soft hint it will happily ignore. Searching
 * "mcdonalds" from Sacramento returned an employee car park 70 miles away followed by
 * Brazil, England and Australia. Photon takes lat/lon as a real bias and is built for
 * search-as-you-type, so the same query returns five McDonald's within five miles -
 * while still putting the city first for "san francisco" rather than blindly choosing
 * whatever is nearest.
 */
class GeocodeApi(private val baseUrl: () -> String) {

    suspend fun search(query: String, near: LatLon?, limit: Int = 8): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val photon = runCatching { searchPhoton(q, near, limit) }.getOrNull()
        if (!photon.isNullOrEmpty()) return photon
        // Photon is a single free instance; if it is down, a worse answer beats none.
        return runCatching { searchNominatim(q, near, limit) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------- photon

    private suspend fun searchPhoton(q: String, near: LatLon?, limit: Int): List<Place> {
        val url = buildString {
            append("https://photon.komoot.io/api?limit=").append(limit + 4)
            append("&q=").append(URLEncoder.encode(q, "UTF-8"))
            if (near != null) {
                append("&lat=").append(near.lat).append("&lon=").append(near.lon)
            }
        }
        val features = JSONObject(Http.getString(url)).optJSONArray("features")
            ?: return emptyList()

        val out = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val p = f.optJSONObject("properties") ?: continue
            val category = p.optString("osm_value")
            if (category in JUNK) continue

            val point = LatLon(lat, lon)
            val name = p.optString("name").ifBlank {
                listOf(p.optString("street"), p.optString("city"))
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
            if (name.isBlank()) continue
            out += Place(
                name = name,
                address = addressOf(p),
                point = point,
                meters = near?.let { haversineMeters(it, point) },
                category = category,
            )
        }
        // Two branches of the same chain on one corner add nothing to a driver.
        return out.distinctBy { "${it.name}|${it.address}" }.take(limit)
    }

    /** "1901 J Street, Sacramento, CA" from Photon's separate address fields. */
    private fun addressOf(p: JSONObject): String {
        val house = p.optString("housenumber")
        val street = p.optString("street")
        val line = listOf(house, street).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(line, p.optString("city"), p.optString("state"))
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { p.optString("country") }
    }

    // ---------------------------------------------------------------- nominatim

    private suspend fun searchNominatim(q: String, near: LatLon?, limit: Int): List<Place> {
        val sb = StringBuilder(baseUrl().trimEnd('/'))
        sb.append("/search?format=jsonv2&addressdetails=1&limit=").append(limit)
        sb.append("&q=").append(URLEncoder.encode(q, "UTF-8"))
        if (near != null) {
            val d = 0.75
            sb.append("&viewbox=")
                .append(near.lon - d).append(',').append(near.lat + d).append(',')
                .append(near.lon + d).append(',').append(near.lat - d)
            sb.append("&bounded=0")
        }

        val arr = JSONArray(Http.getString(sb.toString()))
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",").trim() }
            val point = LatLon(lat, lon)
            out += Place(
                name = name.ifBlank { "Dropped pin" },
                address = display,
                point = point,
                meters = near?.let { haversineMeters(it, point) },
            )
        }
        return out
    }

    // ------------------------------------------------------------------ reverse

    suspend fun reverse(point: LatLon): Place? =
        reversePhoton(point) ?: reverseNominatim(point)

    private suspend fun reversePhoton(point: LatLon): Place? {
        val url = "https://photon.komoot.io/reverse?limit=1" +
            "&lat=${point.lat}&lon=${point.lon}"
        val body = runCatching { Http.getString(url) }.getOrNull() ?: return null
        val p = runCatching {
            JSONObject(body).getJSONArray("features").getJSONObject(0)
                .getJSONObject("properties")
        }.getOrNull() ?: return null

        val address = addressOf(p)
        val name = p.optString("name").ifBlank { address.substringBefore(",").trim() }
        if (name.isBlank() && address.isBlank()) return null
        return Place(
            name = name.ifBlank { "Dropped pin" },
            address = address,
            point = point,
            category = p.optString("osm_value"),
        )
    }

    private suspend fun reverseNominatim(point: LatLon): Place? {
        val url = buildString {
            append(baseUrl().trimEnd('/'))
            append("/reverse?format=jsonv2&zoom=18&addressdetails=1")
            append("&lat=").append(point.lat)
            append("&lon=").append(point.lon)
        }
        val body = runCatching { Http.getString(url) }.getOrNull() ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val display = o.optString("display_name").ifBlank { return null }
        val name = o.optString("name").ifBlank { display.substringBefore(",").trim() }
        return Place(name.ifBlank { "Dropped pin" }, display, point)
    }

    private companion object {
        /**
         * Features that match a name but are useless as a destination. Searching a fuel
         * brand otherwise returns the forecourt roof and the car wash alongside the
         * station itself.
         */
        val JUNK = setOf(
            "roof", "wall", "fence", "hedge", "tree", "bench", "waste_basket",
            "street_lamp", "surveillance", "bicycle_parking", "vending_machine",
        )
    }
}
