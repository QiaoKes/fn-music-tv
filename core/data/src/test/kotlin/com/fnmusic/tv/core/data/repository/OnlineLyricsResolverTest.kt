package com.fnmusic.tv.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fnmusic.tv.core.data.local.AppDatabase
import com.fnmusic.tv.core.data.local.CachedMatchedLyricEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.lyrics.LyricsCandidate
import com.fnmusic.tv.core.lyrics.LyricsMatchResult
import com.fnmusic.tv.core.lyrics.LyricsSourceId
import com.fnmusic.tv.core.lyrics.LyricsTrackKind
import com.fnmusic.tv.core.lyrics.MatchedLyrics
import com.fnmusic.tv.core.lyrics.TimedLyricsLine
import com.fnmusic.tv.core.lyrics.TimedLyricsTrack
import com.fnmusic.tv.core.lyrics.TimedLyricsWord
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnlineLyricsResolverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var localStore: LocalStore
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() {
        context.deleteDatabase(AppDatabase.NAME)
        localStore = LocalStore(context)
    }

    @After fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        localStore.database.close()
        context.deleteDatabase(AppDatabase.NAME)
    }

    @Test fun `successful match is persisted and reused with original plus translation`() = runBlocking {
        val calls = AtomicInteger()
        val first = resolver {
            calls.incrementAndGet()
            LyricsMatchResult.Found(matchedLyrics())
        }

        val initial = first.resolve(track()) ?: error("expected online lyrics")
        assertEquals(listOf("原文", "translation"), initial.timeline?.lines?.single()?.texts)
        assertEquals(1, calls.get())

        val afterMemoryRestart = resolver { error("persisted match should avoid a new search") }
            .resolve(track()) ?: error("expected cached online lyrics")
        assertEquals(initial.document.content, afterMemoryRestart.document.content)
        assertEquals(1, calls.get())
    }

    @Test fun `not found is briefly memoized without inventing lyrics`() = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver {
            calls.incrementAndGet()
            LyricsMatchResult.NotFound
        }

        assertEquals(null, resolver.resolve(track()))
        assertEquals(null, resolver.resolve(track()))
        assertEquals(1, calls.get())
    }

    @Test fun `normalized metadata equivalents reuse the same positive cache entry`() = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver {
            calls.incrementAndGet()
            LyricsMatchResult.Found(matchedLyrics())
        }

        assertTrue(resolver.resolve(track()) != null)
        assertTrue(
            resolver.resolve(
                track().copy(
                    title = "ＳＯＮＧ ",
                    artistName = "ARTIST",
                    albumName = "ＡＬＢＵＭ",
                ),
            ) != null,
        )
        assertEquals(1, calls.get())
    }

    @Test fun `cache entries without an explicit schema version are rematched`() = runBlocking {
        val initial = resolver { LyricsMatchResult.Found(matchedLyrics()) }
        assertTrue(initial.resolve(track()) != null)
        val cached = localStore.matchedLyric("server:user", "track") ?: error("expected persisted lyrics")
        localStore.saveMatchedLyric(
            CachedMatchedLyricEntity(
                namespace = cached.namespace,
                trackGuid = cached.trackGuid,
                payload = cached.payload.replace(Regex("\\\"schemaVersion\\\":\\d+,?"), ""),
                accessedAt = cached.accessedAt,
            ),
        )
        val rematches = AtomicInteger()

        val refreshed = resolver {
            rematches.incrementAndGet()
            LyricsMatchResult.Found(matchedLyrics())
        }.resolve(track())

        assertTrue(refreshed != null)
        assertEquals(1, rematches.get())
    }

    @Test fun `word timing and semantic sidecars survive online mapping`() = runBlocking {
        val original = TimedLyricsTrack(
            kind = LyricsTrackKind.Original,
            lines = listOf(
                TimedLyricsLine(
                    startMs = 1_000L,
                    endMs = 2_000L,
                    words = listOf(
                        TimedLyricsWord(1_000L, 1_500L, "你"),
                        TimedLyricsWord(1_500L, 2_000L, "好"),
                    ),
                ),
            ),
        )
        val lyrics = resolver {
            LyricsMatchResult.Found(
                matchedLyrics(
                    original = original,
                    translation = timedTrack(LyricsTrackKind.Translation, "Hello"),
                    romanization = timedTrack(LyricsTrackKind.Romanization, "ni hao"),
                ),
            )
        }.resolve(track()) ?: error("expected online lyrics")

        val line = lyrics.timeline?.lines?.single() ?: error("expected timed line")
        assertEquals(listOf("你好", "Hello", "ni hao"), line.texts)
        assertEquals(listOf("你", "好"), line.words.map { it.text })
        assertEquals(listOf(1_000L, 1_500L), line.words.map { it.startMs })
        assertEquals(2_000L, line.endMs)
    }

    @Test fun `line timed lyrics do not synthesize word timing`() = runBlocking {
        val original = TimedLyricsTrack(
            kind = LyricsTrackKind.Original,
            lines = listOf(
                TimedLyricsLine(
                    startMs = 1_000L,
                    endMs = 2_000L,
                    words = listOf(TimedLyricsWord(text = "whole line")),
                ),
            ),
        )

        val lyrics = resolver {
            LyricsMatchResult.Found(matchedLyrics(original = original, translation = null))
        }.resolve(track()) ?: error("expected online lyrics")

        val line = lyrics.timeline?.lines?.single() ?: error("expected timed line")
        assertEquals("whole line", line.original)
        assertTrue(line.words.isEmpty())
    }

    @Test fun `expired negative cache immediately retries inside the same old bucket`() = runBlocking {
        var timeMs = 1L
        val calls = AtomicInteger()
        val resolver = resolver(now = { timeMs }) {
            if (calls.incrementAndGet() == 1) LyricsMatchResult.NotFound
            else LyricsMatchResult.Found(matchedLyrics())
        }

        assertEquals(null, resolver.resolve(track()))
        timeMs = 300_000L
        assertEquals(null, resolver.resolve(track()))
        assertEquals(1, calls.get())

        timeMs = 300_002L
        assertTrue(resolver.resolve(track()) != null)
        assertEquals(2, calls.get())
    }

    @Test fun `invalid response is not retained as a negative match`() = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver {
            if (calls.incrementAndGet() == 1) LyricsMatchResult.InvalidResponse
            else LyricsMatchResult.Found(matchedLyrics())
        }

        assertTrue(runCatching { resolver.resolve(track()) }.isFailure)
        assertTrue(resolver.resolve(track()) != null)
        assertEquals(2, calls.get())
    }

    @Test fun `network failure is not retained as a negative match`() = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver {
            if (calls.incrementAndGet() == 1) LyricsMatchResult.NetworkFailure
            else LyricsMatchResult.Found(matchedLyrics())
        }

        assertTrue(runCatching { resolver.resolve(track()) }.isFailure)
        assertTrue(resolver.resolve(track()) != null)
        assertEquals(2, calls.get())
    }

    private fun resolver(
        now: () -> Long = { 1_000_000L },
        matcher: suspend () -> LyricsMatchResult,
    ): OnlineLyricsResolver {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
        return OnlineLyricsResolver(
            localStore = localStore,
            responses = SerializedResponseCache(64 * 1024, scope),
            namespace = { "server:user" },
            matcher = { matcher() },
            now = now,
        )
    }

    private fun track() = Track(
        guid = TrackGuid("track"),
        title = "Song",
        artistName = "Artist",
        albumName = "Album",
        coverId = null,
        durationMs = 180_000L,
        isCue = false,
        audioFormat = "FLAC",
    )

    private fun matchedLyrics(
        original: TimedLyricsTrack = timedTrack(LyricsTrackKind.Original, "原文"),
        translation: TimedLyricsTrack? = timedTrack(LyricsTrackKind.Translation, "translation"),
        romanization: TimedLyricsTrack? = null,
    ) = MatchedLyrics(
        source = LyricsSourceId.QqMusic,
        candidate = LyricsCandidate(
            source = LyricsSourceId.QqMusic,
            remoteId = "remote",
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
            durationMs = 180_000L,
        ),
        score = 100.0,
        original = original,
        translation = translation,
        romanization = romanization,
    )

    private fun timedTrack(kind: LyricsTrackKind, text: String) = TimedLyricsTrack(
        kind = kind,
        lines = listOf(
            TimedLyricsLine(
                startMs = 1_000L,
                endMs = 2_000L,
                words = listOf(TimedLyricsWord(1_000L, 2_000L, text)),
            ),
        ),
    )
}
