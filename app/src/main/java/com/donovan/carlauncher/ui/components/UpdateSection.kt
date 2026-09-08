package com.donovan.carlauncher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.dashcam.Dashcam
import com.donovan.carlauncher.dashcam.DashcamStatus
import com.donovan.carlauncher.dashcam.formatBytes
import com.donovan.carlauncher.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Software update block in Settings. Deliberately undramatic: a badge on the nav
 * rail is the only thing that ever appears unprompted, because a modal dialog on a
 * dash screen at 60 mph is the wrong answer to every question.
 */
@Composable
fun UpdateSection(car: CarController) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val state by car.updater.state.collectAsStateWithLifecycle()
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val dash by Dashcam.state.collectAsStateWithLifecycle()
    val nav by car.nav.state.collectAsStateWithLifecycle()

    val busy = when {
        dash.status == DashcamStatus.RECORDING -> "the dashcam is recording"
        nav.active -> "navigation is running"
        else -> null
    }

    CarCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        Column {
            SectionLabel("Software update")
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Car Launcher ${car.updater.installedVersionName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        statusLine(state, car.updater.installedVersionCode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state is UpdateState.Failed) scheme.error
                        else scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (state is UpdateState.Checking) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = { car.updater.check(manual = true) }) { Text("Check now") }
                }
            }

            when (val s = state) {
                is UpdateState.Available -> {
                    NewVersion(s.manifest.versionName, s.manifest.sizeBytes, s.manifest.notes)
                    if (s.meteredHold) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Held back: this connection is metered, so downloading will use " +
                                "your phone's data.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { car.updater.download() }) {
                            Text(if (s.meteredHold) "Download anyway" else "Download")
                        }
                        TextButton(onClick = { car.updater.skip() }) { Text("Skip this version") }
                    }
                }

                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { s.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatBytes(s.bytes)} of ${formatBytes(s.total)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }

                is UpdateState.Ready -> {
                    NewVersion(s.manifest.versionName, s.manifest.sizeBytes, s.manifest.notes)
                    Spacer(Modifier.height(12.dp))

                    if (!car.updater.canInstallPackages()) {
                        Text(
                            "Android needs your permission before the launcher can install " +
                                "its own updates. This is a one-time grant.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            runCatching { context.startActivity(car.updater.unknownSourcesIntent()) }
                        }) { Text("Allow installs") }
                    } else {
                        // Being mid-route or mid-recording is a reason to warn, not a
                        // reason to hide the only button on the screen. Removing it
                        // outright left an update that had already downloaded with no
                        // visible way to install it and no explanation worth reading.
                        if (busy != null) {
                            Text(
                                "Installing restarts the launcher, and $busy.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.error,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { car.updater.install() }) {
                                Text(if (busy != null) "Install anyway" else "Install now")
                            }
                            TextButton(onClick = { car.updater.skip() }) { Text("Not now") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Your settings, camera feeds and permissions all carry over.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                is UpdateState.Installing -> {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Installing ${s.manifest.versionName} - Android will ask you " +
                                "to confirm, then the launcher restarts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                is UpdateState.Failed -> {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { car.updater.dismissError() }) { Text("Dismiss") }
                }

                else -> Unit
            }

            Spacer(Modifier.height(6.dp))

            UpdateToggle(
                label = "Check automatically",
                description = "Looks for a new build every few hours",
                checked = settings.updateCheckEnabled,
                onChange = { v -> car.prefs.update { it.copy(updateCheckEnabled = v) } },
            )
            UpdateToggle(
                label = "Download on Wi-Fi",
                description = "Fetches the update ahead of time, never over the phone hotspot",
                checked = settings.updateAutoDownload,
                onChange = { v -> car.prefs.update { it.copy(updateAutoDownload = v) } },
            )
        }
    }
}

@Composable
private fun NewVersion(versionName: String, sizeBytes: Long, notes: String) {
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(14.dp))
    Text(
        buildString {
            append("Version ")
            append(versionName)
            if (sizeBytes > 0) {
                append("  -  ")
                append(formatBytes(sizeBytes))
            }
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = scheme.primary,
    )
    if (notes.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            notes,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateToggle(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun statusLine(state: UpdateState, installedCode: Long): String = when (state) {
    is UpdateState.Idle -> "Build $installedCode"
    is UpdateState.Checking -> "Checking for updates..."
    is UpdateState.UpToDate -> "Up to date - checked ${clock(state.checkedAtMs)}"
    is UpdateState.Available -> "An update is available"
    is UpdateState.Downloading -> "Downloading..."
    is UpdateState.Ready -> "Ready to install"
    is UpdateState.Installing -> "Waiting for the system installer..."
    is UpdateState.Failed -> state.message
}

private fun clock(ms: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
