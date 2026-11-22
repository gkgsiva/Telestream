package com.telestream.app.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.telestream.app.R
import com.telestream.app.data.ResumeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors Continue watching into the Android TV home screen's "Play next" row. Each program
 * opens telestream://play?chat=...&msg=..., which resumes the video in the app.
 */
object WatchNext {
    private const val TAG = "WatchNext"

    fun deepLink(chatId: Long, messageId: Long): Uri =
        Uri.parse("telestream://play?chat=$chatId&msg=$messageId")

    @SuppressLint("RestrictedApi")
    suspend fun sync(context: Context, entries: List<ResumeEntry>) {
        if (Build.VERSION.SDK_INT < 26) return
        withContext(Dispatchers.IO) { write(context, entries) }
    }

    @SuppressLint("RestrictedApi")
    private fun write(context: Context, entries: List<ResumeEntry>) {
        val resolver = context.contentResolver
        try {
            // Our own programs, by the media id stored as internal provider id.
            val existing = HashMap<String, Long>()
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val program = WatchNextProgram.fromCursor(cursor)
                        val id = program.internalProviderId
                        if (id != null) existing[id] = program.id
                    }
                }
            val wanted = entries.take(10)
            val wantedIds = wanted.map { it.item.id }.toSet()
            existing.filterKeys { it !in wantedIds }.values.forEach { rowId ->
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(rowId), null, null)
            }
            val poster = Uri.parse("android.resource://${context.packageName}/${R.drawable.tv_banner}")
            for (entry in wanted) {
                val program = WatchNextProgram.Builder()
                    .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(entry.updatedAt)
                    .setTitle(entry.item.title)
                    .setDescription(entry.item.chatTitle)
                    .setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .setPosterArtUri(poster)
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setIntentUri(deepLink(entry.item.chatId, entry.item.messageId))
                    .setInternalProviderId(entry.item.id)
                    .build()
                val rowId = existing[entry.item.id]
                if (rowId == null) {
                    resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
                } else {
                    resolver.update(TvContractCompat.buildWatchNextProgramUri(rowId), program.toContentValues(), null, null)
                }
            }
        } catch (e: Exception) {
            // Some TVs have no home screen provider; Continue watching in the app still works.
            Log.i(TAG, "Could not update Play next", e)
        }
    }
}
