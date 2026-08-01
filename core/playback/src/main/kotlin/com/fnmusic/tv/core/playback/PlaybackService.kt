package com.fnmusic.tv.core.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.fnmusic.tv.core.model.preferences.CacheBudget
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var mediaCache: SimpleCache? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(false)
            .setUserAgent("FnMusicTV/0.1")
        val cacheBudget = getSharedPreferences("app_preferences", MODE_PRIVATE)
            .getString("cache_budget", null)
            ?.let { runCatching { CacheBudget.valueOf(it) }.getOrNull() }
            ?: CacheBudget.Default
        var cacheNamespace = "signed-out"
        mediaCache = SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(cacheBudget.mediaBytes),
            StandaloneDatabaseProvider(this),
        )
        val cacheFactory = CacheDataSource.Factory()
            .setCache(requireNotNull(mediaCache))
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheKeyFactory { dataSpec -> "$cacheNamespace:${dataSpec.key ?: dataSpec.uri.toString()}" }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        mediaSession = MediaSession.Builder(this, requireNotNull(player))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(PlaybackCommands.ConfigureAuthCommand)
                        .add(PlaybackCommands.ClearAuthCommand)
                        .add(PlaybackCommands.ClearCacheCommand)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(available)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: android.os.Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == PlaybackCommands.ClearCache) {
                        clearCacheNamespace(cacheNamespace)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == PlaybackCommands.ClearAuth) {
                        httpFactory.setDefaultRequestProperties(emptyMap())
                        clearCacheNamespace(cacheNamespace)
                        cacheNamespace = "signed-out"
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == PlaybackCommands.ConfigureAuth) {
                        val token = args.getString(PlaybackCommands.Token)
                        val namespace = args.getString(PlaybackCommands.CacheNamespace)
                        if (token.isNullOrBlank() || namespace.isNullOrBlank()) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        httpFactory.setDefaultRequestProperties(mapOf("Authorization" to token))
                        cacheNamespace = namespace
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            })
            .build()
    }

    private fun clearCacheNamespace(namespace: String) {
        mediaCache?.keys
            ?.filter { it.startsWith("$namespace:") }
            ?.forEach { key -> runCatching { mediaCache?.removeResource(key) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        mediaCache?.release()
        mediaCache = null
        super.onDestroy()
    }
}
