package com.wrapper.teams

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class TeamsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Low-importance channel for the persistent foreground-service notification.
        val service = NotificationChannel(
            Constants.CHANNEL_SERVICE,
            "后台运行",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "保持 Teams 在后台接收消息和来电"
            setShowBadge(false)
        }

        // Normal messages.
        val messages = NotificationChannel(
            Constants.CHANNEL_MESSAGES,
            "消息通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "聊天和频道的新消息"
            enableVibration(true)
        }

        // High-importance call channel: ringtone + vibration + full-screen.
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val calls = NotificationChannel(
            Constants.CHANNEL_CALLS,
            "来电",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Teams 来电"
            setSound(ringtone, audioAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
        }

        nm.createNotificationChannels(listOf(service, messages, calls))
    }
}
