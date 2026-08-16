package com.fnmusic.tv.update

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var context: android.content.Context
    private lateinit var clientCertificates: HandshakeCertificates

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "updates").deleteRecursively()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
    }

    @After fun tearDown() {
        server.close()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    @Test fun `cancellation closes the call and removes partial files`() = runBlocking {
        val bodyStarted = CountDownLatch(1)
        val callCanceled = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    bodyStarted.countDown()
                }

                override fun canceled(call: Call) {
                    callCanceled.countDown()
                }
            })
            .build()
        val bytes = ByteArray(64 * 1024) { 1 }
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(bytes))
                .throttleBody(1, 1, TimeUnit.SECONDS)
                .build(),
        )
        val manifest = UpdateManifest(
            versionName = "1.0.6",
            versionCode = 22,
            title = "v1.0.6",
            notes = "更新内容",
            apkUrl = server.url("/releases/22/app.apk").toString(),
            apkSize = bytes.size.toLong(),
            apkSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) },
        )
        val request = async(Dispatchers.IO) {
            UpdateDownloader(context, client).download(manifest) {}
        }

        assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
        request.cancelAndJoin()

        assertTrue(callCanceled.await(5, TimeUnit.SECONDS))
        assertTrue(request.isCancelled)
        val updateFiles = File(context.cacheDir, "updates").listFiles().orEmpty()
        assertFalse(updateFiles.any { it.name.endsWith(".part") || it.name.endsWith(".apk") })
    }
}
