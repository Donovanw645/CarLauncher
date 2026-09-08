package com.donovan.carlauncher

import android.app.Application
import android.content.Context
import com.donovan.carlauncher.dashcam.Dashcam
import com.donovan.carlauncher.data.Prefs
import com.donovan.carlauncher.media.MediaBrowserRepo
import com.donovan.carlauncher.media.MediaRemote
import com.donovan.carlauncher.nav.GeocodeApi
import com.donovan.carlauncher.nav.Http
import com.donovan.carlauncher.nav.LatLon
import com.donovan.carlauncher.nav.NavEngine
import com.donovan.carlauncher.nav.RouteApi
import com.donovan.carlauncher.nav.Speech
import com.donovan.carlauncher.system.AppRepo
import com.donovan.carlauncher.system.CarStatusRepo
import com.donovan.carlauncher.system.LocationRepo
import com.donovan.carlauncher.traffic.TrafficRepo
import com.donovan.carlauncher.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * One place that owns every long-lived piece of the launcher. The app is a single
 * activity with no process-death state to restore, so a plain service locator on the
 * Application beats wiring a DI graph.
 */
class CarController(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = Prefs(app)
    val location = LocationRepo(app)
    val status = CarStatusRepo(app)
    val apps = AppRepo(app)
    val mediaRemote = MediaRemote(app)
    val browser = MediaBrowserRepo(app)
    val speech = Speech(app)
    val updater = Updater(app, prefs, scope)
    val traffic = TrafficRepo(app, scope)

    val geocoder = GeocodeApi { prefs.current.geocoderBaseUrl }
    val router = RouteApi { prefs.current.routerBaseUrl }

    val nav = NavEngine(
        routeApi = router,
        scope = scope,
        speech = speech,
        units = { prefs.current.units },
        voiceEnabled = { prefs.current.voiceGuidance },
    )

    init {
        scope.launch {
            location.location.filterNotNull().collect { loc ->
                nav.onLocation(LatLon(loc.latitude, loc.longitude))
            }
        }
    }

    /** Starts a route from wherever the car is now. Returns an error message, or null. */
    fun navigateTo(place: com.donovan.carlauncher.nav.Place): String? {
        if (!location.hasPermission()) return "Location permission is off"
        val from = location.currentPoint() ?: return "Waiting for a GPS fix"
        nav.start(from, place)
        return null
    }

    fun onForeground() {
        location.start()
        status.start()
        mediaRemote.start()
        mediaRemote.attach()
        browser.refreshApps()
        scope.launch { apps.refresh() }
        Dashcam.refreshClips(app)
        // Throttled internally to one call every six hours.
        updater.check()
    }

    fun onBackground() {
        status.stop()
        // Location and the media remote stay live: navigation and the Now Playing
        // widget must keep working while the screen is dimmed at a red light.
    }

    fun shutdown() {
        location.stop()
        status.stop()
        mediaRemote.stop()
        browser.disconnect()
        speech.shutdown()
    }
}

class CarLauncherApp : Application() {

    lateinit var controller: CarController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = CarController(this)

    }
}

val Context.car: CarController
    get() = (applicationContext as CarLauncherApp).controller
