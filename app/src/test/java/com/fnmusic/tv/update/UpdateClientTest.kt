package com.fnmusic.tv.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class UpdateClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun `reads a valid R2 manifest over HTTPS`() = runBlocking {
        val apkUrl = server.url("/releases/22/app.apk")
        server.enqueue(MockResponse.Builder().body(manifest(apkUrl.toString())).build())

        val result = UpdateClient(server.url("/update.json").toString(), client).fetchManifest()

        assertEquals(22L, result.versionCode)
        assertEquals(apkUrl.toString(), result.apkUrl)
        assertEquals("no-cache", server.takeRequest().headers["Cache-Control"])
    }

    @Test fun `rejects oversized manifest before parsing`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("x".repeat(MAX_UPDATE_MANIFEST_BYTES + 1)).build())

        assertThrows(UpdateFailure::class.java) {
            runBlocking { UpdateClient(server.url("/update.json").toString(), client).fetchManifest() }
        }
        Unit
    }

    private fun manifest(apkUrl: String) = """
        {
          "schemaVersion": 1,
          "packageName": "com.fnmusic.tv",
          "versionName": "1.0.6",
          "versionCode": 22,
          "title": "v1.0.6",
          "notes": "更新内容",
          "apk": { "url": "$apkUrl", "size": 1024, "sha256": "${"a".repeat(64)}" },
          "publishedAt": "2026-08-11T00:00:00Z",
          "githubReleaseUrl": "https://github.com/QiaoKes/fn-music-tv/releases/tag/v1.0.6"
        }
    """.trimIndent()
}
