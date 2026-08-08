package com.fnmusic.tv.core.data.repository

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ResponseCacheKey(
    val namespace: String,
    val kind: String,
    val businessKey: String,
    val page: Int? = null,
)

internal class SerializedResponseCache(
    private val capacityBytes: Int,
    private val scope: CoroutineScope,
) {
    init {
        require(capacityBytes > 0)
    }

    private data class Entry(val payload: String, val sizeBytes: Int)
    private data class Generation(val global: Long, val namespace: Long)
    private data class Flight(
        val generation: Generation,
        val deferred: Deferred<String>,
        val persist: suspend (String) -> Unit,
        var waiters: Int,
    )

    private val mutex = Mutex()
    private val acceptanceMutex = Mutex()
    private val entries = LinkedHashMap<ResponseCacheKey, Entry>(16, 0.75f, true)
    private val flights = mutableMapOf<ResponseCacheKey, Flight>()
    private val namespaceGenerations = mutableMapOf<String, Long>()
    private var globalGeneration = 0L
    private var retainedBytes = 0

    suspend fun getOrFetch(
        key: ResponseCacheKey,
        isRetainedValid: (String) -> Boolean = { true },
        persist: suspend (String) -> Unit = {},
        fetch: suspend () -> String,
    ): String {
        var created = false
        val flight = mutex.withLock {
            entries[key]?.let { entry ->
                if (isRetainedValid(entry.payload)) return entry.payload
                entries.remove(key)
                retainedBytes -= entry.sizeBytes
            }
            val generation = generationOf(key.namespace)
            flights[key]?.takeIf { it.generation == generation }?.also {
                it.waiters += 1
            } ?: run {
                created = true
                val deferred = scope.async(start = CoroutineStart.LAZY) { fetch() }
                Flight(generation, deferred, persist, waiters = 1).also { flights[key] = it }
            }
        }
        if (created) flight.deferred.start()
        return try {
            flight.deferred.await().also { payload -> accept(key, flight, payload) }
        } finally {
            withContext(NonCancellable) { detach(key, flight) }
        }
    }

    suspend fun invalidateNamespace(namespace: String) {
        val canceled = acceptanceMutex.withLock {
            mutex.withLock {
                namespaceGenerations[namespace] = (namespaceGenerations[namespace] ?: 0L) + 1L
                val iterator = entries.iterator()
                while (iterator.hasNext()) {
                    val (key, value) = iterator.next()
                    if (key.namespace == namespace) {
                        retainedBytes -= value.sizeBytes
                        iterator.remove()
                    }
                }
                flights.entries
                    .filter { it.key.namespace == namespace }
                    .map { (key, value) ->
                        flights.remove(key)
                        value.deferred
                    }
            }
        }
        canceled.forEach { it.cancel(CancellationException("Response cache namespace was invalidated")) }
    }

    suspend fun invalidateAll() {
        val canceled = acceptanceMutex.withLock {
            mutex.withLock {
                globalGeneration += 1L
                retainedBytes = 0
                entries.clear()
                flights.values.map(Flight::deferred).also { flights.clear() }
            }
        }
        canceled.forEach { it.cancel(CancellationException("Response cache was cleared")) }
    }

    internal suspend fun retainedKeys(): List<ResponseCacheKey> = mutex.withLock { entries.keys.toList() }
    internal suspend fun retainedByteCount(): Int = mutex.withLock { retainedBytes }
    internal suspend fun waiterCount(key: ResponseCacheKey): Int = mutex.withLock { flights[key]?.waiters ?: 0 }

    private suspend fun accept(key: ResponseCacheKey, flight: Flight, payload: String) {
        currentCoroutineContext().ensureActive()
        acceptanceMutex.withLock {
            currentCoroutineContext().ensureActive()
            val alreadyAccepted = mutex.withLock {
                ensureAcceptable(key, flight)
                entries.containsKey(key)
            }
            if (alreadyAccepted) return@withLock

            flight.persist(payload)
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                ensureAcceptable(key, flight)
                put(key, payload)
            }
        }
    }

    private fun ensureAcceptable(key: ResponseCacheKey, flight: Flight) {
        if (
            flights[key] !== flight ||
            generationOf(key.namespace) != flight.generation ||
            flight.waiters <= 0
        ) {
            throw CancellationException("Response cache result is no longer accepted")
        }
    }

    private suspend fun detach(key: ResponseCacheKey, flight: Flight) {
        val cancel = mutex.withLock {
            if (flights[key] !== flight) return@withLock false
            flight.waiters -= 1
            if (flight.waiters == 0) {
                flights.remove(key)
                flight.deferred.isActive
            } else {
                false
            }
        }
        if (cancel) flight.deferred.cancel(CancellationException("No response cache waiters remain"))
    }

    private fun generationOf(namespace: String) = Generation(
        global = globalGeneration,
        namespace = namespaceGenerations[namespace] ?: 0L,
    )

    private fun put(key: ResponseCacheKey, payload: String) {
        entries.remove(key)?.let { retainedBytes -= it.sizeBytes }
        val entry = Entry(payload, payload.toByteArray(Charsets.UTF_8).size)
        entries[key] = entry
        retainedBytes += entry.sizeBytes
        val iterator = entries.iterator()
        while (retainedBytes > capacityBytes && iterator.hasNext()) {
            val (_, removed) = iterator.next()
            retainedBytes -= removed.sizeBytes
            iterator.remove()
        }
    }
}
