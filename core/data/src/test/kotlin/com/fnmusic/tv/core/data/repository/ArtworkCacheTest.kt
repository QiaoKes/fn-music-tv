package com.fnmusic.tv.core.data.repository

import com.fnmusic.tv.core.model.CoverVariant
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtworkCacheTest {
    private lateinit var root: java.io.File
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        root = Files.createTempDirectory("artwork-cache-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test fun `memory and disk hits avoid another download and touch disk`() = runBlocking {
        var now = 1_000L
        val calls = AtomicInteger()
        val first = cache(clock = { now })
        val expected = byteArrayOf(1, 2, 3)

        assertArrayEquals(expected, first.get("server:user", "cover", CoverVariant.Player) {
            calls.incrementAndGet()
            expected
        })
        val file = first.namespaceDirectoryForTest("server:user").listFiles().orEmpty().single()
        assertEquals(1_000L, file.lastModified())

        now = 2_000L
        assertArrayEquals(expected, first.get("server:user", "cover", CoverVariant.Player) {
            error("memory hit expected")
        })
        assertEquals(2_000L, file.lastModified())

        val second = cache(clock = { 3_000L })
        assertArrayEquals(expected, second.get("server:user", "cover", CoverVariant.Player) {
            error("disk hit expected")
        })
        assertEquals(1, calls.get())
        assertEquals(3_000L, file.lastModified())
    }

    @Test fun `concurrent cold miss downloads once`() = runBlocking {
        val cache = cache()
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val first = async {
            cache.get("server:user", "cover", CoverVariant.Grid) {
                calls.incrementAndGet()
                release.await()
                byteArrayOf(7)
            }
        }
        awaitWaiters(cache, 1)
        val second = async {
            cache.get("server:user", "cover", CoverVariant.Grid) {
                error("must join the first request")
            }
        }
        awaitWaiters(cache, 2)
        release.complete(Unit)

        assertArrayEquals(byteArrayOf(7), first.await())
        assertArrayEquals(byteArrayOf(7), second.await())
        assertEquals(1, calls.get())
    }

    @Test fun `one canceled waiter keeps the shared fill legal for its peer`() = runBlocking {
        val cache = cache()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async {
            cache.get("server:user", "cover", CoverVariant.Grid) {
                calls.incrementAndGet()
                release.await()
                byteArrayOf(7)
            }
        }
        awaitWaiters(cache, 1)
        val second = async {
            cache.get("server:user", "cover", CoverVariant.Grid) { error("must join") }
        }
        awaitWaiters(cache, 2)

        first.cancelAndJoin()
        release.complete(Unit)

        assertArrayEquals(byteArrayOf(7), second.await())
        assertEquals(1, calls.get())
        assertEquals(1, cache.namespaceDirectoryForTest("server:user").listFiles().orEmpty().size)
    }

    @Test fun `last canceled waiter cannot fill memory or disk with a late result`() = runBlocking {
        val cache = cache()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val waiter = async {
            cache.get("server:user", "cover", CoverVariant.Grid) {
                calls.incrementAndGet()
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                byteArrayOf(1)
            }
        }
        started.await()

        waiter.cancel()
        release.complete(Unit)
        waiter.join()

        assertTrue(cache.namespaceDirectoryForTest("server:user").listFiles().isNullOrEmpty())
        assertArrayEquals(byteArrayOf(2), cache.get("server:user", "cover", CoverVariant.Grid) {
            calls.incrementAndGet()
            byteArrayOf(2)
        })
        assertEquals(2, calls.get())
    }

    @Test fun `invalid and failed misses remain retryable and leave no files`() = runBlocking {
        val cache = cache(isValid = { bytes -> bytes.firstOrNull() == 1.toByte() })
        val calls = AtomicInteger()

        assertEquals(null, cache.get("server:user", "cover", CoverVariant.Grid) {
            calls.incrementAndGet()
            byteArrayOf(0)
        })
        assertTrue(cache.namespaceDirectoryForTest("server:user").listFiles().isNullOrEmpty())

        assertArrayEquals(byteArrayOf(1), cache.get("server:user", "cover", CoverVariant.Grid) {
            calls.incrementAndGet()
            byteArrayOf(1)
        })
        assertEquals(2, calls.get())
        assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".tmp") })
    }

    @Test fun `current resource retry does not retry invalid artwork`() = runBlocking {
        val cache = cache(isValid = { bytes -> bytes.firstOrNull() == 1.toByte() })
        val calls = AtomicInteger()

        val result = withCurrentResourceRetry(delaysMillis = listOf(0L, 0L)) {
            cache.get("server:user", "invalid", CoverVariant.Grid) {
                calls.incrementAndGet()
                byteArrayOf(0)
            }
        }

        assertEquals(null, result)
        assertEquals(1, calls.get())
        assertTrue(cache.namespaceDirectoryForTest("server:user").listFiles().isNullOrEmpty())
    }

    @Test fun `exceptional download is removed from single flight and retries cleanly`() = runBlocking {
        val cache = cache()
        val calls = AtomicInteger()

        assertThrows(IOException::class.java) {
            runBlocking {
                cache.get("server:user", "cover", CoverVariant.Grid) {
                    calls.incrementAndGet()
                    throw IOException("offline")
                }
            }
        }
        assertTrue(cache.namespaceDirectoryForTest("server:user").listFiles().isNullOrEmpty())

        assertArrayEquals(byteArrayOf(1), cache.get("server:user", "cover", CoverVariant.Grid) {
            calls.incrementAndGet()
            byteArrayOf(1)
        })
        assertEquals(2, calls.get())
        assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".tmp") })
    }

    @Test fun `device budget evicts globally across namespace directories`() = runBlocking {
        var now = 1_000L
        val cache = cache(diskBudget = { 6L }, clock = { now })
        cache.get("server:user-a", "cover-a", CoverVariant.Grid) { byteArrayOf(1, 1, 1, 1) }
        now = 2_000L
        cache.get("server:user-b", "cover-b", CoverVariant.Grid) { byteArrayOf(2, 2, 2, 2) }

        assertTrue(cache.namespaceDirectoryForTest("server:user-a").listFiles().isNullOrEmpty())
        assertEquals(1, cache.namespaceDirectoryForTest("server:user-b").listFiles().orEmpty().size)
        assertEquals(4L, cache.usageBytes())
    }

    @Test fun `startup and budget application actively enforce a lowered global limit`() = runBlocking {
        var budget = 8L
        var now = 1_000L
        val original = cache(diskBudget = { budget }, clock = { now })
        original.get("server:user-a", "cover-a", CoverVariant.Grid) { byteArrayOf(1, 1, 1, 1) }
        now = 2_000L
        original.get("server:user-b", "cover-b", CoverVariant.Grid) { byteArrayOf(2, 2, 2, 2) }
        assertEquals(8L, original.usageBytes())

        budget = 4L
        val restarted = cache(diskBudget = { budget }, clock = { now })
        restarted.initialize()
        assertEquals(4L, restarted.usageBytes())

        budget = 0L
        restarted.applyBudget()
        assertEquals(0L, restarted.usageBytes())
    }

    @Test fun `writes within the tracked budget do not rescan the full cache tree`() = runBlocking {
        val cache = cache(diskBudget = { 1_024L })
        cache.initialize()
        val scansAfterInitialization = cache.fullDiskScansForTest()

        cache.get("server:user", "cover-a", CoverVariant.Grid) { byteArrayOf(1, 2, 3) }
        cache.get("server:user", "cover-b", CoverVariant.Grid) { byteArrayOf(4, 5, 6) }

        assertEquals(scansAfterInitialization, cache.fullDiskScansForTest())
        assertEquals(6L, cache.usageBytes())
    }

    @Test fun `clear namespace rejects late completion without touching other namespace`() = runBlocking {
        val cache = cache()
        cache.get("server:user-b", "cover-b", CoverVariant.Grid) { byteArrayOf(2) }
        val release = CompletableDeferred<Unit>()
        val stale = async {
            cache.get("server:user-a", "cover-a", CoverVariant.Grid) {
                withContext(NonCancellable) { release.await() }
                byteArrayOf(1)
            }
        }
        awaitWaiters(cache, 1, namespace = "server:user-a", coverId = "cover-a")

        cache.clearNamespace("server:user-a")
        release.complete(Unit)
        stale.cancelAndJoin()

        assertTrue(cache.namespaceDirectoryForTest("server:user-a").listFiles().isNullOrEmpty())
        assertEquals(1, cache.namespaceDirectoryForTest("server:user-b").listFiles().orEmpty().size)
        assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".tmp") })
        assertArrayEquals(byteArrayOf(3), cache.get("server:user-a", "cover-a", CoverVariant.Grid) { byteArrayOf(3) })
    }

    private fun cache(
        diskBudget: () -> Long = { 1024L },
        isValid: (ByteArray) -> Boolean = ByteArray::isNotEmpty,
        clock: () -> Long = System::currentTimeMillis,
    ) = ArtworkCache(
        root = root,
        memoryCapacityBytes = 128,
        diskBudgetBytes = diskBudget,
        isValid = isValid,
        scope = scope,
        clock = clock,
    )

    private suspend fun awaitWaiters(
        cache: ArtworkCache,
        expected: Int,
        namespace: String = "server:user",
        coverId: String = "cover",
    ) {
        repeat(10_000) {
            if (cache.waiterCountForTest(namespace, coverId, CoverVariant.Grid) == expected) return
            yield()
        }
        error("Timed out waiting for $expected artwork waiters")
    }
}
