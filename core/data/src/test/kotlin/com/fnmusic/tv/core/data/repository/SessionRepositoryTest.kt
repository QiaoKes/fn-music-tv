package com.fnmusic.tv.core.data.repository

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.fnmusic.tv.core.data.api.ApiDecoder
import com.fnmusic.tv.core.data.api.PasswordLoginRequest
import com.fnmusic.tv.core.data.api.TrimMusicApi
import com.fnmusic.tv.core.data.security.SecureSessionPayload
import com.fnmusic.tv.core.data.security.SecureSessionRead
import com.fnmusic.tv.core.data.security.StoredLoginProfile
import com.fnmusic.tv.core.data.security.TokenStore
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    @Test fun `construction defers secure reads and restore loads them off the main thread`() = runBlocking {
        val tokenStore = FakeTokenStore(initialToken = null)

        val repository = repository(tokenStore)

        assertEquals(0, tokenStore.readCount)
        assertEquals(0, tokenStore.accessCodeReadCount)
        repository.restore()
        assertEquals(1, tokenStore.readCount)
        assertEquals(1, tokenStore.accessCodeReadCount)
        assertTrue(!tokenStore.readOnMainThread)
        assertSignedOut(repository, error = null)
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

    @Test fun `restore retries a server failure without clearing the remembered token`() = runBlocking {
        enqueueSystemConfig()
        enqueueUser()
        val tokenStore = FakeTokenStore(REMEMBERED_TOKEN)
        val repository = repository(tokenStore)
        repository.restore()
        assertTrue(repository.state.value is SessionState.SignedIn)

        enqueueSystemConfig()
        server.enqueue(MockResponse.Builder().code(500).build())

        val retryingRestore = async { repository.restore() }
        withTimeout(5_000) {
            while (repository.state.value !is SessionState.Recovering) yield()
        }

        assertEquals(AppError.NetworkUnavailable, (repository.state.value as SessionState.Recovering).error)
        assertThrows(AppException::class.java) { repository.requireApi() }
        assertEquals(REMEMBERED_TOKEN, tokenStore.token)
        assertEquals(0, tokenStore.clearCount)
        retryingRestore.cancel()
    }

    @Test fun `expired profile token logs in once with the saved hash and replaces token`() = runBlocking {
        enqueueSystemConfig()
        server.enqueue(MockResponse.Builder().code(401).build())
        enqueueLogin("alice", "fresh-token")
        val profile = storedProfile("profile-1", "alice", REMEMBERED_HASH, REMEMBERED_TOKEN)
        val tokenStore = FakeTokenStore(
            initialToken = null,
            initialSession = SecureSessionPayload(activeProfileId = profile.id, profiles = listOf(profile)),
        )
        val repository = repository(tokenStore)

        repository.restore()

        assertTrue(repository.state.value is SessionState.SignedIn)
        assertEquals("fresh-token", tokenStore.session?.profiles?.single()?.userToken)
        val requests = List(4) { server.takeRequest() }
        val loginPayload = ApiDecoder.json.decodeFromString<PasswordLoginRequest>(requests.last().body?.utf8().orEmpty())
        assertEquals("alice", loginPayload.username)
        assertEquals(REMEMBERED_HASH, loginPayload.password)
    }

    @Test fun `invalid saved credentials stop after one fallback login`() = runBlocking {
        enqueueSystemConfig()
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(
            MockResponse.Builder()
                .body("""{"code":120001,"msg":"invalid","data":null}""")
                .build(),
        )
        val profile = storedProfile("profile-1", "alice", REMEMBERED_HASH, REMEMBERED_TOKEN)
        val tokenStore = FakeTokenStore(
            initialToken = null,
            initialSession = SecureSessionPayload(activeProfileId = profile.id, profiles = listOf(profile)),
        )
        val repository = repository(tokenStore)

        repository.restore()

        assertSignedOut(repository, AppError.Unauthenticated)
        assertNull(tokenStore.session?.profiles?.single()?.userToken)
        assertEquals(4, server.requestCount)
    }

    @Test fun `unreadable session payload still restores a legacy token`() = runBlocking {
        enqueueSystemConfig()
        enqueueUser()
        val tokenStore = FakeTokenStore(
            initialToken = REMEMBERED_TOKEN,
            forcedSessionRead = SecureSessionRead.Unreadable,
        )
        val repository = repository(tokenStore)

        repository.restore()

        assertTrue(repository.state.value is SessionState.SignedIn)
        assertEquals(REMEMBERED_TOKEN, repository.playbackCredentials().rawAuthorization)
        assertEquals(0, tokenStore.clearCount)
    }

    @Test fun `login history keeps separate accounts for one server and updates an existing account`() = runBlocking {
        val tokenStore = FakeTokenStore(initialToken = null)
        val repository = repository(tokenStore)

        enqueueSystemConfig()
        enqueueLogin("alice", "alice-token-1")
        repository.login(server.url("music/api/v1/").toString(), false, "alice", "alpha".toCharArray(), true)
        enqueueSystemConfig()
        enqueueLogin("bob", "bob-token")
        repository.login(server.url("music/api/v1/").toString(), false, "bob", "bravo".toCharArray(), true)
        enqueueSystemConfig()
        enqueueLogin("alice", "alice-token-2")
        repository.login(server.url("music/api/v1/").toString(), false, "alice", "alpha-2".toCharArray(), true)

        val profiles = tokenStore.session?.profiles.orEmpty()
        assertEquals(2, profiles.size)
        assertEquals(setOf("alice", "bob"), profiles.map(StoredLoginProfile::username).toSet())
        assertEquals("alice-token-2", profiles.single { it.username == "alice" }.userToken)
    }

    @Test fun `clearing login history does not terminate the current memory session`() = runBlocking {
        val tokenStore = FakeTokenStore(initialToken = null)
        val repository = repository(tokenStore)
        enqueueSystemConfig()
        enqueueLogin("alice", "alice-token")
        repository.login(server.url("music/api/v1/").toString(), false, "alice", "alpha".toCharArray(), true)
        assertTrue(repository.state.value is SessionState.SignedIn)

        repository.clearLoginHistory()

        assertTrue(repository.state.value is SessionState.SignedIn)
        assertNull(tokenStore.session)
        assertEquals("alice-token", repository.playbackCredentials().rawAuthorization)
        assertEquals("", context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE).getString(SERVER, ""))
    }

    @Test fun `login history keeps five newest profiles and deletion preserves signed in session`() = runBlocking {
        val tokenStore = FakeTokenStore(initialToken = null)
        val repository = repository(tokenStore)
        repeat(6) { index ->
            enqueueSystemConfig()
            enqueueLogin("user-$index", "token-$index")
            repository.login(
                server.url("music/api/v1/").toString(),
                false,
                "user-$index",
                "password-$index".toCharArray(),
                true,
            )
        }

        val profiles = tokenStore.session?.profiles.orEmpty()
        assertEquals(5, profiles.size)
        assertTrue(profiles.none { it.username == "user-0" })
        val deletedId = profiles.first { it.username == "user-3" }.id

        repository.deleteLoginHistory(deletedId)

        assertTrue(repository.state.value is SessionState.SignedIn)
        assertEquals("token-5", repository.playbackCredentials().rawAuthorization)
        assertEquals(4, tokenStore.session?.profiles?.size)
        assertTrue(tokenStore.session?.profiles?.none { it.id == deletedId } == true)
    }

    @Test fun `login without remember removes the matching saved profile`() = runBlocking {
        val existing = storedProfile("profile-1", "alice", REMEMBERED_HASH, REMEMBERED_TOKEN)
        val tokenStore = FakeTokenStore(
            initialToken = null,
            initialSession = SecureSessionPayload(activeProfileId = existing.id, profiles = listOf(existing)),
        )
        val repository = repository(tokenStore)
        enqueueSystemConfig()
        enqueueUser()
        repository.restore()
        repository.showLogin()
        enqueueSystemConfig()
        enqueueLogin("alice", "memory-token")

        repository.login(server.url("music/api/v1/").toString(), false, "alice", "alpha".toCharArray(), false)

        assertNull(tokenStore.session)
        assertEquals("memory-token", repository.playbackCredentials().rawAuthorization)
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

    private fun enqueueLogin(username: String, token: String) {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """{"code":0,"msg":"success","data":{"userToken":"$token","user":{"guid":"user-$username","name":"$username"}}}""",
                )
                .build(),
        )
    }

    private fun storedProfile(
        id: String,
        username: String,
        passwordHash: String,
        token: String?,
    ) = StoredLoginProfile(
        id = id,
        server = server.url("music/api/v1/").toString(),
        relayMode = false,
        username = username,
        passwordSha256 = passwordHash,
        userToken = token,
        lastUsedAt = 1,
    )

    private fun assertSignedOut(repository: SessionRepository, error: AppError?) {
        val state = repository.state.value as SessionState.SignedOut
        assertEquals(error, state.error)
    }

    private class FakeTokenStore(
        initialToken: String?,
        initialSession: SecureSessionPayload? = null,
        private val forcedSessionRead: SecureSessionRead? = null,
    ) : TokenStore {
        var token: String? = initialToken
            private set
        var session: SecureSessionPayload? = initialSession
            private set
        var clearCount: Int = 0
            private set
        var readCount: Int = 0
            private set
        var accessCodeReadCount: Int = 0
            private set
        var readOnMainThread: Boolean = false
            private set

        override fun read(): String? {
            readCount += 1
            readOnMainThread = readOnMainThread || Looper.myLooper() == Looper.getMainLooper()
            return token
        }

        override fun readAccessCode(): String? {
            accessCodeReadCount += 1
            readOnMainThread = readOnMainThread || Looper.myLooper() == Looper.getMainLooper()
            return null
        }

        override fun write(token: String) {
            this.token = token
        }

        override fun clear() {
            token = null
            clearCount += 1
        }

        override fun readSession(): SecureSessionRead = forcedSessionRead
            ?: session?.let(SecureSessionRead::Ready)
            ?: SecureSessionRead.Missing

        override fun writeSession(payload: SecureSessionPayload) {
            session = payload
        }

        override fun clearSession() {
            session = null
        }
    }

    private companion object {
        const val SESSION_PREFERENCES = "session"
        const val SERVER = "server"
        const val REMEMBERED_TOKEN = "remembered-token"
        const val REMEMBERED_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
