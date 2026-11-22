package com.telestream.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telestream.app.TelestreamApp
import com.telestream.app.data.ParentalLock
import com.telestream.app.ui.common.LocalIsTv
import com.telestream.app.update.Release
import com.telestream.app.update.UpdateChecker
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Lock icon in the header: unlocks locked chats with the PIN, or locks them again. */
@Composable
fun LockButton() {
    val app = TelestreamApp.instance
    val hasPin by app.settings.hasPin.collectAsStateWithLifecycle(initialValue = false)
    var asking by remember { mutableStateOf(false) }
    if (!hasPin) return
    if (ParentalLock.unlocked) {
        IconButton(onClick = { ParentalLock.unlocked = false }) {
            Icon(Icons.Default.LockOpen, contentDescription = "Lock locked chats again")
        }
    } else {
        IconButton(onClick = { asking = true }) { Icon(Icons.Default.Lock, contentDescription = "Unlock locked chats") }
    }
    if (asking) {
        UnlockDialog(onDismiss = { asking = false }, onUnlocked = { asking = false; ParentalLock.unlocked = true })
    }
}

/** Asks for the parental PIN and calls [onUnlocked] when it is right. */
@Composable
fun UnlockDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val app = TelestreamApp.instance
    val scope = rememberCoroutineScope()
    var wrong by remember { mutableStateOf(false) }
    PinDialog(
        title = "Enter parental PIN",
        error = if (wrong) "Wrong PIN" else null,
        onDismiss = onDismiss,
        onPin = { pin -> scope.launch { if (app.settings.checkPin(pin)) onUnlocked() else wrong = true } },
    )
}

@Composable
fun PinDialog(title: String, error: String?, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { value -> pin = value.filter { it.isDigit() }.take(8) },
                label = { Text("PIN (4 to 8 digits)") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(onClick = { onPin(pin) }, enabled = pin.length >= 4) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class PinStep { NONE, VERIFY_TO_CHANGE, VERIFY_TO_REMOVE, NEW }

@Composable
fun SettingsScreen(onBack: () -> Unit, beforeSwitchAccount: () -> Unit, onOpenRemote: () -> Unit) {
    val app = TelestreamApp.instance
    val scope = rememberCoroutineScope()
    val isTv = LocalIsTv.current
    val accounts by app.settings.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val current by app.settings.currentAccount.collectAsStateWithLifecycle(initialValue = 0)
    val hasPin by app.settings.hasPin.collectAsStateWithLifecycle(initialValue = false)
    val locked by app.settings.lockedChats.collectAsStateWithLifecycle(initialValue = emptySet())
    val followed by app.settings.followedChats.collectAsStateWithLifecycle(initialValue = emptySet())
    val hideDuplicates by app.settings.hideDuplicates.collectAsStateWithLifecycle(initialValue = true)
    var pinStep by remember { mutableStateOf(PinStep.NONE) }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun switchTo(index: Int) {
        beforeSwitchAccount()
        scope.launch { app.td.switchAccount(index) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = if (isTv) 40.dp else 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section("Telegram accounts") }
            items(accounts, key = { it.index }) { account ->
                Surface(
                    onClick = { if (account.index != current) switchTo(account.index) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Text(account.name, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        if (account.index == current) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Current account", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("Switch", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { scope.launch { val index = app.settings.newAccountIndex(); beforeSwitchAccount(); app.td.switchAccount(index) } }) {
                    Text("Add another account")
                }
            }
            item {
                Hint("Lists, hidden chats and Continue watching are shared by all accounts on this device. Downloads belong to the account that made them.")
            }

            item { Section("Parental lock") }
            item {
                if (hasPin) {
                    Hint("${locked.size} chats are locked. Lock or unlock chats under My chats, then Hide or lock. The lock icon at the top unlocks them until you close the app.")
                } else {
                    Hint("Set a PIN, then lock channels or groups under My chats, then Hide or lock. Locked chats disappear from chats, search and Continue watching until the PIN is entered.")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasPin) {
                        OutlinedButton(onClick = { pinError = null; pinStep = PinStep.VERIFY_TO_CHANGE }) { Text("Change PIN") }
                        OutlinedButton(onClick = { pinError = null; pinStep = PinStep.VERIFY_TO_REMOVE }) { Text("Remove PIN") }
                    } else {
                        OutlinedButton(onClick = { pinError = null; pinStep = PinStep.NEW }) { Text("Set PIN") }
                    }
                }
            }

            item { Section("New uploads") }
            item {
                Hint(
                    if (followed.isEmpty()) "Open a channel and tap the bell to get a notification when it posts a new video or song."
                    else "You get notifications for new uploads in ${followed.size} channels. Telestream checks about every 15 minutes.",
                )
            }

            item { Section(if (isTv) "Phone remote" else "Control your TV") }
            item {
                Hint(
                    if (isTv) "Control this TV from Telestream on your phone. Open this page to see the pairing code."
                    else "Use this phone as a remote for Telestream on your TV, or send the video you are watching to it.",
                )
            }
            item { OutlinedButton(onClick = onOpenRemote) { Text(if (isTv) "Show pairing code" else "Find my TV") } }

            item { Section("Search") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide duplicate reposts", modifier = Modifier.weight(1f))
                    Switch(checked = hideDuplicates, onCheckedChange = { scope.launch { app.settings.setHideDuplicates(it) } })
                }
            }
            item { Section("Playback") }
            item { ToggleLine("Play the next episode automatically", app.settings.autoplayNext, true) { app.settings.setAutoplayNext(it) } }

            item { Section("Subtitles") }
            item { SubtitleSettings() }

            item { Section("Downloads") }
            item { DownloadSettings() }

            item { Section("Backup") }
            item { BackupSection() }

            item { Section("Info") }
            item { AboutSection() }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }

    when (pinStep) {
        PinStep.NONE -> {}
        PinStep.VERIFY_TO_CHANGE, PinStep.VERIFY_TO_REMOVE -> PinDialog(
            title = "Enter current PIN",
            error = pinError,
            onDismiss = { pinStep = PinStep.NONE },
            onPin = { pin ->
                scope.launch {
                    if (!app.settings.checkPin(pin)) {
                        pinError = "Wrong PIN"
                    } else if (pinStep == PinStep.VERIFY_TO_REMOVE) {
                        app.settings.setPin(null)
                        ParentalLock.unlocked = false
                        pinStep = PinStep.NONE
                    } else {
                        pinError = null
                        pinStep = PinStep.NEW
                    }
                }
            },
        )
        PinStep.NEW -> PinDialog(
            title = "Choose a new PIN",
            error = null,
            onDismiss = { pinStep = PinStep.NONE },
            onPin = { pin ->
                scope.launch {
                    app.settings.setPin(pin)
                    ParentalLock.unlocked = false
                    pinStep = PinStep.NONE
                }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { UpdateChecker.installedVersion(context) }
    val build = remember { UpdateChecker.installedBuild(context) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoLine("Version", "$version (build $build)")
        InfoLine("Device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        InfoLine("Processor", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        Hint("Telestream plays videos and music from your own Telegram chats. It has no server: your login and API keys stay on this device.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !checking && progress == null, onClick = {
                checking = true
                status = null
                release = null
                scope.launch {
                    try {
                        val latest = UpdateChecker.latest()
                        if (UpdateChecker.isNewer(latest.version, version)) {
                            release = latest
                            status = "Version ${latest.version} is available."
                        } else {
                            status = "You have the latest version."
                        }
                    } catch (e: Exception) {
                        status = e.message ?: "Could not check for updates."
                    } finally {
                        checking = false
                    }
                }
            }) { Text(if (checking) "Checking…" else "Check for update") }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdateChecker.RELEASES_PAGE))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("Releases page") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        release?.let { r ->
            if (r.notes.isNotBlank()) Hint(r.notes.take(600))
            val url = r.apkUrl
            if (url == null) {
                Hint("This release has no APK attached yet.")
            } else if (progress != null) {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            } else {
                androidx.compose.material3.Button(onClick = {
                    if (!UpdateChecker.canInstall(context)) {
                        status = "Allow Telestream to install apps, then tap Download and install again."
                        UpdateChecker.openInstallPermission(context)
                    } else scope.launch {
                        progress = 0f
                        try {
                            val file = UpdateChecker.download(context, url) { p -> progress = p }
                            UpdateChecker.install(context, file)
                            status = "If Android says the app conflicts, uninstall this test build first, then install the update."
                        } catch (e: Exception) {
                            status = e.message ?: "Download failed."
                        } finally {
                            progress = null
                        }
                    }
                }) { Text("Download and install") }
            }
        }
        Section("Open source")
        Hint(
            "TDLib (Boost Software License 1.0), AndroidX and Media3 (Apache 2.0), FFmpeg decoders via Jellyfin media3-ffmpeg-decoder (LGPL), " +
                "Coil (Apache 2.0), ZXing (Apache 2.0). Lyrics come from LRCLIB.",
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        Text(value)
    }
}

@Composable
private fun ToggleLine(label: String, flow: kotlinx.coroutines.flow.Flow<Boolean>, initial: Boolean, onChange: suspend (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val value by flow.collectAsStateWithLifecycle(initialValue = initial)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = { v -> scope.launch { onChange(v) } })
    }
}

@Composable
private fun SubtitleSettings() {
    val app = TelestreamApp.instance
    val scope = rememberCoroutineScope()
    val savedKey by app.settings.openSubtitlesKey.collectAsStateWithLifecycle(initialValue = "")
    val savedLanguages by app.settings.subtitleLanguages.collectAsStateWithLifecycle(initialValue = "en")
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var languages by remember(savedLanguages) { mutableStateOf(savedLanguages) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "The Subtitles button in the player finds subtitles on OpenSubtitles.com. Create a free account there, " +
                "add an API consumer to get a key, and paste it here. It stays on this device.",
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("OpenSubtitles API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = languages,
            onValueChange = { languages = it },
            label = { Text("Languages, like en,ta,hi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (key != savedKey || languages != savedLanguages) {
            OutlinedButton(onClick = {
                scope.launch {
                    app.settings.setOpenSubtitlesKey(key)
                    app.settings.setSubtitleLanguages(languages)
                }
            }) { Text("Save") }
        }
    }
}

@Composable
private fun DownloadSettings() {
    val app = TelestreamApp.instance
    val scope = rememberCoroutineScope()
    val limit by app.settings.downloadLimitGb.collectAsStateWithLifecycle(initialValue = 0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleLine("Download only on Wi-Fi", app.settings.wifiOnlyDownloads, false) { app.settings.setWifiOnlyDownloads(it) }
        ToggleLine("Delete downloaded videos after watching", app.settings.autoDeleteWatched, false) { app.settings.setAutoDeleteWatched(it) }
        Text("Storage limit", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf(0, 5, 10, 20, 50)) { gb ->
                androidx.compose.material3.FilterChip(
                    selected = limit == gb,
                    onClick = { scope.launch { app.settings.setDownloadLimitGb(gb) } },
                    label = { Text(if (gb == 0) "No limit" else "$gb GB") },
                )
            }
        }
    }
}

/** Saves lists, hidden chats, Continue watching and followed channels to a file, and restores them. */
@Composable
private fun BackupSection() {
    val app = TelestreamApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val json = org.json.JSONObject()
                    .put("telestreamBackup", 1)
                    .put("data", org.json.JSONObject(app.sync.encode(app.settings.syncSnapshot())))
                    .put("followed", org.json.JSONArray(app.settings.followedChats.first().toList()))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
                }
                "Backup saved."
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
        }
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } ?: throw IllegalStateException("empty file")
                val json = org.json.JSONObject(text)
                val snapshot = app.sync.decode(json.getJSONObject("data").toString()) ?: throw IllegalStateException("not a Telestream backup")
                // A new stamp makes the restored data win and sync to the other devices.
                app.settings.applySnapshot(snapshot.copy(stamp = System.currentTimeMillis()))
                json.optJSONArray("followed")?.let { arr ->
                    for (i in 0 until arr.length()) app.settings.setChatFollowed(arr.getLong(i), true)
                }
                app.sync.requestSync()
                "Restored ${snapshot.lists.size} lists."
            } catch (e: Exception) {
                "Could not restore: ${e.message}"
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint("Save your lists, hidden chats, Continue watching and followed channels to a file, or restore them. Restoring replaces your current lists.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { save.launch("telestream-backup.json") } }) { Text("Back up to file") }
            OutlinedButton(onClick = { runCatching { restore.launch(arrayOf("application/json", "text/plain", "*/*")) } }) { Text("Restore") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
