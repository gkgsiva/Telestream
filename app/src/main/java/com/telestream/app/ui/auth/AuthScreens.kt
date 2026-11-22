package com.telestream.app.ui.auth

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.telestream.app.data.ApiCredentials
import com.telestream.app.telegram.TdClient
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Page frame shared by all login steps: a centered, scrollable column with a title. */
@Composable
private fun AuthPage(title: String, error: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Text("Telestream", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            content()
        }
    }
}

/** Step 0: each user brings their own Telegram API ID and hash. */
@Composable
fun ApiCredentialsScreen(td: TdClient) {
    val scope = rememberCoroutineScope()
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AuthPage("Connect your Telegram API key", localError) {
        Text(
            "Telestream talks to Telegram with your own API key. Open my.telegram.org on any device, " +
                "sign in, choose \"API development tools\", create an app, then copy the App api_id " +
                "and App api_hash here. They stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter(Char::isDigit) },
            label = { Text("API ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            label = { Text("API hash") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val id = apiId.toIntOrNull()
                when {
                    id == null || id <= 0 -> localError = "The API ID is a number, for example 1234567."
                    !apiHash.matches(Regex("[0-9a-fA-F]{32}")) -> localError = "The API hash is 32 letters and digits."
                    else -> {
                        localError = null
                        scope.launch { td.submitCredentials(ApiCredentials(id, apiHash.lowercase())) }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

/** Steps after the API key: phone or QR, code, 2FA password. */
@Composable
fun LoginScreen(
    td: TdClient,
    state: TdApi.AuthorizationState,
    error: String?,
    preferPhone: Boolean,
    onPreferPhone: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()

    when (state) {
        is TdApi.AuthorizationStateWaitPhoneNumber -> {
            if (!preferPhone) {
                LaunchedEffect(Unit) { td.requestQrCode() }
                AuthPage("Preparing QR code…", error) {
                    OutlinedButton(onClick = { onPreferPhone(true) }) { Text("Use phone number instead") }
                }
            } else {
                PhoneStep(error, onSubmit = { scope.launch { td.setPhoneNumber(it) } }, onUseQr = { onPreferPhone(false) })
            }
        }

        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> QrStep(
            link = state.link,
            error = error,
            onUsePhone = {
                onPreferPhone(true)
                scope.launch { td.restart() }
            },
        )

        is TdApi.AuthorizationStateWaitCode -> CodeStep(
            phone = state.codeInfo.phoneNumber,
            error = error,
            onSubmit = { scope.launch { td.checkCode(it) } },
            onRestart = { scope.launch { td.restart() } },
        )

        is TdApi.AuthorizationStateWaitPassword -> PasswordStep(
            hint = state.passwordHint,
            error = error,
            onSubmit = { scope.launch { td.checkPassword(it) } },
        )

        is TdApi.AuthorizationStateWaitRegistration -> AuthPage("No Telegram account", error) {
            Text("This number has no Telegram account yet. Sign up in the official Telegram app first, then log in here.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { scope.launch { td.restart() } }) { Text("Start over") }
        }

        else -> AuthPage("One more step in Telegram", error) {
            Text(
                "Telegram asked for a login step this app does not support yet (email or a purchase). " +
                    "Log in once with the official Telegram app, then try again here.",
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { scope.launch { td.restart() } }) { Text("Start over") }
        }
    }
}

@Composable
private fun PhoneStep(error: String?, onSubmit: (String) -> Unit, onUseQr: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    AuthPage("Log in to Telegram", error) {
        Text(
            "Enter your phone number with the country code. Telegram will send you a login code.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } },
            label = { Text("Phone number") },
            placeholder = { Text("+91 98765 43210") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (phone.length > 5) onSubmit(phone) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(phone) }, enabled = phone.length > 5, modifier = Modifier.fillMaxWidth()) {
            Text("Send code")
        }
        TextButton(onClick = onUseQr, modifier = Modifier.fillMaxWidth()) { Text("Log in with a QR code instead") }
    }
}

@Composable
private fun QrStep(link: String, error: String?, onUsePhone: () -> Unit) {
    val qr = remember(link) { qrBitmap(link, 720) }
    AuthPage("Scan to log in", error) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "Login QR code",
                modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.padding(start = 20.dp)) {
                Text("1. Open Telegram on your phone")
                Text("2. Go to Settings, then Devices")
                Text("3. Tap \"Link Desktop Device\"")
                Text("4. Point your phone at this code")
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onUsePhone) { Text("Use phone number instead") }
    }
}

@Composable
private fun CodeStep(phone: String, error: String?, onSubmit: (String) -> Unit, onRestart: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AuthPage("Enter the code", error) {
        Text(
            "Telegram sent a login code to $phone, usually as a message in your Telegram app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit) },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.isNotEmpty()) onSubmit(code) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(code) }, enabled = code.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Log in") }
        TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Wrong number? Start over") }
    }
}

@Composable
private fun PasswordStep(hint: String, error: String?, onSubmit: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    AuthPage("Two-step verification", error) {
        Text(
            if (hint.isNotBlank()) "Enter your Telegram password. Hint: $hint" else "Enter your Telegram password.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
