package com.fnmusic.tv.core.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test fun `migration preserves essential account state`() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                "INSERT INTO account_state " +
                    "(namespace, queueJson, frozenQueueJson, playerStyle, cacheBudget, updatedAt) " +
                    "VALUES ('server:user', 'queue', 'frozen', 'Poster', 'Default', 7)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT queueJson, frozenQueueJson, playerStyle, cacheBudget, schemaRevision FROM account_state").use {
                it.moveToFirst()
                assertEquals("queue", it.getString(0))
                assertEquals("frozen", it.getString(1))
                assertEquals("Poster", it.getString(2))
                assertEquals("Default", it.getString(3))
                assertEquals(1, it.getInt(4))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test.db"
    }
}
