package com.donovan.carlauncher.nav

import com.donovan.carlauncher.data.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NavState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val destination: Place? = null,
    val route: Route? = null,
    /** Index of the step currently being driven. The upcoming turn is [upcomingStep]. */
    val legIndex: Int = 0,
    val distanceToManeuverM: Double = 0.0,
    val remainingDistanceM: Double = 0.0,
    val remainingDurationS: Double = 0.0,
    val etaEpochMs: Long = 0L,
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val error: String? = null,
) {
    val upcomingStep: RouteStep?
        get() = route?.steps?.getOrNull(legIndex + 1)

    val followingStep: RouteStep?
        get() = route?.steps?.getOrNull(legIndex + 2)
}

/**
 * Follows a route against the live GPS feed: advances the current step, decides when to
 * speak, notices when the car has left the line, and re-routes.
 */
class NavEngine(
    private val routeApi: RouteApi,
    private val scope: CoroutineScope,
    private val speech: Speech,
    private val units: () -> Units,
    private val voiceEnabled: () -> Boolean,
) {

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private var routeJob: Job? = null
    private var offRouteStrikes = 0
    private val announced = HashSet<String>()
    private var lastRerouteAt = 0L

    // ------------------------------------------------------------------ control

    fun start(from: LatLon, destination: Place) {
        routeJob?.cancel()
        announced.clear()
        offRouteStrikes = 0
        _state.value = NavState(
            active = true,
            loading = true,
            destination = destination,
        )
        routeJob = scope.launch {
            val result = runCatching { routeApi.route(from, destination.point) }
            result.onSuccess { route ->
                _state.value = _state.value.copy(
                    loading = false,
                    route = route,
                    legIndex = 0,
                    remainingDistanceM = route.distanceMeters,
                    remainingDurationS = route.durationSeconds,
                    etaEpochMs = System.currentTimeMillis() + (route.durationSeconds * 1000).toLong(),
                    error = null,
                )
                if (voiceEnabled()) {
                    val first = route.steps.firstOrNull()?.instruction ?: "Starting navigation"
                    val total = formatDistance(route.distanceMeters, units())
                    speech.say("$first. $total to your destination.")
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    active = false,
                    loading = false,
                    error = e.message ?: "Could not get directions",
                )
            }
        }
    }

    fun stop() {
        routeJob?.cancel()
        routeJob = null
        announced.clear()
        offRouteStrikes = 0
        _state.value = NavState()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------------ tracking

    fun onLocation(here: LatLon) {
        val s = _state.value
        if (!s.active || s.arrived) return
        val route = s.route ?: return
        val steps = route.steps
        if (steps.isEmpty()) return

        var legIndex = s.legIndex.coerceIn(0, maxOf(steps.lastIndex - 1, 0))

        // Advance past any maneuvers we have already driven through. The loop handles
        // clusters of short steps (motorway interchanges) in a single GPS gap.
        while (legIndex < steps.lastIndex) {
            val nextManeuver = steps[legIndex + 1].maneuverPoint
            if (haversineMeters(here, nextManeuver) < ADVANCE_RADIUS_M) {
                legIndex++
                announced.removeAll { it.startsWith("${legIndex - 1}:") }
            } else {
                break
            }
        }

        val target = steps.getOrNull(legIndex + 1)
        val distToManeuver = if (target != null) {
            haversineMeters(here, target.maneuverPoint)
        } else {
            haversineMeters(here, steps.last().maneuverPoint)
        }

        // Remaining distance: what is left of this step, plus every step after it.
        var remainingDist = distToManeuver
        for (i in (legIndex + 1) until steps.size) remainingDist += steps[i].distanceMeters
        var remainingTime = 0.0
        for (i in (legIndex + 1) until steps.size) remainingTime += steps[i].durationSeconds
        val currentStep = steps[legIndex]
        if (currentStep.distanceMeters > 1.0) {
            val frac = (distToManeuver / currentStep.distanceMeters).coerceIn(0.0, 1.0)
            remainingTime += currentStep.durationSeconds * frac
        }

        // Arrival.
        val finalPoint = steps.last().maneuverPoint
        val distToFinish = haversineMeters(here, finalPoint)
        if (legIndex >= steps.lastIndex - 1 && distToFinish < ARRIVE_RADIUS_M) {
            if (voiceEnabled() && announced.add("arrived")) {
                speech.say("You have arrived at ${s.destination?.name ?: "your destination"}.")
            }
            _state.value = s.copy(
                legIndex = steps.lastIndex,
                arrived = true,
                distanceToManeuverM = 0.0,
                remainingDistanceM = 0.0,
                remainingDurationS = 0.0,
            )
            return
        }

        // Off-route detection, with a little hysteresis so one bad fix does not re-route.
        val deviation = distanceToPolylineMeters(here, route.geometry)
        val off = deviation > OFF_ROUTE_M
        offRouteStrikes = if (off) offRouteStrikes + 1 else 0

        _state.value = s.copy(
            legIndex = legIndex,
            distanceToManeuverM = distToManeuver,
            remainingDistanceM = remainingDist,
            remainingDurationS = remainingTime,
            etaEpochMs = System.currentTimeMillis() + (remainingTime * 1000).toLong(),
            offRoute = off,
        )

        if (target != null) announce(legIndex, target, distToManeuver)

        val now = System.currentTimeMillis()
        if (offRouteStrikes >= OFF_ROUTE_STRIKES &&
            !s.rerouting &&
            now - lastRerouteAt > REROUTE_COOLDOWN_MS
        ) {
            reroute(here)
        }
    }

    private fun announce(legIndex: Int, target: RouteStep, distance: Double) {
        if (!voiceEnabled()) return
        val stepLength = _state.value.route?.steps?.getOrNull(legIndex)?.distanceMeters ?: 0.0

        val tier = when {
            distance <= IMMINENT_M -> "now"
            distance <= NEAR_M -> "near"
            distance <= FAR_M && stepLength > FAR_M * 1.3 -> "far"
            else -> null
        } ?: return

        if (!announced.add("$legIndex:$tier")) return

        val phrase = if (tier == "now") {
            target.instruction
        } else {
            "${spokenDistance(distance, units()).replaceFirstChar { it.uppercase() }}, " +
                target.instruction.replaceFirstChar { it.lowercase() }
        }
        speech.say(phrase)
    }

    private fun reroute(from: LatLon) {
        val dest = _state.value.destination ?: return
        lastRerouteAt = System.currentTimeMillis()
        offRouteStrikes = 0
        _state.value = _state.value.copy(rerouting = true)
        if (voiceEnabled()) speech.say("Rerouting")

        routeJob?.cancel()
        routeJob = scope.launch {
            runCatching { routeApi.route(from, dest.point) }
                .onSuccess { route ->
                    announced.clear()
                    _state.value = _state.value.copy(
                        route = route,
                        legIndex = 0,
                        rerouting = false,
                        offRoute = false,
                        remainingDistanceM = route.distanceMeters,
                        remainingDurationS = route.durationSeconds,
                        etaEpochMs = System.currentTimeMillis() +
                            (route.durationSeconds * 1000).toLong(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(rerouting = false)
                }
        }
    }

    private companion object {
        const val ADVANCE_RADIUS_M = 28.0
        const val ARRIVE_RADIUS_M = 35.0
        const val OFF_ROUTE_M = 60.0
        const val OFF_ROUTE_STRIKES = 3
        const val REROUTE_COOLDOWN_MS = 15_000L
        const val FAR_M = 750.0
        const val NEAR_M = 260.0
        const val IMMINENT_M = 60.0
    }
}
