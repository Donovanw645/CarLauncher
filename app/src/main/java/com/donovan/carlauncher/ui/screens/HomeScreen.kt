package com.donovan.carlauncher.ui.screens

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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.nav.LatLon
import com.donovan.carlauncher.nav.Place
import com.donovan.carlauncher.ui.components.CarCard
import com.donovan.carlauncher.ui.components.NavBanner
import com.donovan.carlauncher.ui.components.NowPlayingCard
import com.donovan.carlauncher.ui.components.RoundControl
import com.donovan.carlauncher.ui.components.TransportCard
import com.donovan.carlauncher.ui.map.MapCanvas
import com.donovan.carlauncher.ui.map.MapHolder
import com.donovan.carlauncher.ui.map.MapSync
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    car: CarController,
    mapHolder: MapHolder,
    onOpenMaps: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val nav by car.nav.state.collectAsStateWithLifecycle()
    val now by car.mediaRemote.nowPlaying.collectAsStateWithLifecycle()
    val hasMediaAccess by car.mediaRemote.hasAccess.collectAsStateWithLifecycle()

    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    MapSync(car, mapHolder)

    LaunchedEffect(Unit) {
        mapHolder.setOnLongPress(null)
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // -------------------------------------------------------------- map column
        Box(Modifier.weight(1.75f).fillMaxSize()) {
            CarCard(
                modifier = Modifier.fillMaxSize(),
                contentPadding = 0.dp,
            ) {
                MapCanvas(mapHolder, Modifier.fillMaxSize())

                if (nav.active && nav.route != null) {
                    NavBanner(
                        nav = nav,
                        units = settings.units,
                        compact = true,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(10.dp)
                            .fillMaxWidth(0.95f),
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RoundControl(
                        icon = Icons.Rounded.MyLocation,
                        contentDescription = "Recentre map",
                        size = 50.dp,
                        onClick = {
                            mapHolder.recenter(
                                car.location.currentPoint(),
                                car.location.heading.value,
                                zoom = 16.5,
                            )
                        },
                    )
                    RoundControl(
                        icon = Icons.Rounded.OpenInFull,
                        contentDescription = "Open full map",
                        size = 50.dp,
                        filled = true,
                        onClick = onOpenMaps,
                    )
                }

                // Shortcuts live on the map so the map itself can have the room.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MapChip(
                        icon = Icons.Rounded.Home,
                        label = settings.home?.let { "Home" } ?: "Set home",
                        accent = settings.home != null,
                    ) {
                        val place = settings.home
                        toast = if (place == null) {
                            "Set Home in Settings"
                        } else {
                            car.navigateTo(
                                Place(place.label, place.address, LatLon(place.lat, place.lon))
                            ) ?: "Routing to Home"
                        }
                    }
                    MapChip(
                        icon = Icons.Rounded.Work,
                        label = settings.work?.let { "Work" } ?: "Set work",
                        accent = settings.work != null,
                    ) {
                        val place = settings.work
                        toast = if (place == null) {
                            "Set Work in Settings"
                        } else {
                            car.navigateTo(
                                Place(place.label, place.address, LatLon(place.lat, place.lon))
                            ) ?: "Routing to Work"
                        }
                    }
                    MapChip(
                        icon = Icons.Rounded.School,
                        label = settings.school?.let { "School" } ?: "Set school",
                        accent = settings.school != null,
                    ) {
                        val place = settings.school
                        toast = if (place == null) {
                            "Set School in Settings"
                        } else {
                            car.navigateTo(
                                Place(place.label, place.address, LatLon(place.lat, place.lon))
                            ) ?: "Routing to School"
                        }
                    }
                    MapChip(Icons.Rounded.Search, "Search", onClick = onOpenMaps)
                    MapChip(Icons.Rounded.LibraryMusic, "Library", onClick = onOpenMedia)
                }

                if (!car.location.hasPermission()) {
                    LocationPrompt(
                        onRequestPermissions,
                        Modifier.align(Alignment.TopStart).padding(12.dp),
                    )
                }
            }
        }

        // ------------------------------------------------------------ right column
        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NowPlayingCard(
                now = now,
                hasAccess = hasMediaAccess,
                sourceLabel = now?.let { car.apps.labelFor(it.packageName) } ?: "",
                sourceIcon = now?.let { car.apps.iconFor(it.packageName) },
                onPlayPause = car.mediaRemote::playPause,
                onNext = car.mediaRemote::next,
                onPrev = car.mediaRemote::previous,
                onSeek = car.mediaRemote::seekTo,
                onGrantAccess = onOpenNotificationSettings,
                compact = true,
                showTransport = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            TransportCard(
                now = now,
                onPlayPause = car.mediaRemote::playPause,
                onNext = car.mediaRemote::next,
                onPrev = car.mediaRemote::previous,
                onJump = car.mediaRemote::jump,
                modifier = Modifier.fillMaxWidth().height(104.dp),
            )
        }
    }

    val message = toast
    if (message != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            CarCard(
                modifier = Modifier.padding(bottom = 18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentPadding = 14.dp,
            ) {
                Text(message, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Compact shortcut that sits over the map, tuned to stay readable against tiles. */
@Composable
private fun MapChip(
    icon: ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .background(Color(0xE60F141A), RoundedCornerShape(15.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (accent) scheme.primary else scheme.onSurface,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (accent) scheme.primary else scheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun LocationPrompt(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    CarCard(
        modifier = modifier,
        onClick = onRequest,
        contentPadding = 12.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Rounded.MyLocation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Tap to allow location",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
