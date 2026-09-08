package com.donovan.carlauncher.traffic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The catalogue of every Caltrans camera, fetched per district and cached on disk.
 *
 * Caltrans publishes one JSON per district and they are big - District 4 alone is
 * 2.4 MB, and all twelve together are about 11 MB. The car is usually on a phone
 * hotspot, so downloading the whole state to find the dozen cameras on today's route
 * would be indefensible. Instead each district's geographic extent is known up front,
 * and only the districts that could hold a camera within range are fetched.
 *
 * Camera positions effectively never change, so a district stays cached for a week.
 */
class CaltransCatalog(context: Context) {

    private val dir = File(context.filesDir, "caltrans").apply { mkdirs() }

    /**
     * Bounding boxes measured from the feeds themselves rather than from the published
     * district map, because what matters is where the cameras actually are.
     */
    private data class District(
        val n: Int,
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
    )

    private val districts = listOf(
        District(1, 38.7523, 41.9940, -124.1993, -122.6042),
        District(2, 39.7748, 41.9966, -122.9912, -120.0385),
        District(3, 38.0780, 39.7895, -122.1593, -119.9572),
        District(4, 36.9192, 38.5264, -122.7900, -121.5128),
        District(5, 34.3851, 37.1304, -122.0232, -119.4868),
        District(6, 34.8177, 37.3310, -120.7238, -118.8074),
        District(7, 33.7470, 34.7948, -119.2943, -117.6947),
        District(8, 33.4385, 35.6020, -117.7438, -114.5503),
        District(9, 34.9875, 38.3506, -119.4504, -117.8692),
        District(10, 36.9772, 38.4077, -121.6755, -119.9504),
        District(11, 32.5514, 33.3423, -117.3883, -115.4986),
        District(12, 33.3965, 33.9422, -118.0918, -117.5934),
    )

    /**
     * Which districts could contain a camera within [radiusMeters] of the point.
     *
     * The box is padded by the search radius before testing, so a camera just over a
     * district line is still found. Districts overlap, so this often returns two or
     * three - near Los Angeles, 7, 8 and 12 all qualify.
     */
    fun districtsNear(lat: Double, lon: Double, radiusMeters: Double): List<Int> {
        val padLat = radiusMeters / 111_320.0
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val padLon = radiusMeters / (111_320.0 * cosLat)
        return districts.filter {
            lat >= it.latMin - padLat && lat <= it.latMax + padLat &&
                lon >= it.lonMin - padLon && lon <= it.lonMax + padLon
        }.map { it.n }
    }

    /**
     * Cameras for one district, from disk when the cache is fresh and from Caltrans
     * otherwise. A network failure falls back to a stale cache if there is one, since
     * week-old camera positions are still perfectly good.
     */
    fun district(n: Int, maxAgeMs: Long = CACHE_TTL_MS): List<TrafficCamera> {
        val cache = File(dir, "d$n.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < maxAgeMs
        if (fresh) {
            runCatching { return readCache(cache) }
        }
        return runCatching {
            val cams = parseFeed(n, fetch(feedUrl(n)))
            runCatching { writeCache(cache, cams) }
            cams
        }.getOrElse {
            if (cache.exists()) runCatching { readCache(cache) }.getOrDefault(emptyList())
            else throw it
        }
    }

    private fun feedUrl(n: Int) =
        "https://cwwp2.dot.ca.gov/data/d$n/cctv/cctvStatusD%02d.json".format(n)

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CarLauncher/1.0")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code from Caltrans")
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                java.util.zip.GZIPInputStream(raw)
            } else raw
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFeed(district: Int, body: String): List<TrafficCamera> {
        // The feed is served with a BOM often enough to be worth stripping.
        val data = JSONObject(body.removePrefix("﻿")).getJSONArray("data")
        val out = ArrayList<TrafficCamera>(data.length())
        for (i in 0 until data.length()) {
            val c = data.optJSONObject(i)?.optJSONObject("cctv") ?: continue
            if (!c.optString("inService").equals("true", ignoreCase = true)) continue

            val loc = c.optJSONObject("location") ?: continue
            val lat = loc.optString("latitude").toDoubleOrNull() ?: continue
            val lon = loc.optString("longitude").toDoubleOrNull() ?: continue
            if (lat == 0.0 || lon == 0.0) continue

            val img = c.optJSONObject("imageData")
            val still = (img?.optJSONObject("static")?.optString("currentImageURL") ?: "").trim()
            if (!still.startsWith("http")) continue // no snapshot means nothing to list

            // One entry in the state feed carries a truncated video URL. Requiring a
            // real playlist extension drops it rather than handing ExoPlayer garbage.
            val streamRaw = (img?.optString("streamingVideoURL") ?: "").trim()
            val stream = streamRaw.takeIf {
                it.startsWith("http") && it.substringBefore('?').endsWith(".m3u8")
            }

            val name = loc.optString("locationName").trim()
            out += TrafficCamera(
                id = "d$district-${c.optString("index").ifBlank { i.toString() }}",
                name = name.ifBlank { "Camera ${i + 1}" },
                place = loc.optString("nearbyPlace").trim(),
                county = loc.optString("county").trim(),
                route = loc.optString("route").trim(),
                lat = lat,
                lon = lon,
                stillUrl = still,
                streamUrl = stream,
                district = district,
            )
        }
        return out
    }

    // Cached with short keys - this runs to a few hundred kilobytes per district and
    // there is no reason to spend the space twice.
    private fun writeCache(file: File, cams: List<TrafficCamera>) {
        val arr = JSONArray()
        for (c in cams) {
            arr.put(
                JSONObject().apply {
                    put("i", c.id); put("n", c.name); put("p", c.place)
                    put("c", c.county); put("r", c.route)
                    put("y", c.lat); put("x", c.lon)
                    put("s", c.stillUrl); put("v", c.streamUrl ?: "")
                    put("d", c.district)
                }
            )
        }
        file.writeText(arr.toString())
    }

    private fun readCache(file: File): List<TrafficCamera> {
        val arr = JSONArray(file.readText())
        val out = ArrayList<TrafficCamera>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += TrafficCamera(
                id = o.optString("i"),
                name = o.optString("n"),
                place = o.optString("p"),
                county = o.optString("c"),
                route = o.optString("r"),
                lat = o.optDouble("y"),
                lon = o.optDouble("x"),
                stillUrl = o.optString("s"),
                streamUrl = o.optString("v").takeIf { it.isNotBlank() },
                district = o.optInt("d"),
            )
        }
        return out
    }

    companion object {
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
