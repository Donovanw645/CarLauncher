package com.donovan.carlauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.donovan.carlauncher.media.NowPlaying
import java.util.Locale

fun formatClockMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return if (m >= 60) {
        String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/**
 * The launcher's media surface. It drives whichever app owns the current media
 * session, so the driver never leaves this screen to change a track.
 */
@Composable
fun NowPlayingCard(
    now: NowPlaying?,
    hasAccess: Boolean,
    sourceLabel: String,
    sourceIcon: ImageBitmap?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Off when a separate transport card sits underneath, as on the Home screen. */
    showTransport: Boolean = true,
) {
    CarCard(modifier = modifier, contentPadding = if (compact) 14.dp else 20.dp) {
        when {
            !hasAccess -> AccessPrompt(onGrantAccess)
            now == null -> NothingPlaying()
            else -> PlayingContent(
                now = now,
                sourceLabel = sourceLabel,
                sourceIcon = sourceIcon,
                compact = compact,
                showTransport = showTransport,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrev = onPrev,
                onSeek = onSeek,
            )
        }
    }
}

@Composable
private fun AccessPrompt(onGrantAccess: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.NotificationsActive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn on notification access",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "It is how Android lets this launcher control Apple Music and anything " +
                "else that plays audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrantAccess) { Text("Open settings") }
    }
}

@Composable
private fun NothingPlaying() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Nothing playing",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Start something from the Media tab",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Art beside the text in a wide card, art above it in a tall one. Without the switch a
 * tall narrow card gives the square artwork all the width and squeezes the title to
 * nothing.
 */
@Composable
private fun PlayingContent(
    now: NowPlaying,
    sourceLabel: String,
    sourceIcon: ImageBitmap?,
    compact: Boolean,
    showTransport: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight * 1.15f
        val gap = if (compact) 14.dp else 20.dp

        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    now = now,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.width(gap))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    TrackText(now, sourceLabel, sourceIcon, compact)
                    Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                    SeekBar(now = now, onSeek = onSeek)
                    if (showTransport) {
                        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                        TransportRow(now, compact, onPlayPause, onNext, onPrev)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(
                    now = now,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(18.dp)),
                )
                Spacer(Modifier.height(gap))
                Column(Modifier.fillMaxWidth()) {
                    TrackText(now, sourceLabel, sourceIcon, compact)
                    Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                    SeekBar(now = now, onSeek = onSeek)
                    if (showTransport) {
                        Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                        TransportRow(now, compact, onPlayPause, onNext, onPrev)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackText(
    now: NowPlaying,
    sourceLabel: String,
    sourceIcon: ImageBitmap?,
    compact: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (sourceIcon != null) {
            Image(
                bitmap = sourceIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            sourceLabel.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        now.title.ifBlank { "Unknown track" },
        style = if (compact) MaterialTheme.typography.titleLarge
        else MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        listOf(now.artist, now.album).filter { it.isNotBlank() }.joinToString(" - ")
            .ifBlank { "Unknown artist" },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TransportRow(
    now: NowPlaying,
    compact: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        RoundControl(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "Previous track",
            onClick = onPrev,
            enabled = now.canSkipPrev,
            size = if (compact) 50.dp else 58.dp,
        )
        RoundControl(
            icon = if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (now.isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            filled = true,
            size = if (compact) 62.dp else 74.dp,
        )
        RoundControl(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "Next track",
            onClick = onNext,
            enabled = now.canSkipNext,
            size = if (compact) 50.dp else 58.dp,
        )
    }
}

@Composable
private fun Artwork(now: NowPlaying, modifier: Modifier = Modifier) {
    val art = now.artwork
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
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
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }
    }
}

@Composable
private fun SeekBar(now: NowPlaying, onSeek: (Long) -> Unit) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    val duration = now.durationMs
    val liveFraction = if (duration > 0) {
        (now.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shown = if (scrubbing) scrubValue else liveFraction
    val shownPosition = if (scrubbing) (scrubValue * duration).toLong() else now.positionMs

    Column {
        Slider(
            value = shown,
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                onSeek((scrubValue * duration).toLong())
                scrubbing = false
            },
            enabled = now.canSeek,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                thumbColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatClockMs(shownPosition),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (duration > 0) formatClockMs(duration) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
