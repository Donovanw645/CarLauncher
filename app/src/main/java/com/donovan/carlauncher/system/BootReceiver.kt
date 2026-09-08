package com.donovan.carlauncher.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.donovan.carlauncher.MainActivity

/**
 * Brings the launcher up when the tablet powers on with the car.
 *
 * Android 10+ blocks background activity starts, so this only actually fires on
 * older builds or where the app has been granted an exemption. When Car Launcher is
 * set as the device's home app the system starts it on boot anyway and this is a no-op.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
