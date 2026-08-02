package com.fnmusic.tv.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fnmusic.tv.core.data.api.TrimMusicApi
import com.fnmusic.tv.core.data.security.TokenStore
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(SERVER, server.url("music/api/v1/").toString())
            .commit()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun `restore clears an unauthorized remembered token without throwing`() = runBlocking {
        enqueueSystemConfig()
        server.enqueue(MockResponse.Builder().code(401).build())
        val tokenStore = FakeTokenStore(REMEMBERED_TOKEN)
        val repository = repository(tokenStore)

        repository.restore()

        assertSignedOut(repository, AppError.Unauthenticated)
        assertNull(tokenStore.token)
        assertEquals(1, tokenStore.clearCount)
    }

    @Test fun `restore clears a token for a disabled account`() = runBlocking {
        enqueueSystemConfig()
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":120002,"msg":"disabled","data":null}""")
                .build(),
        )
        val tokenStore = FakeTokenStore(REMEMBERED_TOKEN)
        val repository = repository(tokenStore)

        repository.restore()

        assertSignedOut(repository, AppError.AccountDisabled)
        assertNull(tokenStore.token)
        assertEquals(1, tokenStore.clearCount)
    }

    @Test fun `restore retains a remembered token for a server failure`() = runBlocking {
        enqueueSystemConfig()
        enqueueUser()
        val tokenStore = FakeTokenStore(REMEMBERED_TOKEN)
        val repository = repository(tokenStore)
        repository.restore()
        assertTrue(repository.state.value is SessionState.SignedIn)

        enqueueSystemConfig()
        server.enqueue(MockResponse.Builder().code(500).build())

        repository.restore()

        assertSignedOut(repository, AppError.NetworkUnavailable)
        assertThrows(AppException::class.java) { repository.requireApi() }
        assertEquals(REMEMBERED_TOKEN, tokenStore.token)
        assertEquals(0, tokenStore.clearCount)
    }

    @Test fun `restore propagates cancellation without publishing signed out`() = runBlocking {
        enqueueSystemConfig()
        server.enqueue(
            MockResponse.Builder()
                .body("x".repeat(64 * 1024))
                .throttleBody(1, 1, TimeUnit.SECONDS)
                .build(),
        )
        val tokenStore = FakeTokenStore(REMEMBERED_TOKEN)
        val repository = repository(tokenStore)
        val restore = async(Dispatchers.IO) { repository.restore() }
        repeat(3) { assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null) }

        restore.cancel()
        try {
            restore.await()
            throw AssertionError("Expected restore cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is a control-flow signal, not a signed-out error.
        }

        assertEquals(SessionState.Loading, repository.state.value)
        assertEquals(REMEMBERED_TOKEN, tokenStore.token)
        assertEquals(0, tokenStore.clearCount)
    }

    private fun repository(tokenStore: TokenStore) = SessionRepository(
        context = context,
        tokenStore = tokenStore,
        clientFactory = TrimMusicApi::client,
    )

    private fun enqueueSystemConfig() {
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"code":0,"msg":"success","data":{"serverGUID":"server-1","serverName":"NAS","serverVersion":"1","mediasrvVersion":"1"}}""",
                )
                .build(),
        )
    }

    private fun enqueueUser() {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":0,"msg":"success","data":{"guid":"user-1","name":"Test"}}""")
                .build(),
        )
    }

    private fun assertSignedOut(repository: SessionRepository, error: AppError) {
        val state = repository.state.value as SessionState.SignedOut
        assertEquals(error, state.error)
    }

    private class FakeTokenStore(initialToken: String?) : TokenStore {
        var token: String? = initialToken
            private set
        var clearCount: Int = 0
            private set

        override fun read(): String? = token

        override fun write(token: String) {
            this.token = token
        }

        override fun clear() {
            token = null
            clearCount += 1
        }
    }

    private companion object {
        const val SESSION_PREFERENCES = "session"
        const val SERVER = "server"
        const val REMEMBERED_TOKEN = "remembered-token"
    }
}
