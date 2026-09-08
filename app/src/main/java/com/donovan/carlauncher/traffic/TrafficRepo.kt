package com.donovan.carlauncher.traffic

import android.content.Context
import com.donovan.carlauncher.nav.LatLon
import com.donovan.carlauncher.nav.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the CCTV tab is showing right now. */
sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Ready(val cameras: List<NearbyCamera>) : CatalogState
    data class Failed(val message: String) : CatalogState
}

/**
 * Keeps the list of nearby traffic cameras, which one is selected, and how it should be
 * shown.
 *
 * Two separate clocks matter here and conflating them would be a mistake. The
 * *catalogue* - where the cameras are - changes on the order of never, so it is cached
 * for a week. The *pictures* change about once a minute, which is what [thumbTick]
 * drives. Nothing here ever opens a video stream; that only happens when the viewer
 * explicitly asks for one.
 */
class TrafficRepo(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val catalog = CaltransCatalog(context)

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<TrafficCamera?>(null)
    val selected: StateFlow<TrafficCamera?> = _selected.asStateFlow()

    private val _mode = MutableStateFlow(CameraViewMode.STILL)
    val mode: StateFlow<CameraViewMode> = _mode.asStateFlow()

    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()

    /**
     * Bumped once a minute. Appending it to a snapshot URL is what makes the image
     * actually refresh - Caltrans serves these with cache headers that would otherwise
     * leave a stale frame on screen indefinitely.
     */
    private val _thumbTick = MutableStateFlow(System.currentTimeMillis() / 60_000)
    val thumbTick: StateFlow<Long> = _thumbTick.asStateFlow()

    /** Every camera in range, nearest first - the map draws its dots from this. */
    val nearby: List<NearbyCamera>
        get() = (_state.value as? CatalogState.Ready)?.cameras.orEmpty()

    private var loadedAt: LatLon? = null
    private var radiusMiles: Int = 25

    init {
        scope.launch {
            while (true) {
                delay(60_000)
                _thumbTick.value = System.currentTimeMillis() / 60_000
            }
        }
    }

    // ------------------------------------------------------------------ catalogue

    /**
     * Loads the cameras around [here], reusing what is already loaded unless the car
     * has moved far enough for the answer to change.
     */
    fun ensureLoaded(here: LatLon?, miles: Int, force: Boolean = false) {
        here ?: return
        val moved = loadedAt?.let { haversineMeters(it, here) > RELOAD_AFTER_M } ?: true
        val changed = miles != radiusMiles
        if (!force && !moved && !changed && _state.value is CatalogState.Ready) return
        if (_state.value is CatalogState.Loading) return

        radiusMiles = miles
        _state.value = CatalogState.Loading
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val radiusM = miles * 1609.344
                    val wanted = catalog.districtsNear(here.lat, here.lon, radiusM)
                    if (wanted.isEmpty()) return@withContext emptyList()

                    wanted.flatMap { d -> runCatching { catalog.district(d) }.getOrDefault(emptyList()) }
                        .asSequence()
                        .map { NearbyCamera(it, haversineMeters(here, it.point)) }
                        .filter { it.meters <= radiusM }
                        .sortedBy { it.meters }
                        .toList()
                }
            }
            result.onSuccess { cams ->
                loadedAt = here
                _state.value = CatalogState.Ready(cams)
                // Keep the selection pointing at a camera that is still in the list.
                val sel = _selected.value
                if (sel != null && cams.none { it.camera.id == sel.id }) clearSelection()
            }.onFailure {
                _state.value = CatalogState.Failed(
                    it.message ?: "Could not reach Caltrans"
                )
            }
        }
    }

    fun refresh(here: LatLon?, miles: Int) = ensureLoaded(here, miles, force = true)

    // ------------------------------------------------------------------ selection

    fun select(camera: TrafficCamera) {
        _selected.value = camera
        // A camera with no video can only ever be a snapshot, so do not strand the
        // viewer on a Live tab that will never fill in.
        if (!camera.hasLive) _mode.value = CameraViewMode.STILL
    }

    /** Used by the map: pick a camera by id so tapping a dot opens that feed. */
    fun selectById(id: String): Boolean {
        val hit = nearby.firstOrNull { it.camera.id == id } ?: return false
        select(hit.camera)
        return true
    }

    fun clearSelection() {
        _selected.value = null
        _fullscreen.value = false
    }

    fun setMode(mode: CameraViewMode) {
        if (mode == CameraViewMode.LIVE && _selected.value?.hasLive != true) return
        _mode.value = mode
    }

    fun setFullscreen(on: Boolean) {
        if (on && _selected.value == null) return
        _fullscreen.value = on
    }

    private companion object {
        /** Two miles of travel before the 25-mile ring is worth recomputing. */
        const val RELOAD_AFTER_M = 3_200.0
    }
}
