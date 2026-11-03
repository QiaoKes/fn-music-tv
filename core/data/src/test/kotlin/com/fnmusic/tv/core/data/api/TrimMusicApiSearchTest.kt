package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.data.server.ConnectionAccess
import com.fnmusic.tv.core.data.server.NormalizedServer
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrimMusicApiSearchTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun `search tracks hits the search endpoint with keyword paging and size`() = runBlocking {
        enqueueSearchPage("""{"list":[{"guid":"t1","title":"那英 梦一场"}],"total":1,"sort":"createdAt,desc"}""")
        val result = api().searchTracks("梦一场", page = 2, size = 12)

        assertEquals(listOf("t1"), result.list.map(TrackDto::guid))
        assertEquals(1, result.total)
        assertEquals("createdAt,desc", result.sort)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/music/api/v1/search/track", targetPath(request))
        assertEquals(TrimMusicApi.KEYWORD_PARAM, queryName(request, 0))
        assertEquals("梦一场", queryValue(request, TrimMusicApi.KEYWORD_PARAM))
        assertEquals("2", queryValue(request, "page"))
        assertEquals("12", queryValue(request, "size"))
        assertEquals("raw-user-token", request.headers["Authorization"])
    }

    @Test fun `search artists albums reuse the shared q keyword contract`() = runBlocking {
        enqueueSearchPage("""{"list":[{"guid":"a1","name":"那英"}],"total":1,"sort":""}""")
        val artists = api().searchArtists("那英", page = 1)

        assertEquals(listOf("a1"), artists.list.map(ArtistDto::guid))
        assertEquals("/music/api/v1/search/artist", targetPath(server.takeRequest()))

        enqueueSearchPage("""{"list":[{"guid":"b1","name":"半径"}],"total":1,"sort":""}""")
        val albums = api().searchAlbums("半径", page = 1)

        assertEquals(listOf("b1"), albums.list.map(AlbumDto::guid))
        val request = server.takeRequest()
        assertEquals("/music/api/v1/search/album", targetPath(request))
        assertEquals("半径", queryValue(request, TrimMusicApi.KEYWORD_PARAM))
    }

    private fun enqueueSearchPage(body: String) {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":0,"msg":"success","data":$body}""")
                .build(),
        )
    }

    private fun targetPath(request: RecordedRequest): String = request.target.substringBefore('?')

    private fun queryName(request: RecordedRequest, index: Int): String? =
        queryPairs(request).getOrNull(index)?.first

    private fun queryValue(request: RecordedRequest, name: String): String? =
        queryPairs(request).firstOrNull { (key, _) -> key == name }?.second

    private fun queryPairs(request: RecordedRequest): List<Pair<String, String>> =
        request.target.substringAfter('?', "")
            .takeIf(String::isNotEmpty)
            ?.split('&')
            ?.map { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(name, Charsets.UTF_8.name()) to URLDecoder.decode(value, Charsets.UTF_8.name())
            }
            ?: emptyList()

    private fun api(): TrimMusicApi {
        val origin = server.url("/")
        return TrimMusicApi(
            server = NormalizedServer(origin, origin.resolve("music/api/v1/")!!, useHttps = false),
            client = TrimMusicApi.client(),
            token = { "raw-user-token" },
            access = ConnectionAccess(),
        )
    }
}
