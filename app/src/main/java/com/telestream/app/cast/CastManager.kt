package com.telestream.app.cast

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.telestream.app.TelestreamApp
import com.telestream.app.stream.StreamService
import com.telestream.app.telegram.MediaItem
import androidx.media3.common.MediaItem as Media3Item

/**
 * Casting to a Chromecast or Google TV on the same Wi-Fi. The Cast device streams the file from
 * this phone through [com.telestream.app.stream.LocalStreamServer], so the phone has to stay on
 * the network while it plays. Main thread only.
 */
@OptIn(UnstableApi::class)
object CastManager {
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var appContext: Context? = null

    /** The player on the Cast device; null when Cast is not available (no Google Play services). */
    var castPlayer: CastPlayer? = null
        private set

    var casting by mutableStateOf(false)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    val routes = mutableStateListOf<MediaRouter.RouteInfo>()

    private var pending: Pair<MediaItem, Long>? = null

    val available: Boolean get() = castPlayer != null

    fun init(context: Context) {
        if (castContext != null) return
        appContext = context.applicationContext
        val cast = try {
            CastContext.getSharedInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.i(TAG, "Cast is not available on this device", e)
            return
        }
        castContext = cast
        router = MediaRouter.getInstance(context.applicationContext)
        selector = cast.mergedSelector
        castPlayer = CastPlayer(cast).also { player ->
            player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    casting = true
                    deviceName = cast.sessionManager.currentCastSession?.castDevice?.friendlyName
                    pending?.let { (item, position) -> load(item, position) }
                    pending = null
                }

                override fun onCastSessionUnavailable() {
                    casting = false
                    deviceName = null
                    (appContext?.applicationContext as? TelestreamApp)?.streamServer?.stopLan()
                }
            })
        }
    }

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    /** Starts looking for Cast devices while the picker is open. */
    fun startDiscovery() {
        val r = router ?: return
        val sel = selector ?: return
        r.addCallback(sel, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
    }

    fun stopDiscovery() {
        router?.removeCallback(callback)
    }

    private fun refresh() {
        val r = router ?: return
        val sel = selector ?: return
        routes.clear()
        routes.addAll(r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(sel) })
    }

    /** Connects to [route] and plays [item] there from [positionMs]. */
    fun castTo(route: MediaRouter.RouteInfo, item: MediaItem, positionMs: Long, localPlayer: Player?) {
        localPlayer?.pause()
        if (casting) {
            load(item, positionMs)
        } else {
            pending = item to positionMs
            route.select()
        }
    }

    private fun load(item: MediaItem, positionMs: Long) {
        val context = appContext ?: return
        val app = context as TelestreamApp
        val url = app.streamServer.lanUrlFor(item.fileId, item.title)
        if (url == null) {
            message = "Connect this phone to the same Wi-Fi as the TV to cast."
            return
        }
        StreamService.start(context)
        // The default receiver decides by MIME type; Matroska files usually play when sent as MP4.
        val mime = item.mimeType.takeIf { it.startsWith("video/") && !it.contains("matroska") }
            ?: if (item.mimeType.startsWith("audio/")) item.mimeType else "video/mp4"
        val media = Media3Item.Builder()
            .setUri(url)
            .setMimeType(mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.chatTitle).build())
            .build()
        castPlayer?.run {
            setMediaItem(media, positionMs)
            prepare()
            play()
        }
    }

    fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private const val TAG = "CastManager"
}
