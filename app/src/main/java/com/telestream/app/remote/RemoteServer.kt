package com.telestream.app.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.Player
import com.telestream.app.player.PlaybackService
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A video the phone asked the TV to play. */
data class RemotePlay(val chatId: Long, val messageId: Long, val positionMs: Long)

/**
 * Runs on Android TV: lets a phone on the same Wi-Fi control playback. The TV advertises itself
 * over mDNS and shows a 4-digit code; the phone must send that code with every command.
 */
object RemoteServer {
    const val SERVICE_TYPE = "_telestream._tcp."

    /** The pairing code shown in Settings on the TV. */
    val code = mutableStateOf("")

    /** Set when the phone sends a video to play; the TV's navigation picks it up. */
    val playRequest = mutableStateOf<RemotePlay?>(null)

    private var socket: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context) {
        if (socket != null) return
        code.value = (SecureRandom().nextInt(9000) + 1000).toString()
        val server = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the remote control server", e)
            return
        }
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ runCatching { client.use { handle(it) } } }, "remote-conn").apply { isDaemon = true }.start()
            }
        }, "remote-server").apply { isDaemon = true }.start()

        val info = NsdServiceInfo().apply {
            serviceName = "Telestream on ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = server.localPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        val manager = context.applicationContext.getSystemService(NsdManager::class.java)
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        nsd = manager
        registration = listener
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = requestLine.split(' ').getOrNull(1) ?: "/"
        val uri = Uri.parse("http://tv$path")
        val (status, body) = if (uri.getQueryParameter("code") != code.value) {
            403 to JSONObject().put("error", "wrong code")
        } else {
            200 to onMain { command(uri) }
        }
        val bytes = body.toString().toByteArray()
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    /** Runs on the main thread, where the player lives. */
    private fun command(uri: Uri): JSONObject {
        val player: Player? = PlaybackService.current
        val ms = uri.getQueryParameter("ms")?.toLongOrNull() ?: 0L
        when (uri.getQueryParameter("c")) {
            "toggle" -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            "play" -> player?.play()
            "pause" -> player?.pause()
            "next" -> player?.seekToNext()
            "prev" -> player?.seekToPrevious()
            "seek" -> player?.seekTo(ms)
            "rel" -> player?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) }
            "open" -> {
                val chatId = uri.getQueryParameter("chat")?.toLongOrNull()
                val messageId = uri.getQueryParameter("msg")?.toLongOrNull()
                if (chatId != null && messageId != null) playRequest.value = RemotePlay(chatId, messageId, ms)
            }
        }
        return JSONObject()
            .put("title", player?.mediaMetadata?.title?.toString().orEmpty())
            .put("playing", player?.isPlaying == true)
            .put("position", player?.currentPosition ?: 0L)
            .put("duration", player?.duration?.coerceAtLeast(0L) ?: 0L)
    }

    private fun onMain(block: () -> JSONObject): JSONObject {
        val result = AtomicReference(JSONObject())
        val latch = CountDownLatch(1)
        main.post {
            runCatching { result.set(block()) }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result.get()
    }

    private const val TAG = "RemoteServer"
}
