package com.donovan.carlauncher.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.donovan.carlauncher.R
import com.donovan.carlauncher.data.MapTheme
import com.donovan.carlauncher.nav.LatLon
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Calendar

/** One camera's position on the map. */
data class CameraDot(val id: String, val lat: Double, val lon: Double)

object MapStyles {

    /**
     * OpenFreeMap vector styles. Free, no API key, no rate limit - which matters,
     * because the keyless CARTO and Stadia raster endpoints were both closed off and
     * broke this app once already.
     */
    const val DAY = "https://tiles.openfreemap.org/styles/liberty"
    const val NIGHT = "https://tiles.openfreemap.org/styles/dark"

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
     * A style document wrapping a plain XYZ raster server, so the custom tile URL in
     * settings keeps working now that the renderer speaks vector tiles natively.
     */
    fun rasterStyle(baseUrl: String): String {
        val url = (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/") + "{z}/{x}/{y}.png"
        return JSONObject().apply {
            put("version", 8)
            put("sources", JSONObject().put(
                "custom",
                JSONObject().apply {
                    put("type", "raster")
                    put("tiles", JSONArray().put(url))
                    put("tileSize", 256)
                },
            ))
            put("layers", JSONArray().put(
                JSONObject().apply {
                    put("id", "custom")
                    put("type", "raster")
                    put("source", "custom")
                },
            ))
        }.toString()
    }
}

/**
 * Owns the single [MapView] instance and everything drawn on it.
 *
 * Creating a map is expensive and it caches tiles, so the Home preview and the full
 * Maps screen share this one object - they are never on screen at the same time.
 *
 * MapLibre hands back its map and style asynchronously, unlike the osmdroid setup this
 * replaced, so every setter here records what it was asked for and [applyAll] replays
 * that onto the style once it arrives. Without that, anything set during startup - the
 * restored camera, a route already in progress - would be silently dropped.
 */
class MapHolder(context: Context) {

    init {
        MapLibre.getInstance(context)
    }

    val mapView: MapView = MapView(context).apply {
        onCreate(null)
    }

    private var map: MapLibreMap? = null
    private var style: Style? = null

    // Desired state, held so it survives the async style load and style swaps.
    private var car: LatLon? = null
    private var carBearing: Float = 0f
    private var destination: LatLon? = null
    private var route: List<LatLon> = emptyList()
    private var dots: List<CameraDot> = emptyList()
    private var selectedCamera: String? = null
    private var styleUri: String? = null
    private var pendingCamera: CameraPosition? = null

    var following: Boolean = true
        private set

    var headingUp: Boolean = false
        private set

    private var onUserPan: (() -> Unit)? = null
    private var onLongPress: ((LatLon) -> Unit)? = null
    private var onCameraTap: ((String) -> Unit)? = null

    init {
        mapView.getMapAsync { m ->
            map = m
            m.setMinZoomPreference(3.0)
            m.setMaxZoomPreference(20.0)
            m.uiSettings.apply {
                isAttributionEnabled = false
                isLogoEnabled = false
                isCompassEnabled = false
                isRotateGesturesEnabled = false // heading-up is a deliberate toggle
                isTiltGesturesEnabled = false
            }

            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE &&
                    following
                ) {
                    following = false
                    onUserPan?.invoke()
                }
            }

            m.addOnMapLongClickListener { p ->
                onLongPress?.invoke(LatLon(p.latitude, p.longitude))
                onLongPress != null
            }

            m.addOnMapClickListener { p ->
                val handler = onCameraTap
                if (handler == null) {
                    false
                } else {
                    val screen = m.projection.toScreenLocation(p)
                    // A finger is far bigger than a 5px dot, so search a box around it.
                    val hit = m.queryRenderedFeatures(
                        android.graphics.RectF(
                            screen.x - TOUCH_SLOP, screen.y - TOUCH_SLOP,
                            screen.x + TOUCH_SLOP, screen.y + TOUCH_SLOP,
                        ),
                        LAYER_CAMS,
                    ).firstOrNull()
                    val id = hit?.getStringProperty("id")
                    if (id != null) {
                        selectedCamera = id
                        pushCameras()
                        handler(id)
                        true
                    } else false
                }
            }

            applyStyle(styleUri ?: MapStyles.DAY)
            // osmdroid was constructed at zoom 16; MapLibre starts at world view, so
            // without this a first run with no saved camera opens on the whole planet.
            val start = pendingCamera ?: CameraPosition.Builder()
                .target(LatLng(DEFAULT_LAT, DEFAULT_LON))
                .zoom(DEFAULT_ZOOM)
                .build()
            m.moveCamera(CameraUpdateFactory.newCameraPosition(start))
            pendingCamera = null
        }
        attachTouchWatcher()
    }

    /**
     * The camera-move listener only fires once a gesture has actually moved the map, so
     * a plain touch-down would not stop the map chasing the car until it was too late.
     */
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
            false
        }
    }

    fun setOnUserPan(listener: (() -> Unit)?) { onUserPan = listener }

    fun setOnLongPress(listener: ((LatLon) -> Unit)?) { onLongPress = listener }

    fun setOnCameraTap(listener: ((String) -> Unit)?) { onCameraTap = listener }

    fun detachFromParent() {
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    // ------------------------------------------------------------------ appearance

    fun applyTheme(theme: MapTheme, customUrl: String) {
        val wanted = when {
            customUrl.isNotBlank() -> MapStyles.rasterStyle(customUrl.trim())
            MapStyles.wantsNight(theme) -> MapStyles.NIGHT
            else -> MapStyles.DAY
        }
        if (wanted == styleUri) return
        styleUri = wanted
        applyStyle(wanted)
    }

    private fun applyStyle(uri: String) {
        val m = map ?: return
        val builder = if (uri.startsWith("http")) {
            Style.Builder().fromUri(uri)
        } else {
            Style.Builder().fromJson(uri)
        }
        m.setStyle(builder) { s ->
            style = s
            if (uri == MapStyles.NIGHT) brightenNightRoads(s)
            buildLayers(s)
            applyAll()
        }
    }

    /**
     * OpenFreeMap's dark style is built as a quiet backdrop for data overlays, not as
     * something you navigate by: the background is rgb(12,12,12) and minor roads are
     * #181818, which is very nearly black on black. These overrides lift the road
     * network back to readable contrast while keeping the map dark enough for night
     * driving. Every lookup is null-safe, so if upstream renames a layer the map simply
     * keeps that layer's own colour instead of breaking.
     */
    private fun brightenNightRoads(s: Style) {
        fun line(id: String, color: String) {
            s.getLayerAs<LineLayer>(id)?.setProperties(PropertyFactory.lineColor(color))
        }
        line("highway_minor", "#3C3C41")
        line("highway_major_inner", "#6E6E75")
        line("highway_major_subtle", "#4A4A50")
        line("highway_motorway_inner", "#C9A227")   // amber, the way night maps mark motorways
        line("highway_motorway_subtle", "#6B5A1E")
        line("highway_path", "#2E2E33")
        // Water at rgb(27,27,29) is invisible; a little blue helps you place yourself.
        s.getLayerAs<FillLayer>("water")
            ?.setProperties(PropertyFactory.fillColor("#132430"))
    }

    /** Sources and layers are recreated from scratch every time the style changes. */
    private fun buildLayers(s: Style) {
        s.addImage(IMG_CAR, drawableToBitmap(R.drawable.ic_map_car))
        s.addImage(IMG_PIN, drawableToBitmap(R.drawable.ic_map_pin))

        s.addSource(GeoJsonSource(SRC_ROUTE, EMPTY_FC))
        s.addSource(GeoJsonSource(SRC_CAMS, EMPTY_FC))
        s.addSource(GeoJsonSource(SRC_DEST, EMPTY_FC))
        s.addSource(GeoJsonSource(SRC_CAR, EMPTY_FC))

        // Casing under the line so the route reads as a single stroked ribbon.
        s.addLayer(
            LineLayer(LAYER_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#0C2A1E"),
                PropertyFactory.lineWidth(12f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            )
        )
        s.addLayer(
            LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor("#3DDC97"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
            )
        )
        s.addLayer(
            CircleLayer(LAYER_CAMS, SRC_CAMS).withProperties(
                PropertyFactory.circleColor("#4DA3FF"),
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleStrokeColor("#EAF4FF"),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
        s.addLayer(
            SymbolLayer(LAYER_DEST, SRC_DEST).withProperties(
                PropertyFactory.iconImage(IMG_PIN),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        )
        s.addLayer(
            SymbolLayer(LAYER_CAR, SRC_CAR).withProperties(
                PropertyFactory.iconImage(IMG_CAR),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotate(0f),
            )
        )
    }

    private fun applyAll() {
        pushCar()
        pushDestination()
        pushRoute()
        pushCameras()
    }

    private fun drawableToBitmap(resId: Int): Bitmap {
        val d = ContextCompat.getDrawable(mapView.context, resId)!!
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    // -------------------------------------------------------------------- contents

    fun setCar(point: LatLon?, headingDegrees: Float) {
        car = point
        carBearing = headingDegrees
        pushCar()
    }

    private fun pushCar() {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(SRC_CAR)?.setGeoJson(pointFc(car))
        // The icon points north; counter-rotate when the map itself is turned.
        val rotation = if (headingUp) 0f else carBearing
        s.getLayerAs<SymbolLayer>(LAYER_CAR)
            ?.setProperties(PropertyFactory.iconRotate(rotation))
    }

    fun setDestination(point: LatLon?) {
        destination = point
        pushDestination()
    }

    private fun pushDestination() {
        style?.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(pointFc(destination))
    }

    fun setRoute(points: List<LatLon>) {
        route = points
        pushRoute()
    }

    private fun pushRoute() {
        val s = style ?: return
        val fc = if (route.size < 2) EMPTY_FC else {
            val coords = JSONArray()
            for (p in route) coords.put(JSONArray().put(p.lon).put(p.lat))
            featureCollection(
                JSONObject().apply {
                    put("type", "Feature")
                    put("properties", JSONObject())
                    put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                }
            )
        }
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(fc)
    }

    fun clearRoute() {
        route = emptyList()
        destination = null
        pushRoute()
        pushDestination()
    }

    /** Blue dots for the public traffic cameras currently in range. */
    fun setCameraDots(newDots: List<CameraDot>) {
        if (dots == newDots) return
        dots = newDots
        selectedCamera = selectedCamera?.takeIf { id -> newDots.any { it.id == id } }
        pushCameras()
    }

    fun setSelectedCamera(id: String?) {
        if (selectedCamera == id) return
        selectedCamera = id
        pushCameras()
    }

    private fun pushCameras() {
        val s = style ?: return
        val features = JSONArray()
        for (d in dots) {
            features.put(
                JSONObject().apply {
                    put("type", "Feature")
                    put("properties", JSONObject().apply {
                        put("id", d.id)
                        put("selected", d.id == selectedCamera)
                    })
                    put("geometry", JSONObject().apply {
                        put("type", "Point")
                        put("coordinates", JSONArray().put(d.lon).put(d.lat))
                    })
                }
            )
        }
        s.getSourceAs<GeoJsonSource>(SRC_CAMS)
            ?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    private fun pointFc(p: LatLon?): String = if (p == null) EMPTY_FC else featureCollection(
        JSONObject().apply {
            put("type", "Feature")
            put("properties", JSONObject())
            put("geometry", JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(p.lon).put(p.lat))
            })
        }
    )

    private fun featureCollection(feature: JSONObject): String = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", JSONArray().put(feature))
        .toString()

    // ---------------------------------------------------------------------- camera

    fun recenter(point: LatLon?, headingDegrees: Float, zoom: Double? = null) {
        following = true
        val m = map ?: return
        point ?: return
        val builder = CameraPosition.Builder(m.cameraPosition)
            .target(LatLng(point.lat, point.lon))
        if (zoom != null) builder.zoom(zoom)
        if (headingUp) builder.bearing(headingDegrees.toDouble())
        m.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
    }

    fun followCar(point: LatLon?, headingDegrees: Float) {
        if (!following || point == null) return
        val m = map ?: return
        val builder = CameraPosition.Builder(m.cameraPosition)
            .target(LatLng(point.lat, point.lon))
        // A first fix arriving while the map is still at its opening zoom should pull
        // in to something you can drive by. Panning clears `following`, so this cannot
        // fight a deliberate zoom-out.
        if (m.cameraPosition.zoom < DRIVING_ZOOM_FLOOR) builder.zoom(DEFAULT_ZOOM)
        if (headingUp) builder.bearing(headingDegrees.toDouble())
        m.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
    }

    fun toggleHeadingUp(headingDegrees: Float) {
        headingUp = !headingUp
        val m = map ?: return
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(m.cameraPosition)
                    .bearing(if (headingUp) headingDegrees.toDouble() else 0.0)
                    .build()
            )
        )
        pushCar()
    }

    fun zoomIn() { map?.animateCamera(CameraUpdateFactory.zoomIn()) }

    fun zoomOut() { map?.animateCamera(CameraUpdateFactory.zoomOut()) }

    /** Frames a whole route with padding so the driver can see where they are going. */
    fun zoomToRoute(points: List<LatLon>) {
        if (points.size < 2) return
        val m = map ?: return
        following = false
        val bounds = LatLngBounds.Builder()
            .includes(points.map { LatLng(it.lat, it.lon) })
            .build()
        runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
    }

    /**
     * Restores where the map was last looking. Without this a cold start with no GPS
     * fix yet parks the camera at 0,0 - the middle of the Atlantic.
     */
    fun restoreCamera(lat: Double, lon: Double, zoom: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        val position = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom.coerceIn(3.0, 20.0))
            .build()
        val m = map
        // Usually called before the map is ready; hold it until it is.
        if (m == null) pendingCamera = position
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(position))
    }

    fun cameraLat(): Double = map?.cameraPosition?.target?.latitude ?: Double.NaN

    fun cameraLon(): Double = map?.cameraPosition?.target?.longitude ?: Double.NaN

    fun cameraZoom(): Double = map?.cameraPosition?.zoom ?: 16.0

    // ------------------------------------------------------------------- lifecycle

    fun onStart() = mapView.onStart()

    fun onResume() = mapView.onResume()

    fun onPause() = mapView.onPause()

    fun onStop() = mapView.onStop()

    fun onLowMemory() = mapView.onLowMemory()

    fun destroy() {
        runCatching { mapView.onDestroy() }
    }

    private companion object {
        const val SRC_ROUTE = "car-route"
        const val SRC_CAMS = "car-cams"
        const val SRC_DEST = "car-dest"
        const val SRC_CAR = "car-position"

        const val LAYER_ROUTE_CASING = "car-route-casing"
        const val LAYER_ROUTE = "car-route-line"
        const val LAYER_CAMS = "car-cams-dots"
        const val LAYER_DEST = "car-dest-pin"
        const val LAYER_CAR = "car-marker"

        const val IMG_CAR = "car-icon"
        const val IMG_PIN = "pin-icon"

        const val TOUCH_SLOP = 28f

        const val DEFAULT_ZOOM = 16.0
        const val DRIVING_ZOOM_FLOOR = 10.0
        // Roughly the middle of California - only ever seen for the instant before the
        // first GPS fix on a fresh install.
        const val DEFAULT_LAT = 36.7783
        const val DEFAULT_LON = -119.4179

        const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
    }
}
