package com.telestream.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether locked chats are visible right now. Entering the PIN unlocks them until the app is
 * closed or the user locks again.
 */
object ParentalLock {
    var unlocked by mutableStateOf(false)

    /** Chats to keep out of sight, given the stored locked set. */
    fun hiddenSet(locked: Set<Long>): Set<Long> = if (unlocked) emptySet() else locked
}
