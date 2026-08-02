package com.fnmusic.tv.core.data.server

import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionResolverTest {
    @Test fun `direct https input uses the standard port`() = runBlocking {
        val resolver = ConnectionResolver(OkHttpClient())

        val target = resolver.resolve("https://nas.dqchub.top", useHttps = false)

        assertEquals("https://nas.dqchub.top/music/api/v1/", target.server.apiBase.toString())
    }

    @Test fun `fnid lookup probes returned addresses and selects the first reachable candidate`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().body(
                    """{"code":0,"msg":"","data":{"ipv4":["127.0.0.1"],"publicIpv4":[],"publicIpv6":[],"fn":[],"port":{"httpPort":${server.port},"httpsPort":1}}}""",
                ).build(),
            )
            server.enqueue(MockResponse.Builder().code(204).build())
            val resolver = ConnectionResolver(OkHttpClient(), server.url("api/v1/fn/con").toString())

            val target = resolver.resolve("sample123", useHttps = true)

            assertEquals("http://127.0.0.1:${server.port}/music/api/v1/", target.server.apiBase.toString())
            assertTrue(!target.relayMode)
            val lookup = server.takeRequest()
            assertEquals("POST", lookup.method)
            assertNotNull(lookup.headers["authx"])
        }
    }

    @Test fun `access code is encoded and relay headers are returned for every request`() {
        val access = ConnectionAccess.from("safe-code", relayMode = true)

        assertEquals("c2FmZS1jb2Rl", access.encodedAccessCode)
        assertEquals("music-token=token; mode=relay", access.headers("token")["Cookie"])
        assertEquals("app", access.headers()["x-access-source"])
    }

    @Test fun `missing required access code has a dedicated error`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(401).build())
            val normalized = ServerUrlNormalizer.normalize(server.url("/").toString(), false) as ServerUrlResult.Valid
            val resolver = ConnectionResolver(OkHttpClient())

            val error = runCatching {
                resolver.verifyAccessCode(ConnectionTarget(normalized.server, false), "")
            }.exceptionOrNull() as AppException

            assertEquals(AppError.AccessCodeRequired, error.error)
        }
    }

    @Test fun `access code verification preserves relay request headers`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(204).build())
            val normalized = ServerUrlNormalizer.normalize(server.url("/").toString(), false) as ServerUrlResult.Valid
            val resolver = ConnectionResolver(OkHttpClient())

            val access = resolver.verifyAccessCode(ConnectionTarget(normalized.server, relayMode = true), "safe-code")

            val request = server.takeRequest()
            assertEquals("c2FmZS1jb2Rl", access.encodedAccessCode)
            assertEquals("mode=relay", request.headers["Cookie"])
            assertEquals("c2FmZS1jb2Rl", request.headers["x-access-code"])
            assertEquals("app", request.headers["x-access-source"])
        }
    }
}
