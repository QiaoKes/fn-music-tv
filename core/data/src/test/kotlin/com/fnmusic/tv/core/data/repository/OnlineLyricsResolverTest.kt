package com.fnmusic.tv.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fnmusic.tv.core.data.local.AppDatabase
import com.fnmusic.tv.core.data.local.CachedMatchedLyricEntity
import com.fnmusic.tv.core.data.local.LocalStore
import com.fnmusic.tv.core.lyrics.LyricsCandidate
import com.fnmusic.tv.core.lyrics.LyricsMatchResult
import com.fnmusic.tv.core.lyrics.LyricsSourceId
import com.fnmusic.tv.core.lyrics.MatchedLyrics
import com.fnmusic.tv.core.lyrics.lyricText
import com.fnmusic.tv.core.lyrics.parseLyrics
import com.fnmusic.tv.core.lyrics.translationText
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
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
        val initialLine = initial.syncedLyrics?.lines?.single() ?: error("expected synced line")
        assertEquals("原文", initialLine.lyricText())
        assertEquals("translation", initialLine.translationText())
        assertEquals(1, calls.get())

        val afterMemoryRestart = resolver { error("persisted match should avoid a new search") }
            .resolve(track()) ?: error("expected cached online lyrics")
        assertEquals(initial.document.content, afterMemoryRestart.document.content)
        assertEquals("translation", afterMemoryRestart.syncedLyrics?.lines?.single()?.translationText())
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

    @Test fun `cache entries from the previous match protocol are rematched`() = runBlocking {
        val initial = resolver { LyricsMatchResult.Found(matchedLyrics()) }
        assertTrue(initial.resolve(track()) != null)
        val cached = localStore.matchedLyric("server:user", "track") ?: error("expected persisted lyrics")
        localStore.saveMatchedLyric(
            cached.copy(payload = cached.payload.replace(Regex("\\\"schemaVersion\\\":\\d+"), "\"schemaVersion\":3")),
        )
        val rematches = AtomicInteger()

        val refreshed = resolver {
            rematches.incrementAndGet()
            LyricsMatchResult.Found(matchedLyrics())
        }.resolve(track())

        assertTrue(refreshed != null)
        assertEquals(1, rematches.get())
    }

    @Test fun `cache entries with a stale metadata protocol fingerprint are rematched`() = runBlocking {
        val initial = resolver { LyricsMatchResult.Found(matchedLyrics()) }
        assertTrue(initial.resolve(track()) != null)
        val cached = localStore.matchedLyric("server:user", "track") ?: error("expected persisted lyrics")
        localStore.saveMatchedLyric(
            cached.copy(
                payload = cached.payload.replace(
                    Regex("\\\"fingerprint\\\":\\\"[^\\\"]+\\\""),
                    "\"fingerprint\":\"previous-protocol\"",
                ),
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

    @Test fun `word timing and semantic sidecars survive cache restart`() = runBlocking {
        val original = "[1000,1000](1000,500,0)你(1500,500,0)好"
        val matched = matchedLyrics(
            parseLyrics(
                original = original,
                translation = "[00:01.00]Hello",
                phonetic = "[00:01.00]ni hao",
            ),
        )

        resolver { LyricsMatchResult.Found(matched) }.resolve(track())
            ?: error("expected online lyrics")
        val restored = resolver { error("cache should be used") }.resolve(track())
            ?: error("expected cached lyrics")
        val line = restored.syncedLyrics?.lines?.single() as? KaraokeLine.MainKaraokeLine
            ?: error("expected karaoke line")

        assertEquals("你好", line.lyricText())
        assertEquals("Hello", line.translation)
        assertEquals("ni hao", line.phonetic)
        assertEquals(listOf("你", "好"), line.syllables.map { it.content })
        assertEquals(listOf(1_000, 1_500), line.syllables.map { it.start })
        assertEquals(2_000, line.end)
    }

    @Test fun `line timed lyrics do not synthesize word timing`() = runBlocking {
        val lyrics = resolver {
            LyricsMatchResult.Found(matchedLyrics(parseLyrics("[00:01.00]whole line")))
        }.resolve(track()) ?: error("expected online lyrics")

        val line = lyrics.syncedLyrics?.lines?.single()
        assertTrue(line is SyncedLine)
        assertEquals("whole line", line?.lyricText())
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
        lyrics: SyncedLyrics = parseLyrics(
            original = "[00:01.00]原文",
            translation = "[00:01.00]translation",
        ),
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
        lyrics = lyrics,
    )
}
