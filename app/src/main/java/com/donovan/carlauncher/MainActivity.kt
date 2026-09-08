package com.donovan.carlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.donovan.carlauncher.data.OrientationMode
import com.donovan.carlauncher.dashcam.Dashcam
import com.donovan.carlauncher.media.CarNotificationListener
import com.donovan.carlauncher.ui.CarShell
import com.donovan.carlauncher.ui.map.MapHolder
import com.donovan.carlauncher.ui.theme.CarLauncherTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var mapHolder: MapHolder

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Whatever the user granted, re-read everything that depends on permissions.
        car.location.start()
        car.status.refreshBluetooth()
        car.browser.refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mapHolder = MapHolder(this)
        car.prefs.current.let { s -> mapHolder.restoreCamera(s.lastLat, s.lastLon, s.lastZoom) }
        goFullScreen()

        setContent {
            val settings by car.prefs.state.collectAsStateWithLifecycle()

            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(settings.orientation) {
                requestedOrientation = when (settings.orientation) {
                    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }

            val accent = remember(settings.accent) { Color(settings.accent) }

            CarLauncherTheme(accent = accent) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    CarShell(
                        car = car,
                        mapHolder = mapHolder,
                        onOpenNotificationSettings = ::openNotificationAccess,
                        onRequestPermissions = ::requestCorePermissions,
                    )
                }
            }
        }

        requestCorePermissions()

        if (car.prefs.current.dashAutoStart && Dashcam.hasCameraPermission(this)) {
            Dashcam.start(this)
        }
    }

    override fun onStart() {
        super.onStart()
        mapHolder.onStart()
    }

    override fun onStop() {
        super.onStop()
        mapHolder.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapHolder.onLowMemory()
    }

    override fun onResume() {
        super.onResume()
        goFullScreen()
        car.onForeground()
        mapHolder.onResume()
    }

    override fun onPause() {
        super.onPause()
        car.onBackground()
        saveCamera()
        mapHolder.onPause()
    }

    private fun saveCamera() {
        val lat = mapHolder.cameraLat()
        val lon = mapHolder.cameraLon()
        if (lat.isNaN() || lon.isNaN()) return
        // 0,0 means the camera never went anywhere real; do not persist it.
        if (lat == 0.0 && lon == 0.0) return
        car.prefs.update {
            it.copy(lastLat = lat, lastLon = lon, lastZoom = mapHolder.cameraZoom())
        }
    }

    override fun onDestroy() {
        // Only tear the world down on a real exit - a config change keeps navigation
        // and playback alive.
        if (isFinishing) car.shutdown()
        mapHolder.destroy()
        super.onDestroy()
    }

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun openNotificationAccess() {
        runCatching { startActivity(CarNotificationListener.settingsIntent()) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    private fun requestCorePermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            addAll(Dashcam.requiredPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.distinct()
        val missing = wanted.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }
        }
    }
}
