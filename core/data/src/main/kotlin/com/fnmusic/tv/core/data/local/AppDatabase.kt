package com.fnmusic.tv.core.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "account_state", primaryKeys = ["namespace"])
data class AccountStateEntity(
    val namespace: String,
    val queueJson: String? = null,
    val frozenQueueJson: String? = null,
    val playerStyle: String? = null,
    val cacheBudget: String? = null,
    val schemaRevision: Int = 1,
    val updatedAt: Long,
)

@Entity(tableName = "cache_page", primaryKeys = ["namespace", "sourceKey", "page"])
data class CachedPageEntity(
    val namespace: String,
    val sourceKey: String,
    val page: Int,
    val payload: String,
    val total: Int,
    val sort: String,
    val accessedAt: Long,
)

@Entity(tableName = "cache_lyric", primaryKeys = ["namespace", "trackGuid"])
data class CachedLyricEntity(
    val namespace: String,
    val trackGuid: String,
    val payload: String,
    val accessedAt: Long,
)

@Entity(tableName = "cache_index", primaryKeys = ["namespace", "indexKey"])
data class CachedIndexEntity(
    val namespace: String,
    val indexKey: String,
    val payload: String,
    val accessedAt: Long,
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureAccount(entity: AccountStateEntity)

    @Query("SELECT * FROM account_state WHERE namespace = :namespace")
    suspend fun account(namespace: String): AccountStateEntity?

    @Query("UPDATE account_state SET queueJson = :queueJson, updatedAt = :updatedAt WHERE namespace = :namespace")
    suspend fun updateQueue(namespace: String, queueJson: String?, updatedAt: Long)

    @Query("UPDATE account_state SET frozenQueueJson = :queueJson, updatedAt = :updatedAt WHERE namespace = :namespace")
    suspend fun updateFrozenQueue(namespace: String, queueJson: String?, updatedAt: Long)

    @Query(
        "UPDATE account_state SET queueJson = :snapshotJson, frozenQueueJson = NULL, " +
            "updatedAt = :updatedAt WHERE namespace = :namespace",
    )
    suspend fun updatePlaybackSnapshot(namespace: String, snapshotJson: String?, updatedAt: Long)

    @Query("UPDATE account_state SET playerStyle = :style, cacheBudget = :budget, updatedAt = :updatedAt WHERE namespace = :namespace")
    suspend fun updateSettings(namespace: String, style: String, budget: String, updatedAt: Long)

    @Upsert
    suspend fun upsertPage(entity: CachedPageEntity)

    @Query("SELECT * FROM cache_page WHERE namespace = :namespace AND sourceKey = :sourceKey AND page = :page")
    suspend fun page(namespace: String, sourceKey: String, page: Int): CachedPageEntity?

    @Query("UPDATE cache_page SET accessedAt = :accessedAt WHERE namespace = :namespace AND sourceKey = :sourceKey AND page = :page")
    suspend fun touchPage(namespace: String, sourceKey: String, page: Int, accessedAt: Long)

    @Upsert
    suspend fun upsertLyric(entity: CachedLyricEntity)

    @Query("SELECT * FROM cache_lyric WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun lyric(namespace: String, trackGuid: String): CachedLyricEntity?

    @Query("UPDATE cache_lyric SET accessedAt = :accessedAt WHERE namespace = :namespace AND trackGuid = :trackGuid")
    suspend fun touchLyric(namespace: String, trackGuid: String, accessedAt: Long)

    @Upsert
    suspend fun upsertIndex(entity: CachedIndexEntity)

    @Query("SELECT * FROM cache_index WHERE namespace = :namespace AND indexKey = :indexKey")
    suspend fun index(namespace: String, indexKey: String): CachedIndexEntity?

    @Query("UPDATE cache_index SET accessedAt = :accessedAt WHERE namespace = :namespace AND indexKey = :indexKey")
    suspend fun touchIndex(namespace: String, indexKey: String, accessedAt: Long)

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_page")
    suspend fun pagePayloadBytes(): Long

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_lyric")
    suspend fun lyricPayloadBytes(): Long

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM cache_index")
    suspend fun indexPayloadBytes(): Long

    @Query("DELETE FROM cache_page WHERE rowid IN (SELECT rowid FROM cache_page ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestPages(count: Int): Int

    @Query("DELETE FROM cache_lyric WHERE rowid IN (SELECT rowid FROM cache_lyric ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestLyrics(count: Int): Int

    @Query("DELETE FROM cache_index WHERE rowid IN (SELECT rowid FROM cache_index ORDER BY accessedAt ASC LIMIT :count)")
    suspend fun evictOldestIndexes(count: Int): Int

    @Query("DELETE FROM cache_page WHERE namespace = :namespace")
    suspend fun deletePages(namespace: String)

    @Query("DELETE FROM cache_lyric WHERE namespace = :namespace")
    suspend fun deleteLyrics(namespace: String)

    @Query("DELETE FROM cache_index WHERE namespace = :namespace")
    suspend fun deleteIndexes(namespace: String)

    @Query("DELETE FROM cache_page")
    suspend fun deleteAllPages()

    @Query("DELETE FROM cache_lyric")
    suspend fun deleteAllLyrics()

    @Query("DELETE FROM cache_index")
    suspend fun deleteAllIndexes()

    @Query("DELETE FROM account_state WHERE namespace = :namespace")
    suspend fun deleteAccount(namespace: String)
}

@Database(
    entities = [AccountStateEntity::class, CachedPageEntity::class, CachedLyricEntity::class, CachedIndexEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        const val NAME = "fn_music_tv.db"
        const val MAX_DATABASE_BYTES = 32L * 1024L * 1024L
        const val EVICTABLE_PAYLOAD_TARGET_BYTES = 24L * 1024L * 1024L

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account_state ADD COLUMN schemaRevision INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val autoVacuum = db.query("PRAGMA auto_vacuum").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    if (autoVacuum != AUTO_VACUUM_INCREMENTAL) {
                        db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                        db.execSQL("VACUUM")
                    }
                    val pageSize = db.query("PRAGMA page_size").use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else 4_096L
                    }
                    db.query("PRAGMA max_page_count=${MAX_DATABASE_BYTES / pageSize}").close()
                    db.query("PRAGMA journal_size_limit=2097152").close()
                    db.query("PRAGMA wal_autocheckpoint=256").close()
                }
            })
            .build()

        private const val AUTO_VACUUM_INCREMENTAL = 2
    }
}
