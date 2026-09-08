package com.donovan.carlauncher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Android only hands out other apps' media sessions to a notification listener.
 * This service does nothing with notifications itself - granting it access is simply
 * the price of being allowed to drive Spotify / YouTube Music / Pocket Casts / etc.
 * from the launcher instead of switching apps.
 */
class CarNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        onConnectionChanged?.invoke(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        onConnectionChanged?.invoke(false)
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        /** Set by [MediaRemote] so it can attach as soon as access is granted. */
        @Volatile
        var onConnectionChanged: ((Boolean) -> Unit)? = null

        fun componentName(context: Context): ComponentName =
            ComponentName(context, CarNotificationListener::class.java)

        /** True when the user has ticked us in Settings > Notification access. */
        fun isEnabled(context: Context): Boolean {
            val flat = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners",
                )
            }.getOrNull() ?: return false
            val me = context.packageName
            return flat.split(':').any { entry ->
                runCatching { ComponentName.unflattenFromString(entry)?.packageName == me }
                    .getOrDefault(false)
            }
        }

        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
