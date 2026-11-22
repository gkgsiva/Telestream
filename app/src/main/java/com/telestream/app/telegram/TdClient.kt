package com.telestream.app.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import com.telestream.app.data.ApiCredentials
import com.telestream.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val code: Int, message: String) : Exception(message)

/**
 * The one TDLib client of the app. Exposes the authorization state, a cache of chats that
 * TDLib has told us about, file progress updates, and coroutine/blocking request helpers.
 */
class TdClient(private val context: Context, private val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    /** Last error worth showing on the login screens (bad API ID, wrong code, ...). */
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError

    private val _fileUpdates = MutableSharedFlow<TdApi.File>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val fileUpdates: SharedFlow<TdApi.File> = _fileUpdates

    val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    @Volatile
    private var client: Client? = null

    init {
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set TDLib log level", e)
        }
        createClient()
    }

    private fun createClient() {
        client = Client.create({ onUpdate(it) }, { Log.e(TAG, "Update handler failed", it) }, null)
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthState(update.authorizationState)
            is TdApi.UpdateNewChat -> chats[update.chat.id] = update.chat
            is TdApi.UpdateChatTitle -> chats[update.chatId]?.title = update.title
            is TdApi.UpdateChatPhoto -> chats[update.chatId]?.photo = update.photo
            is TdApi.UpdateFile -> _fileUpdates.tryEmit(update.file)
        }
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        _authState.value = state
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> scope.launch { sendParameters() }
            // After logOut or close, TDLib needs a brand-new client instance.
            is TdApi.AuthorizationStateClosed -> {
                chats.clear()
                createClient()
            }
        }
    }

    private suspend fun sendParameters() {
        val creds = settings.currentCredentials() ?: return // The UI asks for them first.
        sendParameters(creds)
    }

    private suspend fun sendParameters(creds: ApiCredentials) {
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(context.noBackupFilesDir, "tdlib").absolutePath
            filesDirectory = File(context.filesDir, "tdfiles").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = creds.apiId
            apiHash = creds.apiHash
            systemLanguageCode = Locale.getDefault().language.ifEmpty { "en" }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = APP_VERSION
        }
        runAuth { send(params) }
    }

    /** Called by the credentials screen once the user has entered their API ID and hash. */
    suspend fun submitCredentials(creds: ApiCredentials) {
        settings.saveCredentials(creds)
        if (_authState.value is TdApi.AuthorizationStateWaitTdlibParameters) sendParameters(creds)
    }

    /** Forget the API credentials and restart TDLib so they can be entered again. */
    suspend fun resetCredentials() {
        settings.clearCredentials()
        _authError.value = null
        runCatching { send(TdApi.Close()) }
    }

    /** Closes and restarts TDLib, which returns the login flow to its first step. */
    suspend fun restart() {
        _authError.value = null
        runCatching { send(TdApi.Close()) }
    }

    suspend fun requestQrCode() = runAuth { send(TdApi.RequestQrCodeAuthentication(LongArray(0))) }

    suspend fun setPhoneNumber(phone: String) = runAuth {
        val authSettings = TdApi.PhoneNumberAuthenticationSettings().apply { authenticationTokens = emptyArray() }
        send(TdApi.SetAuthenticationPhoneNumber(phone, authSettings))
    }

    suspend fun checkCode(code: String) = runAuth { send(TdApi.CheckAuthenticationCode(code)) }

    suspend fun checkPassword(password: String) = runAuth { send(TdApi.CheckAuthenticationPassword(password)) }

    suspend fun logOut() = runAuth { send(TdApi.LogOut()) }

    fun clearAuthError() {
        _authError.value = null
    }

    private suspend fun runAuth(block: suspend () -> Unit) {
        _authError.value = null
        try {
            block()
        } catch (e: TdException) {
            _authError.value = friendlyError(e)
        }
    }

    private fun friendlyError(e: TdException): String = when {
        e.message?.contains("API_ID", ignoreCase = true) == true ->
            "Telegram rejected the API ID or hash. Check them at my.telegram.org and enter them again."
        e.message?.contains("PHONE_CODE_INVALID") == true -> "That code is not right. Try again."
        e.message?.contains("PASSWORD_HASH_INVALID") == true -> "Wrong password. Try again."
        e.message?.contains("PHONE_NUMBER_INVALID") == true ->
            "That phone number is not valid. Include the country code, for example +91..."
        else -> e.message ?: "Error ${e.code}"
    }

    /** Sends a request and suspends until TDLib answers. Throws [TdException] on a TDLib error. */
    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R =
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resumeWithException(TdException(-1, "TDLib is not running"))
                return@suspendCancellableCoroutine
            }
            c.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TdException(result.code, result.message))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as R)
                }
            }
        }

    /** Blocking variant for player threads. Never call it on the main thread. */
    fun <R : TdApi.Object> sendBlocking(function: TdApi.Function<R>, timeoutMs: Long = 30_000): R {
        val c = client ?: throw TdException(-1, "TDLib is not running")
        val latch = CountDownLatch(1)
        val ref = AtomicReference<TdApi.Object>()
        c.send(function) { result ->
            ref.set(result)
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TdException(-2, "TDLib request timed out")
        val result = ref.get()
        if (result is TdApi.Error) throw TdException(result.code, result.message)
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    companion object {
        private const val TAG = "TdClient"
        const val APP_VERSION = "0.1.0"
    }
}
