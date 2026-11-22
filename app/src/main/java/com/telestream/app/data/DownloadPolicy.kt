package com.telestream.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Download rules from Settings: Wi-Fi only, a storage limit, and removing watched videos. */
object DownloadPolicy {
    private const val TAG = "DownloadPolicy"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var unmetered = true

    /** Watches the network so Wi-Fi-only downloads pause on mobile data and resume on Wi-Fi. */
    fun start(context: Context) {
        val app = context.applicationContext as TelestreamApp
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        unmetered = !cm.isActiveNetworkMetered
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (now != unmetered) {
                        unmetered = now
                        scope.launch { applyNetworkRule(app) }
                    }
                }
            })
        }
    }

    private suspend fun applyNetworkRule(app: TelestreamApp) {
        if (!app.settings.wifiOnlyDownloads.first()) return
        if (app.td.authState.value !is TdApi.AuthorizationStateReady) return
        for (saved in app.settings.downloads.first()) {
            val item = runCatching { app.repository.loadMessageMedia(saved.chatId, saved.messageId) }.getOrNull() ?: continue
            val file = runCatching { app.repository.fileState(item.fileId) }.getOrNull() ?: continue
            if (file.local.isDownloadingCompleted) continue
            runCatching {
                if (unmetered) app.repository.startDownload(item) else app.repository.pauseDownload(item.fileId, true)
            }.onFailure { Log.w(TAG, "Could not change download", it) }
        }
    }

    /** Starts a download if the rules allow it; returns the message to show. */
    suspend fun download(app: TelestreamApp, item: MediaItem): String {
        val limitGb = app.settings.downloadLimitGb.first()
        if (limitGb > 0) {
            val used = app.settings.downloads.first().sumOf { saved ->
                runCatching { app.repository.loadMessageMedia(saved.chatId, saved.messageId)?.size ?: 0L }.getOrDefault(0L)
            }
            if (used + item.size > (limitGb.toLong() shl 30)) {
                return "Over your $limitGb GB download limit. Delete some downloads or raise the limit in Settings."
            }
        }
        app.repository.startDownload(item)
        app.settings.addDownload(SavedItem.of(item))
        if (app.settings.wifiOnlyDownloads.first() && !unmetered) {
            app.repository.pauseDownload(item.fileId, true)
            return "\"${item.title}\" will download when you're on Wi-Fi."
        }
        return "Downloading \"${item.title}\". See the Downloads tab."
    }

    /** Called when a video was watched to the end. */
    suspend fun onFinished(app: TelestreamApp, itemId: String, fileId: Int?) {
        if (!app.settings.autoDeleteWatched.first()) return
        if (app.settings.downloads.first().none { it.id == itemId }) return
        if (fileId != null) app.repository.deleteDownload(fileId)
        app.settings.removeDownload(itemId)
    }
}
