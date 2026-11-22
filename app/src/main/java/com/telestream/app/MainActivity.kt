package com.telestream.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
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
import com.telestream.app.stream.ExternalPlayer
import com.telestream.app.ui.settings.SettingsScreen
import com.telestream.app.ui.player.Pip
import com.telestream.app.cast.CastManager
import com.telestream.app.remote.RemoteClient
import com.telestream.app.remote.RemoteServer
import com.telestream.app.ui.remote.RemoteScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
import com.telestream.app.ui.player.NowPlayingScreen
import com.telestream.app.ui.player.PlayerScreen
import com.telestream.app.ui.lists.AddToListDialog
import com.telestream.app.ui.lists.ListScreen
import com.telestream.app.data.SavedItem
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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

        handleIntent(intent)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            RemoteServer.start(this)
        } else {
            CastManager.init(this)
        }

        setContent {
            TelestreamTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(app, playback)
                }
            }
        }
    }

    /** Leaving the app while a video plays shrinks it to a floating window (phones only). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (Build.VERSION.SDK_INT >= 26 && !isTv && Pip.canEnter?.invoke() == true) {
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.active = isInPictureInPictureMode
        // Closing the floating window stops the activity without bringing it back: pause the video.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            Pip.pause?.invoke()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val chatId = intent?.getLongExtra(EXTRA_OPEN_CHAT, 0L) ?: 0L
        if (chatId != 0L) openChatRequest.value = chatId
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"

        /** A chat to open, from a new-upload notification. */
        val openChatRequest = androidx.compose.runtime.mutableStateOf<Long?>(null)
    }

    override fun onStart() {
        super.onStart()
        // Pick up lists and progress changed on the user's other devices.
        (application as TelestreamApp).sync.requestSync()
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
                // While adding an account, offer a way back to the ones already logged in.
                val accounts by app.settings.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
                val currentAccount by app.settings.currentAccount.collectAsStateWithLifecycle(initialValue = 0)
                val other = accounts.firstOrNull { it.index != currentAccount }
                if (other != null && accounts.none { it.index == currentAccount }) {
                    val switchScope = rememberCoroutineScope()
                    androidx.compose.material3.TextButton(
                        onClick = { switchScope.launch { app.td.switchAccount(other.index) } },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp),
                    ) { androidx.compose.material3.Text("Back to ${other.name}") }
                }
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
    val context = LocalContext.current
    val player by produceState<Player?>(null) { value = playback.controller() }
    val nowPlaying = rememberNowPlaying(player)
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val fullScreen = route == "player"
    var addToList by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(Unit) { app.sync.requestSync() }

    // A phone remote asked this TV to play something.
    val remotePlay = RemoteServer.playRequest.value
    LaunchedEffect(remotePlay) {
        val request = remotePlay ?: return@LaunchedEffect
        RemoteServer.playRequest.value = null
        val item = runCatching { app.repository.loadMessageMedia(request.chatId, request.messageId) }.getOrNull()
        if (item == null) {
            Toast.makeText(context, "This TV can't open that video. Is it logged in to the same account?", Toast.LENGTH_LONG).show()
        } else {
            playback.playQueue(listOf(item), 0)
            if (request.positionMs > 0) playback.controller().seekTo(request.positionMs)
            nav.navigate(if (item.kind == MediaKind.VIDEO) "player" else "nowplaying")
        }
    }

    val openChat = MainActivity.openChatRequest.value
    LaunchedEffect(openChat) {
        if (openChat != null) {
            MainActivity.openChatRequest.value = null
            nav.navigate("chat/$openChat")
        }
    }

    val download: (MediaItem) -> Unit = { item ->
        scope.launch {
            try {
                app.repository.startDownload(item)
                app.settings.addDownload(SavedItem.of(item))
                Toast.makeText(context, "Downloading \"${item.title}\". See the Downloads tab.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Could not download: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val play: (List<MediaItem>, Int) -> Unit = { items, index ->
        val item = items[index]
        scope.launch {
            if (item.kind == MediaKind.VIDEO) {
                playback.playVideo(item)
                nav.navigate("player")
            } else {
                // Queue the music around the tapped track; the mini player and Now playing take over.
                val audio = items.filter { it.kind == MediaKind.AUDIO }
                playback.playQueue(audio, audio.indexOf(item).coerceAtLeast(0))
                nav.navigate("nowplaying")
            }
        }
    }

    val playSaved: (List<SavedItem>, Int, Boolean) -> Unit = { saved, index, shuffle ->
        scope.launch {
            Toast.makeText(context, "Loading ${saved.size} items…", Toast.LENGTH_SHORT).show()
            // Reload each message so file ids are fresh; skip ones that were deleted from Telegram.
            val loaded = saved.mapIndexedNotNull { i, s ->
                runCatching { app.repository.loadMessageMedia(s.chatId, s.messageId) }.getOrNull()?.let { i to it }
            }
            if (loaded.isEmpty()) {
                Toast.makeText(context, "These items are no longer available in Telegram.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val start = loaded.indexOfFirst { it.first >= index }.coerceAtLeast(0)
            val items = loaded.map { it.second }
            playback.playQueue(items, start, shuffle)
            nav.navigate(if (items[start].kind == MediaKind.VIDEO) "player" else "nowplaying")
        }
    }

    val openPlayer = { nav.navigate(if (nowPlaying.isVideo) "player" else "nowplaying") }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).let { if (fullScreen) it else it.safeDrawingPadding() }) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        onOpenChat = { nav.navigate("chat/$it") },
                        onOpenList = { nav.navigate("list/$it") },
                        onPlay = play,
                        onAddToList = { addToList = it },
                        onDownload = download,
                        onLogOut = { scope.launch { player?.stop(); app.td.logOutAndSwitch() } },
                        onOpenSettings = { nav.navigate("settings") },
                    )
                }
                composable("chat/{chatId}", arguments = listOf(navArgument("chatId") { type = NavType.LongType })) { entry ->
                    ChatScreen(
                        chatId = entry.arguments?.getLong("chatId") ?: 0L,
                        onBack = { nav.popBackStack() },
                        onPlay = play,
                        onAddToList = { addToList = it },
                        onDownload = download,
                    )
                }
                composable("list/{listId}") { entry ->
                    ListScreen(
                        listId = entry.arguments?.getString("listId").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onPlay = playSaved,
                    )
                }
                composable("player") {
                    val vlcContext = LocalContext.current
                    val current = playback.itemFor(nowPlaying.mediaId)
                    PlayerScreen(
                        player,
                        current = current,
                        onPlayOnTv = if (RemoteClient.connected != null && current != null) {
                            {
                                val position = player?.currentPosition ?: 0L
                                player?.pause()
                                scope.launch {
                                    val ok = RemoteClient.playOnTv(current.chatId, current.messageId, position) != null
                                    Toast.makeText(context, if (ok) "Playing on your TV" else "Could not reach the TV", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            null
                        },
                        onClose = { nav.popBackStack() },
                        onOpenInVlc = current?.let { item ->
                            {
                                val position = player?.currentPosition ?: 0L
                                player?.pause()
                                ExternalPlayer.openInVlc(vlcContext, item, position)
                            }
                        },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        beforeSwitchAccount = { player?.stop() },
                        onOpenRemote = { nav.navigate("remote") },
                    )
                }
                composable("remote") {
                    RemoteScreen(onBack = { nav.popBackStack() })
                }
                composable("nowplaying") {
                    NowPlayingScreen(player, nowPlaying, playback.itemFor(nowPlaying.mediaId), onClose = { nav.popBackStack() })
                }
            }
        }
        val p = player
        if (p != null && nowPlaying.hasMedia && !nowPlaying.isVideo && route != "player" && route != "nowplaying") {
            MiniPlayer(p, nowPlaying, playback.itemFor(nowPlaying.mediaId), onOpen = openPlayer)
        }
    }

    addToList?.let { item ->
        AddToListDialog(
            item = item,
            onDismiss = { addToList = null },
            onAdded = { name ->
                addToList = null
                Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
            },
        )
    }
}
