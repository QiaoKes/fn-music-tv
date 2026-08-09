package com.fnmusic.tv.core.data.server

import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionResolverTest {
    @Test fun `direct https input uses the standard port`() = runBlocking {
        val resolver = ConnectionResolver(OkHttpClient())

        val target = resolver.resolve("https://nas.example.com", useHttps = false)

        assertEquals("https://nas.example.com/music/api/v1/", target.server.apiBase.toString())
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
            val resolver = ConnectionResolver(
                client = OkHttpClient(),
                fnLookupUrl = server.url("api/v1/fn/con").toString(),
                fnConnectSigner = testSigner(),
            )

            val target = resolver.resolve("sample123", useHttps = true)

            assertEquals("http://127.0.0.1:${server.port}/music/api/v1/", target.server.apiBase.toString())
            assertTrue(!target.relayMode)
            val lookup = server.takeRequest()
            assertEquals("POST", lookup.method)
            assertEquals(
                "nonce=123456&timestamp=1700000000000&sign=49870382dce6ff180806efc57273f8bf",
                lookup.headers["authx"],
            )
        }
    }

    @Test fun `signer clears temporary credential bytes after use`() {
        val prefix = "test-prefix".toByteArray()
        val apiKey = "test-key".toByteArray()
        val credentials = object : FnConnectCredentials {
            override val isConfigured = true
            override fun authxPrefix(): ByteArray = prefix
            override fun apiKey(): ByteArray = apiKey
        }

        val header = FnConnectSigner(
            credentials = credentials,
            nonceProvider = { "123456" },
            timestampProvider = { "1700000000000" },
        ).authx("/api/v1/fn/con", """{"fnId":"sample123"}""")

        assertNotNull(header)
        assertTrue(prefix.all { it == 0.toByte() })
        assertTrue(apiKey.all { it == 0.toByte() })
    }

    @Test fun `unconfigured signer produces no header`() {
        val credentials = object : FnConnectCredentials {
            override val isConfigured = false
            override fun authxPrefix(): ByteArray = error("must not be read")
            override fun apiKey(): ByteArray = error("must not be read")
        }

        assertNull(FnConnectSigner(credentials).authx("/api/v1/fn/con", "{}"))
    }

    @Test fun `signer clears prefix when api key loading fails`() {
        val prefix = "test-prefix".toByteArray()
        val credentials = object : FnConnectCredentials {
            override val isConfigured = true
            override fun authxPrefix(): ByteArray = prefix
            override fun apiKey(): ByteArray = error("unreadable")
        }

        assertTrue(runCatching { FnConnectSigner(credentials).authx("/api/v1/fn/con", "{}") }.isFailure)
        assertTrue(prefix.all { it == 0.toByte() })
    }

    @Test fun `generated credentials match the build environment`() {
        val expectedPrefix = System.getenv("FN_CONNECT_AUTHX_PREFIX")
        val expectedApiKey = System.getenv("FN_CONNECT_API_KEY")
        if (expectedPrefix == null && expectedApiKey == null) {
            assertFalse(GeneratedFnConnectCredentials.isConfigured)
            return
        }

        assertNotNull(expectedPrefix)
        assertNotNull(expectedApiKey)
        assertTrue(GeneratedFnConnectCredentials.isConfigured)
        val expectedPrefixBytes = expectedPrefix!!.toByteArray()
        val expectedApiKeyBytes = expectedApiKey!!.toByteArray()
        val actualPrefix = GeneratedFnConnectCredentials.authxPrefix()
        val actualApiKey = GeneratedFnConnectCredentials.apiKey()
        try {
            assertArrayEquals(expectedPrefixBytes, actualPrefix)
            assertArrayEquals(expectedApiKeyBytes, actualApiKey)
        } finally {
            expectedPrefixBytes.fill(0)
            expectedApiKeyBytes.fill(0)
            actualPrefix.fill(0)
            actualApiKey.fill(0)
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

    private fun testSigner(): FnConnectSigner = FnConnectSigner(
        credentials = object : FnConnectCredentials {
            override val isConfigured = true
            override fun authxPrefix(): ByteArray = "test-prefix".toByteArray()
            override fun apiKey(): ByteArray = "test-key".toByteArray()
        },
        nonceProvider = { "123456" },
        timestampProvider = { "1700000000000" },
    )
}
