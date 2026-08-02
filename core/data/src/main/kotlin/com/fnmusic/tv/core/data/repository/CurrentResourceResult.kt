package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.data.api.isRetryableRequestFailure
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.lyric.LyricTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface CurrentResourceResult<out T> {
    data class Ready<T>(val value: T) : CurrentResourceResult<T>
    data object Absent : CurrentResourceResult<Nothing>
    data class Failure(
        val error: AppError,
        val retryable: Boolean,
    ) : CurrentResourceResult<Nothing>
}

data class CurrentLyrics(
    val document: LyricDocument,
    val timeline: LyricTimeline?,
)

internal suspend fun <T> withCurrentResourceRetry(
    delaysMillis: List<Long> = listOf(250L, 750L),
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: AppException) {
            if (!cause.isRetryableRequestFailure || attempt >= delaysMillis.size) throw cause
            delay(delaysMillis[attempt++])
        }
    }
}
