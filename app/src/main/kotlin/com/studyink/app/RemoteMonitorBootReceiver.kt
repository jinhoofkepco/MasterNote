package com.studyink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the durable Telegram poller after a reboot or an in-place APK update. */
class RemoteMonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        // Android may temporarily reject a foreground-service launch while finishing a package
        // update. The next app foreground also calls startIfEnabled, so this attempt is best-effort
        // and never jeopardizes boot completion.
        runCatching { RemoteMonitorService.startIfEnabled(context.applicationContext) }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
