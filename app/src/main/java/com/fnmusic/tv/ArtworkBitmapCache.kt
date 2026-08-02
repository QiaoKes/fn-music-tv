package com.fnmusic.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.fnmusic.tv.core.model.CoverVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ArtworkBitmapCache(
    private val scope: CoroutineScope,
    private val loader: suspend (coverId: String, variant: CoverVariant) -> Bitmap?,
    maxBytes: Int = DEFAULT_MAX_BYTES,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
) {
    private data class Key(val coverId: String, val variant: CoverVariant)

    private val lock = Any()
    private val loadPermits = Semaphore(maxConcurrentLoads)
    private val memory = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private val inFlight = mutableMapOf<Key, Deferred<Bitmap?>>()
    private var generation = 0L

    fun peek(coverId: String, variant: CoverVariant): Bitmap? = synchronized(lock) {
        memory.get(Key(coverId, variant))
    }

    suspend fun get(coverId: String, variant: CoverVariant): Bitmap? {
        val key = Key(coverId, variant)
        val request = synchronized(lock) {
            memory.get(key)?.let { return it }
            inFlight[key] ?: createRequestLocked(key)
        }
        return request.await()
    }

    fun prefetch(coverId: String, variant: CoverVariant): Job? {
        val key = Key(coverId, variant)
        return synchronized(lock) {
            if (memory.get(key) != null) return null
            inFlight[key] ?: createRequestLocked(key)
        }
    }

    fun clear() {
        val pending = synchronized(lock) {
            generation += 1
            memory.evictAll()
            inFlight.values.toList().also { inFlight.clear() }
        }
        pending.forEach { it.cancel() }
    }

    private fun createRequestLocked(key: Key): Deferred<Bitmap?> {
        val requestGeneration = generation
        val request = scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            loadPermits.withPermit { loader(key.coverId, key.variant) }?.also { bitmap ->
                synchronized(lock) {
                    if (generation == requestGeneration) memory.put(key, bitmap)
                }
            }
        }
        inFlight[key] = request
        request.invokeOnCompletion {
            synchronized(lock) {
                if (inFlight[key] === request) inFlight.remove(key)
            }
        }
        request.start()
        return request
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 40 * 1024 * 1024
        const val DEFAULT_MAX_CONCURRENT_LOADS = 3
    }
}

internal fun decodeArtwork(bytes: ByteArray, targetLongEdge: Int): Bitmap? {
    if (bytes.size > 20 * 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8_192 || bounds.outHeight > 8_192) return null
    if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return null
    var sample = 1
    while (
        bounds.outWidth / sample > targetLongEdge ||
        bounds.outHeight / sample > targetLongEdge ||
        (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 16_000_000L
    ) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
