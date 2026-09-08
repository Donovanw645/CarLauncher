package com.donovan.carlauncher.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** One camera's position on the map. */
data class CameraDot(val id: String, val lat: Double, val lon: Double)

/**
 * Draws every nearby traffic camera as a blue dot and reports taps on them.
 *
 * Deliberately a single overlay drawing circles rather than one osmdroid [Marker] per
 * camera: a busy stretch of freeway can put a couple of hundred cameras in range, and
 * that many Markers - each its own overlay with its own drawable and hit-testing -
 * visibly costs frames while panning.
 */
class CameraDotsOverlay : Overlay() {

    var dots: List<CameraDot> = emptyList()
        set(value) {
            field = value
            selectedId = selectedId?.takeIf { id -> value.any { it.id == id } }
        }

    var selectedId: String? = null

    /** Called with the camera id when one of the dots is tapped. */
    var onTap: ((String) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4DA3FF")
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#EAF4FF")
    }

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#334DA3FF")
    }

    private val reuse = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || dots.isEmpty()) return
        val projection = mapView.projection
        val w = mapView.width
        val h = mapView.height

        for (dot in dots) {
            projection.toPixels(GeoPoint(dot.lat, dot.lon), reuse)
            val x = reuse.x.toFloat()
            val y = reuse.y.toFloat()
            // Cheap cull: a dot well off screen costs nothing to skip.
            if (x < -RADIUS_PX || y < -RADIUS_PX || x > w + RADIUS_PX || y > h + RADIUS_PX) {
                continue
            }
            if (dot.id == selectedId) {
                canvas.drawCircle(x, y, RADIUS_PX * 2.2f, halo)
            }
            canvas.drawCircle(x, y, RADIUS_PX, fill)
            canvas.drawCircle(x, y, RADIUS_PX, ring)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val handler = onTap ?: return false
        if (dots.isEmpty()) return false
        val projection = mapView.projection

        // Nearest dot inside the touch slop, so overlapping cameras resolve to the one
        // actually closest to the finger instead of whichever was drawn first.
        var bestId: String? = null
        var bestDist = TOUCH_SLOP_PX * TOUCH_SLOP_PX
        for (dot in dots) {
            projection.toPixels(GeoPoint(dot.lat, dot.lon), reuse)
            val dx = e.x - reuse.x
            val dy = e.y - reuse.y
            val d2 = dx * dx + dy * dy
            if (d2 <= bestDist) {
                bestDist = d2
                bestId = dot.id
            }
        }
        val id = bestId ?: return false
        selectedId = id
        mapView.invalidate()
        handler(id)
        return true
    }

    private companion object {
        const val RADIUS_PX = 9f
        const val TOUCH_SLOP_PX = 34f
    }
}
