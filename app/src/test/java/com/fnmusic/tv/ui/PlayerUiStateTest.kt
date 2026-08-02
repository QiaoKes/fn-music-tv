package com.fnmusic.tv.ui

import com.fnmusic.tv.NowPlayingPresentation
import com.fnmusic.tv.NowPlayingResourceState
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.PlaybackQueueItem
import com.fnmusic.tv.core.model.playback.QueuePageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateTest {
    @Test fun `home back confirmation requires a second press within two seconds`() {
        assertTrue(isHomeBackConfirmed(previousBackAt = 1_000, currentBackAt = 3_000))
        assertTrue(!isHomeBackConfirmed(previousBackAt = 1_000, currentBackAt = 3_001))
        assertTrue(!isHomeBackConfirmed(previousBackAt = 0, currentBackAt = 1_000))
        assertTrue(!isHomeBackConfirmed(previousBackAt = 2_000, currentBackAt = 1_999))
    }

    @Test fun `queue opens on current row and falls back to first`() {
        val queue = listOf(
            PlaybackQueueItem("a", "A", "Artist", 0, isCurrent = false),
            PlaybackQueueItem("b", "B", "Artist", 1, isCurrent = true),
            PlaybackQueueItem("c", "C", "Artist", 2, isCurrent = false),
        )

        assertEquals(1, initialQueueFocusIndex(queue))
        assertEquals(0, initialQueueFocusIndex(queue.map { it.copy(isCurrent = false) }))
        assertEquals(0, initialQueueFocusIndex(emptyList()))
    }

    @Test fun `dynamic queue focus preserves a retained row then follows the new current row`() {
        assertEquals("b:0", queueFocusTargetKey(listOf("a:0", "b:0", "c:0"), 0, "b:0"))
        assertEquals("d:0", queueFocusTargetKey(listOf("a:0", "d:0"), 1, "b:0"))
        assertNull(queueFocusTargetKey(emptyList(), 0, "b:0"))
    }

    @Test fun `retained pagination does not request page one after returning from page two`() {
        val first = retainLoadedPage(
            current = RetainedPageSnapshot<String>(),
            loaded = Page(listOf("a", "b"), page = 1, pageSize = 2, total = 4, sort = "name"),
            key = { it },
        )
        val second = retainLoadedPage(
            current = first,
            loaded = Page(listOf("c", "d"), page = 2, pageSize = 2, total = 4, sort = "name"),
            key = { it },
        )

        assertEquals(listOf("a", "b", "c", "d"), second.entries)
        assertEquals(2, second.page)
        assertTrue(!second.hasNext)
        assertTrue(second.initialLoadCompleted)
        assertTrue(!shouldLoadInitialPage(second))
    }

    @Test fun `retained list keeps its first successful load`() {
        val retained = retainLoadedList(
            current = RetainedListSnapshot<String>(),
            loaded = listOf("playlist-a", "playlist-b"),
        )

        assertEquals(listOf("playlist-a", "playlist-b"), retained.entries)
        assertTrue(retained.initialLoadCompleted)
        assertTrue(!shouldLoadInitialList(retained))
    }

    @Test fun `retained list retries an empty failed initial load on reentry`() {
        val failed = RetainedListSnapshot<String>(
            error = AppError.NetworkUnavailable,
            initialLoadCompleted = true,
        )

        assertTrue(shouldLoadInitialList(failed))
    }

    @Test fun `track pagination retains pages tracks and continuation metadata`() {
        val pageOne = Page(
            items = listOf(queueTrack("a"), queueTrack("b")),
            page = 1,
            pageSize = 2,
            total = 3,
            sort = "title:asc",
        )
        val pageTwo = Page(
            items = listOf(queueTrack("c")),
            page = 2,
            pageSize = 2,
            total = 3,
            sort = "title:asc",
        )

        val retained = retainTrackCollectionPage(
            retainTrackCollectionPage(RetainedTrackCollectionSnapshot(), pageOne, targetPage = 1),
            pageTwo,
            targetPage = 2,
        )

        assertEquals(listOf("a", "b", "c"), retained.tracks.map { it.guid.value })
        assertEquals(listOf(1, 2), retained.loadedPages.map(Page<Track>::page))
        assertEquals(2, retained.page)
        assertTrue(!retained.hasNext)
        assertEquals(3, retained.expectedTotal)
        assertEquals("title:asc", retained.expectedSort)
        assertNull(retained.error)
        assertTrue(retained.initialLoadCompleted)
    }

    @Test fun `play mode labels cover the controller cycle`() {
        assertEquals(
            listOf("列表循环", "随机播放", "单曲循环", "顺序播放"),
            PlayMode.entries.map(::playModeLabel),
        )
    }

    @Test fun `lyric window keeps active line near the second slot`() {
        assertEquals(3..6, playerLyricWindow(lineCount = 10, activeIndex = 4))
    }

    @Test fun `lyric window remains full at timeline boundaries`() {
        assertEquals(0..3, playerLyricWindow(lineCount = 10, activeIndex = -1))
        assertEquals(6..9, playerLyricWindow(lineCount = 10, activeIndex = 9))
        assertEquals(0..2, playerLyricWindow(lineCount = 3, activeIndex = 1))
    }

    @Test fun `poster lyric keeps current line in the second slot at boundaries`() {
        assertEquals(listOf(null, 0, 1, 2), posterLyricIndices(lineCount = 4, activeIndex = -1))
        assertEquals(listOf(null, 0, 1, 2), posterLyricIndices(lineCount = 4, activeIndex = 0))
        assertEquals(listOf(2, 3, null, null), posterLyricIndices(lineCount = 4, activeIndex = 3))
    }

    @Test fun `poster lyric supports single lines and deduplicates translations`() {
        assertEquals(listOf("Plain lyric"), posterLyricTexts(listOf(" Plain lyric ")))
        assertEquals(
            listOf("Hello", "你好"),
            posterLyricTexts(listOf("Hello", "你好", "Hello", "第三行")),
        )
    }

    @Test fun `progress fraction handles invalid duration and clamps endpoints`() {
        assertEquals(0f, playerProgressFraction(positionMs = 1_000, durationMs = 0), 0f)
        assertEquals(0f, playerProgressFraction(positionMs = -1_000, durationMs = 10_000), 0f)
        assertEquals(0.25f, playerProgressFraction(positionMs = 2_500, durationMs = 10_000), 0f)
        assertEquals(1f, playerProgressFraction(positionMs = 12_000, durationMs = 10_000), 0f)
    }

    @Test fun `artwork ambience favors a colorful subject over a large neutral backdrop`() {
        val color = artworkAmbienceColor(
            listOf(
                ArtworkPaletteSwatch(0xFFD5D2C7.toInt(), population = 800),
                ArtworkPaletteSwatch(0xFF9A62E8.toInt(), population = 200),
            ),
        )

        assertTrue(color.blue > color.green)
        assertTrue(color.red > color.green)
        assertTrue(maxOf(color.red, color.green, color.blue) <= 0.35f)
    }

    @Test fun `cool violet subject is not replaced by a pale yellow green backdrop`() {
        val color = artworkAmbienceColor(
            listOf(
                ArtworkPaletteSwatch(0xFFD9DDBA.toInt(), population = 760),
                ArtworkPaletteSwatch(0xFF756DDC.toInt(), population = 240),
            ),
        )

        assertTrue(color.blue > color.red)
        assertTrue(color.blue > color.green)
        assertTrue(maxOf(color.red, color.green, color.blue) <= 0.35f)
    }

    @Test fun `small hot pink accent cannot overpower a mixed cool cover`() {
        val color = artworkAmbienceColor(
            listOf(
                ArtworkPaletteSwatch(0xFFE3DDE8.toInt(), population = 520),
                ArtworkPaletteSwatch(0xFF6DB9D8.toInt(), population = 220),
                ArtworkPaletteSwatch(0xFF7C5BA7.toInt(), population = 170),
                ArtworkPaletteSwatch(0xFFE53483.toInt(), population = 90),
            ),
        )

        assertTrue(color.blue > color.red)
        assertTrue(color.red > color.green)
        assertTrue(maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) <= 0.12f)
    }

    @Test fun `missing artwork ambience uses one stable brand neutral`() {
        val first = fallbackAmbienceColor()
        assertEquals(first, fallbackAmbienceColor())
        assertTrue(maxOf(first.red, first.green, first.blue) <= 0.35f)
    }

    @Test fun `artwork ambience ignores dominant black margins when color remains`() {
        val color = artworkAmbienceColor(
            listOf(
                ArtworkPaletteSwatch(0xFF050505.toInt(), population = 700),
                ArtworkPaletteSwatch(0xFF20C060.toInt(), population = 300),
            ),
        )

        assertTrue(color.green > color.red)
        assertTrue(color.green > color.blue)
    }

    @Test fun `loading visual resource retains only the previous renderable terminal state`() {
        val oldLyrics = NowPlayingResourceState.Ready("old lyrics")

        assertSame(
            oldLyrics,
            retainPlayerVisualResource(oldLyrics, NowPlayingResourceState.Loading),
        )
        assertSame(
            NowPlayingResourceState.Absent,
            retainPlayerVisualResource(NowPlayingResourceState.Absent, NowPlayingResourceState.Loading),
        )
        assertTrue(
            retainPlayerVisualResource(
                NowPlayingResourceState.RetryableFailure(AppError.NetworkUnavailable),
                NowPlayingResourceState.Loading,
            ) is NowPlayingResourceState.Loading,
        )
    }

    @Test fun `visual resource switches immediately when the current track reaches a terminal state`() {
        val previous = NowPlayingResourceState.Ready("old lyrics")
        val current = NowPlayingResourceState.Ready("new lyrics")

        assertSame(current, retainPlayerVisualResource(previous, current))
        assertSame(
            NowPlayingResourceState.Absent,
            retainPlayerVisualResource(previous, NowPlayingResourceState.Absent),
        )
    }

    @Test fun `poster surface preserves hue while lifting ambience`() {
        val source = androidx.compose.ui.graphics.Color(0.1f, 0.2f, 0.34f)
        val surface = posterSurfaceColor(source)

        assertEquals(0.44f, maxOf(surface.red, surface.green, surface.blue), 0.001f)
        assertTrue(surface.blue > surface.green)
        assertTrue(surface.green > surface.red)
    }

    @Test fun `current presentation projects resources only for the complete identity`() {
        val expected = nowPlayingIdentity()
        val bytes = byteArrayOf(1, 2, 3)
        val readyArtwork = NowPlayingResourceState.Ready(bytes)
        val exact = NowPlayingPresentation(
            identity = expected,
            playerStyle = PlayerStyle.Cover,
            artwork = readyArtwork,
            lyrics = NowPlayingResourceState.Absent,
        )

        val projected = projectPlayerPresentation(expected, exact)

        assertEquals(PlayerStyle.Cover, projected.playerStyle)
        assertSame(bytes, (projected.artwork as NowPlayingResourceState.Ready).value)
        assertTrue(projected.metadata is NowPlayingResourceState.Loading)
        assertTrue(projected.lyrics is NowPlayingResourceState.Absent)
    }

    @Test fun `every current presentation identity mismatch projects all resources as loading`() {
        val expected = nowPlayingIdentity()
        val ready = NowPlayingPresentation(
            identity = expected,
            playerStyle = PlayerStyle.Poster,
            artwork = NowPlayingResourceState.Ready(byteArrayOf(7)),
            lyrics = NowPlayingResourceState.Absent,
        )
        val mismatches = listOf(
            expected.copy(namespace = "other-account"),
            expected.copy(mediaId = "other-track"),
            expected.copy(presentationRevision = expected.presentationRevision + 1),
            expected.copy(title = "Other title"),
            expected.copy(artist = "Other artist"),
            expected.copy(audioFormat = "AAC"),
            expected.copy(coverId = null),
        )

        mismatches.forEach { mismatchedIdentity ->
            val projected = projectPlayerPresentation(expected, ready.copy(identity = mismatchedIdentity))

            assertNull(projected.playerStyle)
            assertTrue(projected.metadata is NowPlayingResourceState.Loading)
            assertTrue(projected.artwork is NowPlayingResourceState.Loading)
            assertTrue(projected.lyrics is NowPlayingResourceState.Loading)
            assertTrue(!projected.canRetry)
        }
    }

    @Test fun `current presentation exposes one retryable resource failure`() {
        val identity = nowPlayingIdentity()
        val projected = projectPlayerPresentation(
            identity,
            NowPlayingPresentation(
                identity = identity,
                playerStyle = PlayerStyle.Cover,
                artwork = NowPlayingResourceState.RetryableFailure(AppError.NetworkUnavailable),
            ),
        )

        assertEquals(AppError.NetworkUnavailable, projected.retryableFailure)
        assertTrue(projected.canRetry)
    }

    @Test fun `artwork key includes namespace media revision and player style`() {
        val identity = nowPlayingIdentity()
        val base = playerArtworkKey(identity, PlayerStyle.Cover)

        assertNotEquals(base, playerArtworkKey(identity.copy(namespace = "other-account"), PlayerStyle.Cover))
        assertNotEquals(base, playerArtworkKey(identity.copy(mediaId = "other-track"), PlayerStyle.Cover))
        assertNotEquals(base, playerArtworkKey(identity.copy(presentationRevision = 43), PlayerStyle.Cover))
        assertNotEquals(base, playerArtworkKey(identity, PlayerStyle.Poster))
    }

    @Test fun `player status prioritizes roam queue and presentation retry in that order`() {
        val presentation = playerStatus(
            roamError = null,
            canRetryRoam = false,
            queueError = null,
            canRetryQueue = false,
            presentationError = AppError.NetworkUnavailable,
            canRetryPresentation = true,
            playbackError = "transport",
        )
        val queue = playerStatus(
            roamError = null,
            canRetryRoam = false,
            queueError = "queue",
            canRetryQueue = true,
            presentationError = AppError.NetworkUnavailable,
            canRetryPresentation = true,
            playbackError = null,
        )
        val roam = playerStatus(
            roamError = AppError.NetworkUnavailable,
            canRetryRoam = true,
            queueError = "queue",
            canRetryQueue = true,
            presentationError = AppError.NetworkUnavailable,
            canRetryPresentation = true,
            playbackError = null,
        )

        assertEquals(PlayerStatusRetry.Presentation, presentation?.retry)
        assertEquals(PlayerStatusRetry.Queue, queue?.retry)
        assertEquals(PlayerStatusRetry.Roam, roam?.retry)
    }

    @Test fun `raw page queue segments retain exact positions with two unplayable rows per page`() {
        val pages = listOf(
            Page(
                items = listOf(
                    queueTrack("p0"),
                    queueTrack("blocked-1", accessStatus = 1),
                    queueTrack("p2"),
                    queueTrack("cue-1", isCue = true),
                    queueTrack("p4"),
                ),
                page = 1,
                pageSize = 5,
                total = 10,
                sort = "title:asc",
            ),
            Page(
                items = listOf(
                    queueTrack("blocked-2", accessStatus = 1),
                    queueTrack("p6"),
                    queueTrack("cue-2", isCue = true),
                    queueTrack("p8"),
                    queueTrack("p9"),
                ),
                page = 2,
                pageSize = 5,
                total = 10,
                sort = "title:asc",
            ),
        )

        val window = requireNotNull(exactTrackQueueWindow(pages, selectedIndex = 8))

        assertEquals(listOf(5, 5), window.segments.map { it.rawRowCount })
        assertEquals(listOf(0, 5), window.segments.map { it.sourceStartIndex })
        assertEquals(
            listOf(
                QueuePageItem("p0", 0),
                QueuePageItem("p2", 2),
                QueuePageItem("p4", 4),
                QueuePageItem("p6", 6),
                QueuePageItem("p8", 8),
                QueuePageItem("p9", 9),
            ),
            window.segments.flatMap { it.playableItems },
        )
    }

    private fun nowPlayingIdentity() = NowPlayingIdentity(
        namespace = "account-a",
        mediaId = "track-a",
        presentationRevision = 42,
        title = "Title",
        artist = "Artist",
        audioFormat = "FLAC",
        coverId = "cover-a",
    )

    private fun queueTrack(
        id: String,
        isCue: Boolean = false,
        accessStatus: Int? = null,
    ) = Track(
        guid = TrackGuid(id),
        title = id,
        artistName = "Artist",
        albumName = null,
        coverId = null,
        durationMs = 1_000,
        isCue = isCue,
        accessStatus = accessStatus,
    )
}
