package com.telestream.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telestream.app.data.Settings
import com.telestream.app.telegram.ChatSummary
import com.telestream.app.telegram.InvitePreview
import com.telestream.app.telegram.isInviteLink
import com.telestream.app.telegram.MediaFilter
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.TelegramRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ChatsViewModel(private val repo: TelegramRepository, private val settings: Settings) : ViewModel() {
    var chats by mutableStateOf<List<ChatSummary>>(emptyList())
        private set

    val hidden: StateFlow<Set<Long>> =
        settings.hiddenChats.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** When on, every chat shows a Hide or Show button. */
    var managing by mutableStateOf(false)
    var showHidden by mutableStateOf(false)

    fun setHidden(chatId: Long, hide: Boolean) {
        viewModelScope.launch { settings.setChatHidden(chatId, hide) }
    }
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                chats = repo.myChats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not load your chats"
            } finally {
                loading = false
            }
        }
    }
}

enum class SearchScope(val label: String) {
    MY_CHATS("In my chats"),
    PUBLIC("Find channels"),
}

class SearchViewModel(private val repo: TelegramRepository, private val settings: Settings) : ViewModel() {
    var query by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.MY_CHATS)
        private set
    var filter by mutableStateOf(MediaFilter.VIDEO)
        private set

    val results = mutableStateListOf<MediaItem>()
    var channels by mutableStateOf<List<ChatSummary>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var searched by mutableStateOf(false)
        private set

    /** Preview of a private invite link typed into "Find channels". */
    var invite by mutableStateOf<InvitePreview?>(null)
        private set
    var joinMessage by mutableStateOf<String?>(null)
        private set

    /** Bumped after each page so the list can ask for the next one even if a page was empty. */
    var pagesLoaded by mutableIntStateOf(0)
        private set

    val recent: StateFlow<List<String>> =
        settings.recentSearches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var next: String? = ""
    private var job: Job? = null
    private var generation = 0

    val endReached: Boolean get() = next == null

    fun selectScope(value: SearchScope) {
        scope = value
        if (searched) search()
    }

    fun selectFilter(value: MediaFilter) {
        filter = value
        if (searched && scope == SearchScope.MY_CHATS) search()
    }

    fun search(text: String = query) {
        query = text
        if (text.isBlank()) return
        job?.cancel()
        generation++
        results.clear()
        channels = emptyList()
        error = null
        searched = true
        next = ""
        loading = false
        viewModelScope.launch { settings.addRecentSearch(text) }
        invite = null
        joinMessage = null
        if (scope == SearchScope.PUBLIC) {
            loading = true
            job = viewModelScope.launch {
                try {
                    if (isInviteLink(text)) {
                        invite = repo.checkInviteLink(text.trim())
                        next = null
                        return@launch
                    }
                    channels = repo.searchPublicChats(text)
                    next = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    loading = false
                }
            }
        } else {
            loadMore()
        }
    }

    /** Joins the previewed invite link; calls [onJoined] with the chat id when it worked. */
    fun joinInvite(onJoined: (Long) -> Unit) {
        val preview = invite ?: return
        viewModelScope.launch {
            try {
                val chatId = repo.joinByInviteLink(preview.link)
                if (chatId != 0L) onJoined(chatId) else joinMessage = "Join request sent. An admin has to approve it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                joinMessage = e.message ?: "Could not join"
            }
        }
    }

    fun loadMore() {
        val offset = next ?: return
        if (loading || scope != SearchScope.MY_CHATS || query.isBlank()) return
        loading = true
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                val page = repo.searchEverywhere(query, filter, offset)
                if (gen != generation) return@launch
                results.addAll(page.items)
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
