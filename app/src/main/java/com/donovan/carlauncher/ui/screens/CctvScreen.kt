package com.donovan.carlauncher.ui.screens

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.nav.LatLon
import com.donovan.carlauncher.traffic.CameraViewMode
import com.donovan.carlauncher.traffic.CatalogState
import com.donovan.carlauncher.traffic.NearbyCamera
import com.donovan.carlauncher.traffic.TrafficCamera
import com.donovan.carlauncher.ui.components.CarCard
import com.donovan.carlauncher.ui.components.EmptyHint
import com.donovan.carlauncher.ui.components.RoundControl

/**
 * Public Caltrans traffic cameras near the car.
 *
 * The rule that shapes this screen: opening the tab must not open any video. Arriving
 * here costs a list and a grid of one-minute-old JPEGs, nothing more. A stream is
 * created only when a camera is picked, and it is torn down the moment the selection
 * changes, the mode goes back to Still, or the tab is left.
 */
@Composable
fun CctvScreen(car: CarController) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val location by car.location.location.collectAsStateWithLifecycle()
    val state by car.traffic.state.collectAsStateWithLifecycle()
    val selected by car.traffic.selected.collectAsStateWithLifecycle()
    val mode by car.traffic.mode.collectAsStateWithLifecycle()
    val fullscreen by car.traffic.fullscreen.collectAsStateWithLifecycle()
    val tick by car.traffic.thumbTick.collectAsStateWithLifecycle()

    val here = location?.let { LatLon(it.latitude, it.longitude) }
    LaunchedEffect(here?.lat, here?.lon, settings.cctvRadiusMiles) {
        car.traffic.ensureLoaded(here, settings.cctvRadiusMiles)
    }

    val camera = selected
    if (fullscreen && camera != null) {
        FullscreenViewer(
            camera = camera,
            mode = mode,
            tick = tick,
            onClose = { car.traffic.setFullscreen(false) },
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CameraListPanel(
            state = state,
            selectedId = camera?.id,
            radiusMiles = settings.cctvRadiusMiles,
            tick = tick,
            hasFix = here != null,
            onSelect = { car.traffic.select(it) },
            onRefresh = { car.traffic.refresh(here, settings.cctvRadiusMiles) },
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
        ViewerPanel(
            camera = camera,
            mode = mode,
            tick = tick,
            onSetMode = { car.traffic.setMode(it) },
            onFullscreen = { car.traffic.setFullscreen(true) },
            modifier = Modifier.weight(1.1f).fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------------- list

@Composable
private fun CameraListPanel(
    state: CatalogState,
    selectedId: String?,
    radiusMiles: Int,
    tick: Long,
    hasFix: Boolean,
    onSelect: (TrafficCamera) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // A camera picked by tapping its dot on the map is usually nowhere near the top of
    // the list, so bring it into view - but leave the scroll alone when the row is
    // already on screen, which is the case for every tap made in the list itself.
    LaunchedEffect(selectedId, state) {
        val id = selectedId ?: return@LaunchedEffect
        val cams = (state as? CatalogState.Ready)?.cameras ?: return@LaunchedEffect
        val index = cams.indexOfFirst { it.camera.id == id }
        if (index < 0) return@LaunchedEffect
        val onScreen = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!onScreen) listState.animateScrollToItem(index)
    }

    CarCard(modifier = modifier, contentPadding = 16.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Traffic cameras",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when (state) {
                            is CatalogState.Ready ->
                                "${state.cameras.size} within $radiusMiles mi"
                            is CatalogState.Loading -> "Loading from Caltrans…"
                            else -> "Within $radiusMiles mi"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RoundControl(Icons.Rounded.Refresh, "Refresh", onRefresh, size = 44.dp)
            }
            Spacer(Modifier.height(10.dp))

            when {
                !hasFix -> EmptyHint("Waiting for a GPS fix to find cameras near you.")

                state is CatalogState.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }

                state is CatalogState.Failed ->
                    EmptyHint("Could not load cameras: ${state.message}")

                state is CatalogState.Ready && state.cameras.isEmpty() ->
                    EmptyHint("No Caltrans cameras within $radiusMiles miles.")

                state is CatalogState.Ready ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.cameras, key = { it.camera.id }) { entry ->
                            CameraRow(
                                entry = entry,
                                selected = entry.camera.id == selectedId,
                                tick = tick,
                                onClick = { onSelect(entry.camera) },
                            )
                        }
                    }

                else -> EmptyHint("Pull the camera list to get started.")
            }
        }
    }
}

@Composable
private fun CameraRow(
    entry: NearbyCamera,
    selected: Boolean,
    tick: Long,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val cam = entry.camera
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(108.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = cam.stillUrl.withTick(tick),
                contentDescription = cam.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cam.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                cam.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "%.1f mi".format(entry.miles),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            Icon(
                if (cam.hasLive) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                contentDescription = if (cam.hasLive) "Has live video" else "Snapshot only",
                tint = if (cam.hasLive) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// -------------------------------------------------------------------------- viewer

@Composable
private fun ViewerPanel(
    camera: TrafficCamera?,
    mode: CameraViewMode,
    tick: Long,
    onSetMode: (CameraViewMode) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CarCard(modifier = modifier, contentPadding = 16.dp) {
        if (camera == null) {
            EmptyHint("Pick a camera from the list to see its feed.")
            return@CarCard
        }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        camera.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        camera.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ModeToggle(camera, mode, onSetMode)
                Spacer(Modifier.width(8.dp))
                RoundControl(Icons.Rounded.OpenInFull, "Fullscreen", onFullscreen, size = 44.dp)
            }
            Spacer(Modifier.height(12.dp))
            CameraView(
                camera = camera,
                mode = mode,
                tick = tick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ModeToggle(
    camera: TrafficCamera,
    mode: CameraViewMode,
    onSetMode: (CameraViewMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModePill("Still", mode == CameraViewMode.STILL, true) {
            onSetMode(CameraViewMode.STILL)
        }
        // Roughly a third of Caltrans cameras publish no video, so the Live pill is
        // shown greyed rather than hidden - otherwise the control appears to move
        // around as you go down the list.
        ModePill("Live", mode == CameraViewMode.LIVE, camera.hasLive) {
            onSetMode(CameraViewMode.LIVE)
        }
    }
}

@Composable
private fun ModePill(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) scheme.primaryContainer else scheme.surfaceVariant)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                active -> scheme.primary
                else -> scheme.onSurfaceVariant
            },
        )
    }
}

/** The picture itself - a refreshing snapshot, or HLS video when asked for. */
@Composable
private fun CameraView(
    camera: TrafficCamera,
    mode: CameraViewMode,
    tick: Long,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val stream = camera.streamUrl
        if (mode == CameraViewMode.LIVE && stream != null) {
            HlsVideo(stream)
        } else {
            AsyncImage(
                model = camera.stillUrl.withTick(tick),
                contentDescription = camera.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun HlsVideo(url: String) {
    val context = LocalContext.current
    var error by remember(url) { mutableStateOf<String?>(null) }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            // Muted, and no audio focus request: a traffic camera must never duck or
            // pause whatever is playing over Bluetooth.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = e.errorCodeName
                }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    Box(Modifier.fillMaxSize(), Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
        )
        error?.let {
            Text(
                "Stream unavailable ($it)",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB4AB),
            )
        }
    }
}

// ---------------------------------------------------------------------- fullscreen

@Composable
private fun FullscreenViewer(
    camera: TrafficCamera,
    mode: CameraViewMode,
    tick: Long,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraView(
            camera = camera,
            mode = mode,
            tick = tick,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .background(Color(0xCC0B0E13), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                camera.name,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(48.dp)
                .background(Color(0xCC0B0E13), CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close fullscreen",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Caltrans serves snapshots with caching headers that would otherwise leave the same
 * frame on screen forever. The minute counter changes the URL, which is what actually
 * forces a new fetch.
 */
private fun String.withTick(tick: Long): String =
    if (contains('?')) "$this&t=$tick" else "$this?t=$tick"
