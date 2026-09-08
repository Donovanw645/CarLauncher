package com.donovan.carlauncher.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.media.BrowseItem
import com.donovan.carlauncher.media.Crumb
import com.donovan.carlauncher.media.QueueEntry
import com.donovan.carlauncher.ui.components.AudioVisualizer
import com.donovan.carlauncher.ui.components.CarCard
import com.donovan.carlauncher.ui.components.EmptyHint
import com.donovan.carlauncher.ui.components.RoundControl
import com.donovan.carlauncher.ui.components.SectionLabel
import com.donovan.carlauncher.ui.components.formatClockMs
import kotlinx.coroutines.delay

private enum class LibraryTab { BROWSE, QUEUE }

@Composable
fun MediaScreen(
    car: CarController,
    onOpenNotificationSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayerPanel(
            car = car,
            onOpenNotificationSettings = onOpenNotificationSettings,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
        LibraryPanel(
            car = car,
            modifier = Modifier.weight(1.1f).fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------- player side

@Composable
private fun PlayerPanel(
    car: CarController,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by car.mediaRemote.nowPlaying.collectAsStateWithLifecycle()
    val hasAccess by car.mediaRemote.hasAccess.collectAsStateWithLifecycle()
    val sources by car.mediaRemote.sources.collectAsStateWithLifecycle()
    val volume by car.mediaRemote.volume.collectAsStateWithLifecycle()

    CarCard(modifier = modifier, contentPadding = 18.dp) {
        Column(Modifier.fillMaxSize()) {
            if (!hasAccess) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Notification access is off",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Without it Android will not let this launcher see or control " +
                            "other music apps. Nothing is read from your notifications.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onOpenNotificationSettings) { Text("Open settings") }
                }
                return@Column
            }

            if (sources.size > 1) {
                SectionLabel("Playing from")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (src in sources) {
                        SourceChip(
                            label = car.apps.labelFor(src.packageName),
                            active = src.packageName == now?.packageName,
                            playing = src.isPlaying,
                            onClick = { car.mediaRemote.selectSource(src.packageName) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            val np = now
            if (np == null) {
                EmptyHint(
                    "Nothing is playing. Pick something from your library on the right, " +
                        "or start a track in any music app."
                )
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val art = np.artwork
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = "Album art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                np.title.ifBlank { "Unknown track" },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(np.artist, np.album).filter { it.isNotBlank() }.joinToString(" - ")
                    .ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))
            PositionBar(
                positionMs = np.positionMs,
                durationMs = np.durationMs,
                enabled = np.canSeek,
                onSeek = car.mediaRemote::seekTo,
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControl(
                    Icons.Rounded.SkipPrevious, "Previous track",
                    car.mediaRemote::previous, enabled = np.canSkipPrev, size = 58.dp,
                )
                Spacer(Modifier.width(14.dp))
                RoundControl(
                    icon = if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (np.isPlaying) "Pause" else "Play",
                    onClick = car.mediaRemote::playPause,
                    filled = true,
                    size = 82.dp,
                )
                Spacer(Modifier.width(14.dp))
                RoundControl(
                    Icons.Rounded.SkipNext, "Next track",
                    car.mediaRemote::next, enabled = np.canSkipNext, size = 58.dp,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.VolumeDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Slider(
                    value = volume.fraction,
                    onValueChange = { car.mediaRemote.setVolume((it * volume.max).toInt()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        thumbColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Icon(
                    Icons.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, active: Boolean, playing: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(
                if (active) scheme.primaryContainer else scheme.surfaceVariant,
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing) {
            Box(Modifier.size(8.dp).background(scheme.primary, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) scheme.primary else scheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun PositionBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }

    val live = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shown = if (scrubbing) scrubValue else live

    Column {
        Slider(
            value = shown,
            onValueChange = { scrubbing = true; scrubValue = it },
            onValueChangeFinished = {
                onSeek((scrubValue * durationMs).toLong())
                scrubbing = false
            },
            enabled = enabled,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                thumbColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatClockMs(if (scrubbing) (scrubValue * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (durationMs > 0) formatClockMs(durationMs) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------- library side

@Composable
private fun LibraryPanel(car: CarController, modifier: Modifier = Modifier) {
    val browse by car.browser.state.collectAsStateWithLifecycle()
    val queue by car.mediaRemote.queue.collectAsStateWithLifecycle()
    val queueTitle by car.mediaRemote.queueTitle.collectAsStateWithLifecycle()
    val nowPlaying by car.mediaRemote.nowPlaying.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(LibraryTab.BROWSE) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { car.browser.refreshApps() }

    // Debounced so a fast typist does not fire a search per keystroke at the music app.
    LaunchedEffect(query, browse.connected) {
        val q = query.trim()
        if (q.isEmpty()) {
            car.browser.clearSearch()
            return@LaunchedEffect
        }
        delay(450)
        car.browser.search(q)
    }

    CarCard(modifier = modifier, contentPadding = 16.dp) {
        Column(Modifier.fillMaxSize()) {
            // --- header: source picker + tab switch
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (tab == LibraryTab.BROWSE) "Apple Music" else "Library",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                TabPill("Browse", tab == LibraryTab.BROWSE) { tab = LibraryTab.BROWSE }
                Spacer(Modifier.width(8.dp))
                TabPill("Queue", tab == LibraryTab.QUEUE) { tab = LibraryTab.QUEUE }
            }

            Spacer(Modifier.height(12.dp))

            Column(Modifier.weight(1f)) {
                when (tab) {
                    LibraryTab.QUEUE -> QueueList(
                        entries = queue,
                        title = queueTitle,
                        onPlay = { car.mediaRemote.skipToQueueItem(it.id) },
                    )

                    LibraryTab.BROWSE -> BrowseSection(
                        car = car,
                        query = query,
                        onQueryChange = { query = it },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            AudioVisualizer(
                playing = nowPlaying?.isPlaying == true,
                accent = MaterialTheme.colorScheme.primary,
                secondary = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
    }
}

@Composable
private fun TabPill(label: String, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) scheme.primary else scheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                if (active) scheme.primaryContainer else scheme.surfaceVariant,
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun BrowseSection(
    car: CarController,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val browse by car.browser.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    // Only Apple Music is offered, so connect to it without making the driver pick.
    LaunchedEffect(browse.apps) {
        val target = browse.apps.firstOrNull()
        if (target != null && browse.connected == null) car.browser.connect(target)
    }

    when {
        browse.apps.isEmpty() -> {
            EmptyHint(
                if (car.browser.appleMusicInstalled()) {
                    "Apple Music is installed but is not offering a library on this " +
                        "tablet. Open it once and sign in, then come back."
                } else {
                    "Apple Music is not installed on this tablet. Install it from the " +
                        "Play Store and sign in to use this tab."
                }
            )
            return
        }

        browse.connected == null || browse.connecting -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        browse.refused -> {
            RefusedNotice(car = car, app = browse.connected)
            return
        }
    }

    // --- search box
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                if (browse.searchSupported) "Search playlists, albums, artists"
                else "Search (this app may not support it)"
            )
        },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (browse.searching) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceVariant,
            unfocusedContainerColor = scheme.surfaceVariant,
        ),
    )

    Spacer(Modifier.height(10.dp))

    // --- breadcrumb
    val results = browse.searchResults
    if (results == null && browse.path.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Back",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier
                    .clickable { car.browser.up() }
                    .background(scheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                browse.path.joinToString("  ›  ") { it.title },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    val shown = results ?: browse.items

    when {
        browse.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        results != null && results.isEmpty() -> EmptyHint(
            "No matches. ${browse.connected?.label ?: "This app"} may not support search - " +
                "try browsing instead."
        )

        shown.isEmpty() -> EmptyHint(
            browse.error ?: "Nothing here. Some apps only fill this in once you are signed in."
        )

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.mediaId ?: it.title }) { item ->
                BrowseRow(item) {
                    if (item.browsable) {
                        car.browser.open(Crumb(item.mediaId ?: return@BrowseRow, item.title))
                    } else {
                        car.browser.play(item)
                    }
                }
            }
        }
    }
}

/**
 * Shown when a music app allowlists its browse clients (Spotify, YouTube Music and
 * Apple Music all do). Browsing is off the table, but asking the app to play something
 * by name still works, so that is what this offers.
 */
@Composable
private fun RefusedNotice(car: CarController, app: com.donovan.carlauncher.media.BrowseApp?) {
    val scheme = MaterialTheme.colorScheme
    var ask by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val label = app?.label ?: "That app"

    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "$label keeps its library private",
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "It only lets approved clients like Android Auto list its playlists. Transport " +
                "controls, album art and the Queue tab all still work - and you can ask it " +
                "to play something by name without leaving the launcher.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ask,
                onValueChange = { ask = it; note = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Playlist, album or artist") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceVariant,
                    unfocusedContainerColor = scheme.surfaceVariant,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = ask.isNotBlank() && app != null,
                onClick = {
                    val target = app ?: return@Button
                    note = when {
                        // Best case: the app already owns a session, so this stays in-app.
                        car.mediaRemote.hasSession(target.packageName) -> {
                            car.mediaRemote.selectSource(target.packageName)
                            car.mediaRemote.playFromSearch(ask)
                            "Asked ${target.label} to play \"$ask\""
                        }

                        car.browser.playViaIntent(target, ask) ->
                            "Sent \"$ask\" to ${target.label} - it may open briefly"

                        else -> "${target.label} would not take that request"
                    }
                },
            ) { Text("Play") }
        }
        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
        }
    }
}

@Composable
private fun BrowseRow(item: BrowseItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.iconUri != null) {
                AsyncImage(
                    model = item.iconUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    if (item.browsable) Icons.Rounded.Folder else Icons.Rounded.Album,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            if (item.browsable) Icons.Rounded.ChevronRight else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QueueList(entries: List<QueueEntry>, title: String?, onPlay: (QueueEntry) -> Unit) {
    if (entries.isEmpty()) {
        EmptyHint(
            "The playing app is not sharing a queue right now. Start a playlist and it " +
                "usually shows up here."
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (!title.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                QueueRow(index + 1, entry) { onPlay(entry) }
            }
        }
    }
}

@Composable
private fun QueueRow(number: Int, entry: QueueEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (entry.isCurrent) scheme.primaryContainer else scheme.surface,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (entry.isCurrent) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Now playing",
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (entry.isCurrent) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
