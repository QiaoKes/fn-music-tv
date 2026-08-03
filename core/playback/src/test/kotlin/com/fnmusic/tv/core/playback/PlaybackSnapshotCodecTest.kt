package com.fnmusic.tv.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.fnmusic.tv.core.model.RoamNode
import com.fnmusic.tv.core.model.RoamWindow
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.QueueKind
import com.fnmusic.tv.core.model.playback.QueuePageItem
import com.fnmusic.tv.core.model.playback.QueuePageSegment
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.playback.SlidingQueueState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackSnapshotCodecTest {
    @Test
    fun `favorite queue source survives snapshot round trip`() {
        val snapshot = PlaybackSnapshot(
            generation = 1,
            revision = 2,
            items = listOf(mediaItem("favorite-a")),
            index = 0,
            positionMs = 0,
            source = QueueSource.Favorites("favoriteAt,desc"),
            window = SlidingQueueState.fromSegments(
                listOf(
                    QueuePageSegment(
                        page = 1,
                        rawRowCount = 1,
                        playableItems = listOf(QueuePageItem("favorite-a", 0)),
                        sort = "favoriteAt,desc",
                        knownTotal = 1,
                    ),
                ),
                currentIndex = 0,
            ),
            kind = QueueKind.Normal,
            mode = PlayMode.ListRepeat,
            shuffleOrder = emptyList(),
            roamWindow = null,
            currentRoamId = null,
            frozen = null,
            playIntent = PlaybackPlayIntent.Pause,
        )

        val decoded = requireNotNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot)))

        assertEquals(QueueSource.Favorites("favoriteAt,desc"), decoded.source)
        assertEquals(listOf("favorite-a"), decoded.window?.guids)
    }

    @Test
    fun `v2 round trip preserves exact segments roam window frozen queue and play intent`() {
        val source = QueueSource.Playlist("playlist-1", "title")
        val segments = listOf(
            QueuePageSegment(
                page = 3,
                rawRowCount = 4,
                playableItems = listOf(
                    QueuePageItem("normal-a", 100),
                    QueuePageItem("normal-b", 103),
                ),
                sort = "title",
                knownTotal = 205,
                pageSize = 50,
                sourceStartIndex = 100,
            ),
            QueuePageSegment(
                page = 4,
                rawRowCount = 2,
                playableItems = listOf(QueuePageItem("normal-c", 151)),
                sort = "title",
                knownTotal = 205,
                pageSize = 50,
                sourceStartIndex = 150,
            ),
        )
        val frozen = PlaybackSnapshot(
            generation = 6,
            revision = 40,
            items = listOf(mediaItem("normal-a"), mediaItem("normal-b"), mediaItem("normal-c")),
            index = 1,
            positionMs = 12_345L,
            source = source,
            window = SlidingQueueState.fromSegments(segments, currentIndex = 1),
            kind = QueueKind.Normal,
            mode = PlayMode.Shuffle,
            shuffleOrder = listOf("normal-c", "normal-a", "normal-b"),
            roamWindow = null,
            currentRoamId = null,
            frozen = null,
            playIntent = PlaybackPlayIntent.Play,
        )
        val roamWindow = RoamWindow(
            previous = RoamNode("roam-prev", track("track-prev", isCue = true)),
            current = RoamNode("roam-current", track("track-current")),
            next = RoamNode("roam-next", track("track-next", accessStatus = 7)),
        )
        val active = PlaybackSnapshot(
            generation = 7,
            revision = 41,
            items = listOf(mediaItem("track-current")),
            index = 0,
            positionMs = 987L,
            source = null,
            window = null,
            kind = QueueKind.Roam,
            mode = PlayMode.Shuffle,
            shuffleOrder = emptyList(),
            roamWindow = roamWindow,
            currentRoamId = "roam-current",
            frozen = frozen,
            playIntent = PlaybackPlayIntent.Pause,
        )

        val decoded = requireNotNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(active)))

        assertEquals(7L, decoded.generation)
        assertEquals(41L, decoded.revision)
        assertEquals(PlaybackPlayIntent.Pause, decoded.playIntent)
        assertEquals(PlayMode.Shuffle, decoded.mode)
        assertEquals(roamWindow, decoded.roamWindow)
        assertEquals("roam-current", decoded.currentRoamId)
        assertFalse(decoded.legacy)
        val decodedFrozen = requireNotNull(decoded.frozen)
        assertEquals(PlaybackPlayIntent.Play, decodedFrozen.playIntent)
        assertEquals(segments, decodedFrozen.window?.segments)
        assertEquals(listOf("normal-a", "normal-b", "normal-c"), decodedFrozen.window?.guids)
        assertEquals(listOf("normal-c", "normal-a", "normal-b"), decodedFrozen.shuffleOrder)
        assertEquals("cover-normal-b", decodedFrozen.items[1].mediaMetadata.extras?.getString(COVER_ID_KEY))
    }

    @Test
    fun `legacy active and frozen queues restore without inferring filtered paging segments`() {
        val legacyActive = legacyQueueJson("roam-track", includeWindow = true)
        val legacyFrozen = legacyQueueJson("normal-track", includeWindow = true)

        val decoded = requireNotNull(PlaybackSnapshotCodec.decode(legacyActive, legacyFrozen))

        assertTrue(decoded.legacy)
        assertEquals(QueueKind.Roam, decoded.kind)
        assertEquals(listOf("roam-track"), decoded.items.map(MediaItem::mediaId))
        assertNull(decoded.window)
        assertEquals(listOf("normal-track"), decoded.frozen?.items?.map(MediaItem::mediaId))
        assertNull(decoded.frozen?.window)
        assertEquals(PlaybackPlayIntent.Pause, decoded.playIntent)
    }

    @Test
    fun `v2 window without exact segments fails closed`() {
        val root = JSONObject(legacyQueueJson("track-a", includeWindow = true))
            .put("version", PlaybackSnapshotCodec.Version)
            .put("generation", 1)
            .put("revision", 2)
            .put("kind", QueueKind.Normal.name)
            .put("mode", PlayMode.ListRepeat.name)
            .put("playIntent", PlaybackPlayIntent.Pause.name)
            .put("shuffleOrder", JSONArray())

        assertNull(PlaybackSnapshotCodec.decode(root.toString()))
    }

    @Test
    fun `v2 rejects missing unknown and internally inconsistent required state`() {
        val valid = JSONObject(
            PlaybackSnapshotCodec.encode(
                PlaybackSnapshot(
                    generation = 1,
                    revision = 2,
                    items = listOf(mediaItem("track-a")),
                    index = 0,
                    positionMs = 3L,
                    source = null,
                    window = null,
                    kind = QueueKind.Normal,
                    mode = PlayMode.ListRepeat,
                    shuffleOrder = emptyList(),
                    roamWindow = null,
                    currentRoamId = null,
                    frozen = null,
                    playIntent = PlaybackPlayIntent.Pause,
                ),
            ),
        )

        assertNull(
            PlaybackSnapshotCodec.decode(
                JSONObject(valid.toString()).apply { remove("playIntent") }.toString(),
            ),
        )
        assertNull(PlaybackSnapshotCodec.decode(JSONObject(valid.toString()).put("mode", "FutureMode").toString()))
        assertNull(PlaybackSnapshotCodec.decode(JSONObject(valid.toString()).put("revision", -1L).toString()))
        assertNull(
            PlaybackSnapshotCodec.decode(
                JSONObject(valid.toString())
                    .put("mode", PlayMode.Shuffle.name)
                    .put("shuffleOrder", JSONArray())
                    .toString(),
            ),
        )
    }

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri("https://example.test/$id.flac")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Title $id")
                .setArtist("Artist $id")
                .setAlbumTitle("Album $id")
                .setArtworkUri(Uri.parse("https://example.test/$id.jpg"))
                .setExtras(Bundle().apply {
                    putString(AUDIO_FORMAT_KEY, "FLAC")
                    putString(COVER_ID_KEY, "cover-$id")
                })
                .build(),
        )
        .build()

    private fun track(
        id: String,
        isCue: Boolean = false,
        accessStatus: Int? = 0,
    ): Track = Track(
        guid = TrackGuid(id),
        title = "Title $id",
        artistName = "Artist $id",
        albumName = "Album $id",
        coverId = "cover-$id",
        durationMs = 123_456L,
        isCue = isCue,
        accessStatus = accessStatus,
        audioFormat = "FLAC",
    )

    private fun legacyQueueJson(id: String, includeWindow: Boolean): String = JSONObject()
        .put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("uri", "https://example.test/$id.flac")
                    .put("title", "Title $id")
                    .put("artist", "Artist $id"),
            ),
        )
        .put("index", 0)
        .put("position", 321L)
        .put("source", JSONObject().put("kind", "playlist").put("guid", "playlist-1").put("sort", "title"))
        .also { root ->
            if (includeWindow) {
                root.put(
                    "window",
                    JSONObject()
                        .put("start", 100)
                        .put("firstPage", 3)
                        .put("lastPage", 3)
                        .put("total", 205)
                        .put("sort", "title"),
                )
            }
        }
        .toString()
}
