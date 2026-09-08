package com.donovan.carlauncher.traffic

import com.donovan.carlauncher.nav.LatLon

/**
 * One public Caltrans traffic camera.
 *
 * Roughly a third of the state's cameras publish no video at all - only a JPEG that
 * refreshes about once a minute - so [streamUrl] is genuinely optional and every
 * caller has to cope with its absence rather than assuming a stream is there.
 */
data class TrafficCamera(
    val id: String,
    /** Caltrans' own name for the camera, e.g. "Hwy 5 at Pocket". */
    val name: String,
    /** The town it is nearest to, e.g. "Sacramento". */
    val place: String,
    val county: String,
    val route: String,
    val lat: Double,
    val lon: Double,
    val stillUrl: String,
    val streamUrl: String?,
    val district: Int,
) {
    val point: LatLon get() = LatLon(lat, lon)

    val hasLive: Boolean get() = !streamUrl.isNullOrBlank()

    /** "Sacramento · I-5" - whichever halves actually exist. */
    val subtitle: String
        get() = listOf(place, route).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A camera plus how far it is from wherever the car currently is. */
data class NearbyCamera(
    val camera: TrafficCamera,
    val meters: Double,
) {
    val miles: Double get() = meters / 1609.344
}

/** Whether the viewer shows moving video or the refreshing snapshot. */
enum class CameraViewMode { STILL, LIVE }
