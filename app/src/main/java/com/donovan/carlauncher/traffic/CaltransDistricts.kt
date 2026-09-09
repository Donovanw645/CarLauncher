package com.donovan.carlauncher.traffic

/**
 * Where each Caltrans district actually is.
 *
 * Caltrans publishes every one of its feeds - cameras, lane closures, chain control -
 * one file per district, and they are big. Knowing each district's extent up front
 * means only the two or three that could matter get downloaded, instead of all twelve.
 *
 * The boxes were measured from the camera feed itself rather than taken from the
 * published district map, because what matters is where the equipment is.
 */
object CaltransDistricts {

    data class Box(
        val n: Int,
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
    )

    val all = listOf(
        Box(1, 38.7523, 41.9940, -124.1993, -122.6042),
        Box(2, 39.7748, 41.9966, -122.9912, -120.0385),
        Box(3, 38.0780, 39.7895, -122.1593, -119.9572),
        Box(4, 36.9192, 38.5264, -122.7900, -121.5128),
        Box(5, 34.3851, 37.1304, -122.0232, -119.4868),
        Box(6, 34.8177, 37.3310, -120.7238, -118.8074),
        Box(7, 33.7470, 34.7948, -119.2943, -117.6947),
        Box(8, 33.4385, 35.6020, -117.7438, -114.5503),
        Box(9, 34.9875, 38.3506, -119.4504, -117.8692),
        Box(10, 36.9772, 38.4077, -121.6755, -119.9504),
        Box(11, 32.5514, 33.3423, -117.3883, -115.4986),
        Box(12, 33.3965, 33.9422, -118.0918, -117.5934),
    )

    /**
     * Which districts could hold something within [radiusMeters] of the point.
     *
     * The box is padded by the radius before testing, so a camera or closure just over
     * a district line is still found. Districts overlap - near Los Angeles, 7, 8 and 12
     * all qualify - so this routinely returns more than one.
     */
    fun near(lat: Double, lon: Double, radiusMeters: Double): List<Int> {
        val padLat = radiusMeters / 111_320.0
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val padLon = radiusMeters / (111_320.0 * cosLat)
        return all.filter {
            lat >= it.latMin - padLat && lat <= it.latMax + padLat &&
                lon >= it.lonMin - padLon && lon <= it.lonMax + padLon
        }.map { it.n }
    }
}
