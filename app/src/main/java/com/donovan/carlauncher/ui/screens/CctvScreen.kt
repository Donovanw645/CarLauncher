package com.donovan.carlauncher.ui.screens

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.cctv.CameraStream
import com.donovan.carlauncher.cctv.StreamKind
import com.donovan.carlauncher.cctv.kindFor
import com.donovan.carlauncher.data.CameraFeed
import com.donovan.carlauncher.ui.components.CarCard

/**
 * An NVR wall for the cameras on the car. Streams only run while this tab is on screen -
 * every tile stops decoding the moment it leaves composition.
 */
@Composable
fun CctvScreen(car: CarController) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val feeds = settings.activeCameras
    var solo by remember { mutableStateOf<String?>(null) }

    if (feeds.isEmpty()) {
        CarCard(modifier = Modifier.fillMaxSize(), contentPadding = 28.dp) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No cameras set up yet",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add them in Settings › Cameras. Point each one at your streaming " +
                        "server, for example http://192.168.1.50:8080/?action=stream for " +
                        "mjpg-streamer, or rtsp://192.168.1.50:8554/front for MediaMTX. " +
                        "There is a Test button next to each.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val soloFeed = feeds.firstOrNull { it.url == solo }
    val shown = if (soloFeed != null) listOf(soloFeed) else {
        feeds.take(settings.cctvLayout.coerceIn(1, 4))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cols = if (shown.size <= 1) 1 else 2
        val rows = if (shown.size <= 2) 1 else 2
        val gap = 10.dp

        // Pixel size of one tile, so frames get downscaled to what is actually drawn.
        val tileW = with(density) { ((maxWidth - gap * (cols - 1)) / cols).roundToPx() }
        val tileH = with(density) { ((maxHeight - gap * (rows - 1)) / rows).roundToPx() }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (row in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (col in 0 until cols) {
                        val index = row * cols + col
                        val feed = shown.getOrNull(index)
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            if (feed != null) {
                                CameraTile(
                                    feed = feed,
                                    targetWidth = tileW,
                                    targetHeight = tileH,
                                    soloed = soloFeed != null,
                                    onToggleSolo = {
                                        solo = if (soloFeed != null) null else feed.url
                                    },
                                )
                            } else {
                                CarCard(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = 0.dp,
                                ) {
                                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                                        Text(
                                            "Empty slot",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    feed: CameraFeed,
    targetWidth: Int,
    targetHeight: Int,
    soloed: Boolean,
    onToggleSolo: () -> Unit,
) {
    CarCard(modifier = Modifier.fillMaxSize(), contentPadding = 0.dp) {
        when (kindFor(feed.url)) {
            StreamKind.VIDEO -> VideoTile(feed)
            else -> MjpegTile(feed, targetWidth, targetHeight)
        }

        // Name plate.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .background(Color(0xCC0B0E13), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                feed.name.ifBlank { "Camera" },
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Expand / collapse.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(40.dp)
                .background(Color(0xCC0B0E13), CircleShape)
                .clickable { onToggleSolo() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (soloed) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                contentDescription = if (soloed) "Back to grid" else "Show only this camera",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------------- MJPEG tile

@Composable
private fun MjpegTile(feed: CameraFeed, targetWidth: Int, targetHeight: Int) {
    val scope = rememberCoroutineScope()
    val stream = remember(feed.url) { CameraStream(feed) }

    DisposableEffect(stream, targetWidth, targetHeight) {
        stream.start(scope, targetWidth, targetHeight)
        onDispose { stream.stop() }
    }

    val frame by stream.frame.collectAsStateWithLifecycle()
    val status by stream.status.collectAsStateWithLifecycle()
    val message by stream.message.collectAsStateWithLifecycle()
    val fps by stream.fps.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
        val bitmap = frame
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = feed.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (status) {
            CameraStream.Status.CONNECTING -> if (bitmap == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Connecting",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CameraStream.Status.ERROR -> StreamError(message)

            CameraStream.Status.LIVE -> Unit
        }

        if (status == CameraStream.Status.LIVE && fps > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(Color(0xCC0B0E13), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    "$fps fps",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------- video tile

@OptIn(UnstableApi::class)
@Composable
private fun VideoTile(feed: CameraFeed) {
    val context = LocalContext.current
    var error by remember(feed.url) { mutableStateOf<String?>(null) }

    val player = remember(feed.url) {
        ExoPlayer.Builder(context).build().apply {
            // Muted and no audio focus: a camera must never duck or pause the music.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = e.errorCodeName
                }
            })
            if (feed.url.startsWith("rtsp", ignoreCase = true)) {
                // Interleaved over TCP: far more reliable than UDP on patchy car wifi.
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(MediaItem.fromUri(feed.url))
                setMediaSource(source)
            } else {
                setMediaItem(MediaItem.fromUri(feed.url))
            }
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { view -> player.setVideoSurfaceView(view) }
            },
        )
        error?.let { StreamError(it) }
    }
}

@Composable
private fun StreamError(message: String?) {
    Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No signal",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            message ?: "Retrying",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
