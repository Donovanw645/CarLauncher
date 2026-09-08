package com.donovan.carlauncher.ui.screens

import android.widget.MediaController as SystemMediaController
import android.widget.VideoView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.dashcam.Clip
import com.donovan.carlauncher.dashcam.Dashcam
import com.donovan.carlauncher.dashcam.DashcamStatus
import com.donovan.carlauncher.dashcam.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashcamScreen(
    car: CarController,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val state by Dashcam.state.collectAsStateWithLifecycle()
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf<Clip?>(null) }

    LaunchedEffect(Unit) { Dashcam.refreshClips(context) }

    // Camera comes up as soon as the tab is open, so there is a live view before you
    // press record. It is released again on the way out unless a recording is running.
    DisposableEffect(settings.dashFacing, settings.dashQuality) {
        Dashcam.startPreview(context)
        onDispose { Dashcam.stopPreview(context) }
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------- live view
            CarCardBox(modifier = Modifier.weight(1.4f).fillMaxHeight()) {
                if (!Dashcam.hasCameraPermission(context)) {
                    PermissionPrompt(onRequestPermissions)
                } else {
                    CameraPane(cameraOn = state.status != DashcamStatus.IDLE)
                }

                // status overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val recording = state.status == DashcamStatus.RECORDING
                    Icon(
                        Icons.Rounded.FiberManualRecord,
                        contentDescription = null,
                        tint = if (recording) Color(0xFFFF4D4D) else Color(0xFF6A7480),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state.status) {
                            DashcamStatus.RECORDING ->
                                "REC  ${formatElapsed(state.elapsedMs)}  ·  " +
                                    "clip ${state.segmentCount + 1}"
                            DashcamStatus.STARTING -> "Starting camera"
                            DashcamStatus.PREVIEW -> "Live  ·  not recording"
                            DashcamStatus.ERROR -> "Error"
                            DashcamStatus.IDLE -> "Standby"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }

                // record button
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                ) {
                    RecordButton(
                        recording = state.status == DashcamStatus.RECORDING,
                        enabled = Dashcam.hasCameraPermission(context),
                        onClick = { Dashcam.toggle(context) },
                    )
                }

                state.message?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(
                                MaterialTheme.colorScheme.error,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }

            // ------------------------------------------------------------- clips
            CarCardBox(modifier = Modifier.weight(1f).fillMaxHeight(), padding = 16.dp) {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Recordings",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${formatBytes(state.loopBytes)} / ${settings.dashMaxStorageGb} GB" +
                                if (state.savedBytes > 0) {
                                    "  ·  ${formatBytes(state.savedBytes)} kept"
                                } else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${settings.dashSegmentMinutes}-minute clips at " +
                            "${settings.dashQuality.label}, saved to " +
                            "${Dashcam.activeStorage(context)?.label ?: "internal"} storage. " +
                            "Oldest clips are deleted first - tap the padlock to keep one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (state.clips.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No recordings yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.clips, key = { it.file.absolutePath }) { clip ->
                                ClipRow(
                                    clip = clip,
                                    onPlay = { playing = clip },
                                    onToggleLock = {
                                        Dashcam.setLocked(context, clip, !clip.locked)
                                    },
                                    onDelete = { Dashcam.delete(context, clip) },
                                )
                            }
                        }
                    }
                }
            }
        }

        playing?.let { clip ->
            ClipPlayer(clip = clip, onClose = { playing = null })
        }
    }
}

@Composable
private fun CarCardBox(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun CameraPane(cameraOn: Boolean) {
    if (!cameraOn) {
        // Only reached if the camera failed or was released; normally the tab holds it.
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0B0E13)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.VideocamOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Camera off",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Press record to start the loop",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { view -> Dashcam.attachPreview(view.surfaceProvider) },
    )

    DisposableEffect(Unit) {
        onDispose { Dashcam.detachPreview() }
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (recording) Color(0xFFFF4D4D) else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(84.dp)
            .background(Color(0x66000000), CircleShape)
            .padding(6.dp)
            .background(if (enabled) bg else Color(0xFF3A424C), CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (recording) Icons.Rounded.Stop else Icons.Rounded.Videocam,
            contentDescription = if (recording) "Stop recording" else "Start recording",
            tint = Color(0xFF05130B),
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
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
            "Camera access needed",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "The dashcam records to this tablet only. Nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Grant camera access") }
    }
}

@Composable
private fun ClipRow(
    clip: Clip,
    onPlay: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fmt = remember { SimpleDateFormat("EEE d MMM  ·  h:mm:ss a", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(scheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(scheme.surface, CircleShape)
                .clickable { onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Play clip",
                tint = scheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fmt.format(Date(clip.startedAt)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatBytes(clip.sizeBytes) + if (clip.locked) "  ·  kept" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (clip.locked) scheme.primary else scheme.onSurfaceVariant,
            )
        }
        Icon(
            if (clip.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            contentDescription = if (clip.locked) "Allow deletion" else "Keep this clip",
            tint = if (clip.locked) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { onToggleLock() }
                .padding(8.dp),
        )
        Icon(
            Icons.Rounded.Delete,
            contentDescription = "Delete clip",
            tint = scheme.error,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { onDelete() }
                .padding(8.dp),
        )
    }
}

@Composable
private fun ClipPlayer(clip: Clip, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(0.72f).height(430.dp),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(clip.file.absolutePath)
                        setMediaController(
                            SystemMediaController(ctx).also { it.setAnchorView(this) }
                        )
                        setOnPreparedListener { it.isLooping = false; start() }
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clip.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
                Spacer(Modifier.width(16.dp))
                Button(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Close")
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}
