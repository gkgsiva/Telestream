package com.telestream.app.stream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.telestream.app.TelestreamApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while VLC streams from [LocalStreamServer], since Telestream is in the
 * background then. Stops itself once nothing has been read for a few minutes.
 */
class StreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Streaming to other players", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Streaming to VLC")
            .setContentText("Telestream is sending the video from Telegram.")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (!watching) {
            watching = true
            val server = (application as TelestreamApp).streamServer
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    val idle = System.currentTimeMillis() - server.lastActivity
                    if (server.activeConnections == 0 && idle > IDLE_MS) {
                        stopSelf()
                        break
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "streaming"
        private const val NOTIFICATION_ID = 42
        private const val IDLE_MS = 3 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
        }
    }
}
