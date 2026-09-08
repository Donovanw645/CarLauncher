package com.donovan.carlauncher.media

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
    val canSeek: Boolean,
    val shuffleOn: Boolean,
    val repeatMode: Int, // PlaybackState.REPEAT_MODE_*
)

data class MediaSourceInfo(
    val packageName: String,
    val isPlaying: Boolean,
)

/** One entry of the playing app's current queue - its playlist, in other words. */
data class QueueEntry(
    val id: Long,
    val title: String,
    val subtitle: String,
    val iconUri: Uri?,
    val isCurrent: Boolean,
)

data class VolumeState(val level: Int, val max: Int) {
    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max
}

/**
 * Drives whatever app currently owns a media session, so playback is controlled from
 * the launcher rather than by switching to Spotify's own UI.
 *
 * Requires notification-listener access (see [CarNotificationListener]).
 */
class MediaRemote(private val context: Context) {

    private val msm =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _sources = MutableStateFlow<List<MediaSourceInfo>>(emptyList())
    val sources: StateFlow<List<MediaSourceInfo>> = _sources.asStateFlow()

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _volume = MutableStateFlow(readVolume())
    val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _queueTitle = MutableStateFlow<String?>(null)
    val queueTitle: StateFlow<String?> = _queueTitle.asStateFlow()

    private var controllers: List<MediaController> = emptyList()
    private val callbacks = HashMap<String, MediaController.Callback>()

    /** Package the user explicitly picked from the source chips, if it is still alive. */
    private var pinnedPackage: String? = null

    private var ticker: Job? = null
    private var started = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list.orEmpty()) }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            _volume.value = readVolume()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (started) return
        started = true

        CarNotificationListener.onConnectionChanged = { attach() }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver,
            )
        }
        attach()

        ticker = scope.launch {
            while (true) {
                delay(500)
                refreshNowPlaying()
                _volume.value = readVolume()
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        ticker?.cancel()
        ticker = null
        CarNotificationListener.onConnectionChanged = null
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { context.contentResolver.unregisterContentObserver(volumeObserver) }
        unbindAll()
    }

    /** Call after returning from the notification-access settings screen. */
    fun attach() {
        val granted = CarNotificationListener.isEnabled(context)
        _hasAccess.value = granted
        if (!granted) {
            unbindAll()
            _nowPlaying.value = null
            _sources.value = emptyList()
            return
        }
        val component = CarNotificationListener.componentName(context)
        runCatching {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            bind(msm.getActiveSessions(component))
        }.onFailure {
            // Access was revoked between the check and the call.
            _hasAccess.value = false
        }
    }

    private fun bind(list: List<MediaController>) {
        unbindAll()
        controllers = list
        for (c in list) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refreshNowPlaying()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refreshNowPlaying()
                override fun onSessionDestroyed() = attach()
            }
            callbacks[c.sessionToken.toString()] = cb
            runCatching { c.registerCallback(cb, handler) }
        }
        refreshNowPlaying()
    }

    private fun unbindAll() {
        for (c in controllers) {
            val cb = callbacks[c.sessionToken.toString()] ?: continue
            runCatching { c.unregisterCallback(cb) }
        }
        callbacks.clear()
        controllers = emptyList()
    }

    // ------------------------------------------------------------------ state

    private fun activeController(): MediaController? {
        if (controllers.isEmpty()) return null
        pinnedPackage?.let { pinned ->
            controllers.firstOrNull { it.packageName == pinned }?.let { return it }
        }
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.let { return it }
        controllers.firstOrNull { hasContent(it) }?.let { return it }
        return controllers.firstOrNull()
    }

    private fun hasContent(c: MediaController): Boolean {
        val m = c.metadata ?: return false
        return !m.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
    }

    private fun refreshNowPlaying() {
        _sources.value = controllers
            .filter { hasContent(it) || it.playbackState != null }
            .map {
                MediaSourceInfo(
                    packageName = it.packageName,
                    isPlaying = it.playbackState?.state == PlaybackState.STATE_PLAYING,
                )
            }
            .distinctBy { it.packageName }

        val c = activeController()
        if (c == null) {
            _nowPlaying.value = null
            _queue.value = emptyList()
            _queueTitle.value = null
            return
        }
        refreshQueue(c)
        val meta = c.metadata
        val state = c.playbackState
        val actions = state?.actions ?: 0L

        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.let { ps ->
            if (playing) {
                val drift = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
                ps.position + (drift * ps.playbackSpeed).toLong()
            } else {
                ps.position
            }
        } ?: 0L

        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = album,
            artwork = art,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = position.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE),
            isPlaying = playing,
            isBuffering = state?.state == PlaybackState.STATE_BUFFERING ||
                state?.state == PlaybackState.STATE_CONNECTING,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L && duration > 0,
            shuffleOn = runCatching {
                c.playbackState?.extras?.getBoolean("shuffle", false) ?: false
            }.getOrDefault(false),
            repeatMode = 0,
        )
    }

    private fun refreshQueue(c: MediaController) {
        val activeId = c.playbackState?.activeQueueItemId ?: -1L
        val entries = runCatching {
            c.queue?.map { item ->
                val d = item.description
                QueueEntry(
                    id = item.queueId,
                    title = d.title?.toString().orEmpty().ifBlank { "Untitled" },
                    subtitle = d.subtitle?.toString().orEmpty(),
                    iconUri = d.iconUri,
                    isCurrent = item.queueId == activeId,
                )
            }
        }.getOrNull().orEmpty()
        _queue.value = entries
        _queueTitle.value = runCatching { c.queueTitle?.toString() }.getOrNull()
    }

    // ------------------------------------------------------------------ transport

    fun skipToQueueItem(id: Long) = withTransport { it.skipToQueueItem(id) }

    /**
     * Voice-assistant style "play this". Works against any app that owns a session,
     * including ones that refuse to let us browse their library.
     */
    fun playFromSearch(query: String) = withTransport { it.playFromSearch(query, null) }

    fun hasSession(packageName: String): Boolean =
        controllers.any { it.packageName == packageName }

    fun selectSource(packageName: String?) {
        pinnedPackage = packageName
        refreshNowPlaying()
    }

    private inline fun withTransport(block: (MediaController.TransportControls) -> Unit) {
        activeController()?.let { runCatching { block(it.transportControls) } }
        handler.postDelayed({ refreshNowPlaying() }, 120)
    }

    fun playPause() {
        val playing = _nowPlaying.value?.isPlaying == true
        withTransport { if (playing) it.pause() else it.play() }
    }

    fun next() = withTransport { it.skipToNext() }

    fun previous() = withTransport { it.skipToPrevious() }

    fun seekTo(ms: Long) = withTransport { it.seekTo(ms) }

    fun stopPlayback() = withTransport { it.stop() }

    /** Nudges playback forward/back for podcasts and long tracks. */
    fun jump(deltaMs: Long) {
        val np = _nowPlaying.value ?: return
        val target = (np.positionMs + deltaMs).coerceIn(0L, maxOf(np.durationMs, 0L))
        seekTo(target)
    }

    // ------------------------------------------------------------------ volume

    private fun readVolume(): VolumeState = runCatching {
        VolumeState(
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }.getOrDefault(VolumeState(0, 0))

    fun setVolume(level: Int) {
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                level.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
                0,
            )
        }
        _volume.value = readVolume()
    }
}
