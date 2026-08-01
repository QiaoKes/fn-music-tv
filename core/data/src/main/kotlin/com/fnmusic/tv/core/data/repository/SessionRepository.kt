package com.fnmusic.tv.core.data.repository

import android.content.Context
import com.fnmusic.tv.core.data.api.TrimMusicApi
import com.fnmusic.tv.core.data.security.SecureTokenStore
import com.fnmusic.tv.core.data.server.NormalizedServer
import com.fnmusic.tv.core.data.server.ServerUrlNormalizer
import com.fnmusic.tv.core.data.server.ServerUrlResult
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.PlaybackCredentials
import com.fnmusic.tv.core.model.ServerIdentity
import com.fnmusic.tv.core.model.User
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState {
    data object Loading : SessionState
    data class SignedOut(
        val savedServer: String = "",
        val recentServers: List<String> = emptyList(),
        val error: AppError? = null,
    ) : SessionState
    data class SignedIn(val server: ServerIdentity, val user: User) : SessionState
}

class SessionRepository(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val secureTokenStore = SecureTokenStore(context)
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    private var memoryToken: String? = secureTokenStore.read()
    private var api: TrimMusicApi? = null
    private val authVerification = Mutex()

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
        val token = memoryToken
        if (savedServer.isBlank() || token == null) {
            _state.value = signedOut()
            return
        }
        runCatching { connect(savedServer, useHttps = false) }
            .onSuccess { connected ->
                val user = connected.api.me().toDomain()
                api = connected.api
                _state.value = SessionState.SignedIn(connected.server, user)
            }
            .onFailure { cause ->
                val error = (cause as? AppException)?.error ?: AppError.Unknown()
                if (error == AppError.Unauthenticated || error == AppError.AccountDisabled) clearToken()
                _state.value = signedOut(error)
            }
    }

    suspend fun login(serverInput: String, useHttps: Boolean, username: String, password: CharArray, remember: Boolean) {
        try {
            val connected = connect(serverInput, useHttps)
            val result = connected.api.login(username, password.concatToString(), deviceId)
            memoryToken = result.userToken
            if (remember) secureTokenStore.write(result.userToken) else secureTokenStore.clear()
            recordServer(connected.normalized.persistentApiBase())
            api = connected.api
            _state.value = SessionState.SignedIn(connected.server, result.user.toDomain())
        } finally {
            password.fill('\u0000')
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

    private suspend fun connect(input: String, useHttps: Boolean): ConnectedServer {
        val normalized = (ServerUrlNormalizer.normalize(input, useHttps) as? ServerUrlResult.Valid)?.server
            ?: throw AppException(AppError.Unknown("invalid_server"))
        val candidate = TrimMusicApi(normalized, TrimMusicApi.client()) { memoryToken }
        return ConnectedServer(normalized, candidate.systemConfig().toDomain(), candidate)
    }

    private fun clearToken() {
        memoryToken = null
        secureTokenStore.clear()
    }

    private fun recordServer(server: String) {
        val updated = (listOf(server) + recentServers).distinct().take(MAX_RECENT_SERVERS)
        preferences.edit().apply {
            putString(SERVER, server)
            repeat(MAX_RECENT_SERVERS) { remove("$RECENT_SERVER_PREFIX$it") }
            updated.forEachIndexed { index, value -> putString("$RECENT_SERVER_PREFIX$index", value) }
        }.apply()
    }

    private fun signedOut(error: AppError? = null) = SessionState.SignedOut(
        savedServer = savedServer,
        recentServers = recentServers,
        error = error,
    )

    private data class ConnectedServer(
        val normalized: NormalizedServer,
        val server: ServerIdentity,
        val api: TrimMusicApi,
    )

    private companion object {
        const val SERVER = "server"
        const val DEVICE = "device_id"
        const val RECENT_SERVER_PREFIX = "recent_server_"
        const val MAX_RECENT_SERVERS = 5
    }
}
