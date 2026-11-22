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
import kotlinx.coroutines.launch

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
fun SettingsScreen(onBack: () -> Unit, beforeSwitchAccount: () -> Unit) {
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

            item { Section("Search") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hide duplicate reposts", modifier = Modifier.weight(1f))
                    Switch(checked = hideDuplicates, onCheckedChange = { scope.launch { app.settings.setHideDuplicates(it) } })
                }
            }
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
