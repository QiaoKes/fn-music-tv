package com.fnmusic.tv.core.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stores recently used search keywords locally, bounded and deduplicated, newest first. */
class SearchHistoryStore(context: Context) {
    private val store = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<List<String>> = _state.asStateFlow()

    /** Records [keyword]: dedup, move to front, trim to [MAX_ENTRIES]. Blank keywords are ignored. */
    fun record(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val next = (listOf(trimmed) + _state.value.filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX_ENTRIES)
        _state.value = next
        store.edit().putString(KEY, next.joinToString(SEPARATOR)).apply()    }

    fun clear() {
        if (_state.value.isEmpty() && store.getString(KEY, null) == null) return
        _state.value = emptyList()
        store.edit().remove(KEY).apply()
    }

    private fun read(): List<String> =
        store.getString(KEY, null)
            ?.takeIf(String::isNotBlank)
            ?.split(SEPARATOR)
            ?.filter(String::isNotBlank)
            ?: emptyList()

    companion object {
        internal const val KEY = "keywords"
        internal const val MAX_ENTRIES = 12

        // History entries are joined into one SharedPreferences value; the unit separator byte
        // cannot appear in a trimmed search keyword.
        internal const val SEPARATOR = "\u001f"
    }
}
