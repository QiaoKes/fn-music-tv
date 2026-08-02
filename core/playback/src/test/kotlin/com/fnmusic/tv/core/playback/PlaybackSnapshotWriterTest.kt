package com.fnmusic.tv.core.playback

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotWriterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `structural writes are fifo and acknowledged after persistence`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<String?>())
        val writer = PlaybackSnapshotWriter(scope) { _, payload ->
            writes += payload
            if (payload == "first") {
                started.complete(Unit)
                release.await()
            }
        }

        val first = async {
            writer.writeStructural(request(1, "first"))
        }
        started.await()
        val second = async {
            writer.writeStructural(request(2, "second"))
        }

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)

        assertEquals(PlaybackSnapshotWriteResult.Committed, first.await())
        assertEquals(PlaybackSnapshotWriteResult.Committed, second.await())
        assertEquals(listOf("first", "second"), writes)
    }

    @Test
    fun `checkpoints conflate only within structural barriers`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<String?>())
        val writer = PlaybackSnapshotWriter(scope) { _, payload ->
            writes += payload
            if (payload == "active-structure") {
                started.complete(Unit)
                release.await()
            }
        }

        val active = async { writer.writeStructural(request(1, "active-structure")) }
        started.await()
        assertTrue(writer.enqueueCheckpoint(request(2, "checkpoint-2")))
        assertTrue(writer.enqueueCheckpoint(request(3, "checkpoint-3")))
        val barrier = async(start = CoroutineStart.UNDISPATCHED) {
            writer.writeStructural(request(4, "barrier"))
        }
        assertTrue(writer.enqueueCheckpoint(request(5, "checkpoint-5")))
        assertTrue(writer.enqueueCheckpoint(request(6, "checkpoint-6")))

        release.complete(Unit)

        assertEquals(PlaybackSnapshotWriteResult.Committed, active.await())
        assertEquals(PlaybackSnapshotWriteResult.Committed, barrier.await())
        awaitWrites(writes, 4)
        assertEquals(
            listOf("active-structure", "checkpoint-3", "barrier", "checkpoint-6"),
            writes,
        )
    }

    @Test
    fun `stale revisions never overwrite a newer committed revision`() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<String?>())
        val writer = PlaybackSnapshotWriter(scope) { _, payload -> writes += payload }

        assertEquals(
            PlaybackSnapshotWriteResult.Committed,
            writer.writeStructural(request(8, "revision-8")),
        )
        assertEquals(
            PlaybackSnapshotWriteResult.SkippedStale,
            writer.writeStructural(request(7, "stale-structure")),
        )
        assertTrue(writer.enqueueCheckpoint(request(6, "stale-checkpoint")))
        assertTrue(writer.enqueueCheckpoint(request(9, "revision-9")))

        awaitWrites(writes, 2)
        assertEquals(listOf("revision-8", "revision-9"), writes)
    }

    @Test
    fun `revisions are isolated by namespace`() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val writer = PlaybackSnapshotWriter(scope) { namespace, payload ->
            writes += namespace to payload
        }

        writer.writeStructural(request(10, "account-a", namespace = "server:a"))
        writer.writeStructural(request(1, "account-b", namespace = "server:b"))

        assertEquals(
            listOf("server:a" to "account-a", "server:b" to "account-b"),
            writes,
        )
    }

    @Test
    fun `structural failure reaches its acknowledgement and writer continues`() = runBlocking {
        val writes = Collections.synchronizedList(mutableListOf<String?>())
        val writer = PlaybackSnapshotWriter(scope) { _, payload ->
            if (payload == "fails") throw IOException("disk unavailable")
            writes += payload
        }

        val failure = assertThrows(IOException::class.java) {
            runBlocking { writer.writeStructural(request(1, "fails")) }
        }
        assertEquals("disk unavailable", failure.message)
        assertEquals(
            PlaybackSnapshotWriteResult.Committed,
            writer.writeStructural(request(2, "recovers")),
        )
        assertEquals(listOf("recovers"), writes)
    }

    @Test
    fun `canceling writer fails active and pending acknowledgements`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val writer = PlaybackSnapshotWriter(scope) { _, _ ->
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val active = async { writer.writeStructural(request(1, "active")) }
        started.await()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            writer.writeStructural(request(2, "pending"))
        }

        writer.cancel()

        assertThrows(CancellationException::class.java) {
            runBlocking { active.await() }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { pending.await() }
        }
        assertFalse(writer.enqueueCheckpoint(request(3, "closed")))
    }

    @Test
    fun `canceling an acknowledger does not cancel an accepted structural write`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        val writer = PlaybackSnapshotWriter(scope) { _, _ ->
            started.complete(Unit)
            release.await()
            persisted.complete(Unit)
        }
        val caller = launch {
            writer.writeStructural(request(1, "snapshot"))
        }
        started.await()

        caller.cancel()
        release.complete(Unit)

        withTimeout(5_000) { persisted.await() }
    }

    @Test
    fun `transition submission is synchronous and flush completes only after latest acknowledgement`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<String?>())
        val writer = PlaybackSnapshotWriter(scope) { _, payload ->
            writes += payload
            if (payload == "queue-replaced") {
                started.complete(Unit)
                release.await()
            }
        }
        val persistence = PlaybackSnapshotPersistence(scope, writer)

        val replacement = persistence.submitStructural(request(10, "queue-replaced"))
        started.await()
        val exit = persistence.submitStructural(request(11, "roam-exited"))
        val flush = async { persistence.flush() }

        assertFalse(replacement.isCompleted)
        assertFalse(exit.isCompleted)
        assertFalse(flush.isCompleted)
        release.complete(Unit)

        assertEquals(PlaybackSnapshotWriteResult.Committed, replacement.await())
        assertEquals(PlaybackSnapshotWriteResult.Committed, exit.await())
        assertEquals(PlaybackSnapshotWriteResult.Committed, flush.await())
        assertEquals(listOf("queue-replaced", "roam-exited"), writes)
    }

    private suspend fun awaitWrites(writes: List<*>, expected: Int) {
        withTimeout(5_000) {
            while (writes.size < expected) {
                kotlinx.coroutines.yield()
            }
        }
    }

    private fun request(
        revision: Long,
        payload: String,
        namespace: String = "server:user",
    ) = PlaybackSnapshotWriteRequest(namespace, revision, payload)
}
