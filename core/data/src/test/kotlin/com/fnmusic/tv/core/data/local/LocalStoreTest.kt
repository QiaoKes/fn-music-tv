package com.fnmusic.tv.core.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: LocalStore

    @Before fun setUp() {
        context.deleteDatabase(AppDatabase.NAME)
        store = LocalStore(context)
    }

    @After fun tearDown() {
        store.database.close()
        context.deleteDatabase(AppDatabase.NAME)
    }

    @Test fun `namespaces isolate essential and evictable state`() = runBlocking {
        store.saveQueue("server:user-a", "queue-a")
        store.saveQueue("server:user-b", "queue-b")
        store.savePage(CachedPageEntity("server:user-a", "tracks", 1, "page-a", 1, "createdAt,desc", 1))

        assertEquals("queue-a", store.account("server:user-a")?.queueJson)
        assertEquals("queue-b", store.account("server:user-b")?.queueJson)
        assertNotNull(store.page("server:user-a", "tracks", 1))
        assertNull(store.page("server:user-b", "tracks", 1))

        store.clearNamespace("server:user-a", includeEssential = true)
        assertNull(store.account("server:user-a"))
        assertEquals("queue-b", store.account("server:user-b")?.queueJson)
    }

    @Test fun `eviction preserves essential state and bounds database ownership`() = runBlocking {
        val namespace = "server:user"
        store.saveQueue(namespace, "essential-queue")
        val payload = "x".repeat(1024 * 1024)
        repeat(30) { page ->
            store.savePage(CachedPageEntity(namespace, "all-tracks", page, payload, 30, "createdAt,desc", page.toLong()))
        }

        assertEquals("essential-queue", store.account(namespace)?.queueJson)
        assertTrue(store.physicalBytes() <= AppDatabase.MAX_DATABASE_BYTES)
        assertTrue(store.database.dao().pagePayloadBytes() <= AppDatabase.EVICTABLE_PAYLOAD_TARGET_BYTES)
        assertTrue(store.budgetAuditCount < 30)
        assertTrue(store.spaceReclaimCount > 0)
        store.database.openHelper.writableDatabase.query("PRAGMA auto_vacuum").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
    }

    @Test fun `small writes reuse the budget estimate without reclaiming space`() = runBlocking {
        val namespace = "server:user"

        store.savePage(CachedPageEntity(namespace, "tracks", 1, "page-a", 2, "name", 1))
        val auditsAfterCalibration = store.budgetAuditCount
        store.savePage(CachedPageEntity(namespace, "tracks", 2, "page-b", 2, "name", 2))

        assertEquals(1, auditsAfterCalibration)
        assertEquals(auditsAfterCalibration, store.budgetAuditCount)
        assertEquals(0, store.spaceReclaimCount)
    }

    @Test fun `playback snapshot update atomically replaces queue and clears legacy frozen queue`() = runBlocking {
        val namespace = "server:user"
        store.saveQueue(namespace, "legacy-active")
        store.saveFrozenQueue(namespace, "legacy-frozen")

        store.savePlaybackSnapshot(namespace, "{\"version\":2,\"revision\":7}")

        val account = store.account(namespace)
        assertEquals("{\"version\":2,\"revision\":7}", account?.queueJson)
        assertNull(account?.frozenQueueJson)
    }
}
