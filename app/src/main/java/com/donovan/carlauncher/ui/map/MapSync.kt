package com.donovan.carlauncher.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donovan.carlauncher.CarController
import com.donovan.carlauncher.nav.LatLon

/**
 * Pushes live state into the shared [MapHolder]. Mounted by whichever screen currently
 * shows the map, so there is exactly one writer at a time.
 */
@Composable
fun MapSync(car: CarController, mapHolder: MapHolder) {
    val location by car.location.location.collectAsStateWithLifecycle()
    val heading by car.location.heading.collectAsStateWithLifecycle()
    val navState by car.nav.state.collectAsStateWithLifecycle()
    val settings by car.prefs.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings.mapTheme, settings.customTileUrl) {
        mapHolder.applyTheme(settings.mapTheme, settings.customTileUrl)
    }

    LaunchedEffect(settings.mapHeadingUp, heading) {
        mapHolder.setHeadingUp(settings.mapHeadingUp, heading)
    }

    LaunchedEffect(location, heading) {
        val point = location?.let { LatLon(it.latitude, it.longitude) }
        mapHolder.setCar(point, heading)
        mapHolder.followCar(point, heading)
    }

    LaunchedEffect(navState.route, navState.destination) {
        val route = navState.route
        if (route == null) {
            mapHolder.clearRoute()
        } else {
            mapHolder.setRoute(route.geometry)
            mapHolder.setDestination(navState.destination?.point)
        }
    }
}
