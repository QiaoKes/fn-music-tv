package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.data.server.NormalizedServer
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TrimMusicApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun `password login hashes the utf8 password before sending`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"code":0,"msg":"success","data":{"userToken":"token","user":{"guid":"user-1","name":"test"}}}""",
                )
                .build(),
        )

        val result = api().login("test", "plain-text-password", "device-1")

        assertEquals("token", result.userToken)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/music/api/v1/user/password-login", request.target)
        assertEquals(null, request.headers["Authorization"])
        val payload = ApiDecoder.json.decodeFromString<PasswordLoginRequest>(request.body?.utf8().orEmpty())
        assertEquals("test", payload.username)
        assertEquals("50df0896ecc8b3e44dad34d9578269d46becd5a4b6b76e274baabf15f14854ea", payload.password)
        assertEquals("device-1", payload.deviceId)
    }

    @Test fun `logout revokes the server token with raw authorization`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":0,"msg":"success","data":null}""")
                .build(),
        )
        val origin = server.url("/")
        val api = TrimMusicApi(
            server = NormalizedServer(origin, origin.resolve("music/api/v1/")!!, useHttps = false),
            client = TrimMusicApi.client(),
            token = { "raw-user-token" },
        )

        api.logout()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/music/api/v1/user/logout", request.target)
        assertEquals("raw-user-token", request.headers["Authorization"])
    }

    @Test fun `playlist tracks use bounded paging and raw authorization`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"code":0,"msg":"success","data":{"list":[{"guid":"track-1","title":"Song","duration":90000,"isCue":false,"artists":[],"audioSpec":{}}],"total":51,"sort":"trackAddedAt,desc"}}""",
                )
                .build(),
        )
        val api = api()

        val page = api.playlistTracks("playlist-1", page = 2)

        assertEquals(51, page.total)
        assertEquals("track-1", page.list.single().guid)
        val request = server.takeRequest()
        assertEquals("raw-user-token", request.headers["Authorization"])
        assertEquals(
            "/music/api/v1/track/playlist-detail/list?playlistGUID=playlist-1&page=2&size=50&sort=trackAddedAt%2Cdesc",
            request.target,
        )
    }

    @Test fun `roam start permits an empty successful result`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":0,"msg":"success","data":null}""")
                .build(),
        )

        assertEquals(null, api().roamStart("device-1"))

        val request = server.takeRequest()
        assertEquals("/music/api/v1/track/roam-start?deviceId=device-1", request.target)
        assertEquals("raw-user-token", request.headers["Authorization"])
    }

    @Test fun `http unauthorized is classified separately from network failure`() {
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())

        val error = assertThrows(AppException::class.java) { runBlocking { api().me() } }

        assertEquals(AppError.Unauthenticated, error.error)
        assertEquals("/music/api/v1/user/me", server.takeRequest().target)
    }

    private fun api(): TrimMusicApi {
        val origin = server.url("/")
        return TrimMusicApi(
            server = NormalizedServer(origin, origin.resolve("music/api/v1/")!!, useHttps = false),
            client = TrimMusicApi.client(),
            token = { "raw-user-token" },
        )
    }
}
