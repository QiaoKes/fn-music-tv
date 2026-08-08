package com.fnmusic.tv.core.lyrics

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineLyricsSourcesTest {
    private val request = LyricsMatchRequest("id", "Song", listOf("Artist"), "Album", 180_000)

    @Test fun `qq parses search and bilingual lyrics`() = kotlinx.coroutines.runBlocking {
        val http = FixtureHttp(
            mapOf(
                "client_search_cp" to """{"data":{"song":{"list":[{"songid":1,"songmid":"mid","songname":"Song","albumname":"Album","interval":180,"singer":[{"name":"Artist"}]}]}}}""",
                "fcg_query_lyric_new" to """{"lyric":"[00:01.00]Hello","trans":"[00:01.00]你好"}""",
            ),
        )
        val source = QqMusicLyricsSource(http)

        val candidate = source.search(LyricsSearchQuery("Artist - Song", request)).single()
        val lyrics = source.fetch(candidate)

        assertEquals("mid", candidate.mediaId)
        assertEquals("Hello", lyrics.original.lines.single().text)
        assertEquals("你好", lyrics.translation?.lines?.single()?.text)
    }

    @Test fun `netease prefers yrc and keeps translation`() = kotlinx.coroutines.runBlocking {
        val http = FixtureHttp(
            mapOf(
                "search/get/web" to """{"result":{"songs":[{"id":2,"name":"Song","duration":180000,"artists":[{"name":"Artist"}],"album":{"name":"Album"}}]}}""",
                "song/lyric" to """{"yrc":{"lyric":"[1000,1000](1000,1000,0)Hello"},"lrc":{"lyric":"[00:01.00]Hello"},"tlyric":{"lyric":"[00:01.00]你好"}}""",
            ),
        )
        val source = NeteaseLyricsSource(http)

        val lyrics = source.fetch(source.search(LyricsSearchQuery("Song", request)).single())

        assertEquals(1_000L, lyrics.original.lines.single().words.single().startMs)
        assertEquals("你好", lyrics.translation?.lines?.single()?.text)
    }

    @Test fun `kugou resolves lyric candidate and decodes lrc`() = kotlinx.coroutines.runBlocking {
        val content = Base64.getEncoder().encodeToString("[00:01.00]Hello".toByteArray())
        val http = FixtureHttp(
            mapOf(
                "song_search_v2" to """{"data":{"lists":[{"ID":"3","SongName":"Song","SingerName":"Artist","AlbumName":"Album","Duration":180,"FileHash":"hash"}]}}""",
                "lyrics.kugou.com/search" to """{"candidates":[{"id":"lyric","accesskey":"key","duration":180000,"score":60}]}""",
                "lyrics.kugou.com/download" to """{"content":"$content"}""",
            ),
        )
        val source = KugouLyricsSource(http)

        val lyrics = source.fetch(source.search(LyricsSearchQuery("Song", request)).single())

        assertEquals("Hello", lyrics.original.lines.single().text)
    }

    private class FixtureHttp(private val fixtures: Map<String, String>) : LyricsHttpClient {
        override suspend fun get(url: String, query: Map<String, String>, headers: Map<String, String>): String =
            fixtures.entries.firstOrNull { (key, _) -> url.contains(key) }?.value
                ?: error("No fixture for $url")
    }
}
