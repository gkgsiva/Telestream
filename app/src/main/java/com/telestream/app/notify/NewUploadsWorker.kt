package com.telestream.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telestream.app.MainActivity
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.util.concurrent.TimeUnit

/**
 * Checks followed channels for new videos and songs about every 15 minutes and posts a
 * notification for each channel that has some.
 */
class NewUploadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TelestreamApp
        val followed = app.settings.followedChats.first()
        if (followed.isEmpty()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
            return Result.success()
        }
        val state = withTimeoutOrNull(60_000) {
            app.td.authState.first { it is TdApi.AuthorizationStateReady || isLoginNeeded(it) }
        }
        if (state !is TdApi.AuthorizationStateReady) return Result.success()

        for (chatId in followed) {
            runCatching { check(app, chatId) }
        }
        return Result.success()
    }

    private fun isLoginNeeded(state: TdApi.AuthorizationState?) =
        state is TdApi.AuthorizationStateWaitPhoneNumber ||
            state is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ||
            state is TdApi.AuthorizationStateWaitCode ||
            state is TdApi.AuthorizationStateWaitPassword

    private suspend fun check(app: TelestreamApp, chatId: Long) {
        val td = app.td
        val chat = td.send(TdApi.GetChat(chatId))
        var messages = td.send(TdApi.GetChatHistory(chatId, 0, 0, 50, false)).messages
        // The first call often returns only the newest message; the second fills in the rest.
        if (messages.size <= 1) messages = td.send(TdApi.GetChatHistory(chatId, 0, 0, 50, false)).messages
        if (messages.isEmpty()) return
        val newest = messages.maxOf { it.id }
        val seen = app.settings.lastSeenMessage(chatId)
        app.settings.setLastSeenMessage(chatId, newest)
        if (seen == 0L || newest <= seen) return

        val fresh = messages.filter { it.id > seen }.mapNotNull { MediaItem.from(it, chat.title) }
        if (fresh.isNotEmpty()) notify(chatId, chat.title, fresh)
    }

    private fun notify(chatId: Long, chatTitle: String, items: List<MediaItem>) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "New uploads", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT, chatId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, chatId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val videos = items.count { it.kind == MediaKind.VIDEO }
        val songs = items.size - videos
        val summary = listOfNotNull(
            videos.takeIf { it > 0 }?.let { if (it == 1) "1 new video" else "$it new videos" },
            songs.takeIf { it > 0 }?.let { if (it == 1) "1 new song" else "$it new songs" },
        ).joinToString(" and ")
        val style = NotificationCompat.InboxStyle()
        items.take(5).forEach { style.addLine(it.title) }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(chatTitle)
            .setContentText(if (items.size == 1) items.first().title else summary)
            .setStyle(style.setSummaryText(summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(chatId.hashCode(), notification)
    }

    companion object {
        private const val WORK_NAME = "new-uploads"
        private const val CHANNEL = "new_uploads"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewUploadsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
