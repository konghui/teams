package com.wrapper.teams

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles "Answer" / "Decline" taps from the call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Constants.ACTION_ANSWER_CALL -> {
                CallController.answer()
                NotificationHelper.cancelCall(context)
            }
            Constants.ACTION_DECLINE_CALL -> {
                CallController.decline()
                NotificationHelper.cancelCall(context)
            }
        }
    }
}
