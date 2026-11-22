package com.telestream.app.data

import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import org.json.JSONArray
import org.json.JSONObject

/** A media item saved in a user list. Telegram file ids can change, so we keep the message id. */
data class SavedItem(
    val chatId: Long,
    val messageId: Long,
    val kind: MediaKind,
    val title: String,
    val subtitle: String,
    val chatTitle: String,
    val durationSec: Int,
) {
    val id: String get() = "$chatId:$messageId"

    companion object {
        fun of(item: MediaItem) = SavedItem(
            item.chatId, item.messageId, item.kind, item.title, item.subtitle, item.chatTitle, item.durationSec,
        )
    }
}

/** A user-made list of videos and music. */
data class UserList(val id: String, val name: String, val items: List<SavedItem>)

internal object ListsJson {
    fun encode(lists: List<UserList>): String = JSONArray().apply {
        lists.forEach { list ->
            put(
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("items", JSONArray().apply {
                        list.items.forEach { i ->
                            put(
                                JSONObject()
                                    .put("chatId", i.chatId)
                                    .put("messageId", i.messageId)
                                    .put("kind", i.kind.name)
                                    .put("title", i.title)
                                    .put("subtitle", i.subtitle)
                                    .put("chatTitle", i.chatTitle)
                                    .put("duration", i.durationSec)
                            )
                        }
                    })
            )
        }
    }.toString()

    fun decode(text: String?): List<UserList> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                val items = o.optJSONArray("items") ?: JSONArray()
                UserList(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    items = (0 until items.length()).map { k ->
                        val i = items.getJSONObject(k)
                        SavedItem(
                            chatId = i.getLong("chatId"),
                            messageId = i.getLong("messageId"),
                            kind = runCatching { MediaKind.valueOf(i.getString("kind")) }.getOrDefault(MediaKind.VIDEO),
                            title = i.optString("title"),
                            subtitle = i.optString("subtitle"),
                            chatTitle = i.optString("chatTitle"),
                            durationSec = i.optInt("duration"),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
