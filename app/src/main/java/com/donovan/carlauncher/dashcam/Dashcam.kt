package com.donovan.carlauncher.dashcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.donovan.carlauncher.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class DashcamStatus {
    /** Camera released. */
    IDLE,
    STARTING,
    /** Camera open and showing a preview, but nothing is being written. */
    PREVIEW,
    RECORDING,
    ERROR,
}

data class Clip(
    val file: File,
    val startedAt: Long,
    val sizeBytes: Long,
    val locked: Boolean,
) {
    val name: String get() = file.name
}

data class DashcamState(
    val status: DashcamStatus = DashcamStatus.IDLE,
    val elapsedMs: Long = 0L,
    val segmentBytes: Long = 0L,
    val segmentCount: Int = 0,
    val message: String? = null,
    val clips: List<Clip> = emptyList(),
    val loopBytes: Long = 0L,
    val savedBytes: Long = 0L,
)

/**
 * Process-wide handle on the dashcam. The camera itself lives in [DashcamService] so
 * recording keeps going when the launcher is not the top activity - a dashcam that
 * stops the moment you open Spotify is not a dashcam.
 */
object Dashcam {

    private val _state = MutableStateFlow(DashcamState())
    val state: StateFlow<DashcamState> = _state.asStateFlow()

    internal fun update(block: (DashcamState) -> DashcamState) = _state.update(block)

    /** Set by the service while it is alive, so the UI can hand it a preview surface. */
    @Volatile
    internal var previewUseCase: Preview? = null

    @Volatile
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    // ------------------------------------------------------------------- controls

    fun start(context: Context) {
        if (!hasCameraPermission(context)) {
            _state.update {
                it.copy(status = DashcamStatus.ERROR, message = "Camera permission is off")
            }
            return
        }
        _state.update { it.copy(status = DashcamStatus.STARTING, message = null) }
        val intent = Intent(context, DashcamService::class.java)
            .setAction(DashcamService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, DashcamService::class.java)
            .setAction(DashcamService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }

    fun toggle(context: Context) {
        // STARTING covers bringing the camera up for a preview too, so only a confirmed
        // RECORDING means the button should stop something.
        if (_state.value.status == DashcamStatus.RECORDING) stop(context) else start(context)
    }

    /** Opens the camera so the Dashcam tab shows a live view before you press record. */
    fun startPreview(context: Context) {
        if (!hasCameraPermission(context)) return
        val status = _state.value.status
        if (status == DashcamStatus.RECORDING || status == DashcamStatus.STARTING) return
        val intent = Intent(context, DashcamService::class.java)
            .setAction(DashcamService.ACTION_PREVIEW)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    /** Leaving the tab: drop the camera, unless a recording is in progress. */
    fun stopPreview(context: Context) {
        if (_state.value.status == DashcamStatus.RECORDING) return
        val intent = Intent(context, DashcamService::class.java)
            .setAction(DashcamService.ACTION_STOP_PREVIEW)
        runCatching { context.startService(intent) }
    }

    // -------------------------------------------------------------------- preview

    fun attachPreview(provider: Preview.SurfaceProvider) {
        pendingSurfaceProvider = provider
        previewUseCase?.setSurfaceProvider(provider)
    }

    fun detachPreview() {
        pendingSurfaceProvider = null
        previewUseCase?.setSurfaceProvider(null)
    }

    /** Called by the service once its Preview use case exists. */
    internal fun bindPendingPreview() {
        pendingSurfaceProvider?.let { previewUseCase?.setSurfaceProvider(it) }
    }

    // ------------------------------------------------------------------- storage

    /**
     * A place the dashcam can write. These are app-specific external dirs, which need no
     * storage permission and, on index 1 and up, live on the removable SD card.
     */
    data class StorageOption(
        val index: Int,
        val dir: File,
        val label: String,
        val removable: Boolean,
        val freeBytes: Long,
        val totalBytes: Long,
    )

    fun storageOptions(context: Context): List<StorageOption> {
        val dirs = runCatching {
            context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
        }.getOrNull().orEmpty()

        return dirs.mapIndexedNotNull { index, dir ->
            if (dir == null) return@mapIndexedNotNull null
            val removable = index > 0 || runCatching {
                Environment.isExternalStorageRemovable(dir)
            }.getOrDefault(false)
            StorageOption(
                index = index,
                dir = dir,
                label = if (removable) "SD card" else "Internal",
                removable = removable,
                freeBytes = runCatching { dir.usableSpace }.getOrDefault(0L),
                totalBytes = runCatching { dir.totalSpace }.getOrDefault(0L),
            )
        }
    }

    /**
     * The chosen storage root, falling back to internal if the SD card has been pulled
     * since the setting was made.
     */
    fun activeStorage(context: Context): StorageOption? {
        val options = storageOptions(context)
        if (options.isEmpty()) return null
        val wanted = Prefs(context).current.dashStorageIndex
        return options.firstOrNull { it.index == wanted } ?: options.first()
    }

    private fun root(context: Context): File =
        activeStorage(context)?.dir
            ?: File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), ".")

    fun loopDir(context: Context): File =
        File(root(context), "dashcam").apply { mkdirs() }

    fun savedDir(context: Context): File =
        File(root(context), "dashcam/saved").apply { mkdirs() }

    fun refreshClips(context: Context) {
        val loop = loopDir(context).listFiles().orEmpty().filter { it.isFile }
        val saved = savedDir(context).listFiles().orEmpty().filter { it.isFile }

        val clips = (loop.map { it to false } + saved.map { it to true })
            .filter { it.first.name.endsWith(".mp4", ignoreCase = true) }
            .map { (f, locked) ->
                Clip(
                    file = f,
                    startedAt = f.lastModified(),
                    sizeBytes = f.length(),
                    locked = locked,
                )
            }
            .sortedByDescending { it.startedAt }

        _state.update {
            it.copy(
                clips = clips,
                loopBytes = loop.sumOf { f -> f.length() },
                savedBytes = saved.sumOf { f -> f.length() },
            )
        }
    }

    /** Moves a clip out of the loop folder so pruning cannot reclaim it. */
    fun setLocked(context: Context, clip: Clip, locked: Boolean): Boolean {
        if (clip.locked == locked) return true
        val target = File(if (locked) savedDir(context) else loopDir(context), clip.file.name)
        val ok = runCatching { clip.file.renameTo(target) }.getOrDefault(false)
        refreshClips(context)
        return ok
    }

    fun delete(context: Context, clip: Clip): Boolean {
        val ok = runCatching { clip.file.delete() }.getOrDefault(false)
        refreshClips(context)
        return ok
    }

    /**
     * Deletes oldest unlocked clips until the loop folder fits the cap. Locked clips
     * live in a separate folder and are never touched.
     */
    internal fun prune(context: Context, maxBytes: Long) {
        val files = loopDir(context).listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) total -= size
        }
        refreshClips(context)
    }

    // ---------------------------------------------------------------- permissions

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val requiredPermissions: List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1e6)
    bytes >= 1_000L -> "${bytes / 1000} KB"
    else -> "$bytes B"
}
