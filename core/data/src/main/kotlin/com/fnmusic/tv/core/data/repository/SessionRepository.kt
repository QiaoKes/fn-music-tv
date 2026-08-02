package com.fnmusic.tv.core.data.repository

import android.content.Context
import com.fnmusic.tv.core.data.api.TrimMusicApi
import com.fnmusic.tv.core.data.security.SecureTokenStore
import com.fnmusic.tv.core.data.security.TokenStore
import com.fnmusic.tv.core.data.server.NormalizedServer
import com.fnmusic.tv.core.data.server.ConnectionAccess
import com.fnmusic.tv.core.data.server.ConnectionResolver
import com.fnmusic.tv.core.data.server.ConnectionTarget
import com.fnmusic.tv.core.data.server.ServerUrlNormalizer
import com.fnmusic.tv.core.data.server.ServerUrlResult
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.PlaybackCredentials
import com.fnmusic.tv.core.model.ServerIdentity
import com.fnmusic.tv.core.model.User
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface SessionState {
    data object Loading : SessionState
    data class SignedOut(
        val savedServer: String = "",
        val recentServers: List<String> = emptyList(),
        val error: AppError? = null,
    ) : SessionState
    data class SignedIn(val server: ServerIdentity, val user: User) : SessionState
}

class SessionRepository internal constructor(
    context: Context,
    private val tokenStore: TokenStore,
    private val clientFactory: () -> OkHttpClient,
) {
    constructor(context: Context) : this(context, SecureTokenStore(context), TrimMusicApi::client)

    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private var memoryToken: String? = null
    private var memoryAccessCode: String? = null
    private var rememberedCredentialsLoaded = false
    private var connectionAccess = ConnectionAccess()
    private var api: TrimMusicApi? = null
    private val authVerification = Mutex()
    private val credentialLoadMutex = Mutex()

    val savedServer: String get() = preferences.getString(SERVER, "").orEmpty()
    val recentServers: List<String>
        get() = (0 until MAX_RECENT_SERVERS).mapNotNull { index ->
            preferences.getString("$RECENT_SERVER_PREFIX$index", null)
        }
    val deviceId: String by lazy {
        preferences.getString(DEVICE, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(DEVICE, it).apply()
        }
    }

    suspend fun restore() {
        if (savedServer.isBlank()) {
            _state.value = signedOut()
            return
        }
        loadRememberedCredentials()
        val token = memoryToken
        if (token == null) {
            _state.value = signedOut()
            return
        }
        try {
            val connected = connect(savedServer, useHttps = false, memoryAccessCode.orEmpty(), savedRelayMode)
            val user = connected.api.me().toDomain()
            api = connected.api
            connectionAccess = connected.access
            _state.value = SessionState.SignedIn(connected.server, user)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            api = null
            val error = (cause as? AppException)?.error ?: AppError.Unknown()
            if (error == AppError.Unauthenticated || error == AppError.AccountDisabled) clearToken()
            _state.value = signedOut(error)
        }
    }

    suspend fun login(
        serverInput: String,
        useHttps: Boolean,
        username: String,
        password: CharArray,
        remember: Boolean,
        accessCode: CharArray = charArrayOf(),
    ) {
        try {
            val rawAccessCode = accessCode.concatToString()
            val connected = connect(serverInput, useHttps, rawAccessCode)
            val result = connected.api.login(username, password.concatToString(), deviceId)
            memoryToken = result.userToken
            memoryAccessCode = rawAccessCode.takeIf(String::isNotBlank)
            rememberedCredentialsLoaded = true
            if (remember) {
                tokenStore.write(result.userToken)
                if (rawAccessCode.isNotBlank()) tokenStore.writeAccessCode(rawAccessCode) else tokenStore.clearAccessCode()
            } else {
                tokenStore.clear()
                tokenStore.clearAccessCode()
            }
            recordServer(connected.normalized.persistentApiBase(), connected.access.relayMode)
            api = connected.api
            connectionAccess = connected.access
            _state.value = SessionState.SignedIn(connected.server, result.user.toDomain())
        } finally {
            password.fill('\u0000')
            accessCode.fill('\u0000')
        }
    }

    suspend fun logout() {
        try {
            api?.logout()
        } finally {
            clearToken()
            api = null
            _state.value = signedOut()
        }
    }

    suspend fun playlists(): List<Playlist> = authenticated { it.playlists().map { dto -> dto.toDomain() } }

    fun playbackCredentials(): PlaybackCredentials {
        val currentApi = api ?: throw AppException(AppError.Unauthenticated)
        val current = _state.value as? SessionState.SignedIn ?: throw AppException(AppError.Unauthenticated)
        return PlaybackCredentials(
            currentApi.apiBase(),
            memoryToken ?: throw AppException(AppError.Unauthenticated),
            "${current.server.guid.value}:${current.user.guid.value}",
            connectionAccess.encodedAccessCode,
            connectionAccess.relayMode,
        )
    }

    internal fun requireApi(): TrimMusicApi = api ?: throw AppException(AppError.Unauthenticated)

    fun cacheNamespace(): String {
        val current = _state.value as? SessionState.SignedIn ?: throw AppException(AppError.Unauthenticated)
        return "${current.server.guid.value}:${current.user.guid.value}"
    }

    internal suspend fun <T> authenticated(block: suspend (TrimMusicApi) -> T): T {
        val current = requireApi()
        return try {
            block(current)
        } catch (cause: AppException) {
            when (cause.error) {
                AppError.AccountDisabled -> invalidateSession(AppError.AccountDisabled)
                AppError.Unauthenticated -> verifyCurrentSession(current)
                else -> Unit
            }
            throw cause
        }
    }

    suspend fun verifyCurrentSession(): Boolean {
        val current = api ?: return false
        return verifyCurrentSession(current)
    }

    private suspend fun verifyCurrentSession(candidate: TrimMusicApi): Boolean = authVerification.withLock {
        if (api !== candidate) return@withLock api != null
        try {
            candidate.me()
            true
        } catch (cause: AppException) {
            if (cause.error == AppError.Unauthenticated || cause.error == AppError.AccountDisabled) {
                invalidateSession(cause.error)
                false
            } else {
                true
            }
        }
    }

    private fun invalidateSession(error: AppError) {
        clearToken()
        api = null
        _state.value = signedOut(error)
    }

    private suspend fun connect(
        input: String,
        useHttps: Boolean,
        accessCode: String,
        restoredRelayMode: Boolean? = null,
    ): ConnectedServer {
        try {
            val client = clientFactory()
            val resolver = ConnectionResolver(client)
            val target = if (restoredRelayMode == null) {
                resolver.resolve(input, useHttps)
            } else {
                val normalized = (ServerUrlNormalizer.normalize(input, useHttps) as? ServerUrlResult.Valid)?.server
                    ?: throw AppException(AppError.Unknown("invalid_server"))
                ConnectionTarget(normalized, restoredRelayMode)
            }
            val access = resolver.verifyAccessCode(target, accessCode)
            val candidate = TrimMusicApi(target.server, client, { memoryToken }, access)
            return ConnectedServer(target.server, candidate.systemConfig().toDomain(), candidate, access)
        } catch (cause: java.io.IOException) {
            throw AppException(AppError.NetworkUnavailable, cause)
        }
    }

    private fun clearToken() {
        memoryToken = null
        memoryAccessCode = null
        rememberedCredentialsLoaded = true
        connectionAccess = ConnectionAccess()
        tokenStore.clear()
        tokenStore.clearAccessCode()
    }

    private fun recordServer(server: String, relayMode: Boolean) {
        val updated = (listOf(server) + recentServers).distinct().take(MAX_RECENT_SERVERS)
        preferences.edit().apply {
            putString(SERVER, server)
            putBoolean(RELAY_MODE, relayMode)
            repeat(MAX_RECENT_SERVERS) { remove("$RECENT_SERVER_PREFIX$it") }
            updated.forEachIndexed { index, value -> putString("$RECENT_SERVER_PREFIX$index", value) }
        }.apply()
    }

    private fun signedOut(error: AppError? = null) = SessionState.SignedOut(
        savedServer = savedServer,
        recentServers = recentServers,
        error = error,
    )

    private suspend fun loadRememberedCredentials() = credentialLoadMutex.withLock {
        if (rememberedCredentialsLoaded) return@withLock
        val (token, accessCode) = withContext(Dispatchers.IO) {
            tokenStore.read() to tokenStore.readAccessCode()
        }
        memoryToken = token
        memoryAccessCode = accessCode
        rememberedCredentialsLoaded = true
    }

    private data class ConnectedServer(
        val normalized: NormalizedServer,
        val server: ServerIdentity,
        val api: TrimMusicApi,
        val access: ConnectionAccess,
    )

    private companion object {
        const val SERVER = "server"
        const val DEVICE = "device_id"
        const val RELAY_MODE = "relay_mode"
        const val RECENT_SERVER_PREFIX = "recent_server_"
        const val MAX_RECENT_SERVERS = 5
    }

    private val savedRelayMode: Boolean get() = preferences.getBoolean(RELAY_MODE, false)
}
