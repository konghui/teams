package com.wrapper.teams

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {

    private fun pi(context: Context, intent: Intent, req: Int): PendingIntent =
        PendingIntent.getActivity(
            context, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun piBroadcast(context: Context, intent: Intent, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, req, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Ordinary chat/channel message notification. */
    fun showMessage(
        context: Context, title: String, body: String, tag: String, chatId: String = ""
    ) {
        val open = Intent(context, MainActivity::class.java).apply {
            action = Constants.ACTION_SHOW_MAIN
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (chatId.isNotBlank()) putExtra(Constants.EXTRA_CHAT_ID, chatId)
            putExtra(Constants.EXTRA_CHAT_TITLE, title)
        }
        val n = NotificationCompat.Builder(context, Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(title.ifBlank { "Teams" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi(context, open, tag.hashCode()))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(tag, tag.hashCode(), n)
    }

    /** Full-screen incoming-call notification with answer / decline actions. */
    fun showIncomingCall(context: Context, caller: String, callId: String) {
        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Constants.EXTRA_CALLER, caller)
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        val answer = Intent(context, CallActionReceiver::class.java).apply {
            action = Constants.ACTION_ANSWER_CALL
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }
        val decline = Intent(context, CallActionReceiver::class.java).apply {
            action = Constants.ACTION_DECLINE_CALL
            putExtra(Constants.EXTRA_CALL_ID, callId)
        }

        val n = NotificationCompat.Builder(context, Constants.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Teams 来电")
            .setContentText(caller.ifBlank { "未知来电" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi(context, fullScreen, 0), true)
            .addAction(
                R.drawable.ic_call, "接听",
                piBroadcast(context, answer, 1)
            )
            .addAction(
                R.drawable.ic_call_end, "挂断",
                piBroadcast(context, decline, 2)
            )
            .build().apply {
                flags = flags or Notification.FLAG_INSISTENT
            }

        context.getSystemService(NotificationManager::class.java)
            .notify(Constants.CALL_NOTIFICATION_ID, n)
    }

    fun cancelCall(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(Constants.CALL_NOTIFICATION_ID)
    }
}
