package com.donovan.carlauncher.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.donovan.carlauncher.nav.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper over [LocationManager]. Uses the platform API directly so the app has no
 * dependency on Google Play services - plenty of cheap dash tablets ship without them.
 */
class LocationRepo(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    /** Heading in degrees, smoothed and held through brief stops. */
    private val _heading = MutableStateFlow(0f)
    val heading: StateFlow<Float> = _heading.asStateFlow()

    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _location.value = location
            // Bearing is only meaningful once actually moving; below walking pace the
            // GPS bearing jitters wildly, so hold the last good value.
            if (location.hasBearing() && location.hasSpeed() && location.speed > 1.5f) {
                _heading.value = location.bearing
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun gpsEnabled(): Boolean =
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !hasPermission()) return
        listening = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (p in providers) {
            if (!lm.allProviders.contains(p)) continue
            runCatching {
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (_location.value == null) {
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { _location.value = it }
            }
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { lm.removeUpdates(listener) }
    }

    fun currentPoint(): LatLon? = _location.value?.let { LatLon(it.latitude, it.longitude) }
}
