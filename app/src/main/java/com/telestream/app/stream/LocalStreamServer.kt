package com.telestream.app.stream

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.telestream.app.player.TelegramDataSource
import com.telestream.app.telegram.TdClient
import org.drinkless.tdlib.TdApi
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP server on 127.0.0.1 that serves Telegram files with byte ranges, so other players
 * (the VLC app) can stream them through TDLib the same way our own player does.
 *
 * Every URL carries a random token, so other apps on the device cannot read files through it.
 */
@OptIn(UnstableApi::class)
class LocalStreamServer(private val td: TdClient) {
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    @Volatile
    private var socket: ServerSocket? = null

    /** Second socket on all interfaces, only opened for casting to a TV on the same Wi-Fi. */
    @Volatile
    private var lanSocket: ServerSocket? = null

    private val active = AtomicInteger(0)

    @Volatile
    var lastActivity = System.currentTimeMillis()
        private set

    val activeConnections: Int get() = active.get()

    /** Starts the server if needed and returns the URL for [fileId]. */
    @Synchronized
    fun urlFor(fileId: Int, fileName: String): Uri {
        val server = socket ?: start(InetAddress.getByName("127.0.0.1")).also { socket = it }
        lastActivity = System.currentTimeMillis()
        val name = Uri.encode(fileName.ifBlank { "video" })
        return Uri.parse("http://127.0.0.1:${server.localPort}/$token/$fileId/$name")
    }

    /** URL that other devices on the same network (a Chromecast) can open; null without Wi-Fi. */
    @Synchronized
    fun lanUrlFor(fileId: Int, fileName: String): Uri? {
        val ip = lanAddress() ?: return null
        val server = lanSocket ?: start(null).also { lanSocket = it }
        lastActivity = System.currentTimeMillis()
        val name = Uri.encode(fileName.ifBlank { "video" })
        return Uri.parse("http://$ip:${server.localPort}/$token/$fileId/$name")
    }

    @Synchronized
    fun stopLan() {
        runCatching { lanSocket?.close() }
        lanSocket = null
    }

    private fun lanAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun start(address: InetAddress?): ServerSocket {
        val server = if (address == null) ServerSocket(0, 16) else ServerSocket(0, 16, address)
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                Thread({ handle(client) }, "tg-stream-conn").apply { isDaemon = true }.start()
            }
        }, "tg-stream-server").apply { isDaemon = true }.start()
        return server
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        stopLan()
    }

    private fun handle(client: Socket) {
        active.incrementAndGet()
        lastActivity = System.currentTimeMillis()
        try {
            client.use { serve(it) }
        } catch (e: IOException) {
            // The player closed the connection, usually because it seeked. Nothing to do.
        } catch (e: Exception) {
            Log.w(TAG, "Stream request failed", e)
        } finally {
            active.decrementAndGet()
            lastActivity = System.currentTimeMillis()
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 60_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Range", ignoreCase = true)) {
                range = line.substring(colon + 1).trim()
            }
        }
        val out = client.getOutputStream()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val segments = parts.getOrNull(1)?.trimStart('/')?.split('/') ?: emptyList()
        if (method != "GET" && method != "HEAD") return respondError(out, 405, "Method Not Allowed")
        if (segments.size < 2 || segments[0] != token) return respondError(out, 403, "Forbidden")
        val fileId = segments[1].toIntOrNull() ?: return respondError(out, 404, "Not Found")

        val file = try {
            td.sendBlocking(TdApi.GetFile(fileId))
        } catch (e: Exception) {
            return respondError(out, 404, "Not Found")
        }
        val size = if (file.size > 0) file.size else file.expectedSize
        val mime = mimeFor(segments.getOrNull(2)?.let { Uri.decode(it) } ?: "")

        var start = 0L
        var end = if (size > 0) size - 1 else -1L
        var partial = false
        if (range != null && size > 0) {
            val m = RANGE.matchEntire(range)
            if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                if (a.isEmpty() && b.isNotEmpty()) {
                    start = maxOf(0L, size - b.toLong())
                } else if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                }
                if (start >= size || start > end) {
                    val header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    return
                }
                partial = true
            }
        }

        val header = StringBuilder()
        header.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: $mime\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        if (size > 0) header.append("Content-Length: ${end - start + 1}\r\n")
        if (partial) header.append("Content-Range: bytes $start-$end/$size\r\n")
        header.append("Connection: close\r\n\r\n")
        out.write(header.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        val source = TelegramDataSource(td)
        try {
            val length = if (size > 0) end - start + 1 else C.LENGTH_UNSET.toLong()
            source.open(
                DataSpec.Builder().setUri(TelegramDataSource.uriFor(fileId)).setPosition(start).setLength(length).build(),
            )
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
                lastActivity = System.currentTimeMillis()
            }
            out.flush()
        } finally {
            source.close()
        }
    }

    private fun respondError(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    companion object {
        private const val TAG = "LocalStreamServer"
        private val RANGE = Regex("""bytes=(\d*)-(\d*)""")

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }
}
