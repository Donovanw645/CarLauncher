package com.donovan.carlauncher.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.donovan.carlauncher.media.NowPlaying

/**
 * Transport controls as a standalone block. Used on the Home screen, where they sit
 * directly under the Now Playing card instead of in the bottom strip.
 */
@Composable
fun TransportCard(
    now: NowPlaying?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onJump: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = now != null
    CarCard(modifier = modifier, contentPadding = 8.dp) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            RoundControl(
                Icons.Rounded.Replay10, "Back 10 seconds",
                { onJump(-10_000) }, enabled = live, size = 50.dp,
            )
            RoundControl(
                Icons.Rounded.SkipPrevious, "Previous track",
                onPrev, enabled = now?.canSkipPrev == true, size = 56.dp,
            )
            RoundControl(
                icon = if (now?.isPlaying == true) Icons.Rounded.Pause
                else Icons.Rounded.PlayArrow,
                contentDescription = if (now?.isPlaying == true) "Pause" else "Play",
                onClick = onPlayPause,
                filled = true,
                enabled = live,
                size = 70.dp,
            )
            RoundControl(
                Icons.Rounded.SkipNext, "Next track",
                onNext, enabled = now?.canSkipNext == true, size = 56.dp,
            )
            RoundControl(
                Icons.Rounded.Forward30, "Forward 30 seconds",
                { onJump(30_000) }, enabled = live, size = 50.dp,
            )
        }
    }
}

/**
 * Always-visible transport strip. Whatever tab the driver is on - map, dashcam,
 * settings - play/pause and skip stay one reach away.
 */
@Composable
fun MediaBar(
    now: NowPlaying?,
    sourceLabel: String,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onJump: (Long) -> Unit,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (now == null) return
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth().height(76.dp),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceVariant)
                        .clickable { onOpenMedia() },
                    contentAlignment = Alignment.Center,
                ) {
                    val art = now.artwork
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
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f).clickable { onOpenMedia() },
                ) {
                    Text(
                        now.title.ifBlank { "Unknown track" },
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            now.artist.takeIf { it.isNotBlank() },
                            sourceLabel.takeIf { it.isNotBlank() },
                        ).joinToString(" - ").ifBlank { "Playing" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoundControl(
                        Icons.Rounded.Replay10, "Back 10 seconds",
                        { onJump(-10_000) }, size = 44.dp,
                    )
                    RoundControl(
                        Icons.Rounded.SkipPrevious, "Previous track",
                        onPrev, enabled = now.canSkipPrev, size = 48.dp,
                    )
                    RoundControl(
                        icon = if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (now.isPlaying) "Pause" else "Play",
                        onClick = onPlayPause,
                        filled = true,
                        size = 56.dp,
                    )
                    RoundControl(
                        Icons.Rounded.SkipNext, "Next track",
                        onNext, enabled = now.canSkipNext, size = 48.dp,
                    )
                    RoundControl(
                        Icons.Rounded.Forward30, "Forward 30 seconds",
                        { onJump(30_000) }, size = 44.dp,
                    )
                }
            }

            // Hairline progress along the bottom edge - readable at a glance, costs no height.
            val fraction = if (now.durationMs > 0) {
                (now.positionMs.toFloat() / now.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(scheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(scheme.primary)
                )
            }
        }
    }
}
