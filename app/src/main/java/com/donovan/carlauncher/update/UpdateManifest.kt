package com.donovan.carlauncher.update

import org.json.JSONObject

/**
 * The single JSON document an update channel publishes for its newest build.
 *
 * It lives at a fixed URL that never changes between releases, and carries the
 * versioned APK address inside it - so only one thing needs a stable address:
 *
 *   https://github.com/<owner>/<repo>/releases/latest/download/update.json
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    val notes: String,
) {
    companion object {
        fun parse(json: String): UpdateManifest {
            val o = JSONObject(json)
            val url = o.optString("apkUrl").trim()
            val hash = o.optString("sha256").trim().lowercase()

            // Both of these are load-bearing for safety, so refuse a manifest that is
            // missing either one rather than installing something unverified.
            require(url.startsWith("https://")) { "apkUrl must be an https URL" }
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "manifest has no valid sha256" }

            return UpdateManifest(
                versionCode = o.getLong("versionCode"),
                versionName = o.optString("versionName").ifBlank { "?" },
                apkUrl = url,
                sha256 = hash,
                sizeBytes = o.optLong("sizeBytes", 0L),
                minSdk = o.optInt("minSdk", 24),
                notes = o.optString("notes").trim(),
            )
        }
    }
}

/** Everything the update UI can be showing at any moment. */
sealed interface UpdateState {

    val manifest: UpdateManifest? get() = null

    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val checkedAtMs: Long) : UpdateState

    /**
     * A newer build exists. [meteredHold] means it would have downloaded itself
     * already, but the only network available is the phone hotspot.
     */
    data class Available(
        override val manifest: UpdateManifest,
        val meteredHold: Boolean = false,
    ) : UpdateState

    data class Downloading(
        override val manifest: UpdateManifest,
        val bytes: Long,
        val total: Long,
    ) : UpdateState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** Downloaded and hash-verified, sitting on disk waiting for the install tap. */
    data class Ready(override val manifest: UpdateManifest) : UpdateState

    data class Installing(override val manifest: UpdateManifest) : UpdateState

    data class Failed(
        val message: String,
        override val manifest: UpdateManifest? = null,
    ) : UpdateState
}

/** Whether the nav rail should show its "there is an update" dot. */
val UpdateState.isPending: Boolean
    get() = this is UpdateState.Available ||
        this is UpdateState.Downloading ||
        this is UpdateState.Ready
