package com.donovan.carlauncher.hazards

import com.donovan.carlauncher.nav.LatLon

/**
 * What kind of trouble is on the road ahead.
 *
 * Ordered by how much it should worry a driver, so the worst hazard in a set can be
 * found with `maxOf { it.kind }`.
 */
enum class HazardKind(val label: String) {
    /** Chain controls in force - snow or ice on the pass. */
    CHAIN_CONTROL("Chain control"),

    /** Roadway blocked outright. Slides and washouts show up as these. */
    FULL_CLOSURE("Road closed"),

    COLLISION("Collision"),

    /** Debris, animals, stalled vehicles - CHP's 1125 family. */
    HAZARD("Hazard"),

    /** Lanes down but traffic still moving. Mostly roadwork. */
    LANE_CLOSURE("Lane closure"),
}

/** One thing worth knowing about before you drive into it. */
data class RoadHazard(
    val id: String,
    val kind: HazardKind,
    /** Short headline, e.g. "Collision - I-5 at Pocket Rd". */
    val title: String,
    /** The longer story, when the feed gives one. */
    val detail: String,
    val lat: Double,
    val lon: Double,
    val route: String,
    /** Where the report came from, shown so the driver can judge it. */
    val source: String,
    val epochSeconds: Long,
) {
    val point: LatLon get() = LatLon(lat, lon)
}

/** A hazard plus how far away it is. */
data class NearbyHazard(
    val hazard: RoadHazard,
    val meters: Double,
) {
    val miles: Double get() = meters / 1609.344
}
