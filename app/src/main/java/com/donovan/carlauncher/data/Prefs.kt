package com.donovan.carlauncher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class Units { IMPERIAL, METRIC }

enum class MapTheme { AUTO, DARK, LIGHT }

enum class OrientationMode { LANDSCAPE, PORTRAIT, AUTO }

enum class DashcamQuality(val label: String) {
    SD("480p"),
    HD("720p"),
    FHD("1080p"),
    UHD("4K"),
}

enum class CameraFacing { BACK, FRONT }

/** A saved shortcut (Home / Work / favourite). */
data class SavedPlace(
    val label: String,
    val address: String,
    val lat: Double,
    val lon: Double,
)

data class CarSettings(
    val units: Units = Units.IMPERIAL,
    val mapTheme: MapTheme = MapTheme.AUTO,
    val orientation: OrientationMode = OrientationMode.LANDSCAPE,
    val keepScreenOn: Boolean = true,
    val voiceGuidance: Boolean = true,
    val showSpeedometer: Boolean = true,
    val speedAlertLimit: Int = 0, // 0 = off, otherwise mph/kmh depending on units
    val accent: Long = 0xFF3DDC97,
    val routerBaseUrl: String = "https://router.project-osrm.org",
    val geocoderBaseUrl: String = "https://nominatim.openstreetmap.org",
    val customTileUrl: String = "",
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val school: SavedPlace? = null,
    /** Where the map was last looking, so a cold start without a fix is not mid-ocean. */
    val lastLat: Double = Double.NaN,
    val lastLon: Double = Double.NaN,
    val lastZoom: Double = 16.0,

    // Dashcam
    val dashQuality: DashcamQuality = DashcamQuality.FHD,
    val dashFacing: CameraFacing = CameraFacing.BACK,
    val dashSegmentMinutes: Int = 3,
    val dashMaxStorageGb: Int = 8,
    val dashRecordAudio: Boolean = false,
    val dashAutoStart: Boolean = false,
    /** Index into the device's external files dirs: 0 is internal, 1+ are SD cards. */
    val dashStorageIndex: Int = 0,

    /** Rotate the map so the car always points up, instead of north-up. */
    val mapHeadingUp: Boolean = true,

    // CCTV
    /** How far from the car to look for public Caltrans cameras. */
    val cctvRadiusMiles: Int = 25,

    // Software updates
    /** Poll the update channel in the background. */
    val updateCheckEnabled: Boolean = true,
    /** Fetch the APK without being asked - but only ever on an unmetered network. */
    val updateAutoDownload: Boolean = true,
    val updateManifestUrl: String = DEFAULT_UPDATE_URL,
    val updateLastCheckMs: Long = 0L,
    /** A versionCode the user chose to pass on. */
    val updateSkippedVersion: Int = 0,
)

/**
 * Where the launcher looks for new builds of itself. The address is fixed forever;
 * the manifest behind it names the APK for whichever release is newest.
 */
const val DEFAULT_UPDATE_URL =
    "https://github.com/donovanw645/CarLauncher/releases/latest/download/update.json"

/**
 * SharedPreferences-backed settings exposed as a single observable snapshot.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("car_launcher", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<CarSettings> = _state.asStateFlow()

    val current: CarSettings get() = _state.value

    fun update(block: (CarSettings) -> CarSettings) {
        val next = block(_state.value)
        write(next)
        _state.value = next
    }

    private fun read(): CarSettings = CarSettings(
        units = enumOr(sp.getString(K_UNITS, null), Units.IMPERIAL),
        mapTheme = enumOr(sp.getString(K_MAP_THEME, null), MapTheme.AUTO),
        orientation = enumOr(sp.getString(K_ORIENTATION, null), OrientationMode.LANDSCAPE),
        keepScreenOn = sp.getBoolean(K_KEEP_ON, true),
        voiceGuidance = sp.getBoolean(K_VOICE, true),
        showSpeedometer = sp.getBoolean(K_SPEEDO, true),
        speedAlertLimit = sp.getInt(K_SPEED_ALERT, 0),
        accent = sp.getLong(K_ACCENT, 0xFF3DDC97),
        routerBaseUrl = sp.getString(K_ROUTER, null) ?: "https://router.project-osrm.org",
        geocoderBaseUrl = sp.getString(K_GEOCODER, null) ?: "https://nominatim.openstreetmap.org",
        customTileUrl = sp.getString(K_TILES, null) ?: "",
        home = readPlace(K_HOME),
        work = readPlace(K_WORK),
        school = readPlace(K_SCHOOL),
        lastLat = readDouble(K_LAST_LAT, Double.NaN),
        lastLon = readDouble(K_LAST_LON, Double.NaN),
        lastZoom = readDouble(K_LAST_ZOOM, 16.0),
        dashQuality = enumOr(sp.getString(K_DASH_QUALITY, null), DashcamQuality.FHD),
        dashFacing = enumOr(sp.getString(K_DASH_FACING, null), CameraFacing.BACK),
        dashSegmentMinutes = sp.getInt(K_DASH_SEGMENT, 3),
        dashMaxStorageGb = sp.getInt(K_DASH_STORAGE, 8),
        dashRecordAudio = sp.getBoolean(K_DASH_AUDIO, false),
        dashAutoStart = sp.getBoolean(K_DASH_AUTOSTART, false),
        dashStorageIndex = sp.getInt(K_DASH_STORAGE_INDEX, 0),
        mapHeadingUp = sp.getBoolean(K_HEADING_UP, true),
        cctvRadiusMiles = sp.getInt(K_CCTV_RADIUS, 25),
        updateCheckEnabled = sp.getBoolean(K_UPD_CHECK, true),
        updateAutoDownload = sp.getBoolean(K_UPD_AUTO, true),
        updateManifestUrl = sp.getString(K_UPD_URL, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UPDATE_URL,
        updateLastCheckMs = sp.getLong(K_UPD_LAST, 0L),
        updateSkippedVersion = sp.getInt(K_UPD_SKIP, 0),
    )

    private fun readDouble(key: String, fallback: Double): Double =
        sp.getString(key, null)?.toDoubleOrNull() ?: fallback

    private fun write(s: CarSettings) = sp.edit().apply {
        putString(K_UNITS, s.units.name)
        putString(K_MAP_THEME, s.mapTheme.name)
        putString(K_ORIENTATION, s.orientation.name)
        putBoolean(K_KEEP_ON, s.keepScreenOn)
        putBoolean(K_VOICE, s.voiceGuidance)
        putBoolean(K_SPEEDO, s.showSpeedometer)
        putInt(K_SPEED_ALERT, s.speedAlertLimit)
        putLong(K_ACCENT, s.accent)
        putString(K_ROUTER, s.routerBaseUrl)
        putString(K_GEOCODER, s.geocoderBaseUrl)
        putString(K_TILES, s.customTileUrl)
        writePlace(this, K_HOME, s.home)
        writePlace(this, K_WORK, s.work)
        writePlace(this, K_SCHOOL, s.school)
        putString(K_LAST_LAT, s.lastLat.toString())
        putString(K_LAST_LON, s.lastLon.toString())
        putString(K_LAST_ZOOM, s.lastZoom.toString())
        putString(K_DASH_QUALITY, s.dashQuality.name)
        putString(K_DASH_FACING, s.dashFacing.name)
        putInt(K_DASH_SEGMENT, s.dashSegmentMinutes)
        putInt(K_DASH_STORAGE, s.dashMaxStorageGb)
        putBoolean(K_DASH_AUDIO, s.dashRecordAudio)
        putBoolean(K_DASH_AUTOSTART, s.dashAutoStart)
        putInt(K_DASH_STORAGE_INDEX, s.dashStorageIndex)
        putBoolean(K_HEADING_UP, s.mapHeadingUp)
        putInt(K_CCTV_RADIUS, s.cctvRadiusMiles)
        putBoolean(K_UPD_CHECK, s.updateCheckEnabled)
        putBoolean(K_UPD_AUTO, s.updateAutoDownload)
        putString(K_UPD_URL, s.updateManifestUrl)
        putLong(K_UPD_LAST, s.updateLastCheckMs)
        putInt(K_UPD_SKIP, s.updateSkippedVersion)
    }.apply()

    private fun readPlace(key: String): SavedPlace? {
        val raw = sp.getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            SavedPlace(
                label = o.optString("label"),
                address = o.optString("address"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
            )
        }.getOrNull()
    }

    private fun writePlace(e: SharedPreferences.Editor, key: String, p: SavedPlace?) {
        if (p == null) {
            e.remove(key)
        } else {
            val o = JSONObject()
                .put("label", p.label)
                .put("address", p.address)
                .put("lat", p.lat)
                .put("lon", p.lon)
            e.putString(key, o.toString())
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val K_UNITS = "units"
        const val K_MAP_THEME = "map_theme"
        const val K_ORIENTATION = "orientation"
        const val K_KEEP_ON = "keep_screen_on"
        const val K_VOICE = "voice_guidance"
        const val K_SPEEDO = "show_speedometer"
        const val K_SPEED_ALERT = "speed_alert"
        const val K_ACCENT = "accent"
        const val K_ROUTER = "router_url"
        const val K_GEOCODER = "geocoder_url"
        const val K_TILES = "tile_url"
        const val K_HOME = "place_home"
        const val K_WORK = "place_work"
        const val K_SCHOOL = "place_school"
        const val K_LAST_LAT = "last_lat"
        const val K_LAST_LON = "last_lon"
        const val K_LAST_ZOOM = "last_zoom"
        const val K_DASH_QUALITY = "dash_quality"
        const val K_DASH_FACING = "dash_facing"
        const val K_DASH_SEGMENT = "dash_segment_minutes"
        const val K_DASH_STORAGE = "dash_max_storage_gb"
        const val K_DASH_AUDIO = "dash_record_audio"
        const val K_DASH_AUTOSTART = "dash_autostart"
        const val K_DASH_STORAGE_INDEX = "dash_storage_index"
        const val K_HEADING_UP = "map_heading_up"
        const val K_CCTV_RADIUS = "cctv_radius_miles"
        const val K_UPD_CHECK = "update_check_enabled"
        const val K_UPD_AUTO = "update_auto_download"
        const val K_UPD_URL = "update_manifest_url"
        const val K_UPD_LAST = "update_last_check"
        const val K_UPD_SKIP = "update_skipped_version"
    }
}
