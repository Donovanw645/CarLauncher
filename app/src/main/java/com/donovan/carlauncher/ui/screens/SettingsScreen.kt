package com.donovan.carlauncher.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.cctv.ProbeResult
import com.donovan.carlauncher.cctv.probeCamera
import com.donovan.carlauncher.dashcam.Dashcam
import com.donovan.carlauncher.dashcam.formatBytes
import com.donovan.carlauncher.data.CameraFacing
import com.donovan.carlauncher.data.CameraFeed
import com.donovan.carlauncher.data.DashcamQuality
import com.donovan.carlauncher.data.MAX_CAMERAS
import com.donovan.carlauncher.data.MapTheme
import com.donovan.carlauncher.data.OrientationMode
import com.donovan.carlauncher.data.SavedPlace
import com.donovan.carlauncher.data.Units
import com.donovan.carlauncher.media.CarNotificationListener
import com.donovan.carlauncher.ui.components.CarCard
import com.donovan.carlauncher.ui.components.SectionLabel
import com.donovan.carlauncher.ui.components.UpdateSection
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    car: CarController,
    onOpenNotificationSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------------ display
        SettingsGroup("Display") {
            ChoiceRow(
                label = "Screen orientation",
                options = OrientationMode.entries.map { it to it.name.lowercase() },
                selected = settings.orientation,
                onSelect = { v -> car.prefs.update { it.copy(orientation = v) } },
            )
            ToggleRow(
                label = "Keep screen on",
                description = "Stops the tablet sleeping while it is on the dash",
                checked = settings.keepScreenOn,
                onChange = { v -> car.prefs.update { it.copy(keepScreenOn = v) } },
            )
            ChoiceRow(
                label = "Units",
                options = listOf(Units.IMPERIAL to "miles", Units.METRIC to "kilometres"),
                selected = settings.units,
                onSelect = { v -> car.prefs.update { it.copy(units = v) } },
            )
        }

        // ---------------------------------------------------------------------- map
        SettingsGroup("Map") {
            ChoiceRow(
                label = "Night mode",
                options = listOf(
                    MapTheme.AUTO to "auto (after dark)",
                    MapTheme.DARK to "always on",
                    MapTheme.LIGHT to "off",
                ),
                selected = settings.mapTheme,
                onSelect = { v -> car.prefs.update { it.copy(mapTheme = v) } },
            )
            TextRow(
                label = "Custom tile server",
                description = "Optional. Base URL only - tiles are fetched as {base}/{z}/{x}/{y}.png. " +
                    "Paste a styled dark basemap here (Stadia, Thunderforest, MapTiler all have " +
                    "free keys) and night mode steps aside for it.",
                value = settings.customTileUrl,
                placeholder = "leave blank for standard OpenStreetMap",
                onCommit = { v -> car.prefs.update { it.copy(customTileUrl = v.trim()) } },
            )
        }

        // --------------------------------------------------------------- navigation
        SettingsGroup("Navigation") {
            ToggleRow(
                label = "Spoken directions",
                description = "Announced over the car's Bluetooth, ducking the music",
                checked = settings.voiceGuidance,
                onChange = { v -> car.prefs.update { it.copy(voiceGuidance = v) } },
            )
            SavedPlaceRow(
                car = car,
                label = "Home",
                place = settings.home,
                onSave = { p -> car.prefs.update { it.copy(home = p) } },
            )
            SavedPlaceRow(
                car = car,
                label = "Work",
                place = settings.work,
                onSave = { p -> car.prefs.update { it.copy(work = p) } },
            )
            TextRow(
                label = "Routing server (OSRM)",
                description = "The public demo server has no uptime guarantee. " +
                    "Point this at your own OSRM instance for reliability.",
                value = settings.routerBaseUrl,
                placeholder = "https://router.project-osrm.org",
                onCommit = { v ->
                    car.prefs.update {
                        it.copy(
                            routerBaseUrl = v.trim().ifBlank {
                                "https://router.project-osrm.org"
                            }
                        )
                    }
                },
            )
            TextRow(
                label = "Search server (Nominatim)",
                description = "Used for place search and address lookup",
                value = settings.geocoderBaseUrl,
                placeholder = "https://nominatim.openstreetmap.org",
                onCommit = { v ->
                    car.prefs.update {
                        it.copy(
                            geocoderBaseUrl = v.trim().ifBlank {
                                "https://nominatim.openstreetmap.org"
                            }
                        )
                    }
                },
            )
        }

        // ------------------------------------------------------------------ dashcam
        SettingsGroup("Dashcam") {
            ChoiceRow(
                label = "Recording quality",
                options = DashcamQuality.entries.map { it to it.label },
                selected = settings.dashQuality,
                onSelect = { v -> car.prefs.update { it.copy(dashQuality = v) } },
            )
            ChoiceRow(
                label = "Camera",
                options = listOf(
                    CameraFacing.BACK to "rear (road)",
                    CameraFacing.FRONT to "front (cabin)",
                ),
                selected = settings.dashFacing,
                onSelect = { v -> car.prefs.update { it.copy(dashFacing = v) } },
            )
            ChoiceRow(
                label = "Clip length",
                options = listOf(1 to "1 min", 3 to "3 min", 5 to "5 min", 10 to "10 min"),
                selected = settings.dashSegmentMinutes,
                onSelect = { v -> car.prefs.update { it.copy(dashSegmentMinutes = v) } },
            )
            ChoiceRow(
                label = "Storage limit",
                options = listOf(2 to "2 GB", 4 to "4 GB", 8 to "8 GB", 16 to "16 GB"),
                selected = settings.dashMaxStorageGb,
                onSelect = { v -> car.prefs.update { it.copy(dashMaxStorageGb = v) } },
            )

            val storageOptions = remember(settings.dashStorageIndex) {
                Dashcam.storageOptions(context)
            }
            if (storageOptions.size > 1) {
                ChoiceRow(
                    label = "Save clips to",
                    options = storageOptions.map { opt ->
                        opt.index to "${opt.label} · ${formatBytes(opt.freeBytes)} free"
                    },
                    selected = storageOptions
                        .firstOrNull { it.index == settings.dashStorageIndex }?.index
                        ?: storageOptions.first().index,
                    onSelect = { v -> car.prefs.update { it.copy(dashStorageIndex = v) } },
                )
                Text(
                    "Existing clips stay where they were written - switching only changes " +
                        "where new ones go.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RowLabels(
                    "Save clips to",
                    "No SD card detected. Clips go to internal storage" +
                        (storageOptions.firstOrNull()
                            ?.let { " · ${formatBytes(it.freeBytes)} free" } ?: "") + ".",
                    Modifier.padding(vertical = 10.dp),
                )
            }
            ToggleRow(
                label = "Record audio",
                description = "Captures cabin sound alongside the video",
                checked = settings.dashRecordAudio,
                onChange = { v -> car.prefs.update { it.copy(dashRecordAudio = v) } },
            )
            ToggleRow(
                label = "Start recording on launch",
                description = "Begins the loop as soon as the tablet powers up with the car",
                checked = settings.dashAutoStart,
                onChange = { v -> car.prefs.update { it.copy(dashAutoStart = v) } },
            )
            ActionRow(
                label = "Where clips are stored",
                description = Dashcam.loopDir(context).absolutePath,
                buttonText = "Refresh",
                onClick = { Dashcam.refreshClips(context) },
            )
        }

        // -------------------------------------------------------------------- cctv
        SettingsGroup("Cameras (CCTV)") {
            ChoiceRow(
                label = "Feeds on screen at once",
                options = listOf(1 to "1 camera", 2 to "2 cameras", 4 to "4 cameras"),
                selected = settings.cctvLayout,
                onSelect = { v -> car.prefs.update { it.copy(cctvLayout = v) } },
            )
            Text(
                "MJPEG, snapshot, RTSP and HLS are all handled - paste whatever your " +
                    "streaming server gives you and press Test. Examples: " +
                    "http://192.168.1.50:8080/?action=stream (mjpg-streamer), " +
                    "rtsp://192.168.1.50:8554/front (MediaMTX), " +
                    "http://192.168.1.50/snapshot.jpg (still image).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            for (slot in 0 until MAX_CAMERAS) {
                CameraSlotRow(
                    slot = slot,
                    feed = settings.cameras.getOrNull(slot),
                    onSave = { updated ->
                        car.prefs.update { s ->
                            val list = MutableList(MAX_CAMERAS) { i ->
                                s.cameras.getOrNull(i) ?: CameraFeed("", "")
                            }
                            list[slot] = updated
                            // Trim trailing empties so the list stays tidy.
                            s.copy(cameras = list.dropLastWhile { it.url.isBlank() })
                        }
                    },
                )
            }
        }

        // -------------------------------------------------------------- permissions
        SettingsGroup("Permissions and system") {
            ActionRow(
                label = "Media control access",
                description = if (CarNotificationListener.isEnabled(context)) {
                    "Granted - the launcher can drive other music apps"
                } else {
                    "Not granted - music controls will stay empty"
                },
                buttonText = "Open",
                onClick = onOpenNotificationSettings,
            )
            ActionRow(
                label = "Location, camera and microphone",
                description = "Needed for navigation, speed and the dashcam",
                buttonText = "Request",
                onClick = onRequestPermissions,
            )
            ActionRow(
                label = "Use as the tablet's home screen",
                description = "Makes Car Launcher start automatically with the tablet",
                buttonText = "Choose",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_HOME_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            ActionRow(
                label = "Bluetooth settings",
                description = "Pair the tablet with the car stereo",
                buttonText = "Open",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            ActionRow(
                label = "Wi-Fi settings",
                description = "Connect to the iPhone hotspot",
                buttonText = "Open",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
        }

        UpdateSection(car)

        SettingsGroup("About") {
            Text(
                "Map data and tiles (c) OpenStreetMap contributors, ODbL. " +
                    "Routing by OSRM. Geocoding by Nominatim.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------------ building blocks

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    CarCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
        Column {
            SectionLabel(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun RowLabels(label: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabels(label, description, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        RowLabels(label, null)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                val active = value == selected
                val scheme = MaterialTheme.colorScheme
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            if (active) scheme.primaryContainer else scheme.surfaceVariant,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    description: String?,
    buttonText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabels(label, description, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Button(onClick = onClick) { Text(buttonText) }
    }
}

@Composable
private fun TextRow(
    label: String,
    description: String?,
    value: String,
    placeholder: String,
    onCommit: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        RowLabels(label, description)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { onCommit(draft) }, enabled = draft != value) { Text("Save") }
        }
    }
}

/**
 * One camera slot: a name, a URL, and a Test that actually connects and pulls a frame.
 */
@Composable
private fun CameraSlotRow(
    slot: Int,
    feed: CameraFeed?,
    onSave: (CameraFeed) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember(feed?.name) { mutableStateOf(feed?.name.orEmpty()) }
    var url by remember(feed?.url) { mutableStateOf(feed?.url.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProbeResult?>(null) }
    val scope = rememberCoroutineScope()

    val dirty = name != feed?.name.orEmpty() || url != feed?.url.orEmpty()

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        RowLabels(
            "Camera ${slot + 1}",
            feed?.url?.takeIf { it.isNotBlank() } ?: "Not set",
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.width(200.dp),
                singleLine = true,
                placeholder = { Text(if (slot == 0) "Front" else "Rear") },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceVariant,
                    unfocusedContainerColor = scheme.surfaceVariant,
                ),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; result = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                // Autocorrect and sentence capitalisation will happily turn a typed
                // address into one the server 404s on. Paths are case sensitive.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                placeholder = { Text("http://192.168.1.50:8080/?action=stream") },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceVariant,
                    unfocusedContainerColor = scheme.surfaceVariant,
                ),
            )
            Spacer(Modifier.width(10.dp))
            if (testing) {
                CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Button(
                enabled = url.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        result = probeCamera(url)
                        testing = false
                    }
                },
            ) { Text("Test") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = dirty, onClick = { onSave(CameraFeed(name.trim(), url.trim())) }) {
                Text("Save")
            }
        }
        result?.let { r ->
            Spacer(Modifier.height(6.dp))
            Text(
                (if (r.ok) "OK · " else "Failed · ") + r.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (r.ok) scheme.primary else scheme.error,
            )
        }
    }
}

/** Set Home / Work by typing an address; the first search hit is stored. */
@Composable
private fun SavedPlaceRow(
    car: CarController,
    label: String,
    place: SavedPlace?,
    onSave: (SavedPlace?) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        RowLabels(
            label,
            place?.address ?: "Not set",
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Type an address, then Save") },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            Spacer(Modifier.width(10.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Button(
                enabled = draft.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val hit = runCatching {
                            car.geocoder.search(draft, car.location.currentPoint(), limit = 1)
                                .firstOrNull()
                        }.getOrNull()
                        busy = false
                        if (hit == null) {
                            error = "Could not find that address"
                        } else {
                            onSave(
                                SavedPlace(
                                    label = label,
                                    address = hit.address,
                                    lat = hit.point.lat,
                                    lon = hit.point.lon,
                                )
                            )
                            draft = ""
                        }
                    }
                },
            ) { Text("Save") }
            if (place != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable { onSave(null) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
        val err = error
        if (err != null) {
            Text(
                err,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
