package com.telestream.app.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

/**
 * Equalizer presets, steady volume (a gentle compressor and limiter, Android 9+) and fading
 * between songs, attached to the player's audio session.
 */
class MusicEffects(audioSessionId: Int) {
    private val equalizer: Equalizer? = runCatching { Equalizer(0, audioSessionId) }
        .onFailure { Log.i(TAG, "No equalizer on this device", it) }.getOrNull()

    private val dynamics: DynamicsProcessing? = if (Build.VERSION.SDK_INT >= 28) {
        runCatching { steadyVolume(audioSessionId) }.onFailure { Log.i(TAG, "No dynamics processing", it) }.getOrNull()
    } else {
        null
    }

    init {
        presetNames.clear()
        equalizer?.let { eq ->
            for (i in 0 until eq.numberOfPresets) presetNames.add(eq.getPresetName(i.toShort()))
        }
        steadyAvailable = dynamics != null
    }

    fun setPreset(index: Int) {
        val eq = equalizer ?: return
        runCatching {
            if (index < 0 || index >= eq.numberOfPresets) {
                eq.enabled = false
            } else {
                eq.usePreset(index.toShort())
                eq.enabled = true
            }
        }
    }

    fun setSteady(on: Boolean) {
        runCatching { dynamics?.enabled = on }
    }

    /** Called a few times a second: lowers the volume at the start and end of each song. */
    fun fade(player: Player, fadeSeconds: Int) {
        val music = player.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC
        val duration = player.duration
        if (fadeSeconds <= 0 || !music || duration <= 0 || !player.isPlaying) {
            if (player.volume != 1f) player.volume = 1f
            return
        }
        val fadeMs = fadeSeconds * 1000f
        val position = player.currentPosition
        val level = minOf(1f, position / fadeMs, (duration - position) / fadeMs).coerceIn(0.05f, 1f)
        player.volume = level
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { dynamics?.release() }
    }

    @androidx.annotation.RequiresApi(28)
    private fun steadyVolume(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, false, 0, true, 1, false, 0, true,
        ).build()
        val dp = DynamicsProcessing(0, sessionId, config)
        // One band over the whole range: quiet parts come up, loud parts come down.
        dp.setMbcBandAllChannelsTo(
            0,
            DynamicsProcessing.MbcBand(true, 20000f, 10f, 300f, 3f, -28f, 6f, -90f, 1f, 0f, 8f),
        )
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
        dp.enabled = false
        return dp
    }

    companion object {
        private const val TAG = "MusicEffects"

        /** Preset names of the device's equalizer, for the Sound dialog. */
        val presetNames = mutableStateListOf<String>()

        @Volatile
        var steadyAvailable = false
            private set
    }
}
