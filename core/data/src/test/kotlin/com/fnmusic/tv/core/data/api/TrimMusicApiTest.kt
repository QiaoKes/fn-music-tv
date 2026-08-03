package com.fnmusic.tv.core.data.api

import com.fnmusic.tv.core.data.server.NormalizedServer
import com.fnmusic.tv.core.data.server.ConnectionAccess
import com.fnmusic.tv.core.data.repository.withCurrentResourceRetry
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun `favorite endpoints use documented paths payload and paging`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"code":0,"msg":"success","data":{"list":[{"guid":"track-1","title":"Song","isFavorite":true,"accessStatus":0}],"total":1}}""",
                )
                .build(),
        )
        server.enqueue(MockResponse.Builder().body("""{"code":0,"msg":"success","data":null}""").build())
        server.enqueue(MockResponse.Builder().body("""{"code":0,"msg":"success","data":null}""").build())
        val api = api()

        val favorites = api.favoriteTracks(page = 2)
        api.createFavorite("track-1")
        api.deleteFavorite("track-1")

        assertEquals(1, favorites.total)
        assertTrue(favorites.list.single().toDomain().isFavorite)
        val listRequest = server.takeRequest()
        assertEquals("GET", listRequest.method)
        assertEquals(
            "/music/api/v1/favorite-track/list?page=2&size=50&sort=favoriteAt%2Cdesc",
            listRequest.target,
        )
        val createRequest = server.takeRequest()
        assertEquals("POST", createRequest.method)
        assertEquals("/music/api/v1/favorite-track/create", createRequest.target)
        assertEquals(
            FavoriteTrackRequest("track-1"),
            ApiDecoder.json.decodeFromString<FavoriteTrackRequest>(createRequest.body?.utf8().orEmpty()),
        )
        val deleteRequest = server.takeRequest()
        assertEquals("POST", deleteRequest.method)
        assertEquals("/music/api/v1/favorite-track/delete", deleteRequest.target)
        assertEquals(
            FavoriteTrackRequest("track-1"),
            ApiDecoder.json.decodeFromString<FavoriteTrackRequest>(deleteRequest.body?.utf8().orEmpty()),
        )
    }

    @Test fun `relay and access code headers cover login and authenticated requests`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"code":0,"msg":"success","data":{"userToken":"token","user":{"guid":"user-1","name":"test"}}}""",
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":0,"msg":"success","data":{"guid":"user-1","name":"test"}}""")
                .build(),
        )
        val origin = server.url("/")
        val api = TrimMusicApi(
            server = NormalizedServer(origin, origin.resolve("music/api/v1/")!!, useHttps = false),
            client = TrimMusicApi.client(),
            token = { "raw-user-token" },
            access = ConnectionAccess(encodedAccessCode = "encoded-code", relayMode = true),
        )

        api.login("test", "password", "device-1")
        api.me()

        val login = server.takeRequest()
        assertEquals("mode=relay", login.headers["Cookie"])
        assertEquals("encoded-code", login.headers["x-access-code"])
        assertEquals("app", login.headers["x-access-source"])
        val me = server.takeRequest()
        assertEquals("raw-user-token", me.headers["Authorization"])
        assertEquals("music-token=raw-user-token; mode=relay", me.headers["Cookie"])
        assertEquals("encoded-code", me.headers["x-access-code"])
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

    @Test fun `http1 requests close their connection without transport retries`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .body(
                        """{"code":0,"msg":"success","data":{"serverGUID":"server-1","serverName":"NAS","serverVersion":"1","mediasrvVersion":"1"}}""",
                    )
                    .build(),
            )
        }
        val client = TrimMusicApi.client()
        val api = api(client)

        api.systemConfig()
        api.systemConfig()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("close", first.headers["Connection"])
        assertEquals("close", second.headers["Connection"])
        assertNotEquals(first.connectionIndex, second.connectionIndex)
        assertEquals(2, server.requestCount)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test fun `cover json responses decode api envelopes without becoming transient failures`() {
        val cases = listOf(
            """{"code":120001,"msg":"invalid token","data":null}""" to AppError.Unauthenticated,
            """{"code":100005,"msg":"missing","data":null}""" to AppError.NotFound,
            """{"code":0,"msg":"success","data":null}""" to AppError.Empty,
            "not-json" to AppError.Unknown("invalid_json"),
            """{"code":0,"data":{"unexpected":true}}""" to AppError.Unknown("invalid_json"),
        )

        cases.forEachIndexed { index, (body, expected) ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build(),
            )

            val error = assertThrows(AppException::class.java) {
                runBlocking { api().cover("cover-$index", 320) }
            }

            assertEquals(expected, error.error)
            assertFalse(error.isRetryableRequestFailure)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `http status classification distinguishes retryable and terminal requests`() {
        data class Case(val response: MockResponse, val error: AppError, val retryable: Boolean)
        val cases = listOf(
            Case(MockResponse.Builder().code(401).build(), AppError.Unauthenticated, false),
            Case(MockResponse.Builder().code(404).build(), AppError.NotFound, false),
            Case(
                MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build(),
                AppError.NetworkUnavailable,
                false,
            ),
            Case(MockResponse.Builder().code(408).build(), AppError.NetworkUnavailable, true),
            Case(MockResponse.Builder().code(429).build(), AppError.NetworkUnavailable, true),
            Case(MockResponse.Builder().code(500).build(), AppError.NetworkUnavailable, true),
            Case(MockResponse.Builder().code(400).build(), AppError.NetworkUnavailable, false),
        )

        cases.forEachIndexed { index, case ->
            server.enqueue(case.response)

            val error = assertThrows(AppException::class.java) {
                runBlocking { api().cover("cover-$index", 320) }
            }

            assertEquals(case.error, error.error)
            assertEquals(case.retryable, error.isRetryableRequestFailure)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `current resource retry succeeds within three transient requests`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(408).build())
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/jpeg")
                .body("image")
                .build(),
        )

        val bytes = withCurrentResourceRetry(delaysMillis = listOf(0L, 0L)) {
            api().cover("cover", 320)
        }

        assertArrayEquals("image".toByteArray(), bytes)
        assertEquals(3, server.requestCount)
    }

    @Test fun `current resource retry stops after three server failures`() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/jpeg")
                .body("must-not-run")
                .build(),
        )

        val error = assertThrows(AppException::class.java) {
            runBlocking {
                withCurrentResourceRetry(delaysMillis = listOf(0L, 0L)) {
                    api().cover("cover", 320)
                }
            }
        }

        assertEquals(AppError.NetworkUnavailable, error.error)
        assertTrue(error.isRetryableRequestFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun `current resource retry makes one request for terminal and invalid cover responses`() {
        val responses = listOf(
            MockResponse.Builder().code(401).build(),
            MockResponse.Builder().code(404).build(),
            MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build(),
            MockResponse.Builder().addHeader("Content-Type", "image/jpeg").build(),
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"code":120001,"data":null}""")
                .build(),
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"code":100005,"data":null}""")
                .build(),
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"code":0,"data":null}""")
                .build(),
            MockResponse.Builder().addHeader("Content-Type", "application/json").body("not-json").build(),
        )

        responses.forEachIndexed { index, response ->
            server.enqueue(response)

            assertThrows(AppException::class.java) {
                runBlocking {
                    withCurrentResourceRetry(delaysMillis = listOf(0L, 0L)) {
                        api().cover("cover-$index", 320)
                    }
                }
            }
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `cancellation after response headers cancels the call during body decoding`() = runBlocking {
        val headersReceived = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        val callCanceled = CountDownLatch(1)
        val client = TrimMusicApi.client().newBuilder()
            .eventListener(
                object : EventListener() {
                    override fun responseHeadersEnd(call: Call, response: Response) {
                        headersReceived.countDown()
                    }

                    override fun responseBodyStart(call: Call) {
                        bodyStarted.countDown()
                    }

                    override fun canceled(call: Call) {
                        callCanceled.countDown()
                    }
                },
            )
            .build()
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "image/jpeg")
                .body("x".repeat(64 * 1024))
                .throttleBody(1, 1, TimeUnit.SECONDS)
                .build(),
        )
        val request = async(Dispatchers.IO) {
            withCurrentResourceRetry(delaysMillis = listOf(0L, 0L)) {
                api(client).cover("slow-cover", 800)
            }
        }

        assertTrue(headersReceived.await(5, TimeUnit.SECONDS))
        assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
        request.cancelAndJoin()

        assertTrue(request.isCancelled)
        assertTrue(callCanceled.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
    }

    private fun api(client: OkHttpClient = TrimMusicApi.client()): TrimMusicApi {
        val origin = server.url("/")
        return TrimMusicApi(
            server = NormalizedServer(origin, origin.resolve("music/api/v1/")!!, useHttps = false),
            client = client,
            token = { "raw-user-token" },
        )
    }
}
