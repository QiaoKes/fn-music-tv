package com.fnmusic.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.fnmusic.tv.AuthenticatedAppDependencies
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.preferences.CacheBudget
import com.fnmusic.tv.core.model.preferences.CacheUsage
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Composable
internal fun SettingsScreen(container: AuthenticatedAppDependencies) {
    val preferences by container.appPreferences.state.collectAsStateWithLifecycle()
    val scope = LocalLibraryRetainedState.current.scope
    val coverStyleFocus = remember { FocusRequester() }
    val posterStyleFocus = remember { FocusRequester() }
    var usage by remember { mutableStateOf(CacheUsage(artworkBytes = 0, indexBytes = 0)) }
    suspend fun refreshUsage() {
        usage = container.musicRepository.cacheUsage()
    }
    LaunchedEffect(Unit) {
        container.musicRepository.applyArtworkBudget()
        refreshUsage()
    }
    LaunchedEffect(Unit) {
        yield()
        runCatching { coverStyleFocus.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(72.dp, 50.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("设置", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("播放界面", fontSize = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Cover) },
                modifier = Modifier.focusProperties { right = posterStyleFocus }.focusRequester(coverStyleFocus),
            ) {
                Text(if (preferences.playerStyle == PlayerStyle.Cover) "CD 模式 · 已选" else "CD 模式")
            }
            Button(
                onClick = { container.appPreferences.setPlayerStyle(PlayerStyle.Poster) },
                modifier = Modifier.focusProperties { left = coverStyleFocus }.focusRequester(posterStyleFocus),
            ) {
                Text(if (preferences.playerStyle == PlayerStyle.Poster) "大海报模式 · 已选" else "大海报模式")
            }
        }
        Text("图片磁盘缓存上限", fontSize = 25.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CacheBudget.entries.forEach { budget ->
                Button(onClick = {
                    scope.launch {
                        container.appPreferences.setCacheBudget(budget)
                        container.musicRepository.applyArtworkBudget()
                        refreshUsage()
                    }
                }) {
                    Text(if (preferences.cacheBudget == budget) "${budget.megabytes} MB · 已选" else "${budget.megabytes} MB")
                }
            }
        }
        Text(
            "当前 ${formatBytes(usage.totalBytes)}（图片 ${formatBytes(usage.artworkBytes)} / 资料 ${formatBytes(usage.indexBytes)}）",
            color = FnColors.Muted,
            fontSize = 18.sp,
        )
        Button(onClick = {
            scope.launch {
                container.authenticatedActions.clearAllEvictableCaches()
                refreshUsage()
            }
        }) { Text("清除图片和资料缓存") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f KB".format(bytes / 1024.0)
}
