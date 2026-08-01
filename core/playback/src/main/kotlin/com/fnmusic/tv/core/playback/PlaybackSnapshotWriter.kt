package com.fnmusic.tv.core.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class PlaybackSnapshotWriteRequest(
    val namespace: String,
    val revision: Long,
    val payload: String?,
) {
    init {
        require(namespace.isNotBlank()) { "Playback snapshot namespace must not be blank" }
        require(revision >= 0) { "Playback snapshot revision must not be negative" }
    }
}

internal enum class PlaybackSnapshotWriteResult {
    Committed,
    SkippedStale,
}

internal class PlaybackSnapshotPersistence(
    private val scope: CoroutineScope,
    private val writer: PlaybackSnapshotWriter,
) {
    private val transitionLock = Any()
    private var latestTransition: Deferred<PlaybackSnapshotWriteResult>? = null

    /**
     * CoroutineStart.UNDISPATCHED guarantees that the immutable request reaches the writer's
     * FIFO before this method returns. The returned deferred is the durable transition boundary.
     */
    fun submitStructural(
        request: PlaybackSnapshotWriteRequest,
    ): Deferred<PlaybackSnapshotWriteResult> {
        val transition = scope.async(start = CoroutineStart.UNDISPATCHED) {
            writer.writeStructural(request)
        }
        synchronized(transitionLock) {
            latestTransition = transition
        }
        return transition
    }

    fun enqueueCheckpoint(request: PlaybackSnapshotWriteRequest): Boolean =
        writer.enqueueCheckpoint(request)

    suspend fun flush(): PlaybackSnapshotWriteResult? {
        while (true) {
            val transition = synchronized(transitionLock) { latestTransition } ?: return null
            val result = transition.await()
            if (synchronized(transitionLock) { latestTransition === transition }) return result
        }
    }
}

internal class PlaybackSnapshotWriter(
    scope: CoroutineScope,
    private val writeSnapshot: suspend (namespace: String, payload: String?) -> Unit,
) {
    private sealed interface PendingWrite {
        val request: PlaybackSnapshotWriteRequest

        data class Structural(
            override val request: PlaybackSnapshotWriteRequest,
            val acknowledgement: CompletableDeferred<PlaybackSnapshotWriteResult>,
        ) : PendingWrite

        data class Checkpoint(
            override val request: PlaybackSnapshotWriteRequest,
        ) : PendingWrite
    }

    private val queueLock = Any()
    private val pendingWrites = ArrayDeque<PendingWrite>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var acceptingWrites = true
    private var terminalFailure: Throwable? = null

    private val actor: Job = scope.launch {
        consumeWrites()
    }.also { job ->
        job.invokeOnCompletion(::finish)
    }

    suspend fun writeStructural(
        request: PlaybackSnapshotWriteRequest,
    ): PlaybackSnapshotWriteResult {
        val acknowledgement = CompletableDeferred<PlaybackSnapshotWriteResult>()
        val accepted = synchronized(queueLock) {
            if (!acceptingWrites) {
                false
            } else {
                pendingWrites.addLast(PendingWrite.Structural(request, acknowledgement))
                true
            }
        }
        if (!accepted) throw closedException()

        wakeups.trySend(Unit)
        return acknowledgement.await()
    }

    /**
     * Queues a supplemental position write. A newer checkpoint replaces only the pending
     * checkpoint immediately before it; structural requests therefore remain hard barriers.
     */
    fun enqueueCheckpoint(request: PlaybackSnapshotWriteRequest): Boolean {
        val accepted = synchronized(queueLock) {
            if (!acceptingWrites) {
                false
            } else {
                val pendingCheckpoint = pendingWrites.lastOrNull() as? PendingWrite.Checkpoint
                when {
                    pendingCheckpoint == null -> {
                        pendingWrites.addLast(PendingWrite.Checkpoint(request))
                        true
                    }

                    request.namespace == pendingCheckpoint.request.namespace &&
                        request.revision > pendingCheckpoint.request.revision -> {
                        pendingWrites.removeLast()
                        pendingWrites.addLast(PendingWrite.Checkpoint(request))
                        true
                    }

                    else -> false
                }
            }
        }
        if (accepted) wakeups.trySend(Unit)
        return accepted
    }

    fun cancel() {
        val failure = CancellationException("Playback snapshot writer was canceled")
        finish(failure)
        actor.cancel(failure)
    }

    private suspend fun consumeWrites() {
        val latestCommittedRevision = mutableMapOf<String, Long>()
        while (currentCoroutineContext().isActive) {
            wakeups.receive()
            while (currentCoroutineContext().isActive) {
                val pending = synchronized(queueLock) {
                    pendingWrites.removeFirstOrNull()
                } ?: break
                process(pending, latestCommittedRevision)
            }
        }
    }

    private suspend fun process(
        pending: PendingWrite,
        latestCommittedRevision: MutableMap<String, Long>,
    ) {
        val request = pending.request
        val latestRevision = latestCommittedRevision[request.namespace]
        if (latestRevision != null && request.revision <= latestRevision) {
            pending.acknowledge(PlaybackSnapshotWriteResult.SkippedStale)
            return
        }

        try {
            writeSnapshot(request.namespace, request.payload)
            latestCommittedRevision[request.namespace] = request.revision
            pending.acknowledge(PlaybackSnapshotWriteResult.Committed)
        } catch (failure: Throwable) {
            pending.fail(failure)
            when {
                failure is CancellationException && currentCoroutineContext().isActive -> Unit
                failure is CancellationException -> throw failure
                failure is Exception -> Unit
                else -> throw failure
            }
        }
    }

    private fun finish(cause: Throwable?) {
        val failure = cause ?: CancellationException("Playback snapshot writer completed")
        val acknowledgements = synchronized(queueLock) {
            acceptingWrites = false
            terminalFailure = terminalFailure ?: failure
            wakeups.close(terminalFailure)
            buildList {
                while (pendingWrites.isNotEmpty()) {
                    val pending = pendingWrites.removeFirst()
                    if (pending is PendingWrite.Structural) add(pending.acknowledgement)
                }
            }
        }
        acknowledgements.forEach { it.completeExceptionally(failure) }
    }

    private fun PendingWrite.acknowledge(result: PlaybackSnapshotWriteResult) {
        if (this is PendingWrite.Structural) acknowledgement.complete(result)
    }

    private fun PendingWrite.fail(failure: Throwable) {
        if (this is PendingWrite.Structural) acknowledgement.completeExceptionally(failure)
    }

    private fun closedException(): Throwable = synchronized(queueLock) {
        terminalFailure ?: CancellationException("Playback snapshot writer is closed")
    }
}
