package com.telestream.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private val lockedChatsKey = stringSetPreferencesKey("locked_chats")
    private val pinHashKey = stringPreferencesKey("parental_pin")
    private val followedChatsKey = stringSetPreferencesKey("followed_chats")
    private val accountKey = intPreferencesKey("account")
    private val accountsKey = stringPreferencesKey("accounts")
    private val hideDuplicatesKey = booleanPreferencesKey("hide_duplicates")

    /** Downloads live in each account's own TDLib files folder, so each account has its own list. */
    private fun downloadsKey(account: Int) = stringPreferencesKey(if (account == 0) "downloads" else "downloads_$account")
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
    val downloads: Flow<List<SavedItem>> = context.dataStore.data.map {
        ListsJson.decodeItems(it[downloadsKey(it[accountKey] ?: 0)])
    }

    suspend fun addDownload(item: SavedItem) {
        context.dataStore.edit { prefs ->
            val key = downloadsKey(prefs[accountKey] ?: 0)
            val current = ListsJson.decodeItems(prefs[key]).filter { it.id != item.id }
            prefs[key] = ListsJson.encodeItems(listOf(item) + current)
        }
    }

    suspend fun removeDownload(itemId: String) {
        context.dataStore.edit { prefs ->
            val key = downloadsKey(prefs[accountKey] ?: 0)
            prefs[key] = ListsJson.encodeItems(ListsJson.decodeItems(prefs[key]).filter { it.id != itemId })
        }
    }

    suspend fun clearRecentSearches() {
        context.dataStore.edit { it.remove(recentSearchesKey) }
    }

    suspend fun removeRecentSearch(query: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[recentSearchesKey]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
            prefs[recentSearchesKey] = current.filter { it != query }.joinToString("\n")
        }
    }

    // ---- Parental lock (kept on this device only, never synced) ----

    val lockedChats: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[lockedChatsKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    val hasPin: Flow<Boolean> = context.dataStore.data.map { it[pinHashKey] != null }

    suspend fun setChatLocked(chatId: Long, locked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[lockedChatsKey] ?: emptySet()
            prefs[lockedChatsKey] = if (locked) current + chatId.toString() else current - chatId.toString()
        }
    }

    suspend fun setPin(pin: String?) {
        context.dataStore.edit { prefs ->
            if (pin == null) {
                prefs.remove(pinHashKey)
                prefs.remove(lockedChatsKey)
            } else {
                val salt = UUID.randomUUID().toString()
                prefs[pinHashKey] = salt + ":" + hashPin(salt, pin)
            }
        }
    }

    suspend fun checkPin(pin: String): Boolean {
        val stored = context.dataStore.data.first()[pinHashKey] ?: return true
        val salt = stored.substringBefore(':')
        return stored.substringAfter(':') == hashPin(salt, pin)
    }

    private fun hashPin(salt: String, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- New-upload notifications ----

    val followedChats: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[followedChatsKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun setChatFollowed(chatId: Long, followed: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[followedChatsKey] ?: emptySet()
            prefs[followedChatsKey] = if (followed) current + chatId.toString() else current - chatId.toString()
        }
    }

    /** The newest message id already seen in a followed chat, 0 if never checked. */
    suspend fun lastSeenMessage(chatId: Long): Long =
        context.dataStore.data.first()[longPreferencesKey("seen_$chatId")] ?: 0L

    suspend fun setLastSeenMessage(chatId: Long, messageId: Long) {
        context.dataStore.edit { it[longPreferencesKey("seen_$chatId")] = messageId }
    }

    // ---- Simple device-only preferences ----

    private fun <T> pref(key: androidx.datastore.preferences.core.Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data.map { it[key] ?: default }

    private suspend fun <T> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private val openSubtitlesKeyKey = stringPreferencesKey("opensubtitles_key")
    private val subtitleLanguagesKey = stringPreferencesKey("subtitle_languages")
    private val wifiOnlyKey = booleanPreferencesKey("downloads_wifi_only")
    private val downloadLimitKey = intPreferencesKey("downloads_limit_gb")
    private val autoDeleteKey = booleanPreferencesKey("downloads_auto_delete")
    private val eqPresetKey = intPreferencesKey("eq_preset")
    private val steadyVolumeKey = booleanPreferencesKey("steady_volume")
    private val fadeSecondsKey = intPreferencesKey("fade_seconds")
    private val groupVersionsKey = booleanPreferencesKey("group_versions")
    private val autoplayNextKey = booleanPreferencesKey("autoplay_next")

    /** The user's own OpenSubtitles API key; empty when not set. */
    val openSubtitlesKey: Flow<String> = pref(openSubtitlesKeyKey, "")
    suspend fun setOpenSubtitlesKey(value: String) = put(openSubtitlesKeyKey, value.trim())

    /** Comma separated language codes, like "en,ta". */
    val subtitleLanguages: Flow<String> = pref(subtitleLanguagesKey, "en")
    suspend fun setSubtitleLanguages(value: String) = put(subtitleLanguagesKey, value.trim())

    val wifiOnlyDownloads: Flow<Boolean> = pref(wifiOnlyKey, false)
    suspend fun setWifiOnlyDownloads(value: Boolean) = put(wifiOnlyKey, value)

    /** Download storage limit in GB, 0 for none. */
    val downloadLimitGb: Flow<Int> = pref(downloadLimitKey, 0)
    suspend fun setDownloadLimitGb(value: Int) = put(downloadLimitKey, value)

    val autoDeleteWatched: Flow<Boolean> = pref(autoDeleteKey, false)
    suspend fun setAutoDeleteWatched(value: Boolean) = put(autoDeleteKey, value)

    /** Equalizer preset index, -1 for off. */
    val eqPreset: Flow<Int> = pref(eqPresetKey, -1)
    suspend fun setEqPreset(value: Int) = put(eqPresetKey, value)

    val steadyVolume: Flow<Boolean> = pref(steadyVolumeKey, false)
    suspend fun setSteadyVolume(value: Boolean) = put(steadyVolumeKey, value)

    /** Seconds to fade music out and in between songs, 0 for off. */
    val fadeSeconds: Flow<Int> = pref(fadeSecondsKey, 0)
    suspend fun setFadeSeconds(value: Int) = put(fadeSecondsKey, value)

    val groupVersions: Flow<Boolean> = pref(groupVersionsKey, true)
    suspend fun setGroupVersions(value: Boolean) = put(groupVersionsKey, value)

    val autoplayNext: Flow<Boolean> = pref(autoplayNextKey, true)
    suspend fun setAutoplayNext(value: Boolean) = put(autoplayNextKey, value)

    // ---- Duplicates ----

    val hideDuplicates: Flow<Boolean> = context.dataStore.data.map { it[hideDuplicatesKey] ?: true }

    suspend fun setHideDuplicates(value: Boolean) {
        context.dataStore.edit { it[hideDuplicatesKey] = value }
    }

    // ---- Several Telegram accounts ----

    val currentAccount: Flow<Int> = context.dataStore.data.map { it[accountKey] ?: 0 }

    suspend fun account(): Int = currentAccount.first()

    /** Accounts that have logged in on this device, with the name to show in the switcher. */
    val accounts: Flow<List<Account>> = context.dataStore.data.map { decodeAccounts(it[accountsKey]) }

    suspend fun selectAccount(index: Int) {
        context.dataStore.edit { it[accountKey] = index }
    }

    /** Returns the index for a new, empty account slot. */
    suspend fun newAccountIndex(): Int {
        val prefs = context.dataStore.data.first()
        val used = decodeAccounts(prefs[accountsKey]).map { it.index } + (prefs[accountKey] ?: 0)
        return (used.maxOrNull() ?: 0) + 1
    }

    suspend fun saveAccount(account: Account) {
        context.dataStore.edit { prefs ->
            val others = decodeAccounts(prefs[accountsKey]).filter { it.index != account.index }
            prefs[accountsKey] = encodeAccounts((others + account).sortedBy { it.index })
        }
    }

    suspend fun removeAccount(index: Int) {
        context.dataStore.edit { prefs ->
            prefs[accountsKey] = encodeAccounts(decodeAccounts(prefs[accountsKey]).filter { it.index != index })
            prefs.remove(downloadsKey(index))
        }
    }

    private fun decodeAccounts(json: String?): List<Account> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Account(o.getInt("index"), o.optString("name"), o.optLong("userId"))
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeAccounts(accounts: List<Account>): String {
        val arr = org.json.JSONArray()
        accounts.forEach {
            arr.put(org.json.JSONObject().put("index", it.index).put("name", it.name).put("userId", it.userId))
        }
        return arr.toString()
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

/** A Telegram account logged in on this device. Index 0 is the first one. */
data class Account(val index: Int, val name: String, val userId: Long)

/** Everything that is shared between devices through Saved Messages. */
data class SyncSnapshot(
    val stamp: Long,
    val lists: List<UserList>,
    val hidden: Set<Long>,
    val resume: List<ResumeEntry>,
)
