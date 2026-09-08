package com.donovan.carlauncher.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.nav.speedUnitLabel
import com.donovan.carlauncher.nav.speedValue
import com.donovan.carlauncher.system.NetType
import com.donovan.carlauncher.ui.components.MediaBar
import com.donovan.carlauncher.ui.components.StatusChip
import com.donovan.carlauncher.ui.components.rememberTicker
import com.donovan.carlauncher.ui.map.MapHolder
import com.donovan.carlauncher.ui.screens.AppsScreen
import com.donovan.carlauncher.ui.screens.CctvScreen
import com.donovan.carlauncher.ui.screens.DashcamScreen
import com.donovan.carlauncher.ui.screens.HomeScreen
import com.donovan.carlauncher.ui.screens.MapScreen
import com.donovan.carlauncher.ui.screens.MediaScreen
import com.donovan.carlauncher.ui.screens.SettingsScreen
import com.donovan.carlauncher.update.isPending
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CarScreen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.DirectionsCar),
    MAPS("Maps", Icons.Rounded.Map),
    MEDIA("Media", Icons.Rounded.LibraryMusic),
    DASHCAM("Dashcam", Icons.Rounded.Videocam),
    CCTV("CCTV", Icons.Rounded.Traffic),
    APPS("Apps", Icons.Rounded.Apps),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun CarShell(
    car: CarController,
    mapHolder: MapHolder,
    onOpenNotificationSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    var screen by remember { mutableStateOf(CarScreen.HOME) }
    val nowPlaying by car.mediaRemote.nowPlaying.collectAsStateWithLifecycle()
    val updateState by car.updater.state.collectAsStateWithLifecycle()

    Row(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NavRail(
            current = screen,
            onSelect = { screen = it },
            updatePending = updateState.isPending,
        )

        Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            StatusStrip(car)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f)) {
                when (screen) {
                    CarScreen.HOME -> HomeScreen(
                        car = car,
                        mapHolder = mapHolder,
                        onOpenMaps = { screen = CarScreen.MAPS },
                        onOpenMedia = { screen = CarScreen.MEDIA },
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onRequestPermissions = onRequestPermissions,
                    )

                    CarScreen.MAPS -> MapScreen(
                        car = car,
                        mapHolder = mapHolder,
                        onRequestPermissions = onRequestPermissions,
                    )

                    CarScreen.MEDIA -> MediaScreen(
                        car = car,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                    )

                    CarScreen.DASHCAM -> DashcamScreen(
                        car = car,
                        onRequestPermissions = onRequestPermissions,
                    )

                    CarScreen.CCTV -> CctvScreen(car = car)

                    CarScreen.APPS -> AppsScreen(car = car)

                    CarScreen.SETTINGS -> SettingsScreen(
                        car = car,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onRequestPermissions = onRequestPermissions,
                    )
                }
            }

            // Home and Media each carry their own transport controls; every other tab
            // gets the strip so play/pause is never more than one reach away.
            val ownsControls = screen == CarScreen.MEDIA || screen == CarScreen.HOME
            if (nowPlaying != null && !ownsControls) {
                Spacer(Modifier.height(10.dp))
                MediaBar(
                    now = nowPlaying,
                    sourceLabel = nowPlaying?.let { car.apps.labelFor(it.packageName) }.orEmpty(),
                    onPlayPause = car.mediaRemote::playPause,
                    onNext = car.mediaRemote::next,
                    onPrev = car.mediaRemote::previous,
                    onJump = car.mediaRemote::jump,
                    onOpenMedia = { screen = CarScreen.MEDIA },
                )
            }
        }
    }
}

@Composable
private fun NavRail(
    current: CarScreen,
    onSelect: (CarScreen) -> Unit,
    updatePending: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(96.dp)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        for (item in CarScreen.entries) {
            val selected = item == current
            val scheme = MaterialTheme.colorScheme
            Column(
                modifier = Modifier
                    .width(80.dp)
                    .background(
                        if (selected) scheme.primaryContainer else scheme.background,
                        RoundedCornerShape(18.dp),
                    )
                    .clickable { onSelect(item) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    if (updatePending && item == CarScreen.SETTINGS) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(9.dp)
                                .background(scheme.primary, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(car: CarController) {
    val nowMs by rememberTicker()
    val status by car.status.state.collectAsStateWithLifecycle()
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val location by car.location.location.collectAsStateWithLifecycle()

    val timeFmt = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val amPmFmt = remember { SimpleDateFormat("a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val date = Date(nowMs)

    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            timeFmt.format(date),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                amPmFmt.format(date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                dateFmt.format(date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val speed = location?.let { loc ->
                if (loc.hasSpeed()) speedValue(loc.speed, settings.units) else null
            }
            StatusChip(
                icon = if (location != null) Icons.Rounded.GpsFixed else Icons.Rounded.GpsOff,
                text = if (speed != null) {
                    "$speed ${speedUnitLabel(settings.units)}"
                } else if (location != null) "GPS" else "No GPS",
                tint = if (location != null) MaterialTheme.colorScheme.primary else null,
            )

            StatusChip(
                icon = if (status.btAudioDevice != null) Icons.Rounded.Bluetooth
                else Icons.Rounded.BluetoothDisabled,
                text = status.btAudioDevice ?: "Not paired",
                tint = if (status.btAudioDevice != null) {
                    MaterialTheme.colorScheme.primary
                } else null,
            )

            StatusChip(
                icon = when (status.netType) {
                    NetType.WIFI -> Icons.Rounded.Wifi
                    NetType.CELLULAR -> Icons.Rounded.SignalCellularAlt
                    NetType.NONE -> Icons.Rounded.WifiOff
                    else -> Icons.Rounded.Wifi
                },
                text = when (status.netType) {
                    NetType.WIFI -> status.wifiSsid ?: "Wi-Fi"
                    NetType.CELLULAR -> "Mobile"
                    NetType.ETHERNET -> "Wired"
                    NetType.OTHER -> "Online"
                    NetType.NONE -> "Offline"
                },
                tint = if (status.netType == NetType.NONE) {
                    MaterialTheme.colorScheme.error
                } else null,
            )

            if (status.batteryPct >= 0) {
                StatusChip(
                    icon = if (status.charging) Icons.Rounded.BatteryChargingFull
                    else Icons.Rounded.BatteryFull,
                    text = "${status.batteryPct}%",
                    tint = when {
                        status.charging -> MaterialTheme.colorScheme.primary
                        status.batteryPct <= 15 -> MaterialTheme.colorScheme.error
                        else -> null
                    },
                )
            }
        }
    }
}
