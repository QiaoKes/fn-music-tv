package com.fnmusic.tv.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.PlaybackQueueItem
import com.fnmusic.tv.core.model.playback.QueueKind

data class PlaybackUiState(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val title: String = "",
    val artist: String = "",
    val audioFormat: String = "",
    val mediaId: String = "",
    val coverId: String? = null,
    val artworkUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val currentIndex: Int = 0,
    val itemCount: Int = 0,
    val loadedPlayableCount: Int = 0,
    val queueKind: QueueKind = QueueKind.Normal,
    val queueItems: List<PlaybackQueueItem> = emptyList(),
    val playMode: PlayMode = PlayMode.ListRepeat,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val presentationRevision: Long = 0,
    val nowPlayingIdentity: NowPlayingIdentity? = null,
    val roamBusy: Boolean = false,
    val roamError: AppError? = null,
    val canRetryRoam: Boolean = false,
    val error: PlaybackFailure? = null,
    val queueError: String? = null,
    val canRetryQueue: Boolean = false,
)

data class PlaybackFailure(
    val code: Int,
    val displayName: String,
) {
    val requiresSessionVerification: Boolean
        get() = code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
}

data class PlaybackProgressState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

internal data class CapturedNowPlayingFields(
    val mediaId: String,
    val title: String,
    val artist: String,
    val audioFormat: String,
    val coverId: String?,
    val artworkUrl: String?,
    val album: String? = null,
    val durationMs: Long? = null,
)

internal fun captureNowPlayingFields(item: MediaItem?): CapturedNowPlayingFields? {
    item ?: return null
    val metadata = item.mediaMetadata
    return CapturedNowPlayingFields(
        mediaId = item.mediaId,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        audioFormat = metadata.extras?.getString(AUDIO_FORMAT_KEY).orEmpty(),
        coverId = metadata.extras?.getString(COVER_ID_KEY),
        artworkUrl = metadata.artworkUri?.toString(),
        album = metadata.albumTitle?.toString(),
        durationMs = metadata.extras
            ?.takeIf { it.containsKey(DECLARED_DURATION_MS_KEY) }
            ?.getLong(DECLARED_DURATION_MS_KEY)
            ?.takeIf { it > 0L },
    )
}

internal fun shouldRebuildPlaybackQueue(
    timelineChanged: Boolean,
    mediaItemTransition: Boolean,
    mediaMetadataChanged: Boolean,
): Boolean = timelineChanged || mediaItemTransition || mediaMetadataChanged

internal fun shouldForcePresentationProjection(
    hasMediaItem: Boolean,
    transitionReason: Int,
): Boolean = hasMediaItem && transitionReason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

internal class PlaybackQueueProjector(
    private val maxItems: Int,
) {
    private var items: List<PlaybackQueueItem> = emptyList()

    fun project(player: Player, currentIndex: Int, rebuild: Boolean): List<PlaybackQueueItem> {
        val projectedCount = player.mediaItemCount.coerceAtMost(maxItems)
        if (rebuild || items.size != projectedCount) {
            items = List(projectedCount) { index ->
                val item = player.getMediaItemAt(index)
                PlaybackQueueItem(
                    mediaId = item.mediaId,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    queueIndex = index,
                    isCurrent = index == currentIndex,
                )
            }
        }
        return items
    }

    fun clear() {
        items = emptyList()
    }
}
