package com.fnmusic.tv.core.playback

import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.PlaybackTrack
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.playback.PlayMode

internal enum class PlaybackNamespaceBinding { Initial, Same, Rebind }

internal fun playbackNamespaceBinding(
    currentNamespace: String?,
    incomingNamespace: String,
): PlaybackNamespaceBinding = when {
    currentNamespace == null -> PlaybackNamespaceBinding.Initial
    currentNamespace == incomingNamespace -> PlaybackNamespaceBinding.Same
    else -> PlaybackNamespaceBinding.Rebind
}

internal fun hasConfiguredQueue(restored: Boolean, mediaItemCount: Int): Boolean {
    require(mediaItemCount >= 0)
    return restored || mediaItemCount > 0
}

internal data class PendingShuffleActivation(
    val generation: Long,
    val baseRevision: Long,
    val activationRevision: Long,
    val canonicalIds: List<String>,
    val orderIds: List<String>,
    val fallbackMode: PlayMode,
    val persistOnAccept: Boolean,
    val persistFallbackOnReject: Boolean,
)

internal data class ShuffleAcknowledgement(
    val revision: Long,
    val orderIds: List<String>,
)

internal fun PendingShuffleActivation.accepts(
    acknowledgement: ShuffleAcknowledgement,
    currentGeneration: Long,
    currentRevision: Long,
    currentCanonicalIds: List<String>,
): Boolean =
    generation == currentGeneration &&
        baseRevision == currentRevision &&
        activationRevision == acknowledgement.revision &&
        canonicalIds == currentCanonicalIds &&
        orderIds == acknowledgement.orderIds &&
        canonicalIds.size == orderIds.size &&
        canonicalIds.distinct().size == canonicalIds.size &&
        orderIds.distinct().size == orderIds.size &&
        canonicalIds.toSet() == orderIds.toSet()

internal enum class RoamDirection { Previous, Next }

internal data class ResolvedRoam(
    val window: RoamWindow,
    val track: PlaybackTrack,
)

internal suspend fun resolvePlayableRoam(
    initial: RoamWindow,
    direction: RoamDirection,
    currentCursor: String?,
    maxWindows: Int = 8,
    ensureCurrent: () -> Unit = {},
    prepare: suspend (RoamWindow) -> PlaybackTrack,
    move: suspend (direction: RoamDirection, roamId: String) -> RoamWindow,
): ResolvedRoam {
    require(maxWindows > 0)
    var candidate = initial
    val seen = linkedSetOf<String>()
    currentCursor?.takeIf(String::isNotBlank)?.let(seen::add)

    repeat(maxWindows) { attempt ->
        ensureCurrent()
        val roamId = candidate.current.roamId
        if (roamId.isBlank() || !seen.add(roamId)) throw AppException(AppError.CollectionChanged)
        try {
            return ResolvedRoam(candidate, prepare(candidate))
        } catch (cause: AppException) {
            if (cause.error != AppError.UnavailableTrack && cause.error != AppError.TranscodeUnavailable) throw cause
        }
        if (attempt == maxWindows - 1) throw AppException(AppError.Empty)
        candidate = move(direction, roamId)
    }
    throw AppException(AppError.Empty)
}

internal class RoamAutoAdvanceGate {
    private var consumedToken: String? = null

    fun tryConsume(generation: Long, mediaId: String?): Boolean {
        val token = "$generation:${mediaId.orEmpty()}"
        if (token == consumedToken) return false
        consumedToken = token
        return true
    }

    fun reset() {
        consumedToken = null
    }
}
