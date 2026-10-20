package com.wrapper.teams

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service whose only job is to keep the app process (and therefore
 * the WebView + its long-lived Teams websocket) alive while backgrounded.
 * Best-effort: aggressive OEM ROMs may still kill it unless whitelisted.
 */
class TeamsService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if the system kills us.
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                action = Constants.ACTION_SHOW_MAIN
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, Constants.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Teams 正在后台运行")
            .setContentText("保持接收消息和来电")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.SERVICE_NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(Constants.SERVICE_NOTIFICATION_ID, n)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "TeamsWrapper::bg"
        ).apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        // Try to come back if we were killed unexpectedly.
        start(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val i = Intent(context, TeamsService::class.java)
            ContextCompat_startForeground(context, i)
        }

        private fun ContextCompat_startForeground(context: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
