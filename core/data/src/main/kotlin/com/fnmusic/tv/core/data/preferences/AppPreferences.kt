package com.fnmusic.tv.core.data.preferences

import android.content.Context
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.preferences.AppPreferencesState
import com.fnmusic.tv.core.model.preferences.CacheBudget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppPreferences(context: Context, private val localStore: LocalStore) {
    private val store = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPreferencesState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var namespace: String? = null

    suspend fun bindNamespace(value: String) {
        namespace = value
        val account = localStore.account(value)
        val migratedNamespace = store.getString(MIGRATED_NAMESPACE, null)
        val initial = when {
            account != null -> AppPreferencesState(
                playerStyle = account.playerStyle?.let { runCatching { PlayerStyle.valueOf(it) }.getOrNull() } ?: PlayerStyle.Poster,
                cacheBudget = account.cacheBudget?.let { runCatching { CacheBudget.valueOf(it) }.getOrNull() } ?: CacheBudget.Default,
                onlineLyricsMatchingEnabled = account.onlineLyricsMatchingEnabled,
            )
            migratedNamespace == null -> read().also { store.edit().putString(MIGRATED_NAMESPACE, value).apply() }
            else -> AppPreferencesState()
        }
        _state.value = initial
        store.edit()
            .putString(PLAYER_STYLE, initial.playerStyle.name)
            .putString(CACHE_BUDGET, initial.cacheBudget.name)
            .putBoolean(ONLINE_LYRICS_MATCHING, initial.onlineLyricsMatchingEnabled)
            .apply()
        localStore.saveSettings(
            value,
            initial.playerStyle.name,
            initial.cacheBudget.name,
            initial.onlineLyricsMatchingEnabled,
        )
    }

    fun setPlayerStyle(style: PlayerStyle) {
        store.edit().putString(PLAYER_STYLE, style.name).apply()
        _state.value = _state.value.copy(playerStyle = style)
        persist()
    }

    fun setCacheBudget(budget: CacheBudget) {
        store.edit().putString(CACHE_BUDGET, budget.name).apply()
        _state.value = _state.value.copy(cacheBudget = budget)
        persist()
    }

    fun setOnlineLyricsMatchingEnabled(enabled: Boolean) {
        store.edit().putBoolean(ONLINE_LYRICS_MATCHING, enabled).apply()
        _state.value = _state.value.copy(onlineLyricsMatchingEnabled = enabled)
        persist()
    }

    private fun persist() {
        val currentNamespace = namespace ?: return
        val current = _state.value
        scope.launch {
            localStore.saveSettings(
                currentNamespace,
                current.playerStyle.name,
                current.cacheBudget.name,
                current.onlineLyricsMatchingEnabled,
            )
        }
    }

    private fun read() = AppPreferencesState(
        playerStyle = store.getString(PLAYER_STYLE, null)?.let { runCatching { PlayerStyle.valueOf(it) }.getOrNull() }
            ?: PlayerStyle.Poster,
        cacheBudget = store.getString(CACHE_BUDGET, null)?.let { runCatching { CacheBudget.valueOf(it) }.getOrNull() }
            ?: CacheBudget.Default,
        onlineLyricsMatchingEnabled = store.getBoolean(ONLINE_LYRICS_MATCHING, true),
    )

    private companion object {
        const val PLAYER_STYLE = "player_style"
        const val CACHE_BUDGET = "cache_budget"
        const val ONLINE_LYRICS_MATCHING = "online_lyrics_matching"
        const val MIGRATED_NAMESPACE = "room_migrated_namespace"
    }
}
