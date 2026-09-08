package com.donovan.carlauncher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.nav.LatLon
import com.donovan.carlauncher.nav.Place
import com.donovan.carlauncher.ui.components.CarCard
import com.donovan.carlauncher.ui.components.NavBanner
import com.donovan.carlauncher.ui.components.NavSummary
import com.donovan.carlauncher.ui.components.RoundControl
import com.donovan.carlauncher.ui.map.MapCanvas
import com.donovan.carlauncher.traffic.CatalogState
import com.donovan.carlauncher.ui.map.CameraDot
import com.donovan.carlauncher.ui.map.MapHolder
import com.donovan.carlauncher.ui.map.MapSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MapScreen(
    car: CarController,
    mapHolder: MapHolder,
    onRequestPermissions: () -> Unit,
    onOpenCamera: () -> Unit = {},
) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val nav by car.nav.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var pendingPin by remember { mutableStateOf<Place?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    MapSync(car, mapHolder)
    TrafficDots(car, mapHolder, onOpenCamera)

    // Photon has no one-per-second policy the way Nominatim does, so this only has to
    // be long enough to avoid firing mid-keystroke - short enough that results feel
    // like they appear as you type.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        searchError = null
        delay(180)
        runCatching { car.geocoder.search(q, car.location.currentPoint()) }
            .onSuccess { results = it }
            .onFailure {
                results = emptyList()
                searchError = it.message ?: "Search failed"
            }
        searching = false
    }

    DisposableEffect(Unit) {
        mapHolder.setOnLongPress { point ->
            scope.launch {
                pendingPin = car.geocoder.reverse(point)
                    ?: Place("Dropped pin", "Long-pressed location", point)
            }
        }
        onDispose { mapHolder.setOnLongPress(null) }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    LaunchedEffect(nav.error) {
        nav.error?.let {
            toast = it
            car.nav.clearError()
        }
    }

    fun go(place: Place) {
        focus.clearFocus()
        query = ""
        results = emptyList()
        pendingPin = null
        val err = car.navigateTo(place)
        toast = err ?: "Routing to ${place.name}"
    }

    Box(Modifier.fillMaxSize()) {
        CarCard(modifier = Modifier.fillMaxSize(), contentPadding = 0.dp) {
            MapCanvas(mapHolder, Modifier.fillMaxSize())
        }

        // ------------------------------------------------------------- top left
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .width(430.dp),
        ) {
            if (nav.active && nav.route != null) {
                NavBanner(nav = nav, units = settings.units, modifier = Modifier.fillMaxWidth())
            } else {
                SearchField(
                    query = query,
                    searching = searching,
                    onQueryChange = { query = it },
                    onClear = {
                        query = ""
                        results = emptyList()
                        focus.clearFocus()
                    },
                )
                if (searchError != null) {
                    Spacer(Modifier.padding(top = 6.dp))
                    CarCard(contentPadding = 12.dp) {
                        Text(
                            searchError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 8.dp))
                    CarCard(contentPadding = 6.dp) {
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(results) { place ->
                                ResultRow(place) { go(place) }
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- right controls
        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundControl(Icons.Rounded.Add, "Zoom in", { mapHolder.zoomIn() }, size = 54.dp)
            RoundControl(Icons.Rounded.Remove, "Zoom out", { mapHolder.zoomOut() }, size = 54.dp)
            RoundControl(
                icon = Icons.Rounded.Explore,
                contentDescription = "Toggle heading-up",
                onClick = {
                    car.prefs.update { it.copy(mapHeadingUp = !it.mapHeadingUp) }
                },
                size = 54.dp,
            )
            RoundControl(
                icon = Icons.Rounded.MyLocation,
                contentDescription = "Recentre on car",
                onClick = {
                    if (!car.location.hasPermission()) {
                        onRequestPermissions()
                    } else {
                        mapHolder.recenter(
                            car.location.currentPoint(),
                            car.location.heading.value,
                            zoom = 16.5,
                        )
                    }
                },
                filled = true,
                size = 54.dp,
            )
        }

        // -------------------------------------------------------------- bottom bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                pendingPin != null -> PinConfirm(
                    place = pendingPin!!,
                    onGo = { go(pendingPin!!) },
                    onCancel = { pendingPin = null },
                )

                nav.active && nav.route != null -> NavigatingBar(
                    car = car,
                    onStop = {
                        car.nav.stop()
                        mapHolder.clearRoute()
                        toast = "Navigation stopped"
                    },
                    onOverview = { mapHolder.zoomToRoute(nav.route?.geometry.orEmpty()) },
                )

                nav.loading -> CarCard(contentPadding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Getting directions...")
                    }
                }

                else -> QuickDestinations(car) { place -> go(place) }
            }
        }

        val message = toast
        if (message != null) {
            CarCard(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentPadding = 14.dp,
            ) {
                Text(message, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search for a place or address") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            when {
                searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                query.isNotEmpty() -> Icon(
                    Icons.Rounded.Clear,
                    contentDescription = "Clear search",
                    modifier = Modifier.clickable { onClear() },
                )
            }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun ResultRow(place: Place, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(place.category.replace('_', ' '), place.address)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // How far away it is decides more than anything else whether this is the one
        // you meant, so it gets its own column rather than being buried in the address.
        place.miles?.let { mi ->
            Spacer(Modifier.width(10.dp))
            Text(
                if (mi < 10) "%.1f mi".format(mi) else "%.0f mi".format(mi),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun QuickDestinations(car: CarController, onGo: (Place) -> Unit) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val home = settings.home
    val work = settings.work
    if (home == null && work == null) return

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (home != null) {
            Button(onClick = {
                onGo(Place(home.label, home.address, LatLon(home.lat, home.lon)))
            }) {
                Icon(Icons.Rounded.Home, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Home")
            }
        }
        if (work != null) {
            Button(onClick = {
                onGo(Place(work.label, work.address, LatLon(work.lat, work.lon)))
            }) {
                Icon(Icons.Rounded.Work, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Work")
            }
        }
    }
}

@Composable
private fun PinConfirm(place: Place, onGo: () -> Unit, onCancel: () -> Unit) {
    CarCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(max = 420.dp)) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    place.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onGo) {
                Icon(Icons.Rounded.Navigation, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Drive here")
            }
            Spacer(Modifier.width(8.dp))
            RoundControl(Icons.Rounded.Close, "Cancel", onCancel, size = 48.dp)
        }
    }
}

@Composable
private fun NavigatingBar(
    car: CarController,
    onStop: () -> Unit,
    onOverview: () -> Unit,
) {
    val nav by car.nav.state.collectAsStateWithLifecycle()
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val etaFmt = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    CarCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavSummary(
                nav = nav,
                units = settings.units,
                etaText = if (nav.etaEpochMs > 0) etaFmt.format(Date(nav.etaEpochMs)) else "--",
                modifier = Modifier.width(320.dp),
            )
            Spacer(Modifier.width(16.dp))
            if (nav.rerouting) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Button(onClick = onOverview) { Text("Overview") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Stop") }
        }
    }
}

/**
 * Keeps the map's blue camera dots in step with the catalogue, and turns a tap on one
 * into a jump to that camera in the CCTV tab.
 *
 * The dots are loaded here as well as in the CCTV tab, so they appear for a driver who
 * only ever looks at the map.
 */
@Composable
private fun TrafficDots(
    car: CarController,
    mapHolder: MapHolder,
    onOpenCamera: () -> Unit,
) {
    val settings by car.prefs.state.collectAsStateWithLifecycle()
    val location by car.location.location.collectAsStateWithLifecycle()
    val catalog by car.traffic.state.collectAsStateWithLifecycle()
    val selected by car.traffic.selected.collectAsStateWithLifecycle()

    val here = location?.let { LatLon(it.latitude, it.longitude) }
    LaunchedEffect(here?.lat, here?.lon, settings.cctvRadiusMiles) {
        car.traffic.ensureLoaded(here, settings.cctvRadiusMiles)
    }

    val dots = remember(catalog) {
        (catalog as? CatalogState.Ready)?.cameras.orEmpty().map {
            CameraDot(it.camera.id, it.camera.lat, it.camera.lon)
        }
    }
    LaunchedEffect(dots) { mapHolder.setCameraDots(dots) }
    LaunchedEffect(selected) { mapHolder.setSelectedCamera(selected?.id) }

    DisposableEffect(Unit) {
        mapHolder.setOnCameraTap { id ->
            if (car.traffic.selectById(id)) onOpenCamera()
        }
        onDispose { mapHolder.setOnCameraTap(null) }
    }
}
