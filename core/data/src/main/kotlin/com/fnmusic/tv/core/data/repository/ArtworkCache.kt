package com.fnmusic.tv.core.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import com.fnmusic.tv.core.data.api.ApiRequestFailure
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.CoverVariant
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ArtworkCache(
    private val root: File,
    private val memoryCapacityBytes: Int,
    private val diskBudgetBytes: () -> Long,
    private val isValid: (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(memoryCapacityBytes > 0)
    }

    private data class Key(
        val namespace: String,
        val coverId: String,
        val variant: CoverVariant,
    )

    private data class Generation(val global: Long, val namespace: Long)
    private data class Candidate(val bytes: ByteArray, val needsDiskWrite: Boolean)
    private data class Flight(
        val generation: Generation,
        val deferred: Deferred<Candidate?>,
        var waiters: Int,
    )

    private val stateMutex = Mutex()
    private val diskMutex = Mutex()
    private val acceptanceMutex = Mutex()
    private val memory = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private val flights = mutableMapOf<Key, Flight>()
    private val namespaceGenerations = mutableMapOf<String, Long>()
    private var globalGeneration = 0L
    private var memoryBytes = 0
    private var trackedDiskBytes: Long? = null
    private var fullDiskScans = 0

    suspend fun get(
        namespace: String,
        coverId: String,
        variant: CoverVariant,
        download: suspend () -> ByteArray,
    ): ByteArray? {
        val key = Key(namespace, coverId, variant)
        var created = false
        var memoryHit: ByteArray? = null
        val flight = stateMutex.withLock {
            memory[key]?.let { bytes ->
                memoryHit = bytes
                null
            } ?: run {
                val generation = generationOf(namespace)
                flights[key]?.takeIf { it.generation == generation }?.also {
                    it.waiters += 1
                } ?: run {
                    created = true
                    val deferred = scope.async(start = CoroutineStart.LAZY) {
                        loadCandidate(key, download)
                    }
                    Flight(generation, deferred, waiters = 1).also { flights[key] = it }
                }
            }
        }
        memoryHit?.let { bytes ->
            touch(fileFor(key))
            return bytes
        }
        val activeFlight = requireNotNull(flight)
        if (created) activeFlight.deferred.start()
        return try {
            activeFlight.deferred.await()?.let { candidate -> accept(key, activeFlight, candidate) }
        } finally {
            withContext(NonCancellable) { detach(key, activeFlight) }
        }
    }

    suspend fun clearNamespace(namespace: String) {
        acceptanceMutex.withLock {
            val canceled = stateMutex.withLock {
                namespaceGenerations[namespace] = (namespaceGenerations[namespace] ?: 0L) + 1L
                evictMemory { it.namespace == namespace }
                flights.entries
                    .filter { it.key.namespace == namespace }
                    .map { (key, value) ->
                        flights.remove(key)
                        value.deferred
                    }
            }
            canceled.forEach { it.cancel(CancellationException("Artwork namespace was cleared")) }
            diskMutex.withLock {
                withContext(Dispatchers.IO) {
                    val directory = namespaceDirectory(namespace)
                    val removedBytes = directory.sizeRecursively()
                    if (directory.deleteRecursively()) {
                        trackedDiskBytes = trackedDiskBytes?.let { (it - removedBytes).coerceAtLeast(0L) }
                    } else {
                        trackedDiskBytes = null
                    }
                }
            }
        }
    }

    suspend fun clearAll() {
        acceptanceMutex.withLock {
            val canceled = stateMutex.withLock {
                globalGeneration += 1L
                memory.clear()
                memoryBytes = 0
                flights.values.map(Flight::deferred).also { flights.clear() }
            }
            canceled.forEach { it.cancel(CancellationException("Artwork cache was cleared")) }
            diskMutex.withLock {
                withContext(Dispatchers.IO) {
                    root.listFiles().orEmpty().forEach(File::deleteRecursively)
                    root.mkdirs()
                    trackedDiskBytes = 0L
                }
            }
        }
    }

    suspend fun usageBytes(): Long = diskMutex.withLock {
        withContext(Dispatchers.IO) {
            trackedDiskBytes ?: scanDiskBytes().also { trackedDiskBytes = it }
        }
    }

    suspend fun applyBudget() = diskMutex.withLock {
        withContext(Dispatchers.IO) { trackedDiskBytes = pruneDisk(diskBudgetBytes()) }
    }

    internal fun namespaceDirectoryForTest(namespace: String): File = namespaceDirectory(namespace)
    internal suspend fun waiterCountForTest(
        namespace: String,
        coverId: String,
        variant: CoverVariant,
    ): Int = stateMutex.withLock { flights[Key(namespace, coverId, variant)]?.waiters ?: 0 }
    internal suspend fun fullDiskScansForTest(): Int = diskMutex.withLock { fullDiskScans }

    suspend fun initialize() = diskMutex.withLock {
        withContext(Dispatchers.IO) {
            root.mkdirs()
            purgeLegacyFiles()
            trackedDiskBytes = pruneDisk(diskBudgetBytes())
        }
    }

    private suspend fun loadCandidate(key: Key, download: suspend () -> ByteArray): Candidate? {
        readDisk(key)?.let { return Candidate(it, needsDiskWrite = false) }
        return download().takeIf(isValid)?.let { Candidate(it, needsDiskWrite = true) }
    }

    private suspend fun readDisk(key: Key): ByteArray? = diskMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || !isValid(bytes)) {
                val removedBytes = file.length()
                if (file.delete()) adjustTrackedDiskBytes(-removedBytes)
                null
            } else {
                bytes
            }
        }
    }

    private suspend fun accept(key: Key, flight: Flight, candidate: Candidate): ByteArray {
        currentCoroutineContext().ensureActive()
        return acceptanceMutex.withLock accepted@{
            ensureAcceptable(key, flight)
            stateMutex.withLock { memory[key] }?.let { existing ->
                touch(fileFor(key))
                return@accepted existing
            }

            var wroteDisk = false
            try {
                if (candidate.needsDiskWrite) {
                    writeDisk(key, flight, candidate.bytes)
                    wroteDisk = true
                } else {
                    touch(fileFor(key))
                }
                ensureAcceptable(key, flight)
                stateMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    ensureAcceptableLocked(key, flight)
                    putMemory(key, candidate.bytes)
                }
                candidate.bytes
            } catch (cause: CancellationException) {
                if (wroteDisk) deleteDisk(key)
                throw cause
            }
        }
    }

    private suspend fun writeDisk(key: Key, flight: Flight, bytes: ByteArray) {
        diskMutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = namespaceDirectory(key.namespace).apply { mkdirs() }
                val target = fileFor(key)
                val temporary = File(directory, ".${target.name}.${TEMP_SEQUENCE.incrementAndGet()}.tmp")
                if (trackedDiskBytes == null) trackedDiskBytes = scanDiskBytes()
                val replacedBytes = target.takeIf(File::isFile)?.length() ?: 0L
                var moved = false
                try {
                    temporary.writeBytes(bytes)
                    ensureAcceptable(key, flight)
                    replaceAtomically(temporary, target)
                    moved = true
                    target.setLastModified(clock())
                    adjustTrackedDiskBytes(target.length() - replacedBytes)
                    val budget = diskBudgetBytes().coerceAtLeast(0L)
                    if ((trackedDiskBytes ?: 0L) > budget) {
                        trackedDiskBytes = pruneDisk(budget)
                    }
                    ensureAcceptable(key, flight)
                } catch (cause: CancellationException) {
                    if (moved) {
                        val removedBytes = target.length()
                        if (target.delete()) adjustTrackedDiskBytes(-removedBytes)
                    }
                    throw cause
                } catch (cause: IOException) {
                    trackedDiskBytes = null
                    throw AppException(
                        AppError.NetworkUnavailable,
                        ApiRequestFailure(retryable = true, cause = cause),
                    )
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private suspend fun ensureAcceptable(key: Key, flight: Flight) {
        currentCoroutineContext().ensureActive()
        stateMutex.withLock { ensureAcceptableLocked(key, flight) }
    }

    private fun ensureAcceptableLocked(key: Key, flight: Flight) {
        if (
            flights[key] !== flight ||
            generationOf(key.namespace) != flight.generation ||
            flight.waiters <= 0
        ) {
            throw CancellationException("Artwork result is no longer accepted")
        }
    }

    private fun putMemory(key: Key, bytes: ByteArray) {
        memory.remove(key)?.let { memoryBytes -= it.size }
        memory[key] = bytes
        memoryBytes += bytes.size
        val iterator = memory.iterator()
        while (memoryBytes > memoryCapacityBytes && iterator.hasNext()) {
            val (_, removed) = iterator.next()
            memoryBytes -= removed.size
            iterator.remove()
        }
    }

    private suspend fun deleteDisk(key: Key) {
        diskMutex.withLock {
            withContext(Dispatchers.IO) {
                val file = fileFor(key)
                val removedBytes = file.length()
                if (file.delete()) adjustTrackedDiskBytes(-removedBytes)
            }
        }
    }

    private suspend fun touch(file: File) {
        diskMutex.withLock {
            withContext(Dispatchers.IO) { if (file.isFile) file.setLastModified(clock()) }
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            replaceAtomicallyApi26(source, target)
            return
        }
        if (target.exists() && !target.delete()) throw IOException("artwork_replace_delete_failed")
        if (!source.renameTo(target)) throw IOException("artwork_replace_rename_failed")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun replaceAtomicallyApi26(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun detach(key: Key, flight: Flight) {
        val cancel = stateMutex.withLock {
            if (flights[key] !== flight) return@withLock false
            flight.waiters -= 1
            if (flight.waiters == 0) {
                flights.remove(key)
                flight.deferred.isActive
            } else {
                false
            }
        }
        if (cancel) flight.deferred.cancel(CancellationException("No artwork waiters remain"))
    }

    private fun generationOf(namespace: String) = Generation(
        global = globalGeneration,
        namespace = namespaceGenerations[namespace] ?: 0L,
    )

    private fun evictMemory(predicate: (Key) -> Boolean) {
        val iterator = memory.iterator()
        while (iterator.hasNext()) {
            val (key, value) = iterator.next()
            if (predicate(key)) {
                memoryBytes -= value.size
                iterator.remove()
            }
        }
    }

    private fun fileFor(key: Key): File = File(
        namespaceDirectory(key.namespace),
        "${key.coverId}|${key.variant.name}".sha256(),
    )

    private fun namespaceDirectory(namespace: String): File = File(root, namespace.sha256())

    private fun pruneDisk(limitBytes: Long): Long {
        fullDiskScans += 1
        var retained = 0L
        root.walkTopDown()
            .filter(File::isFile)
            .filterNot { it.name.endsWith(".tmp") }
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val length = file.length()
                if (retained + length <= limitBytes.coerceAtLeast(0L)) {
                    retained += length
                } else {
                    file.delete()
                }
        }
        root.listFiles().orEmpty().filter(File::isDirectory).filter { it.list().isNullOrEmpty() }.forEach(File::delete)
        return retained
    }

    private fun scanDiskBytes(): Long {
        fullDiskScans += 1
        return root.sizeRecursively()
    }

    private fun adjustTrackedDiskBytes(delta: Long) {
        trackedDiskBytes = trackedDiskBytes?.let { (it + delta).coerceAtLeast(0L) }
    }

    private fun purgeLegacyFiles() {
        root.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
        root.walkTopDown().filter(File::isFile).filter { it.name.endsWith(".tmp") }.forEach(File::delete)
    }

    private fun File.sizeRecursively(): Long =
        if (!exists()) 0L else walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val TEMP_SEQUENCE = AtomicLong()
    }
}
