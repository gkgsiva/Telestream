package com.telestream.app.telegram

import org.drinkless.tdlib.TdApi

/** Telegram queries the screens need, mapped to app models. */
class TelegramRepository(private val td: TdClient) {

    /** Channels and groups the user has joined, in Telegram's own order. */
    suspend fun myChats(limit: Int = 200): List<ChatSummary> {
        try {
            td.send(TdApi.LoadChats(TdApi.ChatListMain(), limit))
        } catch (e: TdException) {
            if (e.code != 404) throw e // 404 = everything is already loaded.
        }
        val ids = td.send(TdApi.GetChats(TdApi.ChatListMain(), limit)).chatIds
        return ids.map { chat(it) }.filter { it.kind != ChatKind.PRIVATE }
    }

    /** Public channels and groups whose name or @username matches. */
    suspend fun searchPublicChats(query: String): List<ChatSummary> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val username = usernameFrom(q)
        val exact = username?.let {
            runCatching { td.send(TdApi.SearchPublicChat(it)) }.getOrNull()?.let(::summary)
        }
        val found = td.send(TdApi.SearchPublicChats(q, null)).chatIds.map { chat(it) }
        return (listOfNotNull(exact) + found).distinctBy { it.id }.filter { it.kind != ChatKind.PRIVATE }
    }

    suspend fun chat(id: Long): ChatSummary = summary(td.chats[id] ?: td.send(TdApi.GetChat(id)))

    /** Joins a public chat. Returns false when the chat needs admin approval (request sent). */
    suspend fun joinChat(id: Long): Boolean = td.send(TdApi.JoinChat(id)) is TdApi.ChatJoinResultSuccess

    /** True if the user is in this chat (so it shows in My chats). */
    suspend fun isMember(chatId: Long): Boolean {
        val chat = td.chats[chatId] ?: td.send(TdApi.GetChat(chatId))
        return when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> when (val status = td.send(TdApi.GetSupergroup(type.supergroupId)).status) {
                is TdApi.ChatMemberStatusCreator -> status.isMember
                is TdApi.ChatMemberStatusAdministrator, is TdApi.ChatMemberStatusMember -> true
                is TdApi.ChatMemberStatusRestricted -> status.isMember
                else -> false
            }
            else -> chat.positions.isNotEmpty()
        }
    }

    /** Preview of a private invite link (t.me/+..., t.me/joinchat/...). */
    suspend fun checkInviteLink(link: String): InvitePreview {
        val info = td.send(TdApi.CheckChatInviteLink(link))
        return InvitePreview(
            link = link,
            chatId = info.chatId,
            title = info.title,
            memberCount = info.memberCount,
            description = info.description,
            photoFileId = info.photo?.small?.id,
            minithumbnail = info.photo?.minithumbnail?.data,
            needsApproval = info.createsJoinRequest,
        )
    }

    /** Joins by invite link and returns the chat id (0 if the join needs admin approval first). */
    suspend fun joinByInviteLink(link: String): Long {
        return when (val result = td.send(TdApi.JoinChatByInviteLink(link))) {
            is TdApi.ChatJoinResultSuccess -> result.chatId
            else -> 0L
        }
    }

    /** Loads a saved list item again so it gets a fresh file id. */
    suspend fun loadMessageMedia(chatId: Long, messageId: Long): MediaItem? {
        val message = td.send(TdApi.GetMessage(chatId, messageId))
        val title = runCatching { chat(chatId).title }.getOrDefault("")
        return MediaItem.from(message, title)
    }

    /** Media in one chat, newest first. [from] is the message id to continue from (0 = newest). */
    suspend fun chatMedia(chatId: Long, query: String, filter: MediaFilter, from: Long = 0): Page<Long> {
        val title = chat(chatId).title
        val request = TdApi.SearchChatMessages().apply {
            this.chatId = chatId
            this.query = query.trim()
            fromMessageId = from
            offset = 0
            limit = PAGE_SIZE
            this.filter = tdFilter(filter)
        }
        val found = td.send(request)
        val items = found.messages.mapNotNull { MediaItem.from(it, title) }.filter { matches(it, filter) }
        val next = found.nextFromMessageId.takeIf { it != 0L && found.messages.isNotEmpty() }
        return Page(items, next)
    }

    /** Media matching [query] across every chat the user has joined. [offset] "" = first page. */
    suspend fun searchEverywhere(query: String, filter: MediaFilter, offset: String = ""): Page<String> {
        val request = TdApi.SearchMessages().apply {
            chatList = null
            this.query = query.trim()
            this.offset = offset
            limit = PAGE_SIZE
            this.filter = tdFilter(filter)
            chatTypeFilter = null
            minDate = 0
            maxDate = 0
        }
        val found = td.send(request)
        val items = found.messages.mapNotNull { msg ->
            val title = runCatching { chat(msg.chatId).title }.getOrDefault("")
            MediaItem.from(msg, title)
        }.filter { matches(it, filter) }
        return Page(items, found.nextOffset.takeIf { it.isNotEmpty() })
    }

    /** Downloads a small file (thumbnail, chat photo) fully and returns its local path. */
    suspend fun downloadSmallFile(fileId: Int): String? {
        val file = td.send(TdApi.DownloadFile(fileId, 1, 0, 0, true))
        return file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotEmpty() }
    }

    private fun matches(item: MediaItem, filter: MediaFilter) = when (filter) {
        MediaFilter.VIDEO -> item.kind == MediaKind.VIDEO
        MediaFilter.MUSIC -> item.kind == MediaKind.AUDIO
        MediaFilter.FILES -> true
    }

    private fun tdFilter(filter: MediaFilter): TdApi.SearchMessagesFilter = when (filter) {
        MediaFilter.VIDEO -> TdApi.SearchMessagesFilterVideo()
        MediaFilter.MUSIC -> TdApi.SearchMessagesFilterAudio()
        MediaFilter.FILES -> TdApi.SearchMessagesFilterDocument()
    }

    private fun summary(chat: TdApi.Chat): ChatSummary {
        val kind = when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChatKind.CHANNEL else ChatKind.GROUP
            is TdApi.ChatTypeBasicGroup -> ChatKind.GROUP
            else -> ChatKind.PRIVATE
        }
        return ChatSummary(
            id = chat.id,
            title = chat.title,
            kind = kind,
            photoFileId = chat.photo?.small?.id,
            minithumbnail = chat.photo?.minithumbnail?.data,
        )
    }

    private fun usernameFrom(q: String): String? {
        val s = q.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val name = when {
            s.startsWith("@") -> s.drop(1)
            s.startsWith("t.me/") -> s.removePrefix("t.me/").substringBefore('/').substringBefore('?')
            else -> s
        }
        return name.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,31}")) }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
