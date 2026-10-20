package com.wrapper.teams

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the keep-alive service after device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                TeamsService.start(context)
            }
        }
    }
}
