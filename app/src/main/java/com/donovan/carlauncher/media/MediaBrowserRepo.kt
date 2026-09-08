package com.donovan.carlauncher.media

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A music app that publishes a browsable library (the Android Auto mechanism). */
data class BrowseApp(
    val packageName: String,
    val serviceClass: String,
    val label: String,
)

data class BrowseItem(
    val mediaId: String?,
    val title: String,
    val subtitle: String,
    val iconUri: Uri?,
    val browsable: Boolean,
    val playable: Boolean,
)

data class Crumb(val mediaId: String, val title: String)

data class BrowseState(
    val apps: List<BrowseApp> = emptyList(),
    val connected: BrowseApp? = null,
    val connecting: Boolean = false,
    /** Set when the app refused us, which allowlisted apps legitimately do. */
    val refused: Boolean = false,
    val error: String? = null,
    val path: List<Crumb> = emptyList(),
    val items: List<BrowseItem> = emptyList(),
    val loading: Boolean = false,
    val searchSupported: Boolean = false,
    val searchResults: List<BrowseItem>? = null,
    val searching: Boolean = false,
)

/**
 * Browses another music app's library from inside the launcher.
 *
 * Android's only cross-app library mechanism is [MediaBrowserCompat] - the same one
 * Android Auto uses. Apps opt in by shipping a MediaBrowserService, and many of them
 * check the caller against an allowlist of known clients (Android Auto, Assistant,
 * Wear). When a check fails we get [BrowseState.refused] rather than a crash, and the
 * transport controls in [MediaRemote] keep working regardless.
 */
class MediaBrowserRepo(private val context: Context) {

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private var browser: MediaBrowserCompat? = null
    private var subscribedTo: String? = null
    private var rootId: String? = null

    // ------------------------------------------------------------------ discovery

    /**
     * Music apps we are willing to browse. Deliberately just Apple Music - this is a
     * personal launcher for an Apple Music subscriber, and listing Bluetooth, Google
     * and YouTube alongside it was only ever noise.
     */
    private val allowedPackages = setOf(APPLE_MUSIC)

    /** Apple Music's browsable library service, if the app is installed. */
    fun refreshApps() {
        val pm = context.packageManager
        val intent = Intent(SERVICE_INTERFACE)
        val found = runCatching { pm.queryIntentServices(intent, 0) }.getOrNull().orEmpty()
        val apps = found.mapNotNull { info ->
            val svc = info.serviceInfo ?: return@mapNotNull null
            if (svc.packageName !in allowedPackages) return@mapNotNull null
            BrowseApp(
                packageName = svc.packageName,
                serviceClass = svc.name,
                label = runCatching { svc.applicationInfo.loadLabel(pm).toString() }
                    .getOrNull() ?: "Apple Music",
            )
        }.distinctBy { it.packageName }

        _state.update { it.copy(apps = apps) }
    }

    /** True when Apple Music is installed at all, browsable or not. */
    fun appleMusicInstalled(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(APPLE_MUSIC, 0)
        true
    }.getOrDefault(false)

    // ----------------------------------------------------------------- connection

    fun connect(app: BrowseApp) {
        disconnect()
        _state.update {
            it.copy(
                connected = app,
                connecting = true,
                refused = false,
                error = null,
                path = emptyList(),
                items = emptyList(),
                searchResults = null,
            )
        }

        val callback = object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                val root = runCatching { b.root }.getOrNull()
                if (root.isNullOrEmpty()) {
                    _state.update {
                        it.copy(
                            connecting = false,
                            error = "${app.label} did not offer a library root",
                        )
                    }
                    return
                }
                rootId = root
                val searchSupported = runCatching {
                    b.extras?.getBoolean(EXTRA_SEARCH_SUPPORTED, false) ?: false
                }.getOrDefault(false)
                _state.update { it.copy(connecting = false, searchSupported = searchSupported) }
                open(Crumb(root, app.label))
            }

            override fun onConnectionFailed() {
                _state.update {
                    it.copy(
                        connecting = false,
                        refused = true,
                        error = "${app.label} does not allow other apps to browse its library",
                    )
                }
            }

            override fun onConnectionSuspended() {
                _state.update { it.copy(connecting = false, error = "Connection lost") }
            }
        }

        browser = runCatching {
            MediaBrowserCompat(
                context,
                ComponentName(app.packageName, app.serviceClass),
                callback,
                null,
            ).also { it.connect() }
        }.getOrElse { e ->
            _state.update {
                it.copy(connecting = false, error = e.message ?: "Could not connect")
            }
            null
        }
    }

    fun disconnect() {
        val b = browser
        subscribedTo?.let { id -> runCatching { b?.unsubscribe(id) } }
        subscribedTo = null
        rootId = null
        runCatching { b?.disconnect() }
        browser = null
        _state.update {
            it.copy(
                connected = null,
                connecting = false,
                path = emptyList(),
                items = emptyList(),
                searchResults = null,
                searchSupported = false,
                error = null,
                refused = false,
            )
        }
    }

    // ------------------------------------------------------------------ navigation

    /** Descends into a browsable node and shows its children. */
    fun open(crumb: Crumb) {
        val b = browser ?: return
        if (!b.isConnected) return

        subscribedTo?.let { runCatching { b.unsubscribe(it) } }
        subscribedTo = crumb.mediaId

        _state.update { s ->
            val existing = s.path.indexOfFirst { it.mediaId == crumb.mediaId }
            val path = if (existing >= 0) s.path.take(existing + 1) else s.path + crumb
            s.copy(path = path, loading = true, items = emptyList(), searchResults = null)
        }

        val cb = object : MediaBrowserCompat.SubscriptionCallback() {
            override fun onChildrenLoaded(
                parentId: String,
                children: MutableList<MediaBrowserCompat.MediaItem>,
            ) {
                _state.update {
                    it.copy(loading = false, items = children.map { c -> c.toBrowseItem() })
                }
            }

            override fun onError(parentId: String) {
                _state.update { it.copy(loading = false, error = "Could not load that folder") }
            }
        }
        runCatching { b.subscribe(crumb.mediaId, cb) }
            .onFailure { _state.update { s -> s.copy(loading = false, error = it.message) } }
    }

    fun up() {
        val path = _state.value.path
        if (path.size < 2) return
        open(path[path.size - 2])
    }

    fun home() {
        val root = rootId ?: return
        val label = _state.value.connected?.label ?: "Library"
        _state.update { it.copy(path = emptyList()) }
        open(Crumb(root, label))
    }

    // --------------------------------------------------------------------- search

    fun search(query: String) {
        val b = browser
        val q = query.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(searchResults = null, searching = false) }
            return
        }
        if (b == null || !b.isConnected) return

        _state.update { it.copy(searching = true) }
        val cb = object : MediaBrowserCompat.SearchCallback() {
            override fun onSearchResult(
                query: String,
                extras: Bundle?,
                items: MutableList<MediaBrowserCompat.MediaItem>,
            ) {
                _state.update {
                    it.copy(searching = false, searchResults = items.map { i -> i.toBrowseItem() })
                }
            }

            override fun onError(query: String, extras: Bundle?) {
                _state.update {
                    it.copy(searching = false, searchResults = emptyList())
                }
            }
        }
        runCatching { b.search(q, null, cb) }
            .onFailure { _state.update { s -> s.copy(searching = false, searchResults = emptyList()) } }
    }

    fun clearSearch() {
        _state.update { it.copy(searchResults = null, searching = false) }
    }

    // -------------------------------------------------------------------- playback

    /** Plays a leaf item through the owning app's own session. */
    fun play(item: BrowseItem): Boolean {
        val id = item.mediaId ?: return false
        val controls = transportControls() ?: return false
        return runCatching { controls.playFromMediaId(id, null); true }.getOrDefault(false)
    }

    /**
     * Asks the app to play whatever best matches the text. Works on apps that decline
     * browsing but still accept a voice-style search, which is most of them.
     */
    fun playFromSearch(query: String): Boolean {
        val controls = transportControls() ?: return false
        return runCatching { controls.playFromSearch(query, null); true }.getOrDefault(false)
    }

    /**
     * Last resort for an app that neither lets us browse nor has a live session: fire
     * the standard "play this" intent at it. That app may come to the foreground for a
     * moment, which is why it is only offered once the in-app routes are exhausted.
     */
    fun playViaIntent(app: BrowseApp, query: String): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(app.packageName)
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun transportControls(): MediaControllerCompat.TransportControls? {
        val token = browser?.takeIf { it.isConnected }?.sessionToken ?: return null
        return runCatching {
            MediaControllerCompat(context, token).transportControls
        }.getOrNull()
    }

    companion object {
        const val APPLE_MUSIC = "com.apple.android.music"
        private const val SERVICE_INTERFACE = "android.media.browse.MediaBrowserService"
        private const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}

private fun MediaBrowserCompat.MediaItem.toBrowseItem(): BrowseItem {
    val d = description
    return BrowseItem(
        mediaId = mediaId,
        title = d.title?.toString().orEmpty().ifBlank { "Untitled" },
        subtitle = listOfNotNull(
            d.subtitle?.toString()?.takeIf { it.isNotBlank() },
            d.description?.toString()?.takeIf { it.isNotBlank() },
        ).joinToString(" - "),
        iconUri = d.iconUri,
        browsable = isBrowsable,
        playable = isPlayable,
    )
}
