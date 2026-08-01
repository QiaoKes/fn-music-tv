package com.fnmusic.tv.core.playback

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.ArrayList

internal const val FORWARD_BUFFER_DURATION_MS = 50_000
internal const val BACK_BUFFER_DURATION_MS = 15_000
private const val LEGACY_AUDIO_CACHE_DIRECTORY = "media"
private const val TAG = "PlaybackService"

internal fun validatedShuffleIndices(
    canonicalIds: List<String>,
    requestedIds: List<String>,
): IntArray? {
    if (
        requestedIds.size != canonicalIds.size ||
        requestedIds.any(String::isBlank) ||
        canonicalIds.any(String::isBlank) ||
        requestedIds.distinct().size != requestedIds.size ||
        canonicalIds.distinct().size != canonicalIds.size ||
        requestedIds.toSet() != canonicalIds.toSet()
    ) {
        return null
    }
    val indexById = canonicalIds.withIndex().associate { (index, id) -> id to index }
    return requestedIds.map { id -> indexById.getValue(id) }.toIntArray()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createPlaybackLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        FORWARD_BUFFER_DURATION_MS,
        FORWARD_BUFFER_DURATION_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    )
    .setBackBuffer(BACK_BUFFER_DURATION_MS, false)
    .build()

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createPlaybackHttpDataSourceFactory(): DefaultHttpDataSource.Factory =
    DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(false)
        .setUserAgent("FnMusicTV/0.1")

internal fun deleteLegacyAudioCache(cacheDirectory: File): Boolean = runCatching {
    val canonicalCacheDirectory = cacheDirectory.canonicalFile
    val legacyCache = File(canonicalCacheDirectory, LEGACY_AUDIO_CACHE_DIRECTORY)
    if (!legacyCache.exists()) {
        legacyCache.delete()
        return@runCatching true
    }
    if (legacyCache.canonicalFile != legacyCache.absoluteFile) {
        return@runCatching legacyCache.delete()
    }
    legacyCache.deleteRecursively()
}.getOrDefault(false)

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        if (!deleteLegacyAudioCache(cacheDir)) {
            Log.w(TAG, "Unable to delete legacy audio cache")
        }
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val httpFactory = createPlaybackHttpDataSourceFactory()
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(createPlaybackLoadControl())
            .build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, RoutingPlayer(exoPlayer))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(PlaybackCommands.ConfigureAuthCommand)
                        .add(PlaybackCommands.ClearAuthCommand)
                        .add(PlaybackCommands.SetShuffleOrderCommand)
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
                    if (customCommand.customAction == PlaybackCommands.ClearAuth) {
                        httpFactory.setDefaultRequestProperties(emptyMap())
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == PlaybackCommands.ConfigureAuth) {
                        val token = args.getString(PlaybackCommands.Token)
                        val namespace = args.getString(PlaybackCommands.CacheNamespace)
                        if (token.isNullOrBlank() || namespace.isNullOrBlank()) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        httpFactory.setDefaultRequestProperties(mapOf("Authorization" to token))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    if (customCommand.customAction == PlaybackCommands.SetShuffleOrder) {
                        val requested = args.getStringArrayList(PlaybackCommands.MediaIds).orEmpty()
                        val revision = args.getLong(PlaybackCommands.SnapshotRevision, -1L)
                        val canonical = List(exoPlayer.mediaItemCount) { index ->
                            exoPlayer.getMediaItemAt(index).mediaId
                        }
                        val order = validatedShuffleIndices(canonical, requested)
                        if (revision < 0L || order == null) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        exoPlayer.setShuffleOrder(DefaultShuffleOrder(order, revision))
                        val acknowledgement = android.os.Bundle().apply {
                            putLong(PlaybackCommands.SnapshotRevision, revision)
                            putStringArrayList(PlaybackCommands.MediaIds, ArrayList(requested))
                        }
                        return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS, acknowledgement),
                        )
                    }
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
