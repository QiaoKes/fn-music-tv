package com.fnmusic.tv.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackSnapshotCommitState(
    val namespace: String? = null,
    val requestedRevision: Long = 0L,
    val committedRevision: Long = 0L,
    val failure: String? = null,
) {
    val pending: Boolean get() = requestedRevision > committedRevision && failure == null
}

class PlaybackTransition internal constructor(
    val revision: Long,
    private val completion: Deferred<Unit>,
) {
    suspend fun awaitCommitted() = completion.await()
}

internal class PlaybackSnapshotCommitTracker(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(PlaybackSnapshotCommitState())
    val state: StateFlow<PlaybackSnapshotCommitState> = _state.asStateFlow()
    private var latestTransition: PlaybackTransition? = null

    fun reset(namespace: String) {
        _state.value = PlaybackSnapshotCommitState(namespace = namespace)
    }

    fun track(
        namespace: String,
        revision: Long,
        acknowledgement: Deferred<PlaybackSnapshotWriteResult>,
        afterCommit: suspend () -> Unit = {},
    ): PlaybackTransition {
        val previous = _state.value.takeIf { it.namespace == namespace }
        _state.value = PlaybackSnapshotCommitState(
            namespace = namespace,
            requestedRevision = revision,
            committedRevision = previous?.committedRevision ?: 0L,
        )
        val completion = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                acknowledgement.await()
                afterCommit()
                val current = _state.value
                if (current.namespace == namespace && current.requestedRevision <= revision) {
                    _state.value = current.copy(
                        committedRevision = maxOf(current.committedRevision, revision),
                        failure = null,
                    )
                }
            } catch (failure: Throwable) {
                val current = _state.value
                if (current.namespace == namespace && current.requestedRevision <= revision) {
                    _state.value = current.copy(failure = failure.message ?: failure.javaClass.simpleName)
                }
                throw failure
            }
        }
        return PlaybackTransition(revision, completion).also { transition ->
            synchronized(lock) { latestTransition = transition }
        }
    }

    suspend fun flush() {
        while (true) {
            val transition = synchronized(lock) { latestTransition } ?: return
            transition.awaitCommitted()
            if (synchronized(lock) { latestTransition === transition }) return
        }
    }
}
