package com.telestream.app.stream

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.telestream.app.TelestreamApp
import com.telestream.app.telegram.MediaItem

/** Hands a Telegram file to the VLC app (or another player) through [LocalStreamServer]. */
object ExternalPlayer {
    const val VLC_PACKAGE = "org.videolan.vlc"

    fun isVlcInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(VLC_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun openInVlc(context: Context, item: MediaItem, positionMs: Long = 0) {
        val app = context.applicationContext as TelestreamApp
        val uri = app.streamServer.urlFor(item.fileId, item.title)
        StreamService.start(context)
        val mime = item.mimeType.ifBlank { LocalStreamServer.mimeFor(item.title) }
            .let { if (it == "application/octet-stream") "video/*" else it }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            putExtra("title", item.title)
            if (positionMs > 0) putExtra("position", positionMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isVlcInstalled(context)) {
            intent.setPackage(VLC_PACKAGE)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                intent.setPackage(null)
            }
        }
        try {
            Toast.makeText(context, "VLC is not installed. Pick another player.", Toast.LENGTH_LONG).show()
            context.startActivity(Intent.createChooser(intent, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            installVlc(context)
        }
    }

    private fun installVlc(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VLC_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Install VLC to use this.", Toast.LENGTH_LONG).show()
        }
    }
}
