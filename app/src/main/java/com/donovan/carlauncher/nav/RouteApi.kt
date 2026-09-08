package com.donovan.carlauncher.nav

import org.json.JSONObject

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverPoint: LatLon,
    val maneuverType: String,
    val modifier: String,
    val roadName: String,
)

data class Route(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<LatLon>,
    val steps: List<RouteStep>,
)

/**
 * Driving directions from an OSRM server. The default is the public demo instance,
 * which is fine for personal use but has no uptime guarantee - the router URL is
 * configurable in Settings so it can be pointed at a self-hosted or Valhalla-compatible
 * OSRM deployment.
 */
class RouteApi(private val baseUrl: () -> String) {

    suspend fun route(from: LatLon, to: LatLon): Route {
        val url = buildString {
            append(baseUrl().trimEnd('/'))
            append("/route/v1/driving/")
            append(from.lon).append(',').append(from.lat).append(';')
            append(to.lon).append(',').append(to.lat)
            append("?overview=full&geometries=polyline6&steps=true")
            append("&alternatives=false&annotations=false&continue_straight=default")
        }

        val body = Http.getString(url)
        val root = JSONObject(body)
        val code = root.optString("code", "Error")
        if (!code.equals("Ok", ignoreCase = true)) {
            throw IllegalStateException(
                root.optString("message").ifBlank { "Router returned $code" }
            )
        }
        val routes = root.optJSONArray("routes")
            ?: throw IllegalStateException("No route found")
        val r = routes.optJSONObject(0) ?: throw IllegalStateException("No route found")

        val geometry = decodePolyline(r.optString("geometry"), precision = 6)
        val steps = ArrayList<RouteStep>()

        val legs = r.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val stepsArr = legs.optJSONObject(li)?.optJSONArray("steps") ?: continue
                for (si in 0 until stepsArr.length()) {
                    val s = stepsArr.optJSONObject(si) ?: continue
                    val man = s.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location") ?: continue
                    if (loc.length() < 2) continue
                    val point = LatLon(loc.optDouble(1), loc.optDouble(0))
                    val type = man.optString("type")
                    val modifier = man.optString("modifier")
                    val road = s.optString("name")
                    val exit = if (man.has("exit")) man.optInt("exit") else null
                    steps.add(
                        RouteStep(
                            instruction = buildInstruction(type, modifier, road, exit),
                            distanceMeters = s.optDouble("distance", 0.0),
                            durationSeconds = s.optDouble("duration", 0.0),
                            maneuverPoint = point,
                            maneuverType = type,
                            modifier = modifier,
                            roadName = road,
                        )
                    )
                }
            }
        }

        return Route(
            distanceMeters = r.optDouble("distance", 0.0),
            durationSeconds = r.optDouble("duration", 0.0),
            geometry = geometry,
            steps = steps,
        )
    }
}

private fun ordinal(n: Int): String = when (n) {
    1 -> "1st"
    2 -> "2nd"
    3 -> "3rd"
    else -> "${n}th"
}

private fun dirWord(modifier: String): String = when (modifier) {
    "left" -> "left"
    "right" -> "right"
    "slight left" -> "slightly left"
    "slight right" -> "slightly right"
    "sharp left" -> "sharp left"
    "sharp right" -> "sharp right"
    "uturn" -> "around"
    "straight" -> "straight"
    else -> ""
}

/** Turns OSRM's structured maneuver into something a driver can act on at a glance. */
internal fun buildInstruction(
    type: String,
    modifier: String,
    roadName: String,
    exit: Int?,
): String {
    val onto = if (roadName.isBlank()) "" else " onto $roadName"
    val along = if (roadName.isBlank()) "" else " on $roadName"
    val dir = dirWord(modifier)

    return when (type) {
        "depart" -> if (roadName.isBlank()) "Start driving" else "Head out$along"
        "arrive" -> when (modifier) {
            "left" -> "Arrive - destination on your left"
            "right" -> "Arrive - destination on your right"
            else -> "Arrive at your destination"
        }
        "turn" -> if (modifier == "uturn") "Make a U-turn$onto"
        else if (dir.isBlank()) "Turn$onto" else "Turn $dir$onto"
        "new name" -> "Continue$onto"
        "continue" -> if (modifier == "uturn") "Make a U-turn$onto"
        else if (dir.isBlank() || dir == "straight") "Continue$onto" else "Continue $dir$onto"
        "merge" -> if (dir.isBlank()) "Merge$onto" else "Merge $dir$onto"
        "on ramp" -> if (dir.isBlank()) "Take the ramp$onto" else "Take the ramp on the $dir$onto"
        "off ramp" -> if (dir.isBlank()) "Take the exit$onto" else "Take the exit on the $dir$onto"
        "fork" -> if (dir.isBlank()) "Keep going$onto" else "Keep $dir$onto"
        "end of road" -> if (dir.isBlank()) "Continue$onto" else "Turn $dir$onto"
        "roundabout", "rotary" ->
            if (exit != null) "At the roundabout, take the ${ordinal(exit)} exit$onto"
            else "Enter the roundabout$onto"
        "roundabout turn" ->
            if (dir.isBlank()) "At the roundabout, continue$onto"
            else "At the roundabout, turn $dir$onto"
        "exit roundabout", "exit rotary" -> "Exit the roundabout$onto"
        "notification" -> "Continue$along"
        else -> if (dir.isBlank()) "Continue$onto" else "Keep $dir$onto"
    }
}
