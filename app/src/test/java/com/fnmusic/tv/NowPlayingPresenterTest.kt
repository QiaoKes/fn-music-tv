package com.fnmusic.tv

import com.fnmusic.tv.core.data.repository.CurrentLyrics
import com.fnmusic.tv.core.data.repository.CurrentResourceResult
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.LyricDocument
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPresenterTest {
    @Test
    fun `reverse A B A completions publish only the newest revision`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val metadata = PendingRequests<Track>()
        val lyrics = PendingRequests<CurrentLyrics>()
        val artwork = PendingRequests<ByteArray>()
        val enrichments = mutableListOf<Track>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = { metadata.request(it) },
            currentLyrics = { lyrics.request(it) },
            currentArtwork = { coverId, variant -> artwork.request("$coverId:${variant.name}") },
            enrichCurrentItem = enrichments::add,
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 1L, coverId = "cover-a")
            await { metadata.size == 1 && lyrics.size == 1 && artwork.size == 1 }
            identities.value = identity("B", revision = 2L, coverId = "cover-b")
            await { metadata.size == 2 && lyrics.size == 2 && artwork.size == 2 }
            identities.value = identity("A", revision = 3L, coverId = "cover-a")
            await { metadata.size == 3 && lyrics.size == 3 && artwork.size == 3 }
            assertEquals("cover-a:Player", artwork[2].key)

            metadata[2].complete(CurrentResourceResult.Ready(track("A", "new A", "cover-a")))
            lyrics[2].complete(CurrentResourceResult.Ready(currentLyrics("new A lyric")))
            artwork[2].complete(CurrentResourceResult.Ready(byteArrayOf(3)))
            await { presenter.state.value.isReadyForRevision(3L) }

            metadata[1].complete(CurrentResourceResult.Ready(track("B", "late B", "cover-b")))
            lyrics[1].complete(CurrentResourceResult.Ready(currentLyrics("late B lyric")))
            artwork[1].complete(CurrentResourceResult.Ready(byteArrayOf(2)))
            metadata[0].complete(CurrentResourceResult.Ready(track("A", "old A", "cover-a")))
            lyrics[0].complete(CurrentResourceResult.Ready(currentLyrics("old A lyric")))
            artwork[0].complete(CurrentResourceResult.Ready(byteArrayOf(1)))

            val final = presenter.state.value ?: error("missing presentation")
            assertEquals(3L, final.identity.presentationRevision)
            assertEquals("new A", (final.metadata as NowPlayingResourceState.Ready<Track>).value.title)
            assertEquals(
                "new A lyric",
                (final.lyrics as NowPlayingResourceState.Ready<CurrentLyrics>).value.document.content,
            )
            assertArrayEquals(byteArrayOf(3), (final.artwork as NowPlayingResourceState.Ready<ByteArray>).value)
            assertEquals(listOf("new A"), enrichments.map(Track::title))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `metadata absence retains identity and retry reloads only exhausted assets`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Poster)
        val metadata = PendingRequests<Track>()
        val lyrics = PendingRequests<CurrentLyrics>()
        val artwork = PendingRequests<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = { metadata.request(it) },
            currentLyrics = { lyrics.request(it) },
            currentArtwork = { coverId, variant -> artwork.request("$coverId:${variant.name}") },
            enrichCurrentItem = {},
            applicationScope = scope,
        )

        try {
            presenter.start()
            presenter.start()
            identities.value = identity("A", revision = 4L, coverId = "cover-a", title = "initial A")
            await { metadata.size == 1 && lyrics.size == 1 && artwork.size == 1 }
            assertEquals("cover-a:Poster", artwork[0].key)

            metadata[0].complete(CurrentResourceResult.Absent)
            lyrics[0].complete(
                CurrentResourceResult.Failure(AppError.NetworkUnavailable, retryable = true),
            )
            artwork[0].complete(CurrentResourceResult.Absent)
            await {
                val current = presenter.state.value
                current?.metadata is NowPlayingResourceState.Ready<*> &&
                    current.lyrics is NowPlayingResourceState.RetryableFailure &&
                    current.artwork is NowPlayingResourceState.Absent
            }

            val failed = presenter.state.value ?: error("missing failed presentation")
            assertEquals("initial A", (failed.metadata as NowPlayingResourceState.Ready<Track>).value.title)
            assertTrue(failed.canRetry)
            assertTrue(presenter.retryCurrentPresentation())
            await { lyrics.size == 2 }
            assertEquals(1, metadata.size)
            assertEquals(1, artwork.size)

            lyrics[1].complete(CurrentResourceResult.Ready(currentLyrics("recovered")))
            await { presenter.state.value?.lyrics is NowPlayingResourceState.Ready<*> }
            assertFalse(presenter.state.value?.canRetry ?: true)
            assertFalse(presenter.retryCurrentPresentation())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `mismatched metadata is ignored without exposing a retry`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val enrichments = mutableListOf<Track>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = { CurrentResourceResult.Ready(track("B", "wrong song", "cover-b")) },
            currentLyrics = { CurrentResourceResult.Absent },
            currentArtwork = { _, _ -> CurrentResourceResult.Absent },
            enrichCurrentItem = enrichments::add,
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 5L, coverId = "cover-a", title = "current song")
            await { presenter.state.value?.metadata is NowPlayingResourceState.Ready<*> }

            val presentation = presenter.state.value ?: error("missing presentation")
            assertEquals(
                "current song",
                (presentation.metadata as NowPlayingResourceState.Ready<Track>).value.title,
            )
            assertTrue(enrichments.isEmpty())
            assertFalse(presentation.canRetry)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unexpected resource exceptions become terminal absence rather than retryable network failures`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = { error("invalid metadata") },
            currentLyrics = { error("invalid lyrics") },
            currentArtwork = { _, _ -> error("invalid artwork") },
            enrichCurrentItem = {},
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 6L, coverId = "cover-a", title = "current song")
            await {
                val current = presenter.state.value
                current?.metadata is NowPlayingResourceState.Ready<*> &&
                    current.lyrics is NowPlayingResourceState.Absent &&
                    current.artwork is NowPlayingResourceState.Absent
            }

            assertFalse(presenter.state.value?.canRetry ?: true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `upstream cancellation stays silent and explicit refresh recovers current resources`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val metadataCalls = AtomicInteger()
        val lyricCalls = AtomicInteger()
        val artworkCalls = AtomicInteger()
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = {
                if (metadataCalls.incrementAndGet() == 1) throw CancellationException("cache cleared")
                CurrentResourceResult.Absent
            },
            currentLyrics = {
                if (lyricCalls.incrementAndGet() == 1) throw CancellationException("cache cleared")
                CurrentResourceResult.Ready(currentLyrics("recovered lyric"))
            },
            currentArtwork = { _, _ ->
                if (artworkCalls.incrementAndGet() == 1) throw CancellationException("cache cleared")
                CurrentResourceResult.Ready(byteArrayOf(7))
            },
            enrichCurrentItem = {},
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 7L, coverId = "cover-a", title = "current song")
            await {
                val current = presenter.state.value
                current?.metadata is NowPlayingResourceState.Ready<*> &&
                    current.lyrics is NowPlayingResourceState.Absent &&
                    current.artwork is NowPlayingResourceState.Absent
            }
            assertFalse(presenter.state.value?.canRetry ?: true)
            assertTrue(presenter.refreshCurrentPresentation())
            await { presenter.state.value.isReadyForRevision(7L) }

            assertEquals(2, metadataCalls.get())
            assertEquals(2, lyricCalls.get())
            assertEquals(2, artworkCalls.get())
            assertFalse(presenter.state.value?.canRetry ?: true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a new track with the same cover owns a fresh presentation attempt`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val artwork = PendingRequests<ByteArray>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = { CurrentResourceResult.Absent },
            currentLyrics = { CurrentResourceResult.Absent },
            currentArtwork = { coverId, variant -> artwork.request("$coverId:${variant.name}") },
            enrichCurrentItem = {},
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 7L, coverId = "shared")
            await { artwork.size == 1 }
            identities.value = identity("B", revision = 8L, coverId = "shared")
            await { artwork.size == 2 }

            artwork[0].complete(CurrentResourceResult.Ready(byteArrayOf(1)))
            artwork[1].complete(CurrentResourceResult.Ready(byteArrayOf(2)))
            await { presenter.state.value?.artwork is NowPlayingResourceState.Ready<*> }

            val presentation = presenter.state.value ?: error("missing presentation")
            assertEquals("B", presentation.identity.mediaId)
            assertArrayEquals(
                byteArrayOf(2),
                (presentation.artwork as NowPlayingResourceState.Ready<ByteArray>).value,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `missing cover enrichment starts a new revision and rejects late old resources`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val lyrics = PendingRequests<CurrentLyrics>()
        val artwork = PendingRequests<ByteArray>()
        val metadataCalls = AtomicInteger()
        val enrichments = mutableListOf<Track>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = {
                metadataCalls.incrementAndGet()
                CurrentResourceResult.Ready(track("A", "enriched A", "cover-a"))
            },
            currentLyrics = { lyrics.request(it) },
            currentArtwork = { coverId, variant -> artwork.request("$coverId:${variant.name}") },
            enrichCurrentItem = enrichments::add,
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 11L, coverId = null, title = "initial A")
            await { enrichments.size == 1 && lyrics.size == 1 && artwork.size == 1 }
            assertEquals("cover-a:Player", artwork[0].key)

            identities.value = identity("A", revision = 12L, coverId = "cover-a", title = "enriched A")
            await { metadataCalls.get() == 2 && lyrics.size == 2 && artwork.size == 2 }

            lyrics[1].complete(CurrentResourceResult.Ready(currentLyrics("new lyric")))
            artwork[1].complete(CurrentResourceResult.Ready(byteArrayOf(2)))
            await { presenter.state.value.isReadyForRevision(12L) }

            lyrics[0].complete(CurrentResourceResult.Ready(currentLyrics("late old lyric")))
            artwork[0].complete(CurrentResourceResult.Ready(byteArrayOf(1)))

            val final = presenter.state.value ?: error("missing presentation")
            assertEquals(12L, final.identity.presentationRevision)
            assertEquals(
                "new lyric",
                (final.lyrics as NowPlayingResourceState.Ready<CurrentLyrics>).value.document.content,
            )
            assertArrayEquals(byteArrayOf(2), (final.artwork as NowPlayingResourceState.Ready<ByteArray>).value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `track transition cancels old resources without publishing failures`() {
        val identities = MutableStateFlow<NowPlayingIdentity?>(null)
        val styles = MutableStateFlow(PlayerStyle.Cover)
        val starts = mutableListOf<String>()
        val cancellations = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = NowPlayingPresenter(
            identities = identities,
            playerStyles = styles,
            currentTrackMetadata = { mediaId ->
                if (mediaId == "A") {
                    synchronized(starts) { starts += "metadata" }
                    pendingUntilCancelled { synchronized(cancellations) { cancellations += "metadata" } }
                }
                else CurrentResourceResult.Absent
            },
            currentLyrics = { mediaId ->
                if (mediaId == "A") {
                    synchronized(starts) { starts += "lyrics" }
                    pendingUntilCancelled { synchronized(cancellations) { cancellations += "lyrics" } }
                }
                else CurrentResourceResult.Absent
            },
            currentArtwork = { coverId, _ ->
                if (coverId == "cover-a") {
                    synchronized(starts) { starts += "artwork" }
                    pendingUntilCancelled { synchronized(cancellations) { cancellations += "artwork" } }
                }
                else CurrentResourceResult.Absent
            },
            enrichCurrentItem = {},
            applicationScope = scope,
        )

        try {
            presenter.start()
            identities.value = identity("A", revision = 9L, coverId = "cover-a")
            await { synchronized(starts) { starts.size == 3 } }
            identities.value = identity("B", revision = 10L, coverId = "cover-b")
            await { synchronized(cancellations) { cancellations.size == 3 } }
            await {
                val current = presenter.state.value
                current?.identity?.mediaId == "B" &&
                    current.metadata is NowPlayingResourceState.Ready<*> &&
                    current.lyrics is NowPlayingResourceState.Absent &&
                    current.artwork is NowPlayingResourceState.Absent
            }

            assertFalse(presenter.state.value?.canRetry ?: true)
        } finally {
            scope.cancel()
        }
    }

    private class PendingRequests<T> {
        private val requests = mutableListOf<PendingRequest<T>>()

        val size: Int
            get() = synchronized(requests) { requests.size }

        operator fun get(index: Int): PendingRequest<T> = synchronized(requests) { requests[index] }

        suspend fun request(key: String): CurrentResourceResult<T> = suspendCoroutine { continuation ->
            synchronized(requests) { requests += PendingRequest(key, continuation) }
        }
    }

    private class PendingRequest<T>(
        val key: String,
        private val continuation: Continuation<CurrentResourceResult<T>>,
    ) {
        fun complete(result: CurrentResourceResult<T>) = continuation.resume(result)
    }

    private suspend fun <T> pendingUntilCancelled(onCancel: () -> Unit): CurrentResourceResult<T> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { onCancel() }
        }

    private companion object {
        fun identity(
            mediaId: String,
            revision: Long,
            coverId: String?,
            title: String = mediaId,
        ) = NowPlayingIdentity(
            namespace = "server:user",
            mediaId = mediaId,
            presentationRevision = revision,
            title = title,
            artist = "artist $mediaId",
            audioFormat = "FLAC",
            coverId = coverId,
        )

        fun track(mediaId: String, title: String, coverId: String?) = Track(
            guid = TrackGuid(mediaId),
            title = title,
            artistName = "artist $mediaId",
            albumName = "album $mediaId",
            coverId = coverId,
            durationMs = 1_000L,
            isCue = false,
            audioFormat = "FLAC",
        )

        fun currentLyrics(content: String) = CurrentLyrics(
            document = LyricDocument(
                guid = "lyric",
                content = content,
                isLrc = false,
                offsetMs = 0L,
            ),
            timeline = null,
        )

        fun NowPlayingPresentation?.isReadyForRevision(revision: Long): Boolean =
            this?.identity?.presentationRevision == revision &&
                metadata is NowPlayingResourceState.Ready<*> &&
                lyrics is NowPlayingResourceState.Ready<*> &&
                artwork is NowPlayingResourceState.Ready<*>

        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!condition()) {
                if (System.nanoTime() >= deadline) error("condition was not met before timeout")
                Thread.sleep(1L)
            }
        }
    }
}
