package com.donovan.carlauncher.offline

import android.content.Context
import android.os.Environment
import com.donovan.carlauncher.ui.map.MapStyles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.storage.FileSource
import java.io.File

/** Where the downloaded map lives. Mirrors the dashcam's storage picker. */
data class MapStorageOption(
    val index: Int,
    val dir: File,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
)

sealed interface OfflineState {
    data object None : OfflineState
    data object Preparing : OfflineState
    data class Downloading(
        val completedTiles: Long,
        val requiredResources: Long,
        val completedBytes: Long,
        val precise: Boolean,
    ) : OfflineState {
        /**
         * MapLibre only learns the true resource count part-way through, so before it
         * is precise this is a floor rather than a real fraction.
         */
        val fraction: Float
            get() = if (requiredResources > 0) {
                (completedTiles.toFloat() / requiredResources).coerceIn(0f, 1f)
            } else 0f
    }
    data class Paused(val completedTiles: Long, val completedBytes: Long) : OfflineState
    data class Complete(val tiles: Long, val bytes: Long) : OfflineState
    data class Failed(val message: String) : OfflineState
}

/**
 * Downloads the whole California basemap for offline use.
 *
 * MapLibre keeps offline regions in its own SQLite database, so this is a thin wrapper
 * over [OfflineManager] rather than a tile downloader of its own. Two things need care:
 *
 *  - The database defaults to internal storage, which on this tablet is far too small.
 *    [applyStoragePath] moves it to the SD card before anything is downloaded.
 *  - MapLibre inherits Mapbox's 6,000-tile ceiling. California to zoom 14 is roughly
 *    343,000 tiles, so the limit is raised before a region is created; without that the
 *    download stops a fraction of a percent in and reports a limit-exceeded callback.
 */
class OfflineMaps(private val appContext: Context) {

    private val _state = MutableStateFlow<OfflineState>(OfflineState.None)
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    private var region: OfflineRegion? = null

    private val manager: OfflineManager by lazy {
        OfflineManager.Companion.getInstance(appContext).apply {
            setOfflineMapboxTileCountLimit(TILE_LIMIT)
        }
    }

    // ------------------------------------------------------------------- storage

    fun storageOptions(): List<MapStorageOption> {
        val dirs = runCatching {
            appContext.getExternalFilesDirs(null)
        }.getOrNull().orEmpty()
        return dirs.mapIndexedNotNull { index, dir ->
            if (dir == null) return@mapIndexedNotNull null
            val removable = index > 0 || runCatching {
                Environment.isExternalStorageRemovable(dir)
            }.getOrDefault(false)
            MapStorageOption(
                index = index,
                dir = File(dir, "maps").apply { mkdirs() },
                label = if (removable) "SD card" else "Internal",
                removable = removable,
                freeBytes = runCatching { dir.usableSpace }.getOrDefault(0L),
            )
        }
    }

    /**
     * Points MapLibre's cache at the chosen volume. Must happen before any download
     * starts; MapLibre reopens its database, and tiles already fetched stay behind in
     * the old one.
     */
    fun applyStoragePath(index: Int, onDone: (Boolean) -> Unit = {}) {
        val option = storageOptions().firstOrNull { it.index == index }
            ?: storageOptions().firstOrNull()
        if (option == null) {
            onDone(false)
            return
        }
        runCatching {
            FileSource.setResourcesCachePath(
                appContext,
                option.dir.absolutePath,
                object : FileSource.ResourcesCachePathChangeCallback {
                    override fun onSuccess(path: String) = onDone(true)
                    override fun onError(message: String) {
                        _state.value = OfflineState.Failed("Storage: $message")
                        onDone(false)
                    }
                },
            )
        }.onFailure {
            _state.value = OfflineState.Failed(it.message ?: "Could not set storage path")
            onDone(false)
        }
    }

    // ------------------------------------------------------------------ download

    /** Reconnects to a region left behind by an earlier run, if there is one. */
    fun refreshExisting() {
        runCatching {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val existing = offlineRegions?.firstOrNull() ?: run {
                        _state.value = OfflineState.None
                        return
                    }
                    region = existing
                    existing.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            _state.value = status?.toState() ?: OfflineState.None
                        }

                        override fun onError(error: String?) {
                            _state.value = OfflineState.None
                        }
                    })
                }

                override fun onError(error: String) {
                    _state.value = OfflineState.None
                }
            })
        }
    }

    fun start(storageIndex: Int) {
        _state.value = OfflineState.Preparing
        applyStoragePath(storageIndex) { ok ->
            if (ok) createAndStart()
        }
    }

    private fun createAndStart() {
        val existing = region
        if (existing != null) {
            attach(existing)
            existing.setDownloadState(OfflineRegion.STATE_ACTIVE)
            return
        }
        val definition = OfflineTilePyramidRegionDefinition(
            MapStyles.DAY,
            CALIFORNIA,
            MIN_ZOOM,
            MAX_ZOOM,
            appContext.resources.displayMetrics.density,
        )
        manager.createOfflineRegion(
            definition,
            "California".toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    region = offlineRegion
                    attach(offlineRegion)
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    _state.value = OfflineState.Failed(error)
                }
            },
        )
    }

    private fun attach(r: OfflineRegion) {
        // Without this MapLibre stops talking the moment the region goes inactive, so
        // pausing would halt the download while the UI sat there still saying
        // "downloading" - which is exactly what it did before this line existed.
        r.setDeliverInactiveMessages(true)
        r.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                _state.value = status.toState()
            }

            override fun onError(error: OfflineRegionError) {
                _state.value = OfflineState.Failed(error.message)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                _state.value = OfflineState.Failed("Tile limit reached ($limit)")
            }
        })
    }

    fun pause() {
        val r = region ?: return
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        // Reflect it immediately rather than waiting on a callback round trip; the
        // observer will correct the counts a moment later.
        (state.value as? OfflineState.Downloading)?.let {
            _state.value = OfflineState.Paused(it.completedTiles, it.completedBytes)
        }
    }

    fun delete() {
        val r = region
        if (r == null) {
            _state.value = OfflineState.None
            return
        }
        r.setDownloadState(OfflineRegion.STATE_INACTIVE)
        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                region = null
                _state.value = OfflineState.None
                // Deleting the region frees its rows but leaves the SQLite file at its
                // old size. On a tablet chosen for this app precisely because storage is
                // tight, "deleted" has to actually give the gigabyte back.
                runCatching {
                    manager.packDatabase(object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() = Unit
                        override fun onError(message: String) = Unit
                    })
                }
            }

            override fun onError(error: String) {
                _state.value = OfflineState.Failed(error)
            }
        })
    }

    private fun OfflineRegionStatus.toState(): OfflineState = when {
        isComplete -> OfflineState.Complete(completedTileCount, completedTileSize)
        downloadState == OfflineRegion.STATE_ACTIVE -> OfflineState.Downloading(
            completedTiles = completedTileCount,
            requiredResources = requiredResourceCount,
            completedBytes = completedTileSize,
            precise = isRequiredResourceCountPrecise,
        )
        completedTileCount > 0 -> OfflineState.Paused(completedTileCount, completedTileSize)
        else -> OfflineState.None
    }

    companion object {
        /** The state, generously boxed - the extra ocean costs almost nothing. */
        val CALIFORNIA: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(42.02, -114.13))
            .include(LatLng(32.53, -124.48))
            .build()

        const val MIN_ZOOM = 0.0

        /**
         * Vector tiles are resolution independent, so 14 is where every public source
         * stops and the renderer scales up from there. Going deeper would multiply the
         * download for no visible gain.
         */
        const val MAX_ZOOM = 14.0

        /** California to z14 is ~343,000 tiles; the stock ceiling is 6,000. */
        const val TILE_LIMIT = 500_000L
    }
}
