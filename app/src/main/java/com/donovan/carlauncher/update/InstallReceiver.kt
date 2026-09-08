package com.donovan.carlauncher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.donovan.carlauncher.car

/**
 * Where PackageInstaller reports back to. The interesting case is
 * STATUS_PENDING_USER_ACTION: that is Android handing us the "Update this app?"
 * dialog to launch, which is the one tap the user has to make.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        @Suppress("DEPRECATION")
        val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

        runCatching { context.car.updater.onInstallStatus(status, message, userAction) }
    }

    companion object {
        const val ACTION = "com.donovan.carlauncher.INSTALL_STATUS"
    }
}
