package com.telestream.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Picture-in-picture state shared between the activity and the video player screen. */
object Pip {
    /** True while the activity is shown as a small floating window. */
    var active by mutableStateOf(false)

    /** Set by the video player while it is on screen: whether leaving the app should go to PiP. */
    var canEnter: (() -> Boolean)? = null

    /** Set by the video player: pauses the video when the PiP window is closed. */
    var pause: (() -> Unit)? = null
}
