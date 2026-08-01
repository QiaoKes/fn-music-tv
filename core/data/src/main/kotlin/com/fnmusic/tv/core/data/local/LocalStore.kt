package com.fnmusic.tv.core.data.local

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalStore(context: Context, val database: AppDatabase = AppDatabase.create(context)) {
    private val dao = database.dao()
    private val databaseFile = context.getDatabasePath(AppDatabase.NAME)
    private val budgetMutex = Mutex()

    suspend fun account(namespace: String): AccountStateEntity? = dao.account(namespace)

    suspend fun saveQueue(namespace: String, queueJson: String?) {
        ensureAccount(namespace)
        dao.updateQueue(namespace, queueJson, now())
    }

    suspend fun saveFrozenQueue(namespace: String, queueJson: String?) {
        ensureAccount(namespace)
        dao.updateFrozenQueue(namespace, queueJson, now())
    }

    suspend fun savePlaybackSnapshot(namespace: String, snapshotJson: String?) {
        ensureAccount(namespace)
        dao.updatePlaybackSnapshot(namespace, snapshotJson, now())
    }

    suspend fun saveSettings(namespace: String, style: String, budget: String) {
        ensureAccount(namespace)
        dao.updateSettings(namespace, style, budget, now())
    }

    suspend fun page(namespace: String, sourceKey: String, page: Int): CachedPageEntity? =
        dao.page(namespace, sourceKey, page)?.also { dao.touchPage(namespace, sourceKey, page, now()) }

    suspend fun savePage(entity: CachedPageEntity) {
        dao.upsertPage(entity)
        enforceBudget()
    }

    suspend fun lyric(namespace: String, trackGuid: String): CachedLyricEntity? =
        dao.lyric(namespace, trackGuid)?.also { dao.touchLyric(namespace, trackGuid, now()) }

    suspend fun saveLyric(entity: CachedLyricEntity) {
        dao.upsertLyric(entity)
        enforceBudget()
    }

    suspend fun index(namespace: String, key: String): CachedIndexEntity? =
        dao.index(namespace, key)?.also { dao.touchIndex(namespace, key, now()) }

    suspend fun saveIndex(entity: CachedIndexEntity) {
        dao.upsertIndex(entity)
        enforceBudget()
    }

    suspend fun clearNamespace(namespace: String, includeEssential: Boolean) {
        dao.deletePages(namespace)
        dao.deleteLyrics(namespace)
        dao.deleteIndexes(namespace)
        if (includeEssential) dao.deleteAccount(namespace)
        reclaimSpace()
    }

    suspend fun clearAllEvictable() {
        dao.deleteAllPages()
        dao.deleteAllLyrics()
        dao.deleteAllIndexes()
        reclaimSpace()
    }

    suspend fun physicalBytes(): Long = withContext(Dispatchers.IO) {
        listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .filter(File::isFile)
            .sumOf(File::length)
    }

    private suspend fun ensureAccount(namespace: String) {
        dao.ensureAccount(AccountStateEntity(namespace = namespace, updatedAt = now()))
    }

    private suspend fun enforceBudget() = budgetMutex.withLock {
        var payloadBytes = dao.pagePayloadBytes() + dao.lyricPayloadBytes() + dao.indexPayloadBytes()
        var physicalBytes = physicalBytes()
        while (
            payloadBytes > AppDatabase.EVICTABLE_PAYLOAD_TARGET_BYTES ||
            physicalBytes > AppDatabase.MAX_DATABASE_BYTES
        ) {
            val removed = dao.evictOldestPages(EVICTION_BATCH) +
                dao.evictOldestLyrics(EVICTION_BATCH) +
                dao.evictOldestIndexes(EVICTION_BATCH)
            if (removed == 0) break
            reclaimSpace()
            payloadBytes = dao.pagePayloadBytes() + dao.lyricPayloadBytes() + dao.indexPayloadBytes()
            physicalBytes = physicalBytes()
        }
    }

    private fun checkpoint() {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    }

    private fun reclaimSpace() {
        checkpoint()
        database.openHelper.writableDatabase.query("PRAGMA incremental_vacuum(1024)").close()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val EVICTION_BATCH = 32
    }
}
