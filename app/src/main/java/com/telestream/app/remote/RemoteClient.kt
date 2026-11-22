package com.telestream.app.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A Telestream TV found on the network. */
data class RemoteTv(val name: String, val host: String, val port: Int)

data class RemoteStatus(val title: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

/** Runs on the phone: finds Telestream TVs on the Wi-Fi and sends them commands. */
object RemoteClient {
    val tvs = mutableStateListOf<RemoteTv>()

    /** The TV this phone controls, with its pairing code. */
    var connected by mutableStateOf<Pair<RemoteTv, String>?>(null)
        private set

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun startDiscovery(context: Context) {
        if (discovery != null) return
        val manager = context.applicationContext.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        val tv = RemoteTv(info.serviceName, host, info.port)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            tvs.removeAll { it.name == tv.name }
                            tvs.add(tv)
                        }
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { tvs.removeAll { it.name == info.serviceName } }
            }
        }
        runCatching { manager.discoverServices(RemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        nsd = manager
        discovery = listener
    }

    fun stopDiscovery() {
        val listener = discovery ?: return
        runCatching { nsd?.stopServiceDiscovery(listener) }
        discovery = null
    }

    /** Checks the code with the TV; true when the TV accepted it. */
    suspend fun connect(tv: RemoteTv, code: String): Boolean {
        val ok = request(tv, code, "status") != null
        if (ok) connected = tv to code
        return ok
    }

    fun disconnect() {
        connected = null
    }

    suspend fun send(command: String, extra: Map<String, String> = emptyMap()): RemoteStatus? {
        val (tv, code) = connected ?: return null
        return request(tv, code, command, extra)
    }

    /** Asks the TV to play a Telegram message's video from [positionMs]. */
    suspend fun playOnTv(chatId: Long, messageId: Long, positionMs: Long) =
        send("open", mapOf("chat" to chatId.toString(), "msg" to messageId.toString(), "ms" to positionMs.toString()))

    private suspend fun request(tv: RemoteTv, code: String, command: String, extra: Map<String, String> = emptyMap()): RemoteStatus? =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = (mapOf("code" to code, "c" to command) + extra).entries
                    .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                val conn = URL("http://${tv.host}:${tv.port}/cmd?$query").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 6_000
                    if (conn.responseCode != 200) return@runCatching null
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    RemoteStatus(o.optString("title"), o.optBoolean("playing"), o.optLong("position"), o.optLong("duration"))
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
}
