package com.telestream.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.telestream.app.player.Playback
import com.telestream.app.telegram.MediaItem
import com.telestream.app.telegram.MediaKind
import com.telestream.app.ui.auth.ApiCredentialsScreen
import com.telestream.app.ui.auth.LoginScreen
import com.telestream.app.ui.chat.ChatScreen
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.ui.common.Loading
import com.telestream.app.ui.common.TelestreamTheme
import com.telestream.app.ui.home.HomeScreen
import com.telestream.app.ui.player.MiniPlayer
import com.telestream.app.ui.player.PlayerScreen
import com.telestream.app.ui.player.rememberNowPlaying
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    private lateinit var playback: Playback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TelestreamApp
        playback = Playback(this, app.settings)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Needed for the music notification. Playback works without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            TelestreamTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(app, playback)
                }
            }
        }
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }
}

/** Credentials known? null = still loading from disk. */
private sealed interface CredState {
    data object Loading : CredState
    data class Loaded(val present: Boolean) : CredState
}

@Composable
private fun AppRoot(app: TelestreamApp, playback: Playback) {
    val auth by app.td.authState.collectAsStateWithLifecycle()
    val authError by app.td.authError.collectAsStateWithLifecycle()
    val creds by produceState<CredState>(CredState.Loading) {
        app.settings.credentials.map { CredState.Loaded(it != null) }.collect { value = it }
    }
    val isTv = LocalIsTv.current
    // Survives TDLib restarts, which briefly show the loading screen.
    var preferPhone by rememberSaveable { mutableStateOf(!isTv) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val state = auth
        when {
            creds == CredState.Loaded(false) &&
                (state == null || state is TdApi.AuthorizationStateWaitTdlibParameters) -> ApiCredentialsScreen(app.td)

            state is TdApi.AuthorizationStateReady -> MainNav(app, playback)

            state == null ||
                state is TdApi.AuthorizationStateWaitTdlibParameters ||
                state is TdApi.AuthorizationStateLoggingOut ||
                state is TdApi.AuthorizationStateClosing ||
                state is TdApi.AuthorizationStateClosed -> Loading()

            else -> Box(Modifier.safeDrawingPadding()) {
                LoginScreen(app.td, state!!, authError, preferPhone) { preferPhone = it }
                if (authError?.contains("API ID", ignoreCase = true) == true) {
                    val scope = rememberCoroutineScope()
                    androidx.compose.material3.TextButton(
                        onClick = { scope.launch { app.td.resetCredentials() } },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                    ) { androidx.compose.material3.Text("Enter a different API ID") }
                }
            }
        }
    }
}

@Composable
private fun MainNav(app: TelestreamApp, playback: Playback) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val player by produceState<Player?>(null) { value = playback.controller() }
    val nowPlaying = rememberNowPlaying(player)
    val backStack by nav.currentBackStackEntryAsState()
    val onPlayerScreen = backStack?.destination?.route == "player"

    val play: (List<MediaItem>, Int) -> Unit = { items, index ->
        val item = items[index]
        scope.launch {
            if (item.kind == MediaKind.VIDEO) {
                playback.playVideo(item)
                nav.navigate("player")
            } else {
                // Queue the music around the tapped track and keep browsing; the mini player appears.
                val audio = items.filter { it.kind == MediaKind.AUDIO }
                playback.playQueue(audio, audio.indexOf(item).coerceAtLeast(0))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).let { if (onPlayerScreen) it else it.safeDrawingPadding() }) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        onOpenChat = { nav.navigate("chat/$it") },
                        onPlay = play,
                        onLogOut = { scope.launch { player?.stop(); app.td.logOut() } },
                    )
                }
                composable("chat/{chatId}", arguments = listOf(navArgument("chatId") { type = NavType.LongType })) { entry ->
                    ChatScreen(
                        chatId = entry.arguments?.getLong("chatId") ?: 0L,
                        onBack = { nav.popBackStack() },
                        onPlay = play,
                    )
                }
                composable("player") {
                    PlayerScreen(player, onClose = { nav.popBackStack() })
                }
            }
        }
        val p = player
        if (p != null && nowPlaying.hasMedia && !nowPlaying.isVideo && !onPlayerScreen) {
            MiniPlayer(p, nowPlaying, onOpen = { nav.navigate("player") })
        }
    }
}
