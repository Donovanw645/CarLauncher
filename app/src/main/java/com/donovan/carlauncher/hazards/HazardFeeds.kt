package com.donovan.carlauncher.hazards

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Parsers for the public road-hazard feeds.
 *
 * Three sources, because no single one covers everything a driver wants to know:
 *
 *  - Caltrans lane closures (LCS), per district. Full closures are how slides,
 *    washouts and emergency work actually reach the public, so these carry the
 *    "road is gone" cases as well as ordinary roadwork.
 *  - Caltrans chain control (CC), per district. Snow and ice on the passes.
 *  - CHP computer-aided dispatch. Live collisions and roadway hazards, and by far the
 *    freshest of the three - but CHP publishes only its Sacramento division feed
 *    publicly, so it is a bonus where it applies and absent elsewhere.
 *
 * Every parser is defensive: these are third-party feeds that change without notice,
 * and a launcher that is also the home screen must not crash because a field moved.
 */
object HazardFeeds {

    const val CHP_URL = "https://media.chp.ca.gov/sa_xml/sa.xml"

    fun lcsUrl(district: Int) =
        "https://cwwp2.dot.ca.gov/data/d$district/lcs/lcsStatusD%02d.json".format(district)

    fun ccUrl(district: Int) =
        "https://cwwp2.dot.ca.gov/data/d$district/cc/ccStatusD%02d.json".format(district)

    // ------------------------------------------------------------------ networking

    fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CarLauncher/1.0")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(raw)
            } else raw
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------- lane closures

    /**
     * A closure is only worth reporting while it is actually in force, so anything
     * whose window has passed is dropped here rather than shown as a live hazard.
     */
    fun parseLaneClosures(body: String, nowEpoch: Long): List<RoadHazard> {
        val data = JSONObject(body.removePrefix("﻿")).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<RoadHazard>()
        for (i in 0 until data.length()) {
            val lcs = data.optJSONObject(i)?.optJSONObject("lcs") ?: continue
            val begin = lcs.optJSONObject("location")?.optJSONObject("begin") ?: continue
            val lat = begin.optString("beginLatitude").toDoubleOrNull() ?: continue
            val lon = begin.optString("beginLongitude").toDoubleOrNull() ?: continue
            if (lat == 0.0 || lon == 0.0) continue

            val closure = lcs.optJSONObject("closure") ?: continue
            val stamp = closure.optJSONObject("closureTimestamp")
            val start = stamp?.optString("closureStartEpoch")?.toLongOrNull()
            val end = stamp?.optString("closureEndEpoch")?.toLongOrNull()
            // Drop anything already finished, or not started yet.
            if (end != null && end in 1 until nowEpoch) continue
            if (start != null && start > nowEpoch + LOOKAHEAD_S) continue

            val type = closure.optString("typeOfClosure").trim()
            val work = closure.optString("typeOfWork").trim()
            val route = begin.optString("beginRoute").trim()
            val where = begin.optString("beginLocationName").trim()
            val place = begin.optString("beginNearbyPlace").trim()
            val lanes = closure.optString("lanesClosed").trim()
            val delay = closure.optString("estimatedDelay").trim()

            val full = type.equals("Full", ignoreCase = true) ||
                lanes.equals("All", ignoreCase = true)

            out += RoadHazard(
                id = "lcs-${closure.optString("closureID")}-${closure.optString("logNumber")}",
                kind = if (full) HazardKind.FULL_CLOSURE else HazardKind.LANE_CLOSURE,
                title = buildString {
                    append(if (full) "Road closed" else "Lane closure")
                    if (route.isNotBlank()) append(" - ").append(route)
                    if (where.isNotBlank()) append(" at ").append(where)
                },
                detail = buildString {
                    if (work.isNotBlank()) append(work)
                    if (lanes.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("Lanes: ").append(lanes)
                    }
                    if (delay.isNotBlank() && !delay.equals("Not Reported", true)) {
                        if (isNotEmpty()) append(" · ")
                        append(delay).append(" min delay")
                    }
                    if (place.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(place)
                    }
                }.ifBlank { type },
                lat = lat,
                lon = lon,
                route = route,
                source = "Caltrans",
                epochSeconds = start ?: 0L,
            )
        }
        return out
    }

    // ------------------------------------------------------------- chain control

    /**
     * Chain control reports every checkpoint in the district, almost all of them
     * reading "no controls in effect". Only the ones actually requiring chains are a
     * hazard; status R-0 is the all-clear and is filtered out.
     */
    fun parseChainControl(body: String): List<RoadHazard> {
        val data = JSONObject(body.removePrefix("﻿")).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<RoadHazard>()
        for (i in 0 until data.length()) {
            val cc = data.optJSONObject(i)?.optJSONObject("cc") ?: continue
            if (!cc.optString("inService").equals("true", ignoreCase = true)) continue

            val status = cc.optJSONObject("statusData") ?: continue
            val code = status.optString("status").trim()
            // R-0 means no controls. Anything else is chains in some form.
            if (code.isBlank() || code.equals("R-0", ignoreCase = true)) continue

            val loc = cc.optJSONObject("location") ?: continue
            val lat = loc.optString("latitude").toDoubleOrNull() ?: continue
            val lon = loc.optString("longitude").toDoubleOrNull() ?: continue
            if (lat == 0.0 || lon == 0.0) continue

            val route = loc.optString("route").trim()
            val where = loc.optString("locationName").trim()
            out += RoadHazard(
                id = "cc-${cc.optString("index")}",
                kind = HazardKind.CHAIN_CONTROL,
                title = buildString {
                    append("Chain control")
                    if (route.isNotBlank()) append(" - ").append(route)
                    if (where.isNotBlank()) append(" at ").append(where)
                },
                detail = status.optString("statusDescription").trim().ifBlank { code },
                lat = lat,
                lon = lon,
                route = route,
                source = "Caltrans",
                epochSeconds = 0L,
            )
        }
        return out
    }

    // ---------------------------------------------------------------------- CHP

    private val LOG_RE = Regex("""<Log ID\s*=\s*"([^"]*)"\s*>(.*?)</Log>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * CHP's feed is XML with every value wrapped in literal quote characters inside the
     * element, e.g. `<LogType>"1183-Trfc Collision"</LogType>`, and coordinates packed
     * as `"38559948:121466568"` - millionths of a degree, longitude sign dropped
     * because the whole state is west.
     */
    fun parseChp(body: String): List<RoadHazard> {
        val out = ArrayList<RoadHazard>()
        for (m in LOG_RE.findAll(body)) {
            val id = m.groupValues[1]
            val block = m.groupValues[2]
            val latLon = tag(block, "LATLON") ?: continue
            val parts = latLon.split(':')
            if (parts.size != 2) continue
            val lat = parts[0].trim().toDoubleOrNull()?.div(1_000_000.0) ?: continue
            val lonRaw = parts[1].trim().toDoubleOrNull()?.div(1_000_000.0) ?: continue
            if (lat == 0.0 || lonRaw == 0.0) continue
            // California is entirely west of Greenwich; the feed omits the sign.
            val lon = if (lonRaw > 0) -lonRaw else lonRaw

            val type = tag(block, "LogType").orEmpty()
            val location = tag(block, "Location").orEmpty()
            val area = tag(block, "Area").orEmpty()
            val desc = tag(block, "LocationDesc").orEmpty()

            // The narrative arrives newest-first; the last line is the original call.
            val details = Regex("""<IncidentDetail>"?(.*?)"?</IncidentDetail>""",
                RegexOption.DOT_MATCHES_ALL)
                .findAll(block).map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()

            out += RoadHazard(
                id = "chp-$id",
                kind = if (type.contains("Collision", true) || type.contains("Hit and Run", true)) {
                    HazardKind.COLLISION
                } else HazardKind.HAZARD,
                title = buildString {
                    append(cleanType(type))
                    if (location.isNotBlank()) append(" - ").append(location)
                },
                detail = buildString {
                    if (desc.isNotBlank()) append(desc)
                    if (details.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(details.last())
                    }
                    if (area.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(area)
                    }
                },
                lat = lat,
                lon = lon,
                route = "",
                source = "CHP",
                epochSeconds = 0L,
            )
        }
        return out
    }

    /** Turns CHP's "1183-Trfc Collision-Unkn Inj" into "Trfc Collision-Unkn Inj". */
    private fun cleanType(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return "Incident"
        val dash = t.indexOf('-')
        // Strip the leading radio code, but only when it really is one.
        return if (dash in 1..6 && t.take(dash).any { it.isDigit() }) {
            t.substring(dash + 1).trim()
        } else t
    }

    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        return m.groupValues[1].trim().trim('"').trim()
    }

    private const val LOOKAHEAD_S = 2L * 60 * 60
}
