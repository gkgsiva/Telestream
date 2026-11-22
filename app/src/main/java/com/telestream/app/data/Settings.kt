package com.telestream.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "telestream")

/** Telegram API credentials entered by the user (from my.telegram.org). */
data class ApiCredentials(val apiId: Int, val apiHash: String)

/** Small persistent settings: API credentials, resume positions and recent searches. */
class Settings(private val context: Context) {
    private val apiIdKey = intPreferencesKey("api_id")
    private val apiHashKey = stringPreferencesKey("api_hash")
    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val hiddenChatsKey = stringSetPreferencesKey("hidden_chats")
    private val listsKey = stringPreferencesKey("user_lists")
    private val resumeKey = stringPreferencesKey("continue_watching")
    private val downloadsKey = stringPreferencesKey("downloads")
    /** When synced data (lists, hidden chats, Continue watching) last changed on this device. */
    private val syncStampKey = longPreferencesKey("sync_stamp")

    val credentials: Flow<ApiCredentials?> = context.dataStore.data.map { prefs ->
        val id = prefs[apiIdKey]
        val hash = prefs[apiHashKey]
        if (id != null && !hash.isNullOrBlank()) ApiCredentials(id, hash) else null
    }

    suspend fun currentCredentials(): ApiCredentials? = credentials.first()

    suspend fun saveCredentials(credentials: ApiCredentials) {
        context.dataStore.edit {
            it[apiIdKey] = credentials.apiId
            it[apiHashKey] = credentials.apiHash
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(apiIdKey)
            it.remove(apiHashKey)
        }
    }

    /** Playback position in ms for a media item id ("chatId:messageId"), 0 if none. */
    suspend fun resumePosition(mediaId: String): Long {
        val prefs = context.dataStore.data.first()
        val synced = ListsJson.decodeResume(prefs[resumeKey]).firstOrNull { it.item.id == mediaId }?.positionMs
        return synced ?: prefs[longPreferencesKey("pos_$mediaId")] ?: 0L
    }

    suspend fun saveResumePosition(mediaId: String, positionMs: Long) {
        context.dataStore.edit { it[longPreferencesKey("pos_$mediaId")] = positionMs }
    }

    val recentSearches: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[recentSearchesKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[recentSearchesKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[recentSearchesKey] = (listOf(q) + current.filter { it != q }).take(10).joinToString("\n")
        }
    }

    /** Chats the user hid from My chats. */
    val hiddenChats: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[hiddenChatsKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun setChatHidden(chatId: Long, hidden: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[hiddenChatsKey] ?: emptySet()
            prefs[hiddenChatsKey] = if (hidden) current + chatId.toString() else current - chatId.toString()
            prefs[syncStampKey] = System.currentTimeMillis()
        }
    }

    /** The user's own lists of videos and music. */
    val lists: Flow<List<UserList>> = context.dataStore.data.map { ListsJson.decode(it[listsKey]) }

    private suspend fun editLists(change: (List<UserList>) -> List<UserList>) {
        context.dataStore.edit { prefs ->
            prefs[listsKey] = ListsJson.encode(change(ListsJson.decode(prefs[listsKey])))
            prefs[syncStampKey] = System.currentTimeMillis()
        }
    }

    /** Creates a list and returns its id. */
    suspend fun createList(name: String): String {
        val id = UUID.randomUUID().toString()
        editLists { it + UserList(id, name.trim().ifEmpty { "My list" }, emptyList()) }
        return id
    }

    suspend fun renameList(id: String, name: String) =
        editLists { lists -> lists.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it } }

    suspend fun deleteList(id: String) = editLists { lists -> lists.filter { it.id != id } }

    suspend fun addToList(id: String, item: SavedItem) = editLists { lists ->
        lists.map { if (it.id == id && it.items.none { i -> i.id == item.id }) it.copy(items = it.items + item) else it }
    }

    suspend fun removeFromList(id: String, itemId: String) = editLists { lists ->
        lists.map { if (it.id == id) it.copy(items = it.items.filter { i -> i.id != itemId }) else it }
    }

    suspend fun moveInList(id: String, itemId: String, delta: Int) = editLists { lists ->
        lists.map { list ->
            if (list.id != id) return@map list
            val items = list.items.toMutableList()
            val from = items.indexOfFirst { it.id == itemId }
            val to = from + delta
            if (from < 0 || to !in items.indices) return@map list
            items.add(to, items.removeAt(from))
            list.copy(items = items)
        }
    }

    val syncStamp: Flow<Long> = context.dataStore.data.map { it[syncStampKey] ?: 0L }

    suspend fun clearContinue() {
        context.dataStore.edit { prefs ->
            prefs[resumeKey] = ListsJson.encodeResume(emptyList())
            prefs[syncStampKey] = System.currentTimeMillis()
        }
    }

    /** Videos in progress, newest first. */
    val continueWatching: Flow<List<ResumeEntry>> = context.dataStore.data.map { prefs ->
        ListsJson.decodeResume(prefs[resumeKey]).sortedByDescending { it.updatedAt }
    }

    /**
     * Records progress of a video. Finished videos (last 5%) leave the row; at most 30 are kept.
     */
    suspend fun recordProgress(item: SavedItem, positionMs: Long, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val others = ListsJson.decodeResume(prefs[resumeKey]).filter { it.item.id != item.id }
            val finished = durationMs > 0 && positionMs >= durationMs - durationMs / 20
            val entries = if (finished || positionMs < 5_000) others else listOf(ResumeEntry(item, positionMs, durationMs, now)) + others
            prefs[resumeKey] = ListsJson.encodeResume(entries.sortedByDescending { it.updatedAt }.take(30))
            prefs[longPreferencesKey("pos_${item.id}")] = if (finished) 0L else positionMs
            prefs[syncStampKey] = now
        }
    }

    suspend fun removeFromContinue(itemId: String) {
        context.dataStore.edit { prefs ->
            prefs[resumeKey] = ListsJson.encodeResume(ListsJson.decodeResume(prefs[resumeKey]).filter { it.item.id != itemId })
            prefs[syncStampKey] = System.currentTimeMillis()
        }
    }

    /** Items the user downloaded for offline playback. */
    val downloads: Flow<List<SavedItem>> = context.dataStore.data.map { ListsJson.decodeItems(it[downloadsKey]) }

    suspend fun addDownload(item: SavedItem) {
        context.dataStore.edit { prefs ->
            val current = ListsJson.decodeItems(prefs[downloadsKey]).filter { it.id != item.id }
            prefs[downloadsKey] = ListsJson.encodeItems(listOf(item) + current)
        }
    }

    suspend fun removeDownload(itemId: String) {
        context.dataStore.edit { prefs ->
            prefs[downloadsKey] = ListsJson.encodeItems(ListsJson.decodeItems(prefs[downloadsKey]).filter { it.id != itemId })
        }
    }

    // ---- Sync snapshot (see SyncManager) ----

    suspend fun syncSnapshot(): SyncSnapshot {
        val prefs = context.dataStore.data.first()
        return SyncSnapshot(
            stamp = prefs[syncStampKey] ?: 0L,
            lists = ListsJson.decode(prefs[listsKey]),
            hidden = prefs[hiddenChatsKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
            resume = ListsJson.decodeResume(prefs[resumeKey]),
        )
    }

    /** Replaces local synced data with [snapshot], keeping the newest progress per video from both sides. */
    suspend fun applySnapshot(snapshot: SyncSnapshot) {
        context.dataStore.edit { prefs ->
            val local = ListsJson.decodeResume(prefs[resumeKey])
            val merged = (local + snapshot.resume)
                .groupBy { it.item.id }
                .map { (_, entries) -> entries.maxBy { it.updatedAt } }
                .sortedByDescending { it.updatedAt }
                .take(30)
            prefs[listsKey] = ListsJson.encode(snapshot.lists)
            prefs[hiddenChatsKey] = snapshot.hidden.map { it.toString() }.toSet()
            prefs[resumeKey] = ListsJson.encodeResume(merged)
            prefs[syncStampKey] = snapshot.stamp
        }
    }
}

/** Everything that is shared between devices through Saved Messages. */
data class SyncSnapshot(
    val stamp: Long,
    val lists: List<UserList>,
    val hidden: Set<Long>,
    val resume: List<ResumeEntry>,
)
