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
    private var estimatedPayloadBytes: Long? = null
    private var estimatedPhysicalBytes: Long? = null
    private var unverifiedPhysicalGrowth = 0L
    private var writesSinceBudgetAudit = 0
    internal var budgetAuditCount = 0
        private set
    internal var spaceReclaimCount = 0
        private set

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
        recordEvictableWrite(entity.payload)
    }

    suspend fun lyric(namespace: String, trackGuid: String): CachedLyricEntity? =
        dao.lyric(namespace, trackGuid)?.also { dao.touchLyric(namespace, trackGuid, now()) }

    suspend fun saveLyric(entity: CachedLyricEntity) {
        dao.upsertLyric(entity)
        recordEvictableWrite(entity.payload)
    }

    suspend fun index(namespace: String, key: String): CachedIndexEntity? =
        dao.index(namespace, key)?.also { dao.touchIndex(namespace, key, now()) }

    suspend fun saveIndex(entity: CachedIndexEntity) {
        dao.upsertIndex(entity)
        recordEvictableWrite(entity.payload)
    }

    suspend fun clearNamespace(namespace: String, includeEssential: Boolean) = budgetMutex.withLock {
        dao.deletePages(namespace)
        dao.deleteLyrics(namespace)
        dao.deleteIndexes(namespace)
        if (includeEssential) dao.deleteAccount(namespace)
        reclaimSpace()
        resetBudgetEstimate()
    }

    suspend fun clearAllEvictable() = budgetMutex.withLock {
        dao.deleteAllPages()
        dao.deleteAllLyrics()
        dao.deleteAllIndexes()
        reclaimSpace()
        estimatedPayloadBytes = 0L
        estimatedPhysicalBytes = physicalBytes()
        unverifiedPhysicalGrowth = 0L
        writesSinceBudgetAudit = 0
    }

    suspend fun physicalBytes(): Long = withContext(Dispatchers.IO) {
        listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .filter(File::isFile)
            .sumOf(File::length)
    }

    private suspend fun ensureAccount(namespace: String) {
        dao.ensureAccount(AccountStateEntity(namespace = namespace, updatedAt = now()))
    }

    private suspend fun recordEvictableWrite(payload: String) = budgetMutex.withLock {
        val payloadBytes = payload.encodeToByteArray().size.toLong()
        val nextEstimate = estimatedPayloadBytes?.plus(payloadBytes)
        val nextPhysicalEstimate = estimatedPhysicalBytes?.plus(payloadBytes)
        unverifiedPhysicalGrowth += payloadBytes
        writesSinceBudgetAudit += 1
        estimatedPayloadBytes = nextEstimate
        estimatedPhysicalBytes = nextPhysicalEstimate
        val shouldAudit = nextEstimate == null ||
            nextPhysicalEstimate == null ||
            nextEstimate > AppDatabase.EVICTABLE_PAYLOAD_TARGET_BYTES ||
            nextPhysicalEstimate > AppDatabase.MAX_DATABASE_BYTES ||
            unverifiedPhysicalGrowth >= MAX_UNVERIFIED_PHYSICAL_GROWTH_BYTES ||
            writesSinceBudgetAudit >= MAX_WRITES_WITHOUT_BUDGET_AUDIT
        if (shouldAudit) auditBudget()
    }

    private suspend fun auditBudget() {
        budgetAuditCount += 1
        var payloadBytes = dao.pagePayloadBytes() + dao.lyricPayloadBytes() + dao.indexPayloadBytes()
        var physicalBytes = physicalBytes()
        var removedSinceReclaim = false
        while (payloadBytes > AppDatabase.EVICTABLE_PAYLOAD_TARGET_BYTES) {
            val removed = dao.evictOldestPages(EVICTION_BATCH) +
                dao.evictOldestLyrics(EVICTION_BATCH) +
                dao.evictOldestIndexes(EVICTION_BATCH)
            if (removed == 0) break
            removedSinceReclaim = true
            payloadBytes = dao.pagePayloadBytes() + dao.lyricPayloadBytes() + dao.indexPayloadBytes()
        }
        if (removedSinceReclaim) {
            reclaimSpace()
            physicalBytes = physicalBytes()
        }
        while (physicalBytes > AppDatabase.MAX_DATABASE_BYTES) {
            val removed = dao.evictOldestPages(EVICTION_BATCH) +
                dao.evictOldestLyrics(EVICTION_BATCH) +
                dao.evictOldestIndexes(EVICTION_BATCH)
            if (removed == 0) break
            payloadBytes = dao.pagePayloadBytes() + dao.lyricPayloadBytes() + dao.indexPayloadBytes()
            reclaimSpace()
            physicalBytes = physicalBytes()
        }
        estimatedPayloadBytes = payloadBytes
        estimatedPhysicalBytes = physicalBytes
        unverifiedPhysicalGrowth = 0L
        writesSinceBudgetAudit = 0
    }

    private fun resetBudgetEstimate() {
        estimatedPayloadBytes = null
        estimatedPhysicalBytes = null
        unverifiedPhysicalGrowth = 0L
        writesSinceBudgetAudit = 0
    }

    private fun checkpoint() {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    }

    private fun reclaimSpace() {
        spaceReclaimCount += 1
        checkpoint()
        database.openHelper.writableDatabase.query("PRAGMA incremental_vacuum(1024)").close()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val EVICTION_BATCH = 32
        const val MAX_UNVERIFIED_PHYSICAL_GROWTH_BYTES = 4L * 1024L * 1024L
        const val MAX_WRITES_WITHOUT_BUDGET_AUDIT = 32
    }
}
