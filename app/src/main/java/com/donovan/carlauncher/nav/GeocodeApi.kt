package com.donovan.carlauncher.nav

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class Place(
    val name: String,
    val address: String,
    val point: LatLon,
)

/**
 * Place search backed by Nominatim (OpenStreetMap). No API key, but the usage policy
 * caps this at one request per second - the search box debounces to respect that.
 */
class GeocodeApi(private val baseUrl: () -> String) {

    suspend fun search(query: String, near: LatLon?, limit: Int = 8): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val sb = StringBuilder(baseUrl().trimEnd('/'))
        sb.append("/search?format=jsonv2&addressdetails=1&limit=").append(limit)
        sb.append("&q=").append(URLEncoder.encode(q, "UTF-8"))
        if (near != null) {
            // Bias, not restrict: bounded=0 still allows far-away matches to appear.
            val d = 0.75
            sb.append("&viewbox=")
                .append(near.lon - d).append(',').append(near.lat + d).append(',')
                .append(near.lon + d).append(',').append(near.lat - d)
            sb.append("&bounded=0")
        }

        val body = Http.getString(sb.toString())
        val arr = JSONArray(body)
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",").trim() }
            out.add(
                Place(
                    name = name.ifBlank { "Dropped pin" },
                    address = display,
                    point = LatLon(lat, lon),
                )
            )
        }
        return out
    }

    suspend fun reverse(point: LatLon): Place? {
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
}
