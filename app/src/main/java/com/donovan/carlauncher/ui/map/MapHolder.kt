package com.donovan.carlauncher.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.donovan.carlauncher.R
import com.donovan.carlauncher.data.MapTheme
import com.donovan.carlauncher.nav.LatLon
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Calendar

object TileSources {

    /**
     * Standard OpenStreetMap raster tiles. Deliberately the default: it is the only
     * good basemap that still works with no API key at all. The keyless CARTO and
     * Stadia endpoints were both closed off, so anything prettier means bringing your
     * own key via [custom].
     */
    val standard: ITileSource = TileSourceFactory.MAPNIK

    fun custom(baseUrl: String): ITileSource = XYTileSource(
        "Custom", 0, 20, 256, ".png",
        arrayOf(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"),
        "© OpenStreetMap contributors",
    )

    fun resolve(customUrl: String): ITileSource =
        if (customUrl.isNotBlank()) custom(customUrl.trim()) else standard

    /** Cheap day/night split; good enough to stop the map searing your eyes at 10pm. */
    fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 19
    }

    fun wantsNight(theme: MapTheme): Boolean = when (theme) {
        MapTheme.DARK -> true
        MapTheme.LIGHT -> false
        MapTheme.AUTO -> isNight()
    }

    /**
     * Night rendering without a dark tile set: invert the daytime tiles, then pull a
     * little saturation back out so the inverted greens and pinks do not glow.
     */
    val nightFilter: ColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        ).apply {
            postConcat(ColorMatrix().apply { setSaturation(0.55f) })
        }
    )
}

/**
 * Owns the single [MapView] instance. Creating one is expensive and it caches tiles in
 * memory, so the Home preview and the full Maps screen share this one object - they are
 * never on screen at the same time.
 */
class MapHolder(context: Context) {

    val mapView: MapView = MapView(context).apply {
        // Critical for reuse. By default osmdroid tears the whole MapView down in
        // onDetachedFromWindow - it nulls every overlay's geometry and detaches the tile
        // provider - which happens every time Compose moves this view between the Home
        // card and the Maps screen. Opting out means we own the teardown instead; see
        // MainActivity.onDestroy.
        setDestroyMode(false)
        setTileSource(TileSources.standard)
        setMultiTouchControls(true)
        isTilesScaledToDpi = true
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        setBackgroundColor(Color.parseColor("#0B0E13"))
        minZoomLevel = 3.0
        maxZoomLevel = 20.0
        controller.setZoom(16.0)
    }

    private val routeLine = Polyline(mapView).apply {
        outlinePaint.apply {
            color = Color.parseColor("#3DDC97")
            strokeWidth = 16f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        infoWindow = null
    }

    private val routeCasing = Polyline(mapView).apply {
        outlinePaint.apply {
            color = Color.parseColor("#0C2A1E")
            strokeWidth = 24f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        infoWindow = null
    }

    private val destMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
        infoWindow = null
        isEnabled = false
    }

    private val carMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_map_car)
        infoWindow = null
        isFlat = true
        isEnabled = false
    }

    private val cameraDots = CameraDotsOverlay()

    /** True while the map should chase the car; a pan or pinch turns it off. */
    var following: Boolean = true
        private set

    var headingUp: Boolean = false
        private set

    private var onUserPan: (() -> Unit)? = null
    private var onLongPress: ((LatLon) -> Unit)? = null
    private var lastTileSource: String? = null
    private var lastNight: Boolean? = null

    private val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

        override fun longPressHelper(p: GeoPoint?): Boolean {
            p ?: return false
            val handler = onLongPress ?: return false
            handler(LatLon(p.latitude, p.longitude))
            return true
        }
    })

    init {
        routeLine.isEnabled = false
        routeCasing.isEnabled = false
        mapView.overlays.add(eventsOverlay)
        mapView.overlays.add(cameraDots)
        mapView.overlays.add(routeCasing)
        mapView.overlays.add(routeLine)
        mapView.overlays.add(destMarker)
        mapView.overlays.add(carMarker)
        attachTouchWatcher()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchWatcher() {
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                if (following) {
                    following = false
                    onUserPan?.invoke()
                }
            }
            false // let the MapView handle the gesture as usual
        }
    }

    fun setOnUserPan(listener: (() -> Unit)?) {
        onUserPan = listener
    }

    /** Long-pressing anywhere on the map offers it as a destination. */
    fun setOnLongPress(listener: ((LatLon) -> Unit)?) {
        onLongPress = listener
    }

    fun detachFromParent() {
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    // ------------------------------------------------------------------ appearance

    fun applyTheme(theme: MapTheme, customUrl: String) {
        val source = TileSources.resolve(customUrl)
        if (lastTileSource != source.name()) {
            lastTileSource = source.name()
            mapView.setTileSource(source)
        }
        // A custom tile set is assumed to already be styled the way the user wants it.
        val night = customUrl.isBlank() && TileSources.wantsNight(theme)
        if (night != lastNight) {
            lastNight = night
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (night) TileSources.nightFilter else null
            )
        }
        mapView.invalidate()
    }

    // ------------------------------------------------------------------ contents

    fun setCar(point: LatLon?, headingDegrees: Float) {
        if (point == null) {
            carMarker.isEnabled = false
            return
        }
        carMarker.isEnabled = true
        carMarker.position = GeoPoint(point.lat, point.lon)
        // The icon points north; counter-rotate it when the map itself is rotated.
        carMarker.rotation = if (headingUp) 0f else -headingDegrees
        mapView.invalidate()
    }

    fun setDestination(point: LatLon?) {
        if (point == null) {
            destMarker.isEnabled = false
        } else {
            destMarker.isEnabled = true
            destMarker.position = GeoPoint(point.lat, point.lon)
        }
        mapView.invalidate()
    }

    /** Blue dots for the public traffic cameras currently in range. */
    fun setCameraDots(dots: List<CameraDot>) {
        if (cameraDots.dots == dots) return
        cameraDots.dots = dots
        mapView.invalidate()
    }

    fun setSelectedCamera(id: String?) {
        if (cameraDots.selectedId == id) return
        cameraDots.selectedId = id
        mapView.invalidate()
    }

    /** Tapping a dot hands back the camera id so the CCTV tab can jump to it. */
    fun setOnCameraTap(listener: ((String) -> Unit)?) {
        cameraDots.onTap = listener
    }

    fun setRoute(points: List<LatLon>) {
        if (points.isEmpty()) {
            clearRoute()
            return
        }
        val geo = points.map { GeoPoint(it.lat, it.lon) }
        routeLine.setPoints(geo)
        routeCasing.setPoints(geo)
        routeLine.isEnabled = true
        routeCasing.isEnabled = true
        mapView.invalidate()
    }

    /**
     * Hides the route rather than emptying it: osmdroid throws from
     * Polyline.setPoints(emptyList()).
     */
    fun clearRoute() {
        routeLine.isEnabled = false
        routeCasing.isEnabled = false
        destMarker.isEnabled = false
        mapView.invalidate()
    }

    // ------------------------------------------------------------------ camera

    fun recenter(point: LatLon?, headingDegrees: Float, zoom: Double? = null) {
        following = true
        point ?: return
        mapView.controller.animateTo(GeoPoint(point.lat, point.lon))
        if (zoom != null) mapView.controller.setZoom(zoom)
        if (headingUp) mapView.mapOrientation = -headingDegrees
    }

    fun followCar(point: LatLon?, headingDegrees: Float) {
        if (!following || point == null) return
        mapView.controller.setCenter(GeoPoint(point.lat, point.lon))
        if (headingUp) mapView.mapOrientation = -headingDegrees
    }

    fun toggleHeadingUp(headingDegrees: Float) {
        headingUp = !headingUp
        mapView.mapOrientation = if (headingUp) -headingDegrees else 0f
        mapView.invalidate()
    }

    fun zoomIn() = mapView.controller.zoomIn()

    fun zoomOut() = mapView.controller.zoomOut()

    /** Frames a whole route with padding so the driver can see where they are going. */
    fun zoomToRoute(points: List<LatLon>) {
        if (points.size < 2) return
        following = false
        val box = org.osmdroid.util.BoundingBox.fromGeoPoints(
            points.map { GeoPoint(it.lat, it.lon) }
        )
        runCatching { mapView.zoomToBoundingBox(box, true, 120) }
    }

    /**
     * Restores where the map was last looking. Without this a cold start with no GPS
     * fix yet parks the camera at 0,0 - the middle of the Atlantic.
     */
    fun restoreCamera(lat: Double, lon: Double, zoom: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        mapView.controller.setZoom(zoom.coerceIn(3.0, 20.0))
        mapView.controller.setCenter(GeoPoint(lat, lon))
    }

    fun cameraLat(): Double = mapView.mapCenter.latitude

    fun cameraLon(): Double = mapView.mapCenter.longitude

    fun cameraZoom(): Double = mapView.zoomLevelDouble

    fun onResume() = mapView.onResume()

    fun onPause() = mapView.onPause()

    /** Real teardown, since [setDestroyMode] is off. Call once, when the activity dies. */
    fun destroy() {
        runCatching { mapView.onDetach() }
    }
}
