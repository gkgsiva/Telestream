package com.telestream.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "telestream")

/** Telegram API credentials entered by the user (from my.telegram.org). */
data class ApiCredentials(val apiId: Int, val apiHash: String)

/** Small persistent settings: API credentials, resume positions and recent searches. */
class Settings(private val context: Context) {
    private val apiIdKey = intPreferencesKey("api_id")
    private val apiHashKey = stringPreferencesKey("api_hash")
    private val recentSearchesKey = stringPreferencesKey("recent_searches")

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
    suspend fun resumePosition(mediaId: String): Long =
        context.dataStore.data.first()[longPreferencesKey("pos_$mediaId")] ?: 0L

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
}
