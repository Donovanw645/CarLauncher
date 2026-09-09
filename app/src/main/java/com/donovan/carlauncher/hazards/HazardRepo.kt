package com.donovan.carlauncher.hazards

import com.donovan.carlauncher.nav.LatLon
import com.donovan.carlauncher.nav.haversineMeters
import com.donovan.carlauncher.traffic.CaltransDistricts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HazardState {
    data object Idle : HazardState
    data object Loading : HazardState
    data class Ready(val hazards: List<NearbyHazard>, val atMs: Long) : HazardState
    data class Failed(val message: String) : HazardState
}

/**
 * Road hazards near the car, merged from the Caltrans and CHP feeds.
 *
 * Unlike the camera catalogue, none of this can be cached for long - a collision
 * reported twenty minutes ago has usually cleared - so it refreshes on a timer while
 * the car is moving and is never written to disk.
 */
class HazardRepo(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<HazardState>(HazardState.Idle)
    val state: StateFlow<HazardState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<RoadHazard?>(null)
    val selected: StateFlow<RoadHazard?> = _selected.asStateFlow()

    val nearby: List<NearbyHazard>
        get() = (_state.value as? HazardState.Ready)?.hazards.orEmpty()

    private var loadedAt: LatLon? = null
    private var loadedAtMs: Long = 0L
    private var radiusMiles: Int = 25

    /**
     * Refreshes when the car has moved far enough or the data has gone stale, and
     * otherwise leaves the existing set alone.
     */
    fun ensureLoaded(here: LatLon?, miles: Int, force: Boolean = false) {
        here ?: return
        val now = System.currentTimeMillis()
        val moved = loadedAt?.let { haversineMeters(it, here) > RELOAD_AFTER_M } ?: true
        val stale = now - loadedAtMs > REFRESH_MS
        if (!force && !moved && !stale && _state.value is HazardState.Ready) return
        if (_state.value is HazardState.Loading) return

        radiusMiles = miles
        _state.value = HazardState.Loading
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { collect(here, miles) }
            }
            result.onSuccess { list ->
                loadedAt = here
                loadedAtMs = System.currentTimeMillis()
                _state.value = HazardState.Ready(list, loadedAtMs)
                val sel = _selected.value
                if (sel != null && list.none { it.hazard.id == sel.id }) _selected.value = null
            }.onFailure {
                _state.value = HazardState.Failed(it.message ?: "Could not load hazards")
            }
        }
    }

    fun refresh(here: LatLon?, miles: Int) = ensureLoaded(here, miles, force = true)

    fun select(hazard: RoadHazard?) {
        _selected.value = hazard
    }

    fun selectById(id: String): Boolean {
        val hit = nearby.firstOrNull { it.hazard.id == id } ?: return false
        _selected.value = hit.hazard
        return true
    }

    /**
     * Each feed is fetched independently and a failure in one is swallowed, because a
     * CHP outage should not also cost the driver their chain-control warnings.
     */
    private fun collect(here: LatLon, miles: Int): List<NearbyHazard> {
        val radiusM = miles * 1609.344
        val nowEpoch = System.currentTimeMillis() / 1000
        val districts = CaltransDistricts.near(here.lat, here.lon, radiusM)
        val all = ArrayList<RoadHazard>()

        for (d in districts) {
            runCatching {
                all += HazardFeeds.parseLaneClosures(HazardFeeds.fetch(HazardFeeds.lcsUrl(d)), nowEpoch)
            }
            runCatching {
                all += HazardFeeds.parseChainControl(HazardFeeds.fetch(HazardFeeds.ccUrl(d)))
            }
        }
        runCatching {
            all += HazardFeeds.parseChp(HazardFeeds.fetch(HazardFeeds.CHP_URL))
        }

        return all
            .asSequence()
            .distinctBy { it.id }
            .map { NearbyHazard(it, haversineMeters(here, it.point)) }
            .filter { it.meters <= radiusM }
            // Worst first at similar distance, so the closed road outranks the cone.
            .sortedWith(compareBy({ it.hazard.kind.ordinal }, { it.meters }))
            .toList()
    }

    private companion object {
        const val RELOAD_AFTER_M = 8_000.0
        const val REFRESH_MS = 5L * 60 * 1000
    }
}
