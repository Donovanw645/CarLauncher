package com.donovan.carlauncher.system

/** The few things about a location fix that decide whether it is worth believing. */
data class Fix(
    /** [android.location.LocationManager.GPS_PROVIDER] or NETWORK_PROVIDER. */
    val provider: String?,
    /** Reported accuracy in metres; [Float.MAX_VALUE] when the fix does not say. */
    val accuracyM: Float,
    /** Monotonic timestamp - the wall clock can jump, this cannot. */
    val elapsedNanos: Long,
) {
    val isGps: Boolean get() = provider == "gps"
}

/**
 * Decides whether a newly arrived fix should replace the one on screen.
 *
 * The launcher listens to GPS and network together, because on a cold start or under
 * cover the network provider is the only thing that knows where the car is. The catch
 * is that network fixes are Wi-Fi and cell triangulation - accurate to hundreds of
 * metres, and frequently derived from a cached observation, so they land where the car
 * *was*. Accepting them as equals to GPS makes the car appear to teleport backwards a
 * block and then snap forward on the next satellite fix.
 *
 * Kept free of Android types so the rules can actually be tested.
 */
object FixQuality {

    /** After this long with no update at all, anything is better than nothing. */
    const val STALE_NANOS = 12_000_000_000L

    /** How much vaguer a same-provider fix may be and still be accepted, in metres. */
    const val ACCURACY_SLACK_M = 40f

    /** A last-known fix older than this is not worth seeding the map with. */
    const val SEED_MAX_AGE_NANOS = 120_000_000_000L

    fun isBetter(candidate: Fix, current: Fix?): Boolean {
        if (current == null) return true

        val ageNanos = candidate.elapsedNanos - current.elapsedNanos
        // Out of order. Android does deliver these, and a fix from the past is never an
        // improvement on one from the present.
        if (ageNanos < 0) return false

        // Nothing has arrived in a long time; take whatever we can get.
        if (ageNanos > STALE_NANOS) return true

        // Satellites beat triangulation while the satellite fix is still fresh. This is
        // the rule that stops the backwards teleport.
        if (current.isGps && !candidate.isGps) return false
        if (candidate.isGps && !current.isGps) return true

        // Same provider: take it unless it is markedly vaguer than what we already have.
        return candidate.accuracyM <= current.accuracyM + ACCURACY_SLACK_M
    }
}
