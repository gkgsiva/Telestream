package com.telestream.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telestream.app.telegram.ChatSummary
import com.telestream.app.telegram.MediaFilter
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import com.telestream.app.telegram.SeriesParser
import com.telestream.app.telegram.Show
import com.telestream.app.telegram.TelegramRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Media of one channel or group, filtered by kind and optionally by a search query. */
class ChatViewModel(private val repo: TelegramRepository, val chatId: Long) : ViewModel() {
    var chat by mutableStateOf<ChatSummary?>(null)
        private set
    var filter by mutableStateOf(MediaFilter.VIDEO)
        private set
    var query by mutableStateOf("")
    val items = mutableStateListOf<MediaItem>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pagesLoaded by mutableIntStateOf(0)
        private set

    /** null = not known yet. */
    var isMember by mutableStateOf<Boolean?>(null)
        private set
    var joinMessage by mutableStateOf<String?>(null)
        private set
    var joining by mutableStateOf(false)
        private set

    /** Series view: every video in the chat grouped by show and season. */
    var seriesMode by mutableStateOf(false)
        private set
    var shows by mutableStateOf<List<Show>>(emptyList())
        private set
    var seriesLoading by mutableStateOf(false)
        private set
    var seriesScanned by mutableIntStateOf(0)
        private set
    private var seriesJob: Job? = null

    private var next: Long? = 0L
    private var job: Job? = null
    private var generation = 0

    val endReached: Boolean get() = next == null

    init {
        viewModelScope.launch {
            chat = runCatching { repo.chat(chatId) }.getOrNull()
            isMember = runCatching { repo.isMember(chatId) }.getOrNull()
        }
        reload()
    }

    fun selectFilter(value: MediaFilter) {
        val wasSeries = seriesMode
        seriesMode = false
        if (value == filter && !wasSeries) return
        filter = value
        reload()
    }

    /** Loads up to [SERIES_LIMIT] videos and video files and groups them into shows. */
    fun showSeries() {
        seriesMode = true
        if (seriesJob?.isActive == true || shows.isNotEmpty()) return
        seriesLoading = true
        seriesScanned = 0
        seriesJob = viewModelScope.launch {
            val all = mutableListOf<MediaItem>()
            try {
                for (f in listOf(MediaFilter.VIDEO, MediaFilter.FILES)) {
                    var from: Long? = 0L
                    while (from != null && all.size < SERIES_LIMIT) {
                        val page = repo.chatMedia(chatId, "", f, from)
                        all += page.items.filter { it.kind == MediaKind.VIDEO }
                        seriesScanned = all.size
                        from = page.next
                        shows = SeriesParser.group(all)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                seriesLoading = false
            }
        }
    }

    fun search() = reload()

    fun join() {
        if (joining) return
        joining = true
        viewModelScope.launch {
            try {
                if (repo.joinChat(chatId)) {
                    isMember = true
                    joinMessage = "Joined. It now shows in My chats."
                } else {
                    joinMessage = "Join request sent. An admin has to approve it."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                joinMessage = e.message ?: "Could not join"
            } finally {
                joining = false
            }
        }
    }

    fun reload() {
        job?.cancel()
        generation++
        items.clear()
        error = null
        next = 0L
        loading = false
        loadMore()
    }

    fun loadMore() {
        val from = next ?: return
        if (loading) return
        loading = true
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                val page = repo.chatMedia(chatId, query, filter, from)
                if (gen != generation) return@launch
                items.addAll(page.items)
                next = page.next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    error = e.message
                    next = null
                }
            } finally {
                if (gen == generation) {
                    loading = false
                    pagesLoaded++
                }
            }
        }
    }

    companion object {
        const val SERIES_LIMIT = 2000
    }
}
