package com.telestream.app.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pauses playback after a while, for falling asleep to music. */
object SleepTimer {
    private val scope = MainScope()
    private var job: Job? = null

    /** When playback will pause, in System.currentTimeMillis() time; null when off. */
    var endsAt by mutableStateOf<Long?>(null)
        private set

    fun start(player: Player, delayMs: Long) {
        cancel()
        endsAt = System.currentTimeMillis() + delayMs
        job = scope.launch {
            delay(delayMs)
            player.pause()
            endsAt = null
        }
    }

    /** Pauses when the current song ends. */
    fun endOfTrack(player: Player) {
        val remaining = player.duration - player.currentPosition
        if (remaining > 0) start(player, remaining) else cancel()
    }

    fun cancel() {
        job?.cancel()
        job = null
        endsAt = null
    }
}
