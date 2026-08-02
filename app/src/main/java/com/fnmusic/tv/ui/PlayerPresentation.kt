package com.fnmusic.tv.ui

import com.fnmusic.tv.NowPlayingPresentation
import com.fnmusic.tv.NowPlayingResourceState
import com.fnmusic.tv.core.data.repository.CurrentLyrics
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity

internal data class PlayerPresentationProjection(
    val playerStyle: PlayerStyle?,
    val metadata: NowPlayingResourceState<Track>,
    val artwork: NowPlayingResourceState<ByteArray>,
    val lyrics: NowPlayingResourceState<CurrentLyrics>,
) {
    val retryableFailure: AppError?
        get() = sequenceOf(metadata, artwork, lyrics)
            .filterIsInstance<NowPlayingResourceState.RetryableFailure>()
            .firstOrNull()
            ?.error

    val canRetry: Boolean get() = retryableFailure != null
}

internal fun projectPlayerPresentation(
    expectedIdentity: NowPlayingIdentity?,
    presentation: NowPlayingPresentation?,
): PlayerPresentationProjection {
    val current = presentation?.takeIf { expectedIdentity != null && it.identity == expectedIdentity }
    return if (current == null) {
        PlayerPresentationProjection(
            playerStyle = null,
            metadata = NowPlayingResourceState.Loading,
            artwork = NowPlayingResourceState.Loading,
            lyrics = NowPlayingResourceState.Loading,
        )
    } else {
        PlayerPresentationProjection(
            playerStyle = current.playerStyle,
            metadata = current.metadata,
            artwork = current.artwork,
            lyrics = current.lyrics,
        )
    }
}

internal fun <T> retainPlayerVisualResource(
    previous: NowPlayingResourceState<T>,
    current: NowPlayingResourceState<T>,
): NowPlayingResourceState<T> = if (current is NowPlayingResourceState.Loading) {
    when (previous) {
        is NowPlayingResourceState.Ready,
        NowPlayingResourceState.Absent,
        -> previous
        is NowPlayingResourceState.Loading,
        is NowPlayingResourceState.RetryableFailure,
        -> current
    }
} else {
    current
}

internal class PlayerVisualResourceContinuity<T> {
    private var terminal: OwnedPlayerVisualResource<T>? = null

    fun resolve(
        identity: NowPlayingIdentity?,
        current: NowPlayingResourceState<T>,
    ): NowPlayingResourceState<T> {
        if (identity == null) {
            terminal = null
            return current
        }
        val retained = terminal
            ?.takeIf { it.identity.namespace == identity.namespace }
            ?.state
            ?: NowPlayingResourceState.Loading
        return retainPlayerVisualResource(retained, current).also { displayed ->
            if (current !is NowPlayingResourceState.Loading) {
                terminal = OwnedPlayerVisualResource(identity, displayed)
            }
        }
    }
}

private data class OwnedPlayerVisualResource<T>(
    val identity: NowPlayingIdentity,
    val state: NowPlayingResourceState<T>,
)

internal data class PlayerArtworkKey(
    val namespace: String,
    val mediaId: String,
    val presentationRevision: Long,
    val playerStyle: PlayerStyle,
)

internal fun playerArtworkKey(identity: NowPlayingIdentity, playerStyle: PlayerStyle): PlayerArtworkKey =
    PlayerArtworkKey(
        namespace = identity.namespace,
        mediaId = identity.mediaId,
        presentationRevision = identity.presentationRevision,
        playerStyle = playerStyle,
    )

internal enum class PlayerStatusRetry {
    Roam,
    Queue,
    Presentation,
}

internal data class PlayerStatus(
    val message: String,
    val retry: PlayerStatusRetry?,
)

internal fun playerStatus(
    roamError: AppError?,
    canRetryRoam: Boolean,
    queueError: String?,
    canRetryQueue: Boolean,
    presentationError: AppError?,
    canRetryPresentation: Boolean,
    playbackError: String?,
): PlayerStatus? = when {
    roamError != null -> PlayerStatus(
        message = appErrorMessage(roamError),
        retry = PlayerStatusRetry.Roam.takeIf { canRetryRoam },
    )
    queueError != null -> PlayerStatus(
        message = queueError,
        retry = PlayerStatusRetry.Queue.takeIf { canRetryQueue },
    )
    presentationError != null -> PlayerStatus(
        message = "当前歌曲资源：${appErrorMessage(presentationError)}",
        retry = PlayerStatusRetry.Presentation.takeIf { canRetryPresentation },
    )
    playbackError != null -> PlayerStatus(message = "播放失败：$playbackError", retry = null)
    else -> null
}
