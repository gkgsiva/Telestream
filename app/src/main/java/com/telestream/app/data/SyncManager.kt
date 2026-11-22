package com.telestream.app.data

import android.content.Context
import android.util.Log
import com.telestream.app.telegram.TdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Shares lists, hidden chats and Continue watching between the user's devices by keeping one
 * small JSON file in their own Telegram Saved Messages. The newest change wins; video progress
 * is merged per video.
 */
@OptIn(FlowPreview::class)
class SyncManager(
    private val context: Context,
    private val td: TdClient,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var syncedStamp = -1L

    private val _status = MutableStateFlow<String?>(null)
    /** Last sync result, for the UI ("Synced at 10:42", or an error). */
    val status: StateFlow<String?> = _status

    init {
        // Push local changes a few seconds after they stop happening.
        scope.launch {
            settings.syncStamp
                .debounce(4_000)
                .filter { syncedStamp >= 0 && it > syncedStamp }
                .collect { runCatching { syncNow() } }
        }
    }

    /** Call when the user is logged in and when the app comes to the foreground. */
    fun requestSync() {
        if (td.authState.value !is TdApi.AuthorizationStateReady) return
        scope.launch { runCatching { syncNow() }.onFailure { Log.w(TAG, "Sync failed", it) } }
    }

    suspend fun syncNow() {
        mutex.withLock { syncLocked() }
    }

    private suspend fun syncLocked() {
        try {
            val selfChat = savedMessagesChatId()
            val found = findRemoteMessages(selfChat)
            val remote = found.firstOrNull()?.let { readRemote(it) }
            val local = settings.syncSnapshot()

            when {
                remote != null && remote.stamp > local.stamp -> {
                    settings.applySnapshot(remote)
                    syncedStamp = remote.stamp
                    // Our merged progress may be newer than what is stored remotely.
                    val merged = settings.syncSnapshot()
                    if (merged.resume.toSet() != remote.resume.toSet()) push(selfChat, merged, found.map { it.id })
                }
                remote == null || local.stamp > remote.stamp -> {
                    if (remote == null && local.stamp == 0L && local.lists.isEmpty() && local.resume.isEmpty()) {
                        syncedStamp = 0L // Nothing to share yet.
                    } else {
                        push(selfChat, local, found.map { it.id })
                    }
                }
                else -> syncedStamp = local.stamp
            }
            _status.value = "Synced at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        } catch (e: Exception) {
            _status.value = "Sync failed: ${e.message}"
            throw e
        }
    }

    private suspend fun savedMessagesChatId(): Long {
        val me = td.send(TdApi.GetMe())
        return td.send(TdApi.CreatePrivateChat(me.id, true)).id
    }

    /** Sync messages in Saved Messages, newest first. */
    private suspend fun findRemoteMessages(chatId: Long): List<TdApi.Message> {
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            query = FILE_NAME
            fromMessageId = 0
            offset = 0
            limit = 20
            filter = TdApi.SearchMessagesFilterDocument()
        }
        return td.send(request).messages
            .filter { (it.content as? TdApi.MessageDocument)?.document?.fileName == FILE_NAME }
            .sortedByDescending { it.id }
    }

    private suspend fun readRemote(message: TdApi.Message): SyncSnapshot? {
        val doc = (message.content as? TdApi.MessageDocument)?.document ?: return null
        val file = td.send(TdApi.DownloadFile(doc.document.id, 16, 0, 0, true))
        if (!file.local.isDownloadingCompleted) return null
        return decode(File(file.local.path).readText())
    }

    private suspend fun push(chatId: Long, snapshot: SyncSnapshot, oldMessageIds: List<Long>) {
        val stamp = if (snapshot.stamp > 0) snapshot.stamp else System.currentTimeMillis()
        val file = File(context.cacheDir, "sync/$FILE_NAME").apply { parentFile?.mkdirs() }
        file.writeText(encode(snapshot.copy(stamp = stamp)))

        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal(file.absolutePath)
                disableContentTypeDetection = true
            }
            caption = TdApi.FormattedText(
                "Telestream sync data (your lists, hidden chats and watch progress). Keep this message so your devices stay in sync.",
                emptyArray(),
            )
        }
        val send = TdApi.SendMessage().apply {
            this.chatId = chatId
            options = TdApi.MessageSendOptions().apply { disableNotification = true }
            inputMessageContent = content
        }
        td.send(send)
        if (oldMessageIds.isNotEmpty()) {
            runCatching { td.send(TdApi.DeleteMessages(chatId, oldMessageIds.toLongArray(), true)) }
        }
        syncedStamp = stamp
    }

    private fun encode(s: SyncSnapshot): String = JSONObject()
        .put("version", 1)
        .put("stamp", s.stamp)
        .put("lists", JSONArray(ListsJson.encode(s.lists)))
        .put("hidden", JSONArray().apply { s.hidden.forEach { put(it) } })
        .put("resume", JSONArray(ListsJson.encodeResume(s.resume)))
        .toString()

    private fun decode(text: String): SyncSnapshot? = try {
        val o = JSONObject(text)
        val hidden = o.optJSONArray("hidden") ?: JSONArray()
        SyncSnapshot(
            stamp = o.optLong("stamp"),
            lists = ListsJson.decode(o.optJSONArray("lists")?.toString()),
            hidden = (0 until hidden.length()).map { hidden.getLong(it) }.toSet(),
            resume = ListsJson.decodeResume(o.optJSONArray("resume")?.toString()),
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "SyncManager"
        const val FILE_NAME = "telestream-sync.json"
    }
}
