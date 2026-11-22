package com.telestream.app.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telestream.app.remote.RemoteClient
import com.telestream.app.remote.RemoteServer
import com.telestream.app.remote.RemoteStatus
import com.telestream.app.remote.RemoteTv
import com.telestream.app.ui.common.LocalIsTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** On the phone: pick a Telestream TV and control it. On the TV: shows the pairing code. */
@Composable
fun RemoteScreen(onBack: () -> Unit) {
    val isTv = LocalIsTv.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = if (isTv) 40.dp else 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(if (isTv) "Phone remote" else "Control your TV", style = MaterialTheme.typography.titleLarge)
        }
        if (isTv) TvPairing() else PhoneRemote()
    }
}

@Composable
private fun TvPairing() {
    val code by RemoteServer.code
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Open Telestream on your phone, then Settings, then Control your TV.", style = MaterialTheme.typography.bodyLarge)
        Text("Pick this TV and enter this code:", modifier = Modifier.padding(top = 16.dp))
        Text(code.ifEmpty { "…" }, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "The phone and TV must be on the same Wi-Fi.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun PhoneRemote() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<RemoteTv?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<RemoteStatus?>(null) }
    val connected = RemoteClient.connected

    DisposableEffect(Unit) {
        RemoteClient.startDiscovery(context)
        onDispose { RemoteClient.stopDiscovery() }
    }
    LaunchedEffect(connected) {
        while (connected != null) {
            status = RemoteClient.send("status")
            delay(2_000)
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (connected != null) {
            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(connected.first.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            status?.title?.ifBlank { null } ?: "Nothing playing",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            IconButton(onClick = { scope.launch { status = RemoteClient.send("prev") } }) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                            }
                            IconButton(onClick = { scope.launch { status = RemoteClient.send("rel", mapOf("ms" to "-10000")) } }) {
                                Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds")
                            }
                            FilledIconButton(
                                onClick = { scope.launch { status = RemoteClient.send("toggle") } },
                                modifier = Modifier.size(64.dp),
                            ) {
                                Icon(
                                    if (status?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play or pause",
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            IconButton(onClick = { scope.launch { status = RemoteClient.send("rel", mapOf("ms" to "30000")) } }) {
                                Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds")
                            }
                            IconButton(onClick = { scope.launch { status = RemoteClient.send("next") } }) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next")
                            }
                        }
                        Text(
                            "To send a video to the TV, play it here and tap Play on TV.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(onClick = RemoteClient::disconnect) { Text("Disconnect") }
                    }
                }
            }
        }
        item {
            Text(
                if (RemoteClient.tvs.isEmpty()) "Looking for TVs running Telestream on this Wi-Fi…" else "TVs on this Wi-Fi",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(RemoteClient.tvs, key = { it.name }) { tv ->
            Surface(
                onClick = { error = null; asking = tv },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tv, contentDescription = null)
                    Text(tv.name, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    if (connected?.first == tv) Text("Connected", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    asking?.let { tv ->
        var code by remember(tv) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Connect to ${tv.name}") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Code shown on the TV") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = code.length == 4, onClick = {
                    scope.launch {
                        if (RemoteClient.connect(tv, code)) asking = null else error = "The TV did not accept that code"
                    }
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}
