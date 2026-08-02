package com.fnmusic.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.fnmusic.tv.core.model.CoverVariant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArtworkBitmapCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `compact and grid artwork remain separate exact variants`() = runBlocking {
        val compact = bitmap(Color.RED)
        val grid = bitmap(Color.BLUE)
        val loadCount = AtomicInteger()
        val cache = ArtworkBitmapCache(scope, loader = { _, variant ->
            loadCount.incrementAndGet()
            if (variant == CoverVariant.Compact) compact else grid
        })

        assertSame(compact, cache.get("cover", CoverVariant.Compact))
        assertNull(cache.peek("cover", CoverVariant.Grid))
        assertSame(grid, cache.get("cover", CoverVariant.Grid))
        assertEquals(2, loadCount.get())
    }

    @Test
    fun `concurrent exact requests share one load`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val expected = bitmap(Color.GREEN)
        val loadCount = AtomicInteger()
        val cache = ArtworkBitmapCache(scope, loader = { _, _ ->
            loadCount.incrementAndGet()
            started.complete(Unit)
            release.await()
            expected
        })

        val first = async { cache.get("cover", CoverVariant.Grid) }
        started.await()
        val second = async { cache.get("cover", CoverVariant.Grid) }
        release.complete(Unit)

        assertSame(expected, first.await())
        assertSame(expected, second.await())
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `prefetch populates only the requested exact variant`() = runBlocking {
        val expected = bitmap(Color.YELLOW)
        val cache = ArtworkBitmapCache(scope, loader = { _, _ -> expected })

        cache.prefetch("cover", CoverVariant.Grid)?.join()

        assertSame(expected, cache.peek("cover", CoverVariant.Grid))
        assertNull(cache.peek("cover", CoverVariant.Compact))
    }

    @Test
    fun `decoded memory evicts the least recently used bitmap at its byte limit`() = runBlocking {
        val first = bitmap(Color.CYAN)
        val second = bitmap(Color.DKGRAY)
        val cache = ArtworkBitmapCache(
            scope = scope,
            loader = { coverId, _ -> if (coverId == "first") first else second },
            maxBytes = first.allocationByteCount,
        )

        cache.get("first", CoverVariant.Grid)
        cache.get("second", CoverVariant.Grid)

        assertNull(cache.peek("first", CoverVariant.Grid))
        assertSame(second, cache.peek("second", CoverVariant.Grid))
    }

    @Test
    fun `clear prevents an older load from repopulating memory`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = ArtworkBitmapCache(scope, loader = { _, _ ->
            started.complete(Unit)
            runCatching { release.await() }
            bitmap(Color.MAGENTA)
        })

        val prefetch = cache.prefetch("cover", CoverVariant.Grid)
        started.await()
        cache.clear()
        release.complete(Unit)
        prefetch?.join()

        assertNull(cache.peek("cover", CoverVariant.Grid))
    }

    private fun bitmap(color: Int): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }
}
