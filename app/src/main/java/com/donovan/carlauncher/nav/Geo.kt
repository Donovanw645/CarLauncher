package com.donovan.carlauncher.nav

import com.donovan.carlauncher.data.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_008.8

fun haversineMeters(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
}

fun bearingDegrees(from: LatLon, to: LatLon): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLon = Math.toRadians(to.lon - from.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Shortest distance from [p] to the segment [a]-[b], using a local equirectangular
 * projection. Accurate to well under a metre at the scales we care about.
 */
fun distanceToSegmentMeters(p: LatLon, a: LatLon, b: LatLon): Double {
    val latRef = Math.toRadians((a.lat + b.lat) / 2.0)
    val mPerDegLat = 111_132.0
    val mPerDegLon = 111_320.0 * cos(latRef)

    val px = (p.lon - a.lon) * mPerDegLon
    val py = (p.lat - a.lat) * mPerDegLat
    val bx = (b.lon - a.lon) * mPerDegLon
    val by = (b.lat - a.lat) * mPerDegLat

    val lenSq = bx * bx + by * by
    if (lenSq < 1e-9) return sqrt(px * px + py * py)

    var t = (px * bx + py * by) / lenSq
    t = max(0.0, min(1.0, t))
    val dx = px - t * bx
    val dy = py - t * by
    return sqrt(dx * dx + dy * dy)
}

/** Shortest distance from [p] to a polyline. Returns [Double.MAX_VALUE] for an empty line. */
fun distanceToPolylineMeters(p: LatLon, line: List<LatLon>): Double {
    if (line.isEmpty()) return Double.MAX_VALUE
    if (line.size == 1) return haversineMeters(p, line[0])
    var best = Double.MAX_VALUE
    for (i in 0 until line.size - 1) {
        val d = distanceToSegmentMeters(p, line[i], line[i + 1])
        if (d < best) best = d
    }
    return best
}

/**
 * Decodes a Google/OSRM encoded polyline. [precision] is 5 for the classic format,
 * 6 for OSRM's `polyline6`.
 */
fun decodePolyline(encoded: String, precision: Int = 5): List<LatLon> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLon>(encoded.length / 4)
    var index = 0
    var lat = 0
    var lon = 0

    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        out.add(LatLon(lat / factor, lon / factor))
    }
    return out
}

// ---------------------------------------------------------------- formatting

fun formatDistance(meters: Double, units: Units): String {
    if (meters.isNaN() || meters < 0) return "--"
    return if (units == Units.IMPERIAL) {
        val feet = meters * 3.280839895
        when {
            feet < 1000 -> "${(feet / 10).roundToInt() * 10} ft"
            else -> {
                val miles = meters / 1609.344
                if (miles < 10) String.format(Locale.US, "%.1f mi", miles)
                else "${miles.roundToInt()} mi"
            }
        }
    } else {
        when {
            meters < 950 -> "${(meters / 10).roundToInt() * 10} m"
            else -> {
                val km = meters / 1000.0
                if (km < 10) String.format(Locale.US, "%.1f km", km)
                else "${km.roundToInt()} km"
            }
        }
    }
}

/** Shorter, spoken-friendly form, e.g. "in a quarter mile". */
fun spokenDistance(meters: Double, units: Units): String {
    return if (units == Units.IMPERIAL) {
        val feet = meters * 3.280839895
        when {
            feet < 150 -> "now"
            feet < 800 -> "in ${(feet / 100).roundToInt() * 100} feet"
            feet < 1600 -> "in a quarter mile"
            feet < 3200 -> "in a half mile"
            else -> {
                val miles = meters / 1609.344
                if (miles < 10) String.format(Locale.US, "in %.1f miles", miles)
                else "in ${miles.roundToInt()} miles"
            }
        }
    } else {
        when {
            meters < 50 -> "now"
            meters < 950 -> "in ${(meters / 50).roundToInt() * 50} meters"
            else -> {
                val km = meters / 1000.0
                if (km < 10) String.format(Locale.US, "in %.1f kilometers", km)
                else "in ${km.roundToInt()} kilometers"
            }
        }
    }
}

fun formatDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return "--"
    val total = seconds.roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m} min"
        else -> "<1 min"
    }
}

fun speedValue(metersPerSecond: Float, units: Units): Int {
    val v = if (units == Units.IMPERIAL) metersPerSecond * 2.236936f else metersPerSecond * 3.6f
    return if (v.isNaN() || v < 0.5f) 0 else v.roundToInt()
}

fun speedUnitLabel(units: Units): String = if (units == Units.IMPERIAL) "mph" else "km/h"

/** Normalises a heading difference into -180..180. */
fun headingDelta(from: Double, to: Double): Double {
    var d = (to - from + 540.0) % 360.0 - 180.0
    if (abs(d) < 1e-9) d = 0.0
    return d
}
