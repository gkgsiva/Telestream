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
        if (value == filter) return
        filter = value
        reload()
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
}
